package org.humanshaped.garageagent

import org.json.JSONObject

/**
 * MOVES THE REAL DOOR. A HomeKit controller run on the Pixel in its own
 * process (app_process), so the live agent is never stopped:
 *   adb shell CLASSPATH=<app.apk>:<test.apk> app_process / org.humanshaped.garageagent.RealDoorCli <setup-code> [open|close|cycle]
 * Pairs, sends the commands, prints every event, then removes its pairing.
 */
object RealDoorCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val code = args[0]
        val plan = when (args.getOrElse(1) { "cycle" }) { "open" -> listOf(0); "close" -> listOf(1); else -> listOf(0, 1) }
        val t = System.currentTimeMillis()
        fun log(m: String) = println("+${System.currentTimeMillis() - t}ms $m")
        val ctrl = TestController(Agent.HAP_PORT)
        check(ctrl.pair(code)) { "pair-setup failed" }
        log("paired")
        val http = ctrl.session()
        log("session verified")
        try {
            val (_, initial) = http.request("GET", "/characteristics?id=1.11,1.12,1.13", null)
            log("initial ${String(initial)}")
            http.request("PUT", "/characteristics", "application/hap+json",
                """{"characteristics":[{"aid":1,"iid":11,"ev":true},{"aid":1,"iid":12,"ev":true},{"aid":1,"iid":13,"ev":true}]}""".toByteArray())
            for (target in plan) {
                val t0 = System.currentTimeMillis()
                val (status, body) = http.request("PUT", "/characteristics", "application/hap+json",
                    """{"characteristics":[{"aid":1,"iid":12,"value":$target}]}""".toByteArray())
                log("${if (target == 0) "OPEN" else "CLOSE"} -> $status ${String(body)} (${System.currentTimeMillis() - t0} ms)")
                if (!status.contains("204")) error("write refused")
                var current = -1
                val deadline = System.currentTimeMillis() + 70_000
                while (current != target && System.currentTimeMillis() < deadline) {
                    val (line, ev) = http.readMessage()
                    if (!line.startsWith("EVENT")) continue
                    val c = JSONObject(String(ev)).getJSONArray("characteristics").getJSONObject(0)
                    log("event iid=${c.getInt("iid")} value=${c.get("value")}")
                    if (c.getInt("iid") == 11) current = c.getInt("value")
                }
                log(if (current == target) "REACHED ${if (target == 0) "open" else "closed"}" else "TIMEOUT, current=$current")
                if (target != plan.last()) Thread.sleep(10_000)
            }
        } finally {
            ctrl.unpair(http)
            log("unpaired")
        }
    }
}
