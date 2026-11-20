package org.humanshaped.garageagent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Setup and health in one list: each check says what it is, why it matters,
 * whether it is done, and offers the one action that fixes it. The door moves
 * from Apple Home, never from here.
 */
class MainActivity : Activity() {
    private class Check(
        val title: String,
        val why: String,
        val ok: Boolean,
        val detail: String,
        val action: String? = null,
        val run: (() -> Unit)? = null,
    )

    private inner class Row(parent: LinearLayout) {
        val title = text(17f, bold = true)
        val state = text(14f, bold = true)
        val why = text(14f, secondary = true)
        val detail = text(14f)
        val button = Button(this@MainActivity)
        var run: (() -> Unit)? = null
        init {
            val box = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, pad, 0, pad / 2) }
            val head = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(state)
            box.addView(head); box.addView(why); box.addView(detail); box.addView(button)
            button.setOnClickListener { run?.invoke() }
            parent.addView(box)
        }
        fun show(c: Check) {
            title.text = c.title
            state.text = if (c.ok) "Done" else "Needs you"
            state.setTextColor(if (c.ok) okColor else attentionColor)
            why.text = c.why
            detail.text = c.detail
            detail.visibility = if (c.detail.isEmpty()) View.GONE else View.VISIBLE
            button.text = c.action ?: ""
            button.visibility = if (c.action == null) View.GONE else View.VISIBLE
            run = c.run
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val tick = object : Runnable { override fun run() { render(); handler.postDelayed(this, 1000) } }
    private var pad = 0
    private var primary = Color.WHITE
    private var secondaryColor = Color.GRAY
    private var okColor = Color.GREEN
    private var attentionColor = Color.RED
    private lateinit var col: LinearLayout
    private lateinit var door: TextView
    private lateinit var next: TextView
    private lateinit var intro: TextView
    private lateinit var update: LinearLayout
    private lateinit var updateText: TextView
    private lateinit var code: TextView
    private val rows = mutableListOf<Row>()
    private lateinit var logView: TextView
    @Volatile private var busy: String? = null

    private fun text(size: Float, bold: Boolean = false, secondary: Boolean = false) = TextView(this).apply {
        textSize = size
        setTextColor(if (secondary) secondaryColor else primary)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, pad / 4, 0, pad / 4)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        Agent.start(this)
        pad = (16 * resources.displayMetrics.density).toInt()
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary, android.R.attr.textColorSecondary))
        primary = ta.getColor(0, Color.WHITE); secondaryColor = ta.getColor(1, Color.GRAY)
        ta.recycle()
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        okColor = if (night) Color.parseColor("#81C995") else Color.parseColor("#137333")
        attentionColor = if (night) Color.parseColor("#F6AEA9") else Color.parseColor("#B3261E")

        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        door = text(28f, bold = true).also { col.addView(it) }
        intro = text(16f, secondary = true).apply {
            text = "This phone presses the button in myQ whenever Apple Home asks it to. Set it up once, and it runs on its own after that."
        }.also { col.addView(it) }
        next = text(17f).also { col.addView(it) }
        update = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, pad / 2, 0, pad / 2); visibility = View.GONE }
        updateText = text(16f, bold = true).also { update.addView(it) }
        update.addView(text(14f, secondary = true).apply {
            text = "Download it, open it, and tap Update. Your Apple Home pairing and settings stay just as they are."
        })
        update.addView(Button(this).apply {
            text = "Download update"
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateCheck.DOWNLOAD_URL))) }
        })
        col.addView(update)
        code = text(34f, bold = true).apply { typeface = Typeface.MONOSPACE }.also { col.addView(it) }
        repeat(7) { rows.add(Row(col)) }

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, pad, 0, 0) }
        tools.addView(Button(this).apply { text = "Share report"; setOnClickListener { shareReport() } })
        tools.addView(Button(this).apply {
            text = "Reset Apple Home"
            setOnLongClickListener { Agent.resetPairing(); toast("Apple Home pairing reset"); true }
            setOnClickListener { toast("Long-press to remove every Apple Home pairing") }
        })
        col.addView(tools)
        col.addView(text(14f, secondary = true).apply {
            text = "Stuck? Share report writes a plain-text summary of everything above (no passwords, no setup code). Paste it into a new issue on the Garage Door page on GitHub, and tell me what happened."
        })
        logView = text(11f).apply { typeface = Typeface.MONOSPACE; visibility = View.GONE }
        col.addView(Button(this).apply {
            text = "Show details"
            setOnClickListener {
                val show = logView.visibility != View.VISIBLE
                logView.visibility = if (show) View.VISIBLE else View.GONE
                text = if (show) "Hide details" else "Show details"
            }
        })
        col.addView(logView)

        val scroll = ScrollView(this).apply { addView(col) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
        UpdateCheck.maybeCheck(packageManager.getPackageInfo(packageName, 0).versionName ?: "0")
    }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun background(label: String, job: () -> Boolean) {
        if (busy != null) return
        busy = label
        worker.execute {
            val ok = runCatching(job).getOrDefault(false)
            busy = null
            handler.post { toast(if (ok) "$label: done" else "$label: did not work. See the log below.") }
        }
    }

    private fun myqVersion(): String? = runCatching {
        packageManager.getPackageInfo(MyqDriver.MYQ, 0).versionName
    }.getOrNull()

    private fun hasSecureSettings() = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    private fun batteryExempt() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    private fun a11yEnabled() = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        .orEmpty().contains("$packageName/${AgentService::class.java.name}")
    private fun devOptionsOn() = Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    private fun openDevOptions() {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun startPairing() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            return
        }
        PairingFlow.start(this)
        val dnd = getSystemService(android.app.NotificationManager::class.java).currentInterruptionFilter !=
            android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        toast(
            "In Wireless debugging, tap \"Pair device with pairing code\". " +
                if (dnd) "Do Not Disturb is on, so the code notification won't pop up: swipe down from the top to find it."
                else "A notification will pop up asking for the code."
        )
        openDevOptions()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 1) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startPairing()
            else toast("The pairing code is typed into a notification, so notifications must be allowed.")
        }
    }

    private fun checks(): List<Check> {
        val myq = myqVersion()
        val s = Agent.snapshot
        val paired = AdbSelf.hasKey()
        val shellBroken = paired && AdbSelf.lastShellOk == false
        val hidden = Agent.driver?.hiddenDisplayId() != null
        val real = Agent.realDoors()
        val now = System.currentTimeMillis()
        val readingFresh = Agent.screenProblem == null && real.all { it.snapshot.seenAt > 0 && now - it.snapshot.seenAt < 5 * 60_000L && it.snapshot.fault == null }
        val gone = real.firstOrNull { it.slot != 0 && it.missingSince > 0 && now - it.missingSince > 10 * 60_000L }
        fun reading(d: Agent.Door): String {
            val x = d.snapshot
            val prefix = if (real.size > 1 || d.isTest) "${d.label}: " else "Last reading: "
            return prefix + when {
                x.fault != null -> "problem: ${x.fault}."
                x.seenAt == 0L -> "no reading yet."
                else -> "${phaseLabel(x.phase)}, ${ago(x.seenAt)}."
            }
        }
        val id = Agent.identity
        val pairing = PairingFlow.step
        val missing = buildList {
            if (!hasSecureSettings()) add("secure settings")
            if (!a11yEnabled()) add("accessibility service")
            if (!batteryExempt()) add("battery exemption")
        }
        return listOf(
            Check(
                "1. myQ app",
                "The agent works the official myQ app for you, so myQ must be installed and signed in on this phone.",
                myq != null,
                if (myq == null) "myQ is not installed." else "Installed (version $myq). Turn off its auto-update in the Play Store so a redesign cannot surprise you. More than one door? Give each its own name in myQ; that is how Garage Agent tells them apart.",
                if (myq == null) "Get myQ" else "Open myQ",
            ) {
                if (myq == null) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${MyqDriver.MYQ}")))
                else packageManager.getLaunchIntentForPackage(MyqDriver.MYQ)?.let { startActivity(it) }
            },
            Check(
                "2. Wireless debugging",
                "An Android developer setting. The agent connects to it from inside the phone to give itself permissions and to restart its private myQ screen after a reboot. It works only on Wi-Fi.",
                AdbSelf.wirelessDebuggingOn(this) || (paired && hasSecureSettings()),
                when {
                    !devOptionsOn() -> "First turn on Developer options: Settings > About phone > tap Build number seven times. Then come back."
                    !AdbSelf.wirelessDebuggingOn(this) -> "Open Developer options > Wireless debugging and turn it on. Choose \"Always allow on this network\"."
                    else -> "On."
                },
                if (AdbSelf.wirelessDebuggingOn(this) || (paired && hasSecureSettings())) null else if (!devOptionsOn()) "Open About phone" else "Open Developer options",
            ) {
                if (!devOptionsOn()) startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) else openDevOptions()
            },
            Check(
                "3. Pair the agent",
                "Gives the agent its own key to wireless debugging, the same way a computer pairs, except the computer is this phone. You can remove it any time in Wireless debugging's paired devices (\"${AdbSelf.KEY_NAME}\").",
                paired && !shellBroken && pairing !in setOf(PairingFlow.Step.WAITING_FOR_DIALOG, PairingFlow.Step.WAITING_FOR_CODE, PairingFlow.Step.PAIRING),
                when {
                    pairing != PairingFlow.Step.IDLE -> PairingFlow.message
                    shellBroken -> "This phone no longer accepts the agent's key (the pairing was removed, or wireless debugging is off). Pair again."
                    paired -> "Paired."
                    else -> "Tap Pair, then in Wireless debugging tap \"Pair device with pairing code\" and type the code into the notification."
                },
                if (pairing == PairingFlow.Step.WAITING_FOR_DIALOG || pairing == PairingFlow.Step.WAITING_FOR_CODE) "Cancel pairing" else if (paired && !shellBroken) null else "Pair",
            ) {
                if (pairing == PairingFlow.Step.WAITING_FOR_DIALOG || pairing == PairingFlow.Step.WAITING_FOR_CODE) PairingFlow.cancel(this) else startPairing()
            },
            Check(
                "4. Permissions",
                "The agent reads and presses myQ's button through an accessibility service, and stays awake through a battery exemption. Pairing grants these for you.",
                missing.isEmpty() && Agent.driver != null,
                when {
                    missing.isNotEmpty() -> "Missing: ${missing.joinToString(", ")}."
                    Agent.driver == null -> "Granted, but the accessibility service has not started yet. It retries on its own within a minute."
                    else -> "All granted."
                },
                if (missing.isEmpty()) null else if (paired) "Grant" else null,
            ) { background("Granting permissions") { AdbSelf.grantAll(this).also { if (it) Agent.healNow() } } },
            Check(
                "5. Private myQ screen",
                "myQ runs on a hidden screen only the agent can see, so this phone's own screen is never taken over.",
                hidden,
                when {
                    hidden -> "Running."
                    busy != null -> "$busy..."
                    !paired -> "Starts after the agent is paired."
                    else -> "Not running. The agent restarts it on its own within a minute or two; Start tries now."
                },
                if (hidden || !paired) null else "Start",
            ) { background("Starting the private screen") { AdbSelf.startDisplayHost(this) } },
            Check(
                if (real.size > 1) "6. Doors" else "6. Door reading",
                "The agent checks myQ every minute and tells Apple Home. Each door is matched by its name in myQ, so the right door always moves. A reading it cannot trust shows in Home as No Response, never as a guess.",
                readingFresh,
                (listOfNotNull(Agent.screenProblem) + Agent.doors().map { reading(it) }).joinToString("\n"),
                if (gone != null) "Remove \"${gone.myqName}\" from Apple Home" else "Check now",
            ) {
                if (gone != null) Agent.forgetDoor(gone.slot) else Agent.requestRefresh("checked from the agent screen")
            },
            Check(
                "7. Apple Home",
                "Add the door in the Home app on your iPhone, then add it to Control Center or the lock screen. Remote use needs a Home hub (Apple TV or HomePod).",
                id.isPaired,
                if (id.isPaired) {
                    "Your Home is connected (${id.pairings.size} device${if (id.pairings.size == 1) "" else "s"}). Add a door in myQ and it shows up in Home on its own."
                } else {
                    "In the Home app: + > Add Accessory > More options > ${Agent.ACCESSORY_NAME} > Add Anyway, then enter the code above."
                },
            ),
        )
    }

    private fun ago(t: Long): String =
        if (System.currentTimeMillis() - t < 60_000L) "just now" else DateUtils.getRelativeTimeSpanString(t).toString()

    private fun phaseLabel(p: DoorPhase) = when (p) {
        DoorPhase.OPEN -> "open"; DoorPhase.CLOSED -> "closed"; DoorPhase.OPENING -> "opening"
        DoorPhase.CLOSING -> "closing"; DoorPhase.STOPPED -> "stopped"; DoorPhase.UNKNOWN -> "unknown"
    }

    private fun render() {
        val s = Agent.snapshot
        val list = checks()
        val todo = list.count { !it.ok }
        val real = Agent.realDoors()
        val phases = real.map { it.snapshot.phase }.distinct()
        door.text = when {
            s.seenAt == 0L -> if (todo > 0) "Let's set up your garage door" else "Waiting for the first reading"
            real.size == 1 -> "Door ${phaseLabel(s.phase)}"
            phases.size == 1 -> (if (real.size == 2) "Both doors " else "All ${real.size} doors ") + phaseLabel(phases.first())
            else -> real.joinToString(" · ") { "${it.label} ${phaseLabel(it.snapshot.phase)}" }
        } + if (todo > 0 && s.seenAt != 0L) " · $todo to fix" else ""
        val first = list.firstOrNull { !it.ok }
        next.text = when {
            first != null -> "Next: ${first.title.substringAfter(". ")}. ${first.detail}"
            else -> "Everything is working. You can close this app; it keeps running on its own."
        }
        next.setTextColor(if (first == null) okColor else primary)
        code.text = if (Agent.identity.isPaired) "" else Agent.identity.setupCode
        code.visibility = if (Agent.identity.isPaired) View.GONE else View.VISIBLE
        intro.visibility = if (todo > 0) View.VISIBLE else View.GONE
        val newer = UpdateCheck.newer
        update.visibility = if (newer != null) View.VISIBLE else View.GONE
        if (newer != null) updateText.text = "Version $newer of Garage Agent is ready."
        list.forEachIndexed { i, c -> rows[i].show(c) }
        logView.text = Agent.recentLog(40).reversed().joinToString("\n")
    }

    private fun shareReport() {
        val id = Agent.identity
        val s = Agent.snapshot
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        val report = buildString {
            appendLine("Garage Agent report")
            appendLine("Agent $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("myQ ${myqVersion() ?: "not installed"}")
            appendLine()
            checks().forEach { appendLine("[${if (it.ok) "ok" else "NEEDS ATTENTION"}] ${it.title}: ${it.detail}") }
            appendLine()
            Agent.doors().forEach { d ->
                val x = d.snapshot
                appendLine("Door ${d.slot} (${d.label}): ${x.phase} raw=\"${x.rawText}\" seen=${if (x.seenAt == 0L) "never" else ago(x.seenAt)} fault=${x.fault}")
            }
            Agent.screenProblem?.let { appendLine("Problem: $it") }
            appendLine("Apple Home: paired=${id.isPaired} devices=${id.pairings.size} sessions=${Agent.controllerSessions()}")
            appendLine("Wireless debugging on=${AdbSelf.wirelessDebuggingOn(this@MainActivity)} lastShellOk=${AdbSelf.lastShellOk}")
            appendLine()
            appendLine("Recent log:")
            Agent.recentLog(80).forEach { appendLine(it) }
            appendLine()
            appendLine("myQ screen outline (structure only, for adapting to myQ changes):")
            append(Agent.driver?.outline() ?: "(the agent cannot see myQ right now)")
        }.replace(id.setupCode, "xxx-xx-xxx")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Garage Agent report")
            .putExtra(Intent.EXTRA_TEXT, report)
        startActivity(Intent.createChooser(send, "Share report"))
    }
}
