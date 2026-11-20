package org.humanshaped.garageagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.humanshaped.garageagent.hap.Aead
import org.humanshaped.garageagent.hap.Ed25519
import org.humanshaped.garageagent.hap.GarageBackend
import org.humanshaped.garageagent.hap.HapIdentity
import org.humanshaped.garageagent.hap.HapServer
import org.humanshaped.garageagent.hap.Hkdf
import org.humanshaped.garageagent.hap.Tlv8
import org.humanshaped.garageagent.hap.X25519
import org.humanshaped.garageagent.hap.randomBytes
import org.humanshaped.garageagent.hap.readExactly
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.Socket

/** Pairs a test controller with a throwaway accessory over loopback, on the device. */
@RunWith(AndroidJUnit4::class)
class HapLoopbackTest {
    class FakeDoor : GarageBackend {
        @Volatile var current = 1
        @Volatile var target = 1
        val commands = mutableListOf<Int>()
        override fun currentDoorState() = current
        override fun targetDoorState() = target
        override fun obstructionDetected() = false
        override fun setTargetDoorState(target: Int): Int { commands.add(target); this.target = target; return 0 }
        override fun identify() {}
    }

    class Http(private val sock: Socket) {
        private val input = sock.getInputStream().buffered()
        private val out = sock.getOutputStream()
        var readKey: ByteArray? = null
        var writeKey: ByteArray? = null
        private var outN = 0L
        private var inN = 0L
        private var plainBuf = ByteArray(0)
        private var pos = 0

        fun send(raw: ByteArray) {
            val k = writeKey
            if (k == null) { out.write(raw); out.flush(); return }
            var off = 0
            while (off < raw.size) {
                val n = minOf(1024, raw.size - off)
                val aad = byteArrayOf((n and 0xFF).toByte(), (n shr 8).toByte())
                out.write(aad); out.write(Aead.seal(k, Aead.nonce(outN++), raw.copyOfRange(off, off + n), aad))
                off += n
            }
            out.flush()
        }

        private fun readByte(): Int {
            val k = readKey ?: return input.read()
            if (pos >= plainBuf.size) {
                val aad = readExactly(input, 2)!!
                val n = (aad[0].toInt() and 0xFF) or ((aad[1].toInt() and 0xFF) shl 8)
                plainBuf = Aead.open(k, Aead.nonce(inN++), readExactly(input, n + 16)!!, aad)!!
                pos = 0
            }
            return plainBuf[pos++].toInt() and 0xFF
        }

        fun readMessage(): Pair<String, ByteArray> {
            val head = ByteArrayOutputStream(); var last = 0
            while (last != 0x0D0A0D0A) { val b = readByte(); head.write(b); last = (last shl 8) or b }
            val text = head.toString("ISO-8859-1")
            val len = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toInt() ?: 0
            val body = ByteArray(len) { readByte().toByte() }
            return text.lines().first() to body
        }

        fun request(method: String, path: String, type: String?, body: ByteArray = ByteArray(0)): Pair<String, ByteArray> {
            val h = StringBuilder("$method $path HTTP/1.1\r\nHost: test\r\n")
            if (type != null) h.append("Content-Type: $type\r\n")
            h.append("Content-Length: ${body.size}\r\n\r\n")
            send(h.toString().toByteArray() + body)
            return readMessage()
        }

        fun tlv(path: String, vararg items: Pair<Int, ByteArray>) =
            Tlv8.decode(request("POST", path, "application/pairing+tlv8", Tlv8.encode(*items)).second)
    }

    @Test fun pairVerifyReadWriteAndEvents() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        File(ctx.filesDir, "hap-test.bin").delete()
        val identity = HapIdentity.load(ctx, "hap-test.bin")
        val door = FakeDoor()
        val server = HapServer(identity, door, "Test Door", "1.0", 51899, { android.util.Log.i("HapTest", it) }) {}
        server.start()
        try {
            val ctrlSeed = randomBytes(32)
            val ctrlPk = Ed25519.publicKey(ctrlSeed)
            val ctrlId = "11111111-2222-3333-4444-555555555555".toByteArray()

            // Pair setup
            Http(Socket("127.0.0.1", 51899)).run {
                val m2 = tlv("/pair-setup", Tlv8.STATE to Tlv8.byte(1), Tlv8.METHOD to Tlv8.byte(0))
                assertEquals(2, m2[Tlv8.STATE]!![0].toInt())
                val srp = TestSrpClient(identity.setupCode)
                val (a, m1) = srp.respond(m2[Tlv8.SALT]!!, m2[Tlv8.PUBLIC_KEY]!!)
                val m4 = tlv("/pair-setup", Tlv8.STATE to Tlv8.byte(3), Tlv8.PUBLIC_KEY to a, Tlv8.PROOF to m1)
                assertEquals(null, m4[Tlv8.ERROR])
                assertTrue(srp.expectedM2().contentEquals(m4[Tlv8.PROOF]!!))
                val k = srp.sessionKey
                val encKey = Hkdf.sha512(k, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
                val x = Hkdf.sha512(k, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
                val sig = Ed25519.sign(ctrlSeed, x + ctrlId + ctrlPk)
                val sub = Tlv8.encode(Tlv8.IDENTIFIER to ctrlId, Tlv8.PUBLIC_KEY to ctrlPk, Tlv8.SIGNATURE to sig)
                val m6 = tlv("/pair-setup", Tlv8.STATE to Tlv8.byte(5), Tlv8.ENCRYPTED_DATA to Aead.seal(encKey, Aead.nonce("PS-Msg05"), sub))
                assertEquals(null, m6[Tlv8.ERROR])
                val accSub = Tlv8.decode(Aead.open(encKey, Aead.nonce("PS-Msg06"), m6[Tlv8.ENCRYPTED_DATA]!!)!!)
                val accX = Hkdf.sha512(k, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
                assertTrue(Ed25519.verify(accSub[Tlv8.PUBLIC_KEY]!!, accX + accSub[Tlv8.IDENTIFIER]!! + accSub[Tlv8.PUBLIC_KEY]!!, accSub[Tlv8.SIGNATURE]!!))
            }
            assertTrue(identity.isPaired)

            // Pair verify, then encrypted traffic on the same socket
            val http = Http(Socket("127.0.0.1", 51899))
            val priv = randomBytes(32); val pub = X25519.publicKey(priv)
            val v2 = http.tlv("/pair-verify", Tlv8.STATE to Tlv8.byte(1), Tlv8.PUBLIC_KEY to pub)
            val accPub = v2[Tlv8.PUBLIC_KEY]!!
            val shared = X25519.scalarMult(priv, accPub)
            val sk = Hkdf.sha512(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
            val accSub = Tlv8.decode(Aead.open(sk, Aead.nonce("PV-Msg02"), v2[Tlv8.ENCRYPTED_DATA]!!)!!)
            assertTrue(Ed25519.verify(identity.publicKey, accPub + accSub[Tlv8.IDENTIFIER]!! + pub, accSub[Tlv8.SIGNATURE]!!))
            val mySig = Ed25519.sign(ctrlSeed, pub + ctrlId + accPub)
            val v4 = http.tlv("/pair-verify", Tlv8.STATE to Tlv8.byte(3),
                Tlv8.ENCRYPTED_DATA to Aead.seal(sk, Aead.nonce("PV-Msg03"), Tlv8.encode(Tlv8.IDENTIFIER to ctrlId, Tlv8.SIGNATURE to mySig)))
            assertEquals(null, v4[Tlv8.ERROR])
            http.writeKey = Hkdf.sha512(shared, "Control-Salt", "Control-Write-Encryption-Key")
            http.readKey = Hkdf.sha512(shared, "Control-Salt", "Control-Read-Encryption-Key")

            val (st, body) = http.request("GET", "/accessories", null)
            assertTrue(st, st.contains("200"))
            val db = JSONObject(String(body))
            assertEquals("41", db.getJSONArray("accessories").getJSONObject(0).getJSONArray("services").getJSONObject(2).getString("type"))

            val (st2, body2) = http.request("GET", "/characteristics?id=1.11,1.12", null)
            assertTrue(st2.contains("200"))
            assertEquals(1, JSONObject(String(body2)).getJSONArray("characteristics").getJSONObject(0).getInt("value"))

            val sub = """{"characteristics":[{"aid":1,"iid":11,"ev":true}]}""".toByteArray()
            assertTrue(http.request("PUT", "/characteristics", "application/hap+json", sub).first.contains("204"))

            val open = """{"characteristics":[{"aid":1,"iid":12,"value":0}]}""".toByteArray()
            assertTrue(http.request("PUT", "/characteristics", "application/hap+json", open).first.contains("204"))
            assertEquals(listOf(0), door.commands)

            door.current = 2
            server.notifyChanged(11, 2)
            val (evLine, evBody) = http.readMessage()
            assertTrue(evLine, evLine.startsWith("EVENT/1.0 200"))
            assertEquals(2, JSONObject(String(evBody)).getJSONArray("characteristics").getJSONObject(0).getInt("value"))

            // Remove the only admin: the accessory becomes unpaired again.
            val list = Tlv8.decode(http.request("POST", "/pairings", "application/pairing+tlv8",
                Tlv8.encode(Tlv8.STATE to Tlv8.byte(1), Tlv8.METHOD to Tlv8.byte(5))).second)
            assertNotNull(list[Tlv8.IDENTIFIER])
            http.request("POST", "/pairings", "application/pairing+tlv8",
                Tlv8.encode(Tlv8.STATE to Tlv8.byte(1), Tlv8.METHOD to Tlv8.byte(4), Tlv8.IDENTIFIER to ctrlId))
            assertTrue(!identity.isPaired)
        } finally {
            server.stop()
            File(ctx.filesDir, "hap-test.bin").delete()
        }
    }
}
