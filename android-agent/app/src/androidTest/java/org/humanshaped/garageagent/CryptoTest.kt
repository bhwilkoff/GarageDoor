package org.humanshaped.garageagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.humanshaped.garageagent.hap.Aead
import org.humanshaped.garageagent.hap.Ed25519
import org.humanshaped.garageagent.hap.SrpServer
import org.humanshaped.garageagent.hap.Tlv8
import org.humanshaped.garageagent.hap.X25519
import org.humanshaped.garageagent.hap.hex
import org.humanshaped.garageagent.hap.randomBytes
import org.humanshaped.garageagent.hap.unhex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CryptoTest {
    @Test fun x25519Rfc7748() {
        val alice = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".unhex()
        val bob = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb".unhex()
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", X25519.publicKey(alice).hex())
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", X25519.publicKey(bob).hex())
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(shared, X25519.scalarMult(alice, X25519.publicKey(bob)).hex())
        assertEquals(shared, X25519.scalarMult(bob, X25519.publicKey(alice)).hex())
    }

    @Test fun ed25519Rfc8032() {
        val seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".unhex()
        val pub = Ed25519.publicKey(seed)
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", pub.hex())
        val sig = Ed25519.sign(seed, ByteArray(0))
        assertEquals(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            sig.hex(),
        )
        assertTrue(Ed25519.verify(pub, ByteArray(0), sig))
        assertFalse(Ed25519.verify(pub, byteArrayOf(1), sig))
        val seed2 = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb".unhex()
        val sig2 = Ed25519.sign(seed2, byteArrayOf(0x72))
        assertEquals(
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
            sig2.hex(),
        )
    }

    @Test fun chachaPolyRfc8439() {
        val key = "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f".unhex()
        val nonce = "070000004041424344454647".unhex()
        val aad = "50515253c0c1c2c3c4c5c6c7".unhex()
        val pt = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".toByteArray()
        val sealed = Aead.seal(key, nonce, pt, aad)
        assertEquals("1ae10b594f09e26a7e902ecbd0600691", sealed.copyOfRange(sealed.size - 16, sealed.size).hex())
        assertEquals("d31a8d34648e60db7b86afbc53ef7ec2", sealed.copyOfRange(0, 16).hex())
        assertArrayEquals(pt, Aead.open(key, nonce, sealed, aad))
        sealed[3] = (sealed[3] + 1).toByte()
        assertNull(Aead.open(key, nonce, sealed, aad))
    }

    @Test fun tlvFragmentsRoundTrip() {
        val big = ByteArray(600) { it.toByte() }
        val enc = Tlv8.encode(Tlv8.STATE to Tlv8.byte(2), Tlv8.PUBLIC_KEY to big, Tlv8.SALT to ByteArray(16))
        val dec = Tlv8.decode(enc)
        assertArrayEquals(big, dec[Tlv8.PUBLIC_KEY])
        assertEquals(2, dec[Tlv8.STATE]!![0].toInt())
        assertEquals(16, dec[Tlv8.SALT]!!.size)
    }

    @Test fun srpClientAndServerAgree() {
        repeat(3) {
            val code = "123-45-679"
            val server = SrpServer(code)
            val client = TestSrpClient(code)
            val (a, m1) = client.respond(server.salt, server.publicKey())
            assertTrue(server.setClientPublic(a))
            val m2 = server.verify(m1)
            assertNotNull(m2)
            assertArrayEquals(client.expectedM2(), m2)
            assertArrayEquals(client.sessionKey, server.sessionKey)
            val wrong = SrpServer("111-22-333", server.salt)
            val (a2, m1b) = TestSrpClient(code).respond(wrong.salt, wrong.publicKey())
            assertTrue(wrong.setClientPublic(a2))
            assertNull(wrong.verify(m1b))
        }
    }

    @Test fun doorTextParsing() {
        assertEquals(DoorPhase.CLOSED, DoorText.parse("Closed for 23 hours"))
        assertEquals(DoorPhase.OPENING, DoorText.parse("Opening for 2 seconds"))
        assertEquals(DoorPhase.OPEN, DoorText.parse("Open for 3 seconds"))
        assertEquals(DoorPhase.CLOSING, DoorText.parse("Closing for 3 seconds"))
        assertEquals(DoorPhase.UNKNOWN, DoorText.parse("Offline"))
        assertEquals(DoorPhase.UNKNOWN, DoorText.parse(null))
        assertEquals(64, randomBytes(64).size)
    }
}
