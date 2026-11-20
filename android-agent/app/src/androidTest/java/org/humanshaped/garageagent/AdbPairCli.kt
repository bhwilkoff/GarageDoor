package org.humanshaped.garageagent

import org.humanshaped.garageagent.hap.Ed25519
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec

/**
 * Checks the pieces of on-device pairing that need no pairing code, in its own
 * process so the live agent is untouched:
 *   adb shell CLASSPATH=<app.apk>:<test.apk> app_process / org.humanshaped.garageagent.AdbPairCli <adbkey.pk8> <adbkey.pub>
 * The key pair comes from `adb keygen`, whose .pub is the reference encoding.
 */
object AdbPairCli {
    @JvmStatic
    fun main(args: Array<String>) {
        var failures = 0
        fun check(name: String, ok: Boolean) { println("${if (ok) "PASS" else "FAIL"} $name"); if (!ok) failures++ }

        val priv = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(File(args[0]).readBytes())) as RSAPrivateCrtKey
        val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(priv.modulus, priv.publicExponent)) as RSAPublicKey
        val reference = File(args[1]).readText().trim().substringBefore(' ')
        val ours = AdbPair.androidPublicKey(pub, "x").substringBefore(' ')
        check("public key encoding matches adb keygen", ours == reference)

        val der = AdbSelf.selfSignedCert(priv, pub.encoded)
        val cert = runCatching { CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) }.getOrNull()
        check("certificate parses", cert != null)
        check("certificate verifies with its own key", runCatching { cert!!.verify(pub); true }.getOrDefault(false))
        check("certificate carries the same key", cert?.publicKey?.encoded?.contentEquals(pub.encoded) == true)

        fun derive(seed: String): ByteArray {
            var v = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
            while (Ed25519.decompress(v) == null) v = MessageDigest.getInstance("SHA-256").digest(v)
            return v
        }
        check("M is BoringSSL's point", derive("edwards25519 point generation seed (M)").contentEquals(Ed25519.compress(AdbPair.Spake2Client.M)))
        check("N is BoringSSL's point", derive("edwards25519 point generation seed (N)").contentEquals(Ed25519.compress(AdbPair.Spake2Client.N)))

        val a = AdbPair.Spake2Client("123456".toByteArray())
        check("SPAKE2 message is a curve point", Ed25519.decompress(a.msg) != null)
        check("HKDF-SHA256 RFC 5869 case 3", AdbPair.hkdfSha256(ByteArray(22) { 0x0b }, ByteArray(0), 42).joinToString("") { "%02x".format(it) } ==
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8")

        println(if (failures == 0) "ALL PASS" else "$failures FAILED")
    }
}
