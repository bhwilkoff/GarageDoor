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
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The process-wide agent: what we know about each door, the Apple Home
 * accessory that publishes them, and the queue of myQ operations.
 *
 * Doors are matched to Home by their name in myQ, never by their position on
 * myQ's screen, so myQ reordering its list can never make the wrong door move.
 */
object Agent : GarageBackend {
    const val ACCESSORY_NAME = "Garage Door"
    const val HAP_PORT = 51827
    const val TEST_SLOT = 9
    private const val MAX_SLOT = 8
    private const val STALE_MS = 30 * 60_000L
    private const val REFRESH_EVERY_MS = 60_000L
    private const val COMMAND_ACCEPT_WAIT_S = 10L
    const val TEST_DOOR_NAME = "Test Door"

    class Door(val slot: Int, @Volatile var myqName: String?) {
        @Volatile var snapshot = DoorSnapshot(DoorPhase.UNKNOWN, null, 0L, "Not checked yet")
        @Volatile var target: Int? = null
        @Volatile var obstruction = false
        @Volatile var missingSince = 0L
        val isTest get() = slot == TEST_SLOT
        val label get() = if (isTest) "$TEST_DOOR_NAME (pretend)" else myqName ?: "the door"
    }

    private lateinit var appContext: Context
    lateinit var identity: HapIdentity
        private set
    private var server: HapServer? = null
    private var bonjour: Bonjour? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    @Volatile var driver: MyqDriver? = null
    private val doors = ConcurrentSkipListMap<Int, Door>()
    @Volatile private var lastRefreshRequest = 0L
    @Volatile var lastReadAt = 0L
        private set
    /** Something about myQ's screen that stops the agent from acting, in plain words. */
    @Volatile var screenProblem: String? = null
        private set
    /** While a command runs, the target is what was asked for, not what myQ's lagging text implies. */
    @Volatile var commandActive: Int? = null
    @Volatile var inFlight: DoorPhase? = null
    @Volatile var started = false
        private set

    fun doors(): List<Door> = doors.values.toList()
    fun realDoors(): List<Door> = doors.values.filter { !it.isTest }
    fun door(slot: Int): Door? = doors[slot]
    /** The first door's reading, for the parts of the app that show one door. */
    val snapshot: DoorSnapshot get() = doors[0]?.snapshot ?: DoorSnapshot(DoorPhase.UNKNOWN, null, 0L, "Not checked yet")

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

    // ---- Doors, remembered by name ----

    private fun prefs() = appContext.getSharedPreferences("agent", Context.MODE_PRIVATE)

    private fun loadDoors() {
        val saved = prefs().getString("doors", null)
        if (saved != null) {
            val o = JSONObject(saved)
            for (k in o.keys()) {
                val slot = k.toIntOrNull() ?: continue
                doors[slot] = Door(slot, o.optString(k).ifEmpty { null })
            }
        }
        if (doors[0] == null) doors[0] = Door(0, null)
        if (prefs().getBoolean("testDoor", false)) addTestDoor()
    }

    private fun saveDoors() {
        val o = JSONObject()
        for (d in realDoors()) o.put(d.slot.toString(), d.myqName ?: "")
        prefs().edit().putString("doors", o.toString()).apply()
    }

    private fun dbHash(srv: HapServer) = sha512(srv.accessoryDatabase(includeValues = false).toString().toByteArray()).hex().take(16)

    /** The set of doors changed: tell Home to read the accessory again. */
    private fun onDoorsChanged() {
        val srv = server ?: return
        val hash = dbHash(srv)
        if (hash == identity.dbHash) return
        identity.configNumber++
        identity.dbHash = hash
        identity.save()
        bonjour?.advertise(ACCESSORY_NAME, HAP_PORT, identity)
        log("Apple Home will now see ${realDoors().size} door${if (realDoors().size == 1) "" else "s"}${if (doors[TEST_SLOT] != null) " plus the test door" else ""}")
    }

    /** Removes a door myQ no longer shows. The first door is never removed. */
    fun forgetDoor(slot: Int) {
        if (slot == 0 || slot == TEST_SLOT) return
        val d = doors.remove(slot) ?: return
        prefs().edit().remove("label.$slot").apply()
        saveDoors()
        log("Removed \"${d.myqName}\" from Apple Home")
        onDoorsChanged()
    }

    // ---- The pretend door, for seeing two doors in Home without a second door ----

    private fun addTestDoor() {
        doors[TEST_SLOT] = Door(TEST_SLOT, TEST_DOOR_NAME).also {
            it.snapshot = DoorSnapshot(DoorPhase.CLOSED, "Closed", System.currentTimeMillis(), null)
            it.target = 1
        }
    }

    fun setTestDoor(on: Boolean) {
        if (on == (doors[TEST_SLOT] != null)) return
        prefs().edit().putBoolean("testDoor", on).apply()
        if (on) addTestDoor() else doors.remove(TEST_SLOT)
        log(if (on) "Test door added: it opens and closes in pretend only" else "Test door removed")
        onDoorsChanged()
    }

    private fun simulateTestDoor(d: Door, target: Int) {
        val moving = if (target == 0) DoorPhase.OPENING else DoorPhase.CLOSING
        val done = if (target == 0) DoorPhase.OPEN else DoorPhase.CLOSED
        if (d.snapshot.phase == done) { d.target = target; return }
        d.target = target
        reportDoor(d, DoorSnapshot(moving, moving.name.lowercase(), System.currentTimeMillis(), null))
        scheduler.schedule({ reportDoor(d, DoorSnapshot(done, done.name.lowercase(), System.currentTimeMillis(), null)) }, 6, TimeUnit.SECONDS)
    }

    // ---- Life cycle ----

    @Synchronized
    fun start(context: Context) {
        if (started) return
        appContext = context.applicationContext
        logFile = File(appContext.filesDir, "agent.log")
        identity = HapIdentity.load(appContext)
        AdbSelf.importProvisionedKey(appContext)
        loadDoors()
        val version = appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "1.0"
        val srv = HapServer(identity, this, ACCESSORY_NAME, version, HAP_PORT, ::log) { onPairingChanged() }
        val hash = dbHash(srv)
        if (identity.dbHash != hash) {
            if (identity.dbHash.isNotEmpty()) identity.configNumber++
            identity.dbHash = hash
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
        val wanted = prefs().getBoolean("a11yWanted", false)
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
        doors[TEST_SLOT]?.let { it.snapshot = it.snapshot.copy(seenAt = System.currentTimeMillis()) }
        if (driver == null) return
        if (System.currentTimeMillis() - lastReadAt > REFRESH_EVERY_MS) requestRefresh("scheduled")
    }

    fun requestRefresh(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastRefreshRequest < 30_000L) return
        lastRefreshRequest = now
        val d = driver
        if (d == null) {
            reportFault("Accessibility service is off")
            return
        }
        d.refresh(reason)
    }

    // ---- Readings from myQ ----

    /** A reading that could not see the doors at all: every real door gets the fault. */
    fun reportFault(fault: String, slot: Int? = null) {
        for (d in realDoors()) if (slot == null || d.slot == slot) reportDoor(d, DoorSnapshot(DoorPhase.UNKNOWN, null, d.snapshot.seenAt, fault))
    }

    /** One reading of myQ's screen: every door card it showed. */
    fun reportCards(cards: List<MyqDriver.Card>) {
        val now = System.currentTimeMillis()
        lastReadAt = now
        val dupes = cards.mapNotNull { it.name }.groupBy { it }.filter { it.value.size > 1 }.keys
        screenProblem = when {
            dupes.isNotEmpty() -> "myQ shows more than one door named \"${dupes.first()}\". Give each door its own name in myQ, and Garage Agent will tell them apart."
            cards.size > 1 && cards.any { it.name == null } -> "myQ shows ${cards.size} doors, but Garage Agent could not read all of their names, so it will not press anything."
            else -> null
        }
        var changed = false
        val seen = mutableSetOf<Int>()
        for ((i, card) in cards.withIndex()) {
            if (card.name != null && card.name in dupes) continue
            var door = realDoors().firstOrNull { it.myqName != null && it.myqName == card.name }
            if (door == null && cards.size == 1 && realDoors().size == 1) door = doors[0]
            if (door == null && card.name != null) {
                val first = doors[0]
                if (first != null && first.myqName == null && i == 0) door = first
            }
            if (door == null && card.name != null) {
                // Slots are never reused, so a new door can't inherit a removed door's name or room in Home.
                val slot = maxOf(prefs().getInt("nextSlot", 1), (realDoors().maxOfOrNull { it.slot } ?: 0) + 1)
                if (slot > MAX_SLOT) { log("myQ shows more doors than Garage Agent supports; ignoring \"${card.name}\""); continue }
                prefs().edit().putInt("nextSlot", slot + 1).apply()
                door = Door(slot, card.name).also { doors[slot] = it }
                log("myQ shows a new door, \"${card.name}\"; adding it to Apple Home")
                changed = true
            }
            if (door == null) continue
            if (card.name != null && door.myqName != card.name) {
                if (door.myqName == null) changed = true
                door.myqName = card.name
            }
            seen.add(door.slot)
            door.missingSince = 0L
            val phase = DoorText.parse(card.status)
            reportDoor(door, DoorSnapshot(phase, card.status, now, if (phase == DoorPhase.UNKNOWN) "Unrecognized myQ status" else null))
        }
        for (d in realDoors()) {
            if (d.slot in seen) continue
            if (d.missingSince == 0L) d.missingSince = now
            val why = if (d.myqName != null && d.myqName in dupes) "Two doors share this name in myQ" else "myQ does not show this door right now"
            reportDoor(d, DoorSnapshot(DoorPhase.UNKNOWN, null, d.snapshot.seenAt, why))
        }
        if (changed) { saveDoors(); onDoorsChanged() }
    }

    private fun reportDoor(d: Door, s: DoorSnapshot) {
        val old = d.snapshot
        val merged = if (s.fault != null && s.phase == DoorPhase.UNKNOWN) s.copy(rawText = old.rawText) else s
        d.snapshot = merged
        val name = if (realDoors().size > 1 || d.isTest) " ${d.label}" else ""
        if (merged.phase != old.phase || merged.fault != old.fault) {
            log("Door$name: ${merged.phase}${merged.rawText?.let { " (\"$it\")" } ?: ""}${merged.fault?.let { " fault: $it" } ?: ""}")
        }
        val newTarget = if (commandActive == d.slot) d.target else when (merged.phase) {
            DoorPhase.OPEN, DoorPhase.OPENING -> 0
            DoorPhase.CLOSED, DoorPhase.CLOSING -> 1
            else -> d.target
        }
        val srv = server ?: return
        if (newTarget != null && newTarget != d.target) {
            d.target = newTarget
            srv.notifyChanged(Iid.door(d.slot, Iid.TARGET_DOOR), newTarget)
        } else if (d.isTest && newTarget != null) {
            srv.notifyChanged(Iid.door(d.slot, Iid.TARGET_DOOR), newTarget)
        }
        val cur = currentDoorState(d.slot)
        if (cur != null && (merged.phase != old.phase || old.fault != null)) srv.notifyChanged(Iid.door(d.slot, Iid.CURRENT_DOOR), cur)
    }

    fun setObstruction(slot: Int, on: Boolean) {
        val d = doors[slot] ?: return
        if (d.obstruction == on) return
        d.obstruction = on
        log("Obstruction${if (realDoors().size > 1) " ${d.label}" else ""}: $on")
        server?.notifyChanged(Iid.door(slot, Iid.OBSTRUCTION), on)
    }

    fun setTarget(slot: Int, t: Int) { doors[slot]?.target = t }

    // ---- GarageBackend ----

    override fun doorSlots(): List<Int> = doors.keys.toList()

    override fun doorName(slot: Int): String = when {
        slot == TEST_SLOT -> TEST_DOOR_NAME
        else -> doors[slot]?.myqName ?: "Garage Door ${slot + 1}"
    }

    override fun doorLabel(slot: Int): String {
        prefs().getString("label.$slot", null)?.let { return it }
        return if (slot == 0) ACCESSORY_NAME else doorName(slot)
    }

    override fun setDoorLabel(slot: Int, label: String) {
        prefs().edit().putString("label.$slot", label).apply()
        log("Apple Home renamed ${doors[slot]?.label ?: "a door"} to \"$label\"")
    }

    override fun currentDoorState(slot: Int): Int? {
        val d = doors[slot] ?: return null
        val s = d.snapshot
        val age = System.currentTimeMillis() - s.seenAt
        if (!d.isTest && age > 2 * 60_000L) scheduler.execute { requestRefresh("Home asked, reading is ${age / 60_000} min old") }
        if (s.fault != null || age > STALE_MS) return null
        return s.phase.hap
    }

    override fun targetDoorState(slot: Int): Int {
        val d = doors[slot] ?: return 1
        return d.target ?: when (d.snapshot.phase) {
            DoorPhase.OPEN, DoorPhase.OPENING -> 0
            else -> 1
        }
    }

    override fun obstructionDetected(slot: Int): Boolean = doors[slot]?.obstruction ?: false

    override fun setTargetDoorState(slot: Int, target: Int): Int {
        val d = doors[slot] ?: return HapStatus.NOT_FOUND
        if (d.isTest) { simulateTestDoor(d, target); return HapStatus.SUCCESS }
        val drv = driver ?: run {
            log("Command refused: accessibility service is off")
            return HapStatus.COMMUNICATION_FAILURE
        }
        screenProblem?.let { log("Command refused: $it"); return HapStatus.COMMUNICATION_FAILURE }
        val desired = if (target == 0) DoorPhase.OPEN else DoorPhase.CLOSED
        synchronized(this) {
            if (commandActive == slot && inFlight == desired) {
                log("Repeat $desired while one is running; acknowledged")
                return HapStatus.SUCCESS
            }
            inFlight = desired
        }
        val name = d.myqName.takeIf { realDoors().size > 1 }
        val accepted = try {
            drv.command(slot, name, desired).get(COMMAND_ACCEPT_WAIT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            log("Command $desired not accepted in time: ${e.javaClass.simpleName}")
            false
        }
        if (!accepted) return HapStatus.COMMUNICATION_FAILURE
        d.target = target
        return HapStatus.SUCCESS
    }

    override fun identify() = log("Identify requested by Apple Home")
}
