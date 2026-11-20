package org.humanshaped.garageagent

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Looks for a newer release on GitHub when the owner opens the app (never in
 * the background), and offers it. The owner decides whether to install it.
 */
object UpdateCheck {
    const val DOWNLOAD_URL = "https://github.com/bhwilkoff/GarageDoor/releases/latest/download/garage-agent.apk"
    private const val API = "https://api.github.com/repos/bhwilkoff/GarageDoor/releases/latest"
    private const val EVERY_MS = 6 * 60 * 60_000L

    @Volatile var newer: String? = null
        private set
    @Volatile private var lastCheck = 0L
    private val worker = Executors.newSingleThreadExecutor()

    fun maybeCheck(installed: String) {
        val now = System.currentTimeMillis()
        if (now - lastCheck < EVERY_MS) return
        lastCheck = now
        worker.execute {
            runCatching {
                val c = URL(API).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "GarageAgent")
                val tag = c.inputStream.bufferedReader().use { JSONObject(it.readText()).getString("tag_name") }
                val latest = tag.removePrefix("v")
                newer = if (isNewer(latest, installed)) latest else null
                if (newer != null) Agent.log("Version $latest is available")
            }.onFailure { lastCheck = now - EVERY_MS + 10 * 60_000L }
        }
    }

    internal fun isNewer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}
