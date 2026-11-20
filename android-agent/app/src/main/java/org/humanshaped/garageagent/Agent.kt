package org.humanshaped.garageagent

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import org.humanshaped.garageagent.hap.Bonjour
import org.humanshaped.garageagent.hap.GarageBackend
import org.humanshaped.garageagent.hap.HapIdentity
import org.humanshaped.garageagent.hap.HapServer
import org.humanshaped.garageagent.hap.HapStatus
import org.humanshaped.garageagent.hap.Iid
import org.humanshaped.garageagent.hap.sha512
import org.humanshaped.garageagent.hap.hex
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The process-wide agent: what we know about the door, the Apple Home
 * accessory that publishes it, and the queue of myQ operations.
 */
object Agent : GarageBackend {
    const val ACCESSORY_NAME = "Garage Door"
    const val HAP_PORT = 51827
    private const val STALE_MS = 30 * 60_000L
    private const val REFRESH_EVERY_MS = 60_000L
    private const val COMMAND_ACCEPT_WAIT_S = 10L

    private lateinit var appContext: Context
    lateinit var identity: HapIdentity
        private set
    private var server: HapServer? = null
    private var bonjour: Bonjour? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    @Volatile var driver: MyqDriver? = null
    @Volatile var snapshot = DoorSnapshot(DoorPhase.UNKNOWN, null, 0L, "Not checked yet")
        private set
    @Volatile private var target: Int? = null
    @Volatile private var obstruction = false
    @Volatile private var lastRefreshRequest = 0L
    /** While a command runs, the target is what was asked for, not what myQ's lagging text implies. */
    @Volatile var commandActive = false
    @Volatile var inFlight: DoorPhase? = null
    @Volatile var started = false
        private set

    private val logLines = ArrayDeque<String>()
    private lateinit var logFile: File
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun log(msg: String) {
        Log.i("GarageAgent", msg)
        val line = "${timeFmt.format(Date())} $msg"
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > 200) logLines.removeFirst()
            if (::logFile.isInitialized) {
                if (logFile.length() > 512_000) logFile.renameTo(File(logFile.parentFile, "agent.log.1"))
                logFile.appendText(line + "\n")
            }
        }
    }

    fun recentLog(n: Int): List<String> = synchronized(logLines) { logLines.toList().takeLast(n) }

    @Synchronized
    fun start(context: Context) {
        if (started) return
        appContext = context.applicationContext
        logFile = File(appContext.filesDir, "agent.log")
        identity = HapIdentity.load(appContext)
        AdbSelf.importProvisionedKey(appContext)
        val version = appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "1.0"
        val srv = HapServer(identity, this, ACCESSORY_NAME, version, HAP_PORT, ::log) { onPairingChanged() }
        val dbHash = sha512(srv.accessoryDatabase(includeValues = false).toString().toByteArray()).hex().take(16)
        if (identity.dbHash != dbHash) {
            if (identity.dbHash.isNotEmpty()) identity.configNumber++
            identity.dbHash = dbHash
            identity.save()
        }
        srv.start()
        server = srv
        bonjour = Bonjour(appContext, ::log).also { it.advertise(ACCESSORY_NAME, HAP_PORT, identity) }
        val wm = appContext.getSystemService(WifiManager::class.java)
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "garage-agent").apply { acquire() }
        scheduler.scheduleWithFixedDelay({ runCatching { periodicCheck() }.onFailure { log("Periodic check failed: ${it.message}") } }, 15, 15, TimeUnit.SECONDS)
        started = true
        log("Agent started (paired=${identity.isPaired}, setup code ${identity.setupCode})")
    }

    private fun onPairingChanged() {
        bonjour?.advertise(ACCESSORY_NAME, HAP_PORT, identity)
    }

    fun resetPairing() {
        identity.clearPairings()
        server?.let { s -> s.stop(); s.start() }
        onPairingChanged()
        log("Apple Home pairing reset")
    }

    fun controllerSessions(): Int = server?.connectionCount() ?: 0

    private var driverMissingSince = 0L

    /** A crashed accessibility service stays unbound until re-enabled; re-enable it ourselves. */
    private fun healAccessibility() {
        if (driver != null) { driverMissingSince = 0L; return }
        val now = System.currentTimeMillis()
        if (driverMissingSince == 0L) { driverMissingSince = now; return }
        if (now - driverMissingSince < 20_000L) return
        val cr = appContext.contentResolver
        val key = android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val me = "${appContext.packageName}/${AgentService::class.java.name}"
        val current = android.provider.Settings.Secure.getString(cr, key).orEmpty()
        val wanted = appContext.getSharedPreferences("agent", Context.MODE_PRIVATE).getBoolean("a11yWanted", false)
        if (me !in current && !wanted) return
        try {
            val others = current.split(":").filter { it.isNotBlank() && it != me }
            android.provider.Settings.Secure.putString(cr, key, others.joinToString(":"))
            Thread.sleep(800)
            android.provider.Settings.Secure.putString(cr, key, (others + me).joinToString(":"))
            log(if (me in current) "Re-enabled the accessibility service (it was enabled but not bound)" else "Re-added the accessibility service after it was removed (force-stop?)")
            driverMissingSince = now
        } catch (e: SecurityException) {
            log("Accessibility service is unbound and the agent lacks WRITE_SECURE_SETTINGS to fix it")
            driverMissingSince = now + 10 * 60_000L
        }
    }

    private var hostMissingSince = 0L
    private var nextHostAttempt = 0L
    private var hostFailures = 0

    /** Keeps the hidden display alive: if it is gone, restart DisplayHost through local adb. */
    private fun healDisplayHost() {
        val d = driver ?: return
        val now = System.currentTimeMillis()
        if (d.hiddenDisplayId() != null) { hostMissingSince = 0L; hostFailures = 0; return }
        if (hostMissingSince == 0L) { hostMissingSince = now; return }
        if (now - hostMissingSince < 20_000L || now < nextHostAttempt) return
        log("Hidden display is missing; restarting it")
        if (AdbSelf.startDisplayHost(appContext)) {
            hostFailures = 0
            nextHostAttempt = now + 60_000L
        } else {
            hostFailures++
            nextHostAttempt = now + minOf(30 * 60_000L, 60_000L shl minOf(hostFailures, 5))
        }
    }

    /** After pairing or granting: retry the hidden display now instead of waiting out the backoff. */
    fun healNow() {
        hostFailures = 0; nextHostAttempt = 0L
        hostMissingSince = System.currentTimeMillis() - 20_000L
        driverMissingSince = if (driver == null) System.currentTimeMillis() - 20_000L else 0L
        scheduler.execute { runCatching { periodicCheck() } }
    }

    private fun periodicCheck() {
        healAccessibility()
        healDisplayHost()
        if (driver == null) return
        if (System.currentTimeMillis() - snapshot.seenAt > REFRESH_EVERY_MS) requestRefresh("scheduled")
    }

    fun requestRefresh(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastRefreshRequest < 30_000L) return
        lastRefreshRequest = now
        val d = driver
        if (d == null) {
            report(DoorSnapshot(DoorPhase.UNKNOWN, null, snapshot.seenAt, "Accessibility service is off"))
            return
        }
        d.refresh(reason)
    }

    /** Called by the driver with every reading, successful or not. */
    fun report(s: DoorSnapshot) {
        val old = snapshot
        val merged = if (s.fault != null && s.phase == DoorPhase.UNKNOWN) s.copy(rawText = old.rawText) else s
        snapshot = merged
        if (merged.phase != old.phase || merged.fault != old.fault) {
            log("Door: ${merged.phase}${merged.rawText?.let { " (\"$it\")" } ?: ""}${merged.fault?.let { " fault: $it" } ?: ""}")
        }
        val newTarget = if (commandActive) target else when (merged.phase) {
            DoorPhase.OPEN, DoorPhase.OPENING -> 0
            DoorPhase.CLOSED, DoorPhase.CLOSING -> 1
            else -> target
        }
        val srv = server ?: return
        if (newTarget != null && newTarget != target) {
            target = newTarget
            srv.notifyChanged(Iid.TARGET_DOOR, newTarget)
        }
        val cur = currentDoorState()
        if (cur != null && (merged.phase != old.phase || old.fault != null)) srv.notifyChanged(Iid.CURRENT_DOOR, cur)
    }

    fun setObstruction(on: Boolean) {
        if (obstruction == on) return
        obstruction = on
        log("Obstruction: $on")
        server?.notifyChanged(Iid.OBSTRUCTION, on)
    }

    fun setTarget(t: Int) { target = t }

    // ---- GarageBackend ----

    override fun currentDoorState(): Int? {
        val s = snapshot
        val age = System.currentTimeMillis() - s.seenAt
        if (age > 2 * 60_000L) scheduler.execute { requestRefresh("Home asked, reading is ${age / 60_000} min old") }
        if (s.fault != null || age > STALE_MS) return null
        return s.phase.hap
    }

    override fun targetDoorState(): Int = target ?: when (snapshot.phase) {
        DoorPhase.OPEN, DoorPhase.OPENING -> 0
        else -> 1
    }

    override fun obstructionDetected(): Boolean = obstruction

    override fun setTargetDoorState(target: Int): Int {
        val d = driver ?: run {
            log("Command refused: accessibility service is off")
            return HapStatus.COMMUNICATION_FAILURE
        }
        val desired = if (target == 0) DoorPhase.OPEN else DoorPhase.CLOSED
        synchronized(this) {
            if (commandActive && inFlight == desired) {
                log("Repeat ${desired} while one is running; acknowledged")
                return HapStatus.SUCCESS
            }
            inFlight = desired
        }
        val accepted = try {
            d.command(desired).get(COMMAND_ACCEPT_WAIT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            log("Command ${desired} not accepted in time: ${e.javaClass.simpleName}")
            false
        }
        if (!accepted) return HapStatus.COMMUNICATION_FAILURE
        this.target = target
        return HapStatus.SUCCESS
    }

    override fun identify() = log("Identify requested by Apple Home")
}
