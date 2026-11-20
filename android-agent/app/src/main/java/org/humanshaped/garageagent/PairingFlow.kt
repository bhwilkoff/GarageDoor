package org.humanshaped.garageagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.concurrent.Executors

/**
 * Pairs the agent with this phone's wireless debugging, no computer needed.
 * The owner opens "Pair device with pairing code"; the agent sees the pairing
 * service appear and asks for the six-digit code in a notification, so the
 * Settings dialog (which closes if you leave it) stays open while they type.
 */
object PairingFlow {
    private const val STATUS_CHANNEL = "setup_status"
    private const val ALERT_CHANNEL = "pairing_code"
    private const val STATUS_ID = 7
    private const val ALERT_ID = 8
    private const val KEY_CODE = "code"
    const val ACTION_CODE = "org.humanshaped.garageagent.PAIRING_CODE"
    private const val WAIT_MS = 5 * 60_000L

    enum class Step { IDLE, WAITING_FOR_DIALOG, WAITING_FOR_CODE, PAIRING, GRANTING, DONE, FAILED }

    @Volatile var step = Step.IDLE
        private set
    @Volatile var message = ""
        private set
    @Volatile private var target: Pair<String, Int>? = null
    private val worker = Executors.newSingleThreadExecutor()

    fun start(context: Context, note: String = "") {
        val app = context.applicationContext
        if (step in setOf(Step.WAITING_FOR_DIALOG, Step.WAITING_FOR_CODE, Step.PAIRING, Step.GRANTING)) return
        AdbSelf.init(app)
        set(app, Step.WAITING_FOR_DIALOG, note + "Open Wireless debugging and tap \"Pair device with pairing code\".")
        worker.execute {
            if (note.isNotEmpty()) Thread.sleep(3000)
            val deadline = System.currentTimeMillis() + WAIT_MS
            var found: Pair<String, Int>? = null
            while (found == null && System.currentTimeMillis() < deadline && step == Step.WAITING_FOR_DIALOG) {
                found = AdbSelf.findService(app, AdbSelf.PAIRING_SERVICE, timeoutS = 10)
            }
            if (found == null) {
                if (step == Step.WAITING_FOR_DIALOG) set(app, Step.FAILED, "The pairing dialog never appeared. Try again, and keep the phone on Wi-Fi.")
                return@execute
            }
            target = found
            Agent.log("Found the pairing service on port ${found.second}")
            set(app, Step.WAITING_FOR_CODE, "Type the six-digit Wi-Fi pairing code shown in the dialog.")
        }
    }

    fun cancel(context: Context) {
        step = Step.IDLE
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(STATUS_ID); nm.cancel(ALERT_ID)
    }

    fun submit(context: Context, code: String) {
        val app = context.applicationContext
        val t = target ?: return
        val digits = code.filter { it.isDigit() }
        if (digits.length != 6) { set(app, Step.WAITING_FOR_CODE, "The code has six digits. Try again."); return }
        set(app, Step.PAIRING, "Pairing...")
        worker.execute {
            val name = "${AdbSelf.KEY_NAME}@${Build.MODEL.replace(' ', '-')}"
            when (val r = AdbPair.pair(t.first, t.second, digits, name)) {
                is AdbPair.Result.Paired -> {
                    Agent.log("Paired the agent with wireless debugging")
                    set(app, Step.GRANTING, "Paired. Granting the agent's permissions...")
                    Thread.sleep(1500)
                    if (AdbSelf.grantAll(app)) {
                        set(app, Step.DONE, "Paired and set up. The agent can now keep itself running.")
                        Agent.healNow()
                        bringAppForward(app)
                    } else {
                        set(app, Step.FAILED, "Paired, but granting permissions failed. Tap Grant on the setup screen.")
                    }
                }
                is AdbPair.Result.WrongCode -> {
                    Agent.log("Pairing failed: wrong code")
                    step = Step.FAILED
                    start(app, "That code did not match. Each code works once. ")
                }
                is AdbPair.Result.Failed -> {
                    Agent.log("Pairing failed: ${r.reason}")
                    set(app, Step.FAILED, "Pairing failed (${r.reason}). Open the pairing dialog again and retry.")
                }
            }
        }
    }

    private fun set(context: Context, s: Step, msg: String) {
        step = s; message = msg
        notify(context)
    }

    /** Clears Settings off the agent's task and shows the setup screen again, so the next step is in view. */
    private fun bringAppForward(context: Context) {
        val activity = "${context.packageName}/${MainActivity::class.java.name}"
        val ok = AdbSelf.shell(context, "am start -n $activity -f 0x24000000 >/dev/null 2>&1; echo ok")?.contains("ok") == true
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(STATUS_ID)
        if (ok) nm.cancel(ALERT_ID)
    }

    private fun notify(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("setup")
        nm.createNotificationChannel(NotificationChannel(STATUS_CHANNEL, "Setup progress", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Pairing code", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun base(channel: String) = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_garage)
            .setContentTitle("Garage Agent setup")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .setGroup("garage_setup")
        when (step) {
            Step.IDLE -> { nm.cancel(STATUS_ID); nm.cancel(ALERT_ID) }
            Step.WAITING_FOR_CODE -> {
                // A new notification (not an update to the status one), so Android shows it as a pop-up over Settings.
                nm.cancel(STATUS_ID)
                val reply = PendingIntent.getBroadcast(
                    context, 1,
                    Intent(context, PairingCodeReceiver::class.java).setAction(ACTION_CODE),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val input = RemoteInput.Builder(KEY_CODE).setLabel("Six-digit code").build()
                val n = base(ALERT_CHANNEL)
                    .setContentTitle("Enter the pairing code")
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setTimeoutAfter(WAIT_MS)
                    .addAction(Notification.Action.Builder(null, "Enter code", reply).addRemoteInput(input).build())
                    .build()
                runCatching { nm.notify(ALERT_ID, n) }
            }
            Step.FAILED -> { nm.cancel(STATUS_ID); runCatching { nm.notify(ALERT_ID, base(ALERT_CHANNEL).setAutoCancel(true).build()) } }
            // After a direct reply Android keeps the notification until the app updates it, and ignores
            // cancel() until then: so update it in place, and let it time out on its own as a backstop.
            Step.PAIRING, Step.GRANTING -> runCatching {
                nm.notify(ALERT_ID, base(ALERT_CHANNEL).setOnlyAlertOnce(true).setTimeoutAfter(60_000L).build())
            }
            Step.DONE -> runCatching {
                nm.notify(ALERT_ID, base(ALERT_CHANNEL).setOnlyAlertOnce(true).setAutoCancel(true).setTimeoutAfter(4_000L).build())
            }
            Step.WAITING_FOR_DIALOG -> { nm.cancel(ALERT_ID); runCatching { nm.notify(STATUS_ID, base(STATUS_CHANNEL).setOngoing(true).build()) } }
        }
    }

    class PairingCodeReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString() ?: return
            submit(context, code)
        }
    }
}
