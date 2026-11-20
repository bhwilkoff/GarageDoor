package org.humanshaped.garageagent

import org.humanshaped.garageagent.hap.Aead
import org.humanshaped.garageagent.hap.Ed25519
import org.humanshaped.garageagent.hap.Hkdf
import org.humanshaped.garageagent.hap.Tlv8
import org.humanshaped.garageagent.hap.X25519
import org.humanshaped.garageagent.hap.randomBytes
import java.net.Socket

/** A HomeKit controller for on-device tests: pair, verify, then talk encrypted. */
class TestController(private val port: Int) {
    val seed = randomBytes(32)
    val publicKey = Ed25519.publicKey(seed)
    val id = "TEST-${System.currentTimeMillis()}".toByteArray()

    fun pair(code: String): Boolean {
        val http = HapLoopbackTest.Http(Socket("127.0.0.1", port))
        val m2 = http.tlv("/pair-setup", Tlv8.STATE to Tlv8.byte(1), Tlv8.METHOD to Tlv8.byte(0))
        if (m2[Tlv8.ERROR] != null) return false
        val srp = TestSrpClient(code)
        val (a, m1) = srp.respond(m2[Tlv8.SALT]!!, m2[Tlv8.PUBLIC_KEY]!!)
        val m4 = http.tlv("/pair-setup", Tlv8.STATE to Tlv8.byte(3), Tlv8.PUBLIC_KEY to a, Tlv8.PROOF to m1)
        if (m4[Tlv8.ERROR] != null) return false
        val k = srp.sessionKey
        val encKey = Hkdf.sha512(k, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
        val x = Hkdf.sha512(k, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
        val sub = Tlv8.encode(Tlv8.IDENTIFIER to id, Tlv8.PUBLIC_KEY to publicKey, Tlv8.SIGNATURE to Ed25519.sign(seed, x + id + publicKey))
        val m6 = http.tlv("/pair-setup", Tlv8.STATE to Tlv8.byte(5), Tlv8.ENCRYPTED_DATA to Aead.seal(encKey, Aead.nonce("PS-Msg05"), sub))
        return m6[Tlv8.ERROR] == null
    }

    fun session(): HapLoopbackTest.Http {
        val http = HapLoopbackTest.Http(Socket("127.0.0.1", port))
        val priv = randomBytes(32); val pub = X25519.publicKey(priv)
        val v2 = http.tlv("/pair-verify", Tlv8.STATE to Tlv8.byte(1), Tlv8.PUBLIC_KEY to pub)
        val accPub = v2[Tlv8.PUBLIC_KEY]!!
        val shared = X25519.scalarMult(priv, accPub)
        val sk = Hkdf.sha512(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
        val sig = Ed25519.sign(seed, pub + id + accPub)
        val v4 = http.tlv("/pair-verify", Tlv8.STATE to Tlv8.byte(3),
            Tlv8.ENCRYPTED_DATA to Aead.seal(sk, Aead.nonce("PV-Msg03"), Tlv8.encode(Tlv8.IDENTIFIER to id, Tlv8.SIGNATURE to sig)))
        check(v4[Tlv8.ERROR] == null) { "pair-verify failed" }
        http.writeKey = Hkdf.sha512(shared, "Control-Salt", "Control-Write-Encryption-Key")
        http.readKey = Hkdf.sha512(shared, "Control-Salt", "Control-Read-Encryption-Key")
        return http
    }

    fun unpair(http: HapLoopbackTest.Http) {
        http.request("POST", "/pairings", "application/pairing+tlv8",
            Tlv8.encode(Tlv8.STATE to Tlv8.byte(1), Tlv8.METHOD to Tlv8.byte(4), Tlv8.IDENTIFIER to id))
    }
}
