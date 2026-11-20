package org.humanshaped.garageagent

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** myQ's own push alerts tell us the door moved (car remote, wall button), so we re-check. */
class MyqNotificationWatcher : NotificationListenerService() {
    private val delay = Executors.newSingleThreadScheduledExecutor()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != MyqDriver.MYQ) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)
        Agent.log("myQ notification: $title / $text")
        delay.schedule({ Agent.requestRefresh("myQ notification") }, 25, TimeUnit.SECONDS)
    }
}
