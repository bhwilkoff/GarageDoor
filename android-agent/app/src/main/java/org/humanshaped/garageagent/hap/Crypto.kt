package org.humanshaped.garageagent.hap

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

val secureRandom = SecureRandom()

fun randomBytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }

fun sha512(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-512")
    parts.forEach { md.update(it) }
    return md.digest()
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

object Tlv8 {
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val STATE = 0x06
    const val ERROR = 0x07
    const val SIGNATURE = 0x0A
    const val PERMISSIONS = 0x0B
    const val FLAGS = 0x13
    const val SEPARATOR = 0xFF

    const val ERR_UNKNOWN = 1
    const val ERR_AUTHENTICATION = 2
    const val ERR_MAX_TRIES = 5
    const val ERR_UNAVAILABLE = 6
    const val ERR_BUSY = 7

    fun encode(items: List<Pair<Int, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((type, value) in items) {
            if (value.isEmpty()) {
                out.write(type); out.write(0); continue
            }
            var off = 0
            while (off < value.size) {
                val n = minOf(255, value.size - off)
                out.write(type); out.write(n); out.write(value, off, n)
                off += n
            }
        }
        return out.toByteArray()
    }

    fun encode(vararg items: Pair<Int, ByteArray>): ByteArray = encode(items.toList())

    /** Consecutive items of the same type are fragments of one value. */
    fun decode(data: ByteArray): Map<Int, ByteArray> {
        val result = LinkedHashMap<Int, ByteArray>()
        var i = 0
        var lastType = -1
        while (i + 2 <= data.size) {
            val type = data[i].toInt() and 0xFF
            val len = data[i + 1].toInt() and 0xFF
            require(i + 2 + len <= data.size) { "TLV overruns buffer" }
            val value = data.copyOfRange(i + 2, i + 2 + len)
            result[type] = if (type == lastType) result[type]!! + value else value
            lastType = type
            i += 2 + len
        }
        return result
    }

    fun byte(v: Int) = byteArrayOf(v.toByte())
}

object Hkdf {
    fun sha512(ikm: ByteArray, salt: String, info: String, length: Int = 32): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(salt.toByteArray(), "HmacSHA512"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA512"))
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            mac.update(t); mac.update(info.toByteArray()); mac.update(counter.toByte())
            t = mac.doFinal()
            out.write(t)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }
}

object Aead {
    private fun cipher(): Cipher = try {
        Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
    } catch (e: Exception) {
        Cipher.getInstance("ChaCha20-Poly1305")
    }

    fun nonce(label: String): ByteArray = ByteArray(4) + label.toByteArray(Charsets.US_ASCII)

    fun nonce(counter: Long): ByteArray {
        val n = ByteArray(12)
        for (i in 0 until 8) n[4 + i] = ((counter ushr (8 * i)) and 0xFF).toByte()
        return n
    }

    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        val c = cipher()
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(plaintext)
    }

    /** Returns null when the tag does not verify. */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray? = try {
        val c = cipher()
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        if (aad != null) c.updateAAD(aad)
        c.doFinal(sealed)
    } catch (e: Exception) {
        null
    }
}

private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))

internal fun leToInt(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

internal fun intToLe(v: BigInteger, len: Int = 32): ByteArray {
    val be = v.toByteArray()
    val out = ByteArray(len)
    for (i in be.indices) {
        val dst = be.size - 1 - i
        if (dst < len) out[dst] = be[i]
    }
    return out
}

/** RFC 7748 X25519. */
object X25519 {
    private val A24 = BigInteger.valueOf(121665)

    fun scalarMult(k: ByteArray, u: ByteArray): ByteArray {
        val kb = k.copyOf()
        kb[0] = (kb[0].toInt() and 248).toByte()
        kb[31] = ((kb[31].toInt() and 127) or 64).toByte()
        val scalar = leToInt(kb)
        val ub = u.copyOf(); ub[31] = (ub[31].toInt() and 127).toByte()
        val x1 = leToInt(ub).mod(P)
        var x2 = BigInteger.ONE; var z2 = BigInteger.ZERO
        var x3 = x1; var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val kt = if (scalar.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap == 1) { val tx = x2; x2 = x3; x3 = tx; val tz = z2; z2 = z3; z3 = tz }
            swap = kt
            val a = x2.add(z2).mod(P); val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P); val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P); val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P); val cb = c.multiply(b).mod(P)
            x3 = da.add(cb).pow(2).mod(P)
            z3 = x1.multiply(da.subtract(cb).pow(2)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
        }
        if (swap == 1) { x2 = x3; z2 = z3 }
        return intToLe(x2.multiply(z2.modPow(P.subtract(BigInteger.TWO), P)).mod(P))
    }

    fun publicKey(priv: ByteArray): ByteArray = scalarMult(priv, ByteArray(32).also { it[0] = 9 })
}

/** RFC 8032 Ed25519, following the RFC's reference algorithm. */
object Ed25519 {
    internal val L = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val SQRT_M1 = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

    internal class Pt(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    internal val ZERO_PT = Pt(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
    internal val G: Pt

    init {
        val gy = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
        val gx = recoverX(gy, 0)!!
        G = Pt(gx, gy, BigInteger.ONE, gx.multiply(gy).mod(P))
    }

    internal fun add(p: Pt, q: Pt): Pt {
        val a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P)
        val b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P)
        val c = BigInteger.TWO.multiply(p.t).multiply(q.t).multiply(D).mod(P)
        val d = BigInteger.TWO.multiply(p.z).multiply(q.z).mod(P)
        val e = b.subtract(a); val f = d.subtract(c); val g = d.add(c); val h = b.add(a)
        return Pt(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    internal fun neg(p: Pt): Pt = Pt(P.subtract(p.x).mod(P), p.y, p.z, P.subtract(p.t).mod(P))

    internal fun mul(s0: BigInteger, p0: Pt): Pt {
        var s = s0; var p = p0; var q = ZERO_PT
        while (s.signum() > 0) {
            if (s.testBit(0)) q = add(q, p)
            p = add(p, p)
            s = s.shiftRight(1)
        }
        return q
    }

    private fun equal(p: Pt, q: Pt): Boolean =
        p.x.multiply(q.z).subtract(q.x.multiply(p.z)).mod(P).signum() == 0 &&
            p.y.multiply(q.z).subtract(q.y.multiply(p.z)).mod(P).signum() == 0

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null
        val x2 = y.multiply(y).subtract(BigInteger.ONE)
            .multiply(D.multiply(y).multiply(y).add(BigInteger.ONE).modInverse(P)).mod(P)
        if (x2.signum() == 0) return if (sign == 1) null else BigInteger.ZERO
        var x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) x = x.multiply(SQRT_M1).mod(P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) return null
        if ((if (x.testBit(0)) 1 else 0) != sign) x = P.subtract(x)
        return x
    }

    internal fun compress(p: Pt): ByteArray {
        val zi = p.z.modInverse(P)
        val x = p.x.multiply(zi).mod(P)
        val y = p.y.multiply(zi).mod(P)
        val v = if (x.testBit(0)) y.setBit(255) else y
        return intToLe(v)
    }

    internal fun decompress(s: ByteArray): Pt? {
        if (s.size != 32) return null
        var y = leToInt(s)
        val sign = if (y.testBit(255)) 1 else 0
        y = y.clearBit(255)
        val x = recoverX(y, sign) ?: return null
        return Pt(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    private fun expand(seed: ByteArray): Pair<BigInteger, ByteArray> {
        val h = sha512(seed)
        val a = h.copyOfRange(0, 32)
        a[0] = (a[0].toInt() and 248).toByte()
        a[31] = ((a[31].toInt() and 63) or 64).toByte()
        return leToInt(a) to h.copyOfRange(32, 64)
    }

    private fun hashModL(vararg parts: ByteArray): BigInteger = leToInt(sha512(*parts)).mod(L)

    fun publicKey(seed: ByteArray): ByteArray = compress(mul(expand(seed).first, G))

    fun sign(seed: ByteArray, msg: ByteArray): ByteArray {
        val (a, prefix) = expand(seed)
        val pub = compress(mul(a, G))
        val r = hashModL(prefix, msg)
        val rs = compress(mul(r, G))
        val h = hashModL(rs, pub, msg)
        val s = r.add(h.multiply(a)).mod(L)
        return rs + intToLe(s)
    }

    fun verify(pub: ByteArray, msg: ByteArray, sig: ByteArray): Boolean {
        if (pub.size != 32 || sig.size != 64) return false
        val a = decompress(pub) ?: return false
        val rs = sig.copyOfRange(0, 32)
        val r = decompress(rs) ?: return false
        val s = leToInt(sig.copyOfRange(32, 64))
        if (s >= L) return false
        val h = hashModL(rs, pub, msg)
        return equal(mul(s, G), add(r, mul(h, a)))
    }
}

/**
 * SRP-6a server as HomeKit uses it: 3072-bit group from RFC 5054, g = 5,
 * SHA-512, username "Pair-Setup". k and u hash N-padded values; the proof
 * hashes minimal big-endian encodings (the convention HAP accessories use).
 */
class SrpServer(password: String, val salt: ByteArray = randomBytes(16), privateB: ByteArray = randomBytes(32)) {
    companion object {
        val N = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
            16,
        )
        val g: BigInteger = BigInteger.valueOf(5)
        const val USER = "Pair-Setup"
        private const val NLEN = 384

        fun pad(v: BigInteger): ByteArray {
            val m = minimal(v)
            return ByteArray(NLEN - m.size) + m
        }

        fun minimal(v: BigInteger): ByteArray {
            val b = v.toByteArray()
            return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
        }

        fun h(vararg parts: ByteArray) = sha512(*parts)
        fun hInt(vararg parts: ByteArray) = BigInteger(1, sha512(*parts))

        val k: BigInteger = hInt(pad(N), pad(g))

        fun x(salt: ByteArray, password: String): BigInteger =
            hInt(salt, h("$USER:$password".toByteArray()))
    }

    private val v = g.modPow(x(salt, password), N)
    private val b = BigInteger(1, privateB)
    val B: BigInteger = k.multiply(v).add(g.modPow(b, N)).mod(N)
    var sessionKey: ByteArray? = null
        private set
    private var expectedM1: ByteArray? = null
    private var aBytes: ByteArray? = null

    fun publicKey(): ByteArray = minimal(B)

    /** Returns false when A is invalid. */
    fun setClientPublic(a: ByteArray): Boolean {
        val A = BigInteger(1, a)
        if (A.mod(N).signum() == 0) return false
        val u = hInt(pad(A), pad(B))
        if (u.signum() == 0) return false
        val S = A.multiply(v.modPow(u, N)).modPow(b, N)
        val K = h(minimal(S))
        sessionKey = K
        aBytes = minimal(A)
        val hn = h(minimal(N)); val hg = h(minimal(g))
        val xor = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
        expectedM1 = h(xor, h(USER.toByteArray()), salt, minimal(A), minimal(B), K)
        return true
    }

    /** Verifies the client proof; on success returns the server proof M2. */
    fun verify(m1: ByteArray): ByteArray? {
        val exp = expectedM1 ?: return null
        if (!MessageDigest.isEqual(exp, m1)) return null
        return h(aBytes!!, m1, sessionKey!!)
    }
}
