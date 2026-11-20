package org.humanshaped.garageagent

import android.net.ssl.SSLSockets
import org.humanshaped.garageagent.hap.Ed25519
import org.humanshaped.garageagent.hap.intToLe
import org.humanshaped.garageagent.hap.leToInt
import org.humanshaped.garageagent.hap.randomBytes
import org.humanshaped.garageagent.hap.sha512
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket

/**
 * The client half of Android's "Pair device with pairing code", so the
 * agent can pair its own key with this phone's wireless debugging from a
 * code the owner types, with no computer. Mirrors AOSP adb
 * (pairing_connection.cpp, pairing_auth.cpp, aes_128_gcm.cpp) and
 * BoringSSL's SPAKE2 over edwards25519 (spake25519.cc).
 */
object AdbPair {
    sealed class Result {
        object Paired : Result()
        object WrongCode : Result()
        class Failed(val reason: String) : Result()
    }

    private const val TYPE_SPAKE2 = 0
    private const val TYPE_PEER_INFO = 1
    private const val PEER_INFO_SIZE = 8192
    private val CLIENT_NAME = "adb pair client\u0000".toByteArray()
    private val SERVER_NAME = "adb pair server\u0000".toByteArray()

    fun pair(host: String, port: Int, code: String, keyName: String): Result {
        val (key, cert) = AdbSelf.keyPair()
        val pub = cert.publicKey as RSAPublicKey
        return try {
            Socket().use { raw ->
                raw.connect(InetSocketAddress(host, port), 5000)
                raw.soTimeout = 15000
                val tls = AdbSelf.tlsContext(key, cert).socketFactory.createSocket(raw, host, port, true) as SSLSocket
                tls.startHandshake()
                val ekm = SSLSockets.exportKeyingMaterial(tls, "adb-label\u0000", null, 64) ?: return Result.Failed("no TLS keying material")
                val spake = Spake2Client(code.toByteArray() + ekm)
                val out = tls.outputStream
                val input = DataInputStream(tls.inputStream)

                out.write(header(TYPE_SPAKE2, spake.msg.size) + spake.msg); out.flush()
                val theirMsg = readPacket(input, TYPE_SPAKE2)
                val gcm = Gcm(hkdfSha256(spake.finish(theirMsg) ?: return Result.Failed("bad SPAKE2 message"),
                    "adb pairing_auth aes-128-gcm key".toByteArray(), 16))

                val info = ByteArray(PEER_INFO_SIZE)
                info[0] = 0
                val keyString = androidPublicKey(pub, keyName).toByteArray()
                System.arraycopy(keyString, 0, info, 1, keyString.size)
                val sealed = gcm.seal(info)
                out.write(header(TYPE_PEER_INFO, sealed.size) + sealed); out.flush()
                val theirs = runCatching { readPacket(input, TYPE_PEER_INFO) }.getOrNull() ?: return Result.WrongCode
                if (gcm.open(theirs) == null) Result.WrongCode else Result.Paired
            }
        } catch (e: Exception) {
            Result.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun header(type: Int, len: Int): ByteArray =
        ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN).put(1).put(type.toByte()).putInt(len).array()

    private fun readPacket(input: DataInputStream, type: Int): ByteArray {
        val h = ByteArray(6); input.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.BIG_ENDIAN)
        val version = b.get().toInt(); val t = b.get().toInt(); val len = b.int
        require(version == 1 && t == type && len in 1..PEER_INFO_SIZE * 2) { "unexpected packet $version/$t/$len" }
        return ByteArray(len).also { input.readFully(it) }
    }

    /** BoringSSL SPAKE2, Alice's side, including its password-scalar adjustment. */
    internal class Spake2Client(password: ByteArray, privateSeed: ByteArray = randomBytes(64)) {
        private val privateScalar = leToInt(privateSeed).mod(Ed25519.L).shiftLeft(3)
        private val passwordHash = sha512(password)
        private val passwordScalar: BigInteger = run {
            var w = leToInt(passwordHash).mod(Ed25519.L)
            var order = Ed25519.L
            for (bit in 0..2) {
                if (w.testBit(bit)) w = w.add(order)
                order = order.shiftLeft(1)
            }
            w
        }
        val msg: ByteArray = Ed25519.compress(Ed25519.add(Ed25519.mul(privateScalar, Ed25519.G), Ed25519.mul(passwordScalar, M)))

        fun finish(theirMsg: ByteArray): ByteArray? {
            val q = Ed25519.decompress(theirMsg) ?: return null
            val unmasked = Ed25519.add(q, Ed25519.neg(Ed25519.mul(passwordScalar, N)))
            val shared = Ed25519.compress(Ed25519.mul(privateScalar, unmasked))
            val d = MessageDigest.getInstance("SHA-512")
            for (part in listOf(CLIENT_NAME, SERVER_NAME, msg, theirMsg, shared, passwordHash)) {
                d.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(part.size.toLong()).array())
                d.update(part)
            }
            return d.digest()
        }

        companion object {
            val M = Ed25519.decompress(unhex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))!!
            val N = Ed25519.decompress(unhex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))!!
        }
    }

    private class Gcm(key: ByteArray) {
        private val spec = SecretKeySpec(key, "AES")
        private var encSeq = 0L
        private var decSeq = 0L
        private fun nonce(seq: Long) = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(seq).array()
        fun seal(data: ByteArray): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(128, nonce(encSeq++))) }.doFinal(data)
        fun open(data: ByteArray): ByteArray? = runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(128, nonce(decSeq++))) }.doFinal(data)
        }.getOrNull()
    }

    internal fun hkdfSha256(ikm: ByteArray, info: ByteArray, len: Int): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ByteArray(32), "HmacSHA256")) }.doFinal(ikm)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        val out = ByteArrayOutputStream()
        var t = ByteArray(0); var i = 1
        while (out.size() < len) { mac.update(t); mac.update(info); mac.update(i.toByte()); t = mac.doFinal(); out.write(t); i++ }
        return out.toByteArray().copyOf(len)
    }

    /** adb's public key format (android_pubkey_encode, base64, then " name"). */
    internal fun androidPublicKey(pub: RSAPublicKey, name: String): String {
        val n = pub.modulus
        val two32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = two32.subtract(n.mod(two32).modInverse(two32)).mod(two32)
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
        val b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(64).putInt(n0inv.toInt())
        b.put(intToLe(n, 256)).put(intToLe(rr, 256))
        b.putInt(pub.publicExponent.toInt())
        return android.util.Base64.encodeToString(b.array(), android.util.Base64.NO_WRAP) + " " + name
    }

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
