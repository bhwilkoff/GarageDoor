package org.humanshaped.garageagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Turns the pretend second door on or off, so two doors can be tried in Apple
 * Home without a second myQ door. Only the shell can send it (it requires the
 * DUMP permission), so it is reachable from a computer over adb and nothing else:
 *
 *   adb shell am broadcast -n org.humanshaped.garageagent/.TestDoorReceiver --ez on true
 */
class TestDoorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Agent.start(context)
        Agent.setTestDoor(intent.getBooleanExtra("on", true))
    }
}
