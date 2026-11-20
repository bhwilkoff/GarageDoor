package org.humanshaped.garageagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.app.ActivityOptions
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Operates the official myQ app the way a person would: bring it forward,
 * read the door's status line, tap the door button, watch the result, and
 * hand the screen back to whatever app was in front before.
 */
class MyqDriver(private val service: AgentService) {
    companion object {
        const val MYQ = "com.chamberlain.android.liftmaster.myq"
        private const val STATUS_ID = "device_status"
        private const val BUTTON_DESC = "device state icon"
        private const val HOME_TAB_ID = "$MYQ:id/bottom_nav_tab_1"
        private const val OPEN_TIMEOUT_MS = 30_000L
        private const val CLOSE_TIMEOUT_MS = 45_000L
        private const val SETTLE_WAIT_MS = 45_000L
    }

    data class Screen(val status: String?, val button: Rect?, val homeTab: Rect?, val texts: List<String>)

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "myq-driver") }
    val busy = AtomicBoolean(false)

    // ---- Reading the screen ----

    /** The hidden display DisplayHost runs myQ on, if it exists. */
    fun hiddenDisplayId(): Int? = service.getSystemService(DisplayManager::class.java).displays
        .firstOrNull { it.name == DisplayHost.DISPLAY_NAME }?.displayId

    private fun myqRoot(): AccessibilityNodeInfo? {
        val hidden = hiddenDisplayId()
        if (hidden != null) {
            return runCatching { service.windowsOnAllDisplays.get(hidden) }.getOrNull().orEmpty()
                .mapNotNull { it.root }.firstOrNull { it.packageName == MYQ }
        }
        val fromWindows = runCatching { service.windows }.getOrNull().orEmpty()
            .mapNotNull { it.root }.firstOrNull { it.packageName == MYQ }
        return fromWindows ?: service.rootInActiveWindow?.takeIf { it.packageName == MYQ }
    }

    fun readScreen(): Screen? {
        val root = myqRoot() ?: return null
        var status: String? = null
        var button: Rect? = null
        var homeTab: Rect? = null
        val texts = mutableListOf<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 60) return
            val id = n.viewIdResourceName
            if (status == null && id == STATUS_ID) status = n.text?.toString()
            if (button == null && n.contentDescription?.toString() == BUTTON_DESC) button = Rect().also { n.getBoundsInScreen(it) }
            if (homeTab == null && id == HOME_TAB_ID) homeTab = Rect().also { n.getBoundsInScreen(it) }
            n.text?.toString()?.takeIf { it.isNotBlank() && texts.size < 40 }?.let { texts.add(it) }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return Screen(status, button, homeTab, texts)
    }

    private fun snapshotOf(s: Screen?, fault: String? = null): DoorSnapshot {
        val phase = DoorText.parse(s?.status)
        val f = fault ?: if (s?.status == null) "myQ did not show the door" else if (phase == DoorPhase.UNKNOWN) "Unrecognized myQ status" else null
        return DoorSnapshot(phase, s?.status, System.currentTimeMillis(), f)
    }

    /** Passive reading while myQ happens to be on screen. */
    fun passiveRead() {
        if (busy.get()) return
        worker.execute {
            if (busy.get()) return@execute
            val s = readScreen() ?: return@execute
            if (s.status != null && DoorText.parse(s.status) != DoorPhase.UNKNOWN) Agent.report(snapshotOf(s))
        }
    }

    // ---- Moving between apps ----

    private fun myqInFront(): Boolean = service.rootInActiveWindow?.packageName == MYQ

    private fun ensureAwake(): String? {
        val pm = service.getSystemService(PowerManager::class.java)
        if (!pm.isInteractive) {
            Agent.log("Screen is off; waking it")
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "garage-agent:wake")
            wl.acquire(60_000L)
            Thread.sleep(1000)
        }
        val km = service.getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) return "The Pixel is locked"
        if (!pm.isInteractive) return "The Pixel screen is off"
        return null
    }

    private fun launchMyq(): Boolean {
        val intent = service.packageManager.getLaunchIntentForPackage(MYQ) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(intent)
        return true
    }

    /** Brings myQ forward on its home screen and waits for a settled status line. */
    private fun launchOnHidden(id: Int): Boolean {
        val intent = service.packageManager.getLaunchIntentForPackage(MYQ) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            service.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle())
        }.onFailure { Agent.log("Could not start myQ on the hidden display: ${it.message}") }.isSuccess
    }

    /** Hidden display: myQ is already there, always unlocked; nothing on the real screen changes. */
    private fun openHiddenSession(id: Int): Pair<Screen?, String?> {
        var screen = readScreen()
        if (screen?.status == null) {
            if (screen == null) launchOnHidden(id)
            val deadline = System.currentTimeMillis() + 12_000L
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(400)
                screen = readScreen()
                if (screen?.status != null) break
                screen?.homeTab?.let { tap(it.centerX(), it.centerY()) }
            }
        }
        if (screen?.status == null) {
            Agent.log("Hidden display has no myQ door. Visible text: ${screen?.texts?.take(12)}")
            return screen to "myQ did not show the door"
        }
        return screen to null
    }

    private fun openSession(): Pair<Screen?, String?> {
        hiddenDisplayId()?.let { return openHiddenSession(it) }
        ensureAwake()?.let { return null to it }
        if (!myqInFront() && !launchMyq()) return null to "myQ is not installed"
        val deadline = System.currentTimeMillis() + 12_000L
        var relaunched = false
        var screen: Screen? = null
        while (System.currentTimeMillis() < deadline) {
            screen = readScreen()
            if (screen?.status != null) break
            if (screen?.homeTab != null) tap(screen.homeTab!!.centerX(), screen.homeTab!!.centerY())
            if (screen == null && !relaunched && System.currentTimeMillis() > deadline - 8_000L) {
                launchMyq(); relaunched = true
            }
            Thread.sleep(400)
        }
        if (screen?.status == null) {
            Agent.log("myQ screen without a door. Visible text: ${screen?.texts?.take(12)}")
            return screen to "myQ did not show the door"
        }
        // myQ redraws from the network shortly after it comes forward; wait for two matching reads.
        Thread.sleep(1500)
        var last = readScreen()
        repeat(4) {
            Thread.sleep(900)
            val next = readScreen()
            if (next?.status != null && next.status == last?.status) return next to null
            last = next
        }
        return last to null
    }

    private fun returnTo(previous: String?) {
        val home = service.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName
        val launch = previous?.takeIf { it != home }?.let { service.packageManager.getLaunchIntentForPackage(it) }
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { service.startActivity(launch) }.onFailure { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
        } else {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
    }

    private fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val builder = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
        hiddenDisplayId()?.let { builder.setDisplayId(it) }
        val gesture = builder.build()
        val latch = CountDownLatch(1)
        var ok = false
        val dispatched = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(g: GestureDescription?) { latch.countDown() }
        }, null)
        if (!dispatched) return false
        latch.await(3, TimeUnit.SECONDS)
        return ok
    }

    // ---- Operations ----

    fun refresh(reason: String) {
        if (hiddenDisplayId() == null) {
            Agent.log("Not checking ($reason): the hidden display is down, and checks never take the real screen")
            return
        }
        worker.execute {
            busy.set(true)
            val previous = service.lastExternalPackage
            val wasInFront = true
            try {
                val (screen, fault) = openSession()
                Agent.report(snapshotOf(screen, fault))
            } catch (e: Exception) {
                Agent.report(DoorSnapshot(DoorPhase.UNKNOWN, null, System.currentTimeMillis(), "Check failed: ${e.message}"))
            } finally {
                if (!wasInFront) returnTo(previous)
                busy.set(false)
            }
        }
    }

    /** Completes true once myQ has accepted the command (or nothing needed doing), false if it could not. */
    fun command(desired: DoorPhase): CompletableFuture<Boolean> {
        val accepted = CompletableFuture<Boolean>()
        worker.execute {
            busy.set(true)
            val previous = service.lastExternalPackage
            val wasInFront = hiddenDisplayId() != null || myqInFront()
            if (hiddenDisplayId() == null) Agent.log("Hidden display is down; using the real screen for this command")
            Agent.commandActive = true
            try {
                runCommand(desired, accepted)
            } catch (e: Exception) {
                Agent.log("Command failed: ${e.javaClass.simpleName} ${e.message}")
                Agent.report(DoorSnapshot(DoorPhase.UNKNOWN, null, System.currentTimeMillis(), "Command failed"))
            } finally {
                Agent.commandActive = false
                Agent.inFlight = null
                Agent.report(Agent.snapshot)
                accepted.complete(false)
                if (!wasInFront) returnTo(previous)
                busy.set(false)
            }
        }
        return accepted
    }

    private fun runCommand(desired: DoorPhase, accepted: CompletableFuture<Boolean>) {
        val (first, fault) = openSession()
        if (fault != null) {
            Agent.report(snapshotOf(first, fault))
            return
        }
        var screen = first!!
        var phase = DoorText.parse(screen.status)
        Agent.report(snapshotOf(screen))

        if (phase.isMoving) {
            // A door already in motion: accept now, act once it settles.
            accepted.complete(true)
            val until = System.currentTimeMillis() + SETTLE_WAIT_MS
            while (phase.isMoving && System.currentTimeMillis() < until) {
                Thread.sleep(1000)
                screen = readScreen() ?: break
                phase = DoorText.parse(screen.status)
                Agent.report(snapshotOf(screen))
            }
        }
        if (phase == desired) {
            Agent.log("Door already ${desired.name.lowercase()}; nothing to do")
            Agent.setTarget(if (desired == DoorPhase.OPEN) 0 else 1)
            accepted.complete(true)
            return
        }
        if (phase != DoorPhase.OPEN && phase != DoorPhase.CLOSED) {
            Agent.log("Not tapping: door state is $phase (\"${screen.status}\")")
            return
        }
        val button = screen.button ?: run {
            Agent.log("Not tapping: door button not found")
            return
        }
        Agent.log("Tapping myQ door button to ${if (desired == DoorPhase.OPEN) "open" else "close"}")
        if (!tap(button.centerX(), button.centerY())) {
            Agent.log("Tap gesture was not delivered")
            return
        }
        val acceptDeadline = System.currentTimeMillis() + 6_000L
        var ok = false
        while (System.currentTimeMillis() < acceptDeadline) {
            Thread.sleep(300)
            val s = readScreen() ?: continue
            val p = DoorText.parse(s.status)
            if (s.button == null || p.isMoving || p == desired) { ok = true; break }
        }
        if (!ok) {
            Agent.log("myQ did not accept the tap")
            return
        }
        Agent.setTarget(if (desired == DoorPhase.OPEN) 0 else 1)
        accepted.complete(true)

        val timeout = if (desired == DoorPhase.OPEN) OPEN_TIMEOUT_MS else CLOSE_TIMEOUT_MS
        val until = System.currentTimeMillis() + timeout
        var sawClosing = false
        while (System.currentTimeMillis() < until) {
            Thread.sleep(1000)
            val s = readScreen() ?: continue
            val p = DoorText.parse(s.status)
            if (p == DoorPhase.CLOSING) sawClosing = true
            if (p != DoorPhase.UNKNOWN) Agent.report(snapshotOf(s))
            if (p == desired) {
                Agent.log("Door reached ${desired.name.lowercase()}")
                if (desired == DoorPhase.CLOSED) Agent.setObstruction(false)
                return
            }
        }
        val final = readScreen()
        val p = DoorText.parse(final?.status)
        Agent.log("Door did not reach ${desired.name.lowercase()} in ${timeout / 1000}s; myQ says \"${final?.status}\"")
        if (desired == DoorPhase.CLOSED && sawClosing && p == DoorPhase.OPEN) Agent.setObstruction(true)
        Agent.report(snapshotOf(final, if (p == DoorPhase.UNKNOWN) "Door did not finish moving" else null))
    }
}
