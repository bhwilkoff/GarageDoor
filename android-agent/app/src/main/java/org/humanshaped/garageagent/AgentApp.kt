package org.humanshaped.garageagent

import android.app.Application

class AgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { Agent.log("CRASH in ${t.name}: ${e.stackTraceToString().take(1500)}") }
            previous?.uncaughtException(t, e)
        }
        Agent.start(this)
        KeepAliveService.start(this)
    }
}
