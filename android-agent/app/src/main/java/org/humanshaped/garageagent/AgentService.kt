package org.humanshaped.garageagent

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class AgentService : AccessibilityService() {
    @Volatile var lastExternalPackage: String? = null
        private set
    private var driver: MyqDriver? = null
    private var lastPassive = 0L

    private val ignored = setOf("com.android.systemui", "android", MyqDriver.MYQ)

    override fun onServiceConnected() {
        Agent.start(this)
        getSharedPreferences("agent", MODE_PRIVATE).edit().putBoolean("a11yWanted", true).apply()
        val d = MyqDriver(this)
        driver = d
        Agent.driver = d
        Agent.log("Accessibility service connected")
        Agent.requestRefresh("agent started")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            pkg !in ignored && pkg != packageName && !pkg.contains("inputmethod")
        ) {
            lastExternalPackage = pkg
        }
        if (pkg == MyqDriver.MYQ) {
            val now = System.currentTimeMillis()
            if (now - lastPassive > 1500) {
                lastPassive = now
                driver?.passiveRead()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (Agent.driver === driver) Agent.driver = null
        Agent.log("Accessibility service stopped")
        super.onDestroy()
    }
}
