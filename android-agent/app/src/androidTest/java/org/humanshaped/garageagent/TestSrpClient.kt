package org.humanshaped.garageagent

import org.humanshaped.garageagent.hap.SrpServer
import org.humanshaped.garageagent.hap.randomBytes
import java.math.BigInteger

/** The controller side of HomeKit SRP, for tests only. */
class TestSrpClient(private val code: String) {
    private val a = BigInteger(1, randomBytes(32))
    private val A = SrpServer.g.modPow(a, SrpServer.N)
    lateinit var sessionKey: ByteArray
    private lateinit var m1: ByteArray

    fun respond(salt: ByteArray, bBytes: ByteArray): Pair<ByteArray, ByteArray> {
        val N = SrpServer.N; val g = SrpServer.g
        val B = BigInteger(1, bBytes)
        val u = SrpServer.hInt(SrpServer.pad(A), SrpServer.pad(B))
        val x = SrpServer.x(salt, code)
        val base = B.subtract(SrpServer.k.multiply(g.modPow(x, N))).mod(N)
        val S = base.modPow(a.add(u.multiply(x)), N)
        sessionKey = SrpServer.h(SrpServer.minimal(S))
        val hn = SrpServer.h(SrpServer.minimal(N)); val hg = SrpServer.h(SrpServer.minimal(g))
        val xor = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
        m1 = SrpServer.h(xor, SrpServer.h(SrpServer.USER.toByteArray()), salt, SrpServer.minimal(A), SrpServer.minimal(B), sessionKey)
        return SrpServer.minimal(A) to m1
    }

    fun expectedM2(): ByteArray = SrpServer.h(SrpServer.minimal(A), m1, sessionKey)
}
