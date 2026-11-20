package org.humanshaped.garageagent

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.Settings
import org.humanshaped.garageagent.hap.Vault
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * Connects to this phone's own wireless debugging over loopback with a
 * dedicated paired key, to run shell-level commands (granting the agent's
 * permissions, starting DisplayHost) without any computer.
 */
object AdbSelf {
    const val CONNECT_SERVICE = "_adb-tls-connect._tcp"
    const val PAIRING_SERVICE = "_adb-tls-pairing._tcp"
    const val KEY_NAME = "garage-agent"
    private const val ALIAS = "garage_agent_adb"
    private const val A_CNXN = 0x4e584e43
    private const val A_STLS = 0x534c5453
    private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f
    private const val A_WRTE = 0x45545257
    private const val A_CLSE = 0x45534c43
    private const val A_AUTH = 0x48545541

    private lateinit var ctx: Context
    @Volatile var lastShellOk: Boolean? = null
        private set

    fun init(context: Context) { if (!::ctx.isInitialized) ctx = context.applicationContext }

    private fun vaultFile() = File(ctx.filesDir, "adb-key.bin")

    fun hasKey(): Boolean = ::ctx.isInitialized && vaultFile().exists()

    /**
     * Seals a key and certificate pushed by setup.sh into the Keystore-encrypted
     * vault, then deletes the plaintext. (A key held inside AndroidKeyStore cannot
     * do the raw RSA operation TLS 1.3's RSA-PSS needs.)
     */
    fun importProvisionedKey(context: Context) {
        init(context)
        val keyFile = File(ctx.filesDir, "adbkey.pk8")
        val certFile = File(ctx.filesDir, "adbkey.crt")
        if (!keyFile.exists() || !certFile.exists()) return
        try {
            val pk8 = keyFile.readBytes(); val crt = certFile.readBytes()
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pk8))
            CertificateFactory.getInstance("X.509").generateCertificate(crt.inputStream())
            seal(pk8, crt)
            Agent.log("Sealed the agent's adb key in the vault")
        } catch (e: Exception) {
            Agent.log("adb key import failed: ${e.message}")
        } finally {
            keyFile.delete(); certFile.delete()
        }
    }

    private fun seal(pk8: ByteArray, crt: ByteArray) {
        val blob = ByteBuffer.allocate(4 + pk8.size + crt.size).putInt(pk8.size).put(pk8).put(crt).array()
        Vault.write(vaultFile(), blob)
    }

    /** The agent's adb key, created on first use. */
    @Synchronized
    fun keyPair(): Pair<PrivateKey, X509Certificate> {
        if (!hasKey()) {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            seal(kp.private.encoded, selfSignedCert(kp.private, kp.public.encoded))
            Agent.log("Created the agent's own adb key")
        }
        val blob = ByteBuffer.wrap(Vault.read(vaultFile())!!)
        val pk8 = ByteArray(blob.int).also { blob.get(it) }
        val crt = ByteArray(blob.remaining()).also { blob.get(it) }
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pk8))
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(crt.inputStream()) as X509Certificate
        return key to cert
    }

    // ---- a minimal self-signed X.509 certificate (adb only checks the public key) ----

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val len = content.size
        val head = when {
            len < 0x80 -> byteArrayOf(len.toByte())
            len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
            else -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
        }
        return byteArrayOf(tag.toByte()) + head + content
    }
    private fun seq(vararg parts: ByteArray) = der(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    private fun time(tag: Int, pattern: String, d: Date) =
        der(tag, SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d).toByteArray())

    internal fun selfSignedCert(key: PrivateKey, spki: ByteArray): ByteArray {
        val sha256Rsa = seq(der(0x06, byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b)), byteArrayOf(0x05, 0x00))
        val name = seq(der(0x31, seq(der(0x06, byteArrayOf(0x55, 0x04, 0x03)), der(0x0c, KEY_NAME.toByteArray()))))
        val now = Date()
        val tbs = seq(
            der(0xa0, der(0x02, byteArrayOf(2))),
            der(0x02, BigInteger(63, java.security.SecureRandom()).add(BigInteger.ONE).toByteArray()),
            sha256Rsa, name,
            seq(time(0x17, "yyMMddHHmmss'Z'", now), time(0x18, "yyyyMMddHHmmss'Z'", Date(now.time + 100L * 365 * 86_400_000L))),
            name, spki,
        )
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(key); update(tbs) }.sign()
        return seq(tbs, sha256Rsa, der(0x03, byteArrayOf(0) + sig))
    }

    fun tlsContext(key: PrivateKey, cert: X509Certificate): SSLContext {
        val chain = arrayOf(cert)
        val km = object : X509ExtendedKeyManager() {
            override fun getClientAliases(t: String?, i: Array<out Principal>?) = arrayOf(ALIAS)
            override fun chooseClientAlias(t: Array<out String>?, i: Array<out Principal>?, s: Socket?) = ALIAS
            override fun chooseEngineClientAlias(t: Array<out String>?, i: Array<out Principal>?, e: javax.net.ssl.SSLEngine?) = ALIAS
            override fun getServerAliases(t: String?, i: Array<out Principal>?) = null
            override fun chooseServerAlias(t: String?, i: Array<out Principal>?, s: Socket?) = null
            override fun getCertificateChain(a: String?) = chain
            override fun getPrivateKey(a: String?) = key
        }
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        }
        return SSLContext.getInstance("TLSv1.3").apply { init(arrayOf(km), arrayOf(tm), null) }
    }

    fun wirelessDebuggingOn(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1

    /** Turns wireless debugging back on after a reboot; possible only once the agent holds WRITE_SECURE_SETTINGS. */
    private fun ensureWirelessDebugging(context: Context) {
        if (wirelessDebuggingOn(context)) return
        try {
            val cr = context.contentResolver
            if (Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) != 1) Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
            Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
            Agent.log("Turned on wireless debugging")
            Thread.sleep(3000)
        } catch (e: SecurityException) {
            Agent.log("Wireless debugging is off, and the agent may not turn it on yet")
        }
    }

    /** Finds a wireless debugging service this phone advertises; returns host and port. */
    fun findService(context: Context, type: String, timeoutS: Long = 15): Pair<String, Int>? {
        val nsd = context.getSystemService(NsdManager::class.java)
        val latch = CountDownLatch(1)
        var found: Pair<String, Int>? = null
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        val mine = runCatching { java.net.NetworkInterface.getByInetAddress(r.host) != null }.getOrDefault(false)
                        if (mine && found == null) { found = r.host.hostAddress!! to r.port; latch.countDown() }
                    }
                    override fun onResolveFailed(i: NsdServiceInfo, code: Int) {}
                })
            }
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
        }
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        latch.await(timeoutS, TimeUnit.SECONDS)
        runCatching { nsd.stopServiceDiscovery(listener) }
        return found
    }

    private fun packet(cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val b = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size)
        b.putInt(data.fold(0) { acc, x -> acc + (x.toInt() and 0xFF) })
        b.putInt(cmd xor -1)
        b.put(data)
        return b.array()
    }

    private class Msg(val cmd: Int, val a0: Int, val a1: Int, val data: ByteArray)

    private fun read(input: InputStream): Msg {
        val d = DataInputStream(input)
        val h = ByteArray(24); d.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int
        val data = ByteArray(len); d.readFully(data)
        return Msg(cmd, a0, a1, data)
    }

    /** Runs one shell command as the shell user; returns its output, or null on failure. */
    fun shell(context: Context, command: String): String? {
        init(context)
        importProvisionedKey(ctx)
        if (!hasKey()) { Agent.log("The agent is not paired with wireless debugging yet"); return null }
        ensureWirelessDebugging(ctx)
        val (_, port) = findService(ctx, CONNECT_SERVICE) ?: run {
            Agent.log("Wireless debugging port not found (is wireless debugging on, and the phone on Wi-Fi?)")
            lastShellOk = false
            return null
        }
        val (key, cert) = keyPair()
        Socket().use { raw ->
            raw.connect(InetSocketAddress("127.0.0.1", port), 5000)
            raw.soTimeout = 15000
            raw.getOutputStream().write(packet(A_CNXN, 0x01000001, 1024 * 1024, "host::\u0000".toByteArray()))
            val first = read(raw.getInputStream())
            if (first.cmd != A_STLS) { Agent.log("adb: expected STLS, got ${Integer.toHexString(first.cmd)}"); lastShellOk = false; return null }
            raw.getOutputStream().write(packet(A_STLS, 0x01000000, 0))
            val tls = try {
                (tlsContext(key, cert).socketFactory.createSocket(raw, "127.0.0.1", port, true) as SSLSocket).also { it.startHandshake() }
            } catch (e: javax.net.ssl.SSLException) {
                Agent.log("adb: this phone does not accept the agent's key (not paired, or the pairing was removed)")
                lastShellOk = false
                return null
            }
            val input = tls.inputStream
            val out: OutputStream = tls.outputStream
            val cnxn = read(input)
            if (cnxn.cmd == A_AUTH) { Agent.log("adb: this key is not paired"); lastShellOk = false; return null }
            if (cnxn.cmd != A_CNXN) { Agent.log("adb: expected CNXN, got ${Integer.toHexString(cnxn.cmd)}"); lastShellOk = false; return null }
            // No pty: a pty session hangs up its children when we disconnect.
            out.write(packet(A_OPEN, 1, 0, "shell,raw:$command\u0000".toByteArray()))
            val result = ByteArrayOutputStream()
            while (true) {
                val m = read(input)
                when (m.cmd) {
                    A_OKAY -> {}
                    A_WRTE -> { result.write(m.data); out.write(packet(A_OKAY, 1, m.a0)) }
                    A_CLSE -> { out.write(packet(A_CLSE, 1, m.a0)); break }
                }
            }
            tls.close()
            lastShellOk = true
            return result.toString(Charsets.UTF_8.name())
        }
    }

    /**
     * Everything setup.sh grants from a computer, granted through the agent's own
     * shell instead: secure settings, the accessibility service, the notification
     * watcher, and the battery exemption.
     */
    fun grantAll(context: Context): Boolean {
        val pkg = context.packageName
        val svc = "$pkg/${AgentService::class.java.name}"
        val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val wanted = (current.split(":").filter { it.isNotBlank() && it != svc } + svc).joinToString(":")
        context.getSharedPreferences("agent", Context.MODE_PRIVATE).edit().putBoolean("a11yWanted", true).apply()
        val cmd = listOf(
            "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
            "pm grant $pkg android.permission.POST_NOTIFICATIONS",
            "appops set $pkg RUN_ANY_IN_BACKGROUND allow",
            "dumpsys deviceidle whitelist +$pkg >/dev/null",
            "cmd notification allow_listener $pkg/${MyqNotificationWatcher::class.java.name}",
            "settings put secure enabled_accessibility_services '$wanted'",
            "settings put secure accessibility_enabled 1",
        ).joinToString("; ") { "$it 2>/dev/null" } + "; echo granted"
        val out = runCatching { shell(context, cmd) }.onFailure { Agent.log("Granting failed: ${it.javaClass.simpleName} ${it.message}") }.getOrNull()
        val ok = out?.contains("granted") == true
        Agent.log(if (ok) "Granted the agent's permissions through wireless debugging" else "Could not grant the agent's permissions")
        return ok
    }

    /** Starts DisplayHost as the shell user, and waits until its display exists before hanging up. */
    fun startDisplayHost(context: Context): Boolean {
        val apk = context.applicationInfo.sourceDir
        val cmd = "CLASSPATH=$apk setsid nohup app_process / ${DisplayHost::class.java.name} " +
            "> /data/local/tmp/displayhost.log 2>&1 < /dev/null & " +
            "for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do " +
            "grep -q DISPLAY /data/local/tmp/displayhost.log && { sleep 2; echo started; break; }; sleep 0.5; done"
        val out = runCatching { shell(context, cmd) }.onFailure { Agent.log("adb self-connect failed: ${it.javaClass.simpleName} ${it.message}") }.getOrNull()
        if (out?.contains("started") == true) { Agent.log("Started the hidden display host"); return true }
        return false
    }
}
