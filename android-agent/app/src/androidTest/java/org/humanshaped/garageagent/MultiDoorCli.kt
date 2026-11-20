package org.humanshaped.garageagent

import android.content.Context
import android.content.ContextWrapper
import org.humanshaped.garageagent.hap.GarageBackend
import org.humanshaped.garageagent.hap.HapIdentity
import org.humanshaped.garageagent.hap.HapServer
import org.json.JSONObject
import java.io.File

/**
 * Two doors over the real protocol, on the device, in its own process (the live
 * agent and its Home pairing are untouched):
 *   adb shell CLASSPATH=<app.apk>:<test.apk> app_process / org.humanshaped.garageagent.MultiDoorCli
 * A throwaway accessory with two doors on port 51901; a test controller pairs,
 * reads both, moves the second, and checks the first kept its original identifiers.
 */
object MultiDoorCli {
    class TwoDoors : GarageBackend {
        val current = mutableMapOf(0 to 1, 1 to 0)
        val commands = mutableListOf<Pair<Int, Int>>()
        override fun doorSlots() = listOf(0, 1)
        override fun doorName(slot: Int) = if (slot == 0) "Near House" else "Side Door"
        val labels = mutableMapOf(0 to "Garage Door", 1 to "Side Door")
        override fun doorLabel(slot: Int) = labels[slot]!!
        override fun setDoorLabel(slot: Int, label: String) { labels[slot] = label }
        override fun currentDoorState(slot: Int) = current[slot]
        override fun targetDoorState(slot: Int) = if (current[slot] == 0) 0 else 1
        override fun obstructionDetected(slot: Int) = false
        override fun setTargetDoorState(slot: Int, target: Int): Int { commands.add(slot to target); return 0 }
        override fun identify() {}
    }

    @JvmStatic
    fun main(args: Array<String>) {
        var failures = 0
        fun check(name: String, ok: Boolean) { println("${if (ok) "PASS" else "FAIL"} $name"); if (!ok) failures++ }

        android.os.Looper.prepareMainLooper()
        // A bare app_process has no AndroidKeyStore provider; apps get it from ActivityThread.
        Class.forName("android.security.keystore2.AndroidKeyStoreProvider").getMethod("install").invoke(null)
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val sys = at.getDeclaredMethod("getSystemContext").apply { isAccessible = true }.invoke(thread) as Context
        val dir = File("/data/local/tmp/garage-multidoor").apply { deleteRecursively(); mkdirs() }
        val ctx = object : ContextWrapper(sys) { override fun getFilesDir() = dir }

        val identity = HapIdentity.load(ctx, "hap-multidoor.bin")
        val backend = TwoDoors()
        val server = HapServer(identity, backend, "Garage Door", "test", 51901, { }) {}
        server.start()
        try {
            val ctrl = TestController(51901)
            check("pairs", ctrl.pair(identity.setupCode))
            val http = ctrl.session()

            val services = JSONObject(String(http.request("GET", "/accessories", null).second))
                .getJSONArray("accessories").getJSONObject(0).getJSONArray("services")
            val doors = (0 until services.length()).map { services.getJSONObject(it) }.filter { it.getString("type") == "41" }
            check("two garage door services", doors.size == 2)
            check("first door keeps service 10 and is primary", doors.getOrNull(0)?.getInt("iid") == 10 && doors[0].optBoolean("primary"))
            check("second door is service 110", doors.getOrNull(1)?.getInt("iid") == 110)
            val names = doors.map { d -> (0 until d.getJSONArray("characteristics").length()).map { d.getJSONArray("characteristics").getJSONObject(it) }
                .first { it.getString("type") == "23" }.getString("value") }
            check("names: Garage Door for the first, myQ name for the second", names == listOf("Garage Door", "Side Door"))
            val labels = doors.map { d -> (0 until d.getJSONArray("characteristics").length()).map { d.getJSONArray("characteristics").getJSONObject(it) }
                .first { it.getString("type") == "E3" }.getString("value") }
            check("each door has its own label for Home (ConfiguredName)", labels == listOf("Garage Door", "Side Door"))
            check("labels live on 15 and 115", doors.map { d -> (0 until d.getJSONArray("characteristics").length()).map { d.getJSONArray("characteristics").getJSONObject(it) }
                .first { it.getString("type") == "E3" }.getInt("iid") } == listOf(15, 115))
            val rename = """{"characteristics":[{"aid":1,"iid":115,"value":"Shop Door"}]}""".toByteArray()
            check("Home can rename the second door", http.request("PUT", "/characteristics", "application/hap+json", rename).first.contains("204"))
            check("and the new name is kept", backend.labels[1] == "Shop Door" && backend.labels[0] == "Garage Door")

            val read = JSONObject(String(http.request("GET", "/characteristics?id=1.11,1.111", null).second)).getJSONArray("characteristics")
            check("first door reads closed on 11", read.getJSONObject(0).getInt("value") == 1)
            check("second door reads open on 111", read.getJSONObject(1).getInt("value") == 0)

            val sub = """{"characteristics":[{"aid":1,"iid":111,"ev":true}]}""".toByteArray()
            check("subscribes to the second door", http.request("PUT", "/characteristics", "application/hap+json", sub).first.contains("204"))
            val close2 = """{"characteristics":[{"aid":1,"iid":112,"value":1}]}""".toByteArray()
            check("close the second door", http.request("PUT", "/characteristics", "application/hap+json", close2).first.contains("204"))
            check("the command went to the second door only", backend.commands == listOf(1 to 1))

            backend.current[1] = 3
            server.notifyChanged(111, 3)
            val (evLine, evBody) = http.readMessage()
            val ev = JSONObject(String(evBody)).getJSONArray("characteristics").getJSONObject(0)
            check("event for the second door arrives on 111", evLine.startsWith("EVENT/1.0 200") && ev.getInt("iid") == 111 && ev.getInt("value") == 3)

            val bogus = """{"characteristics":[{"aid":1,"iid":212,"value":0}]}""".toByteArray()
            check("a door that does not exist is refused", http.request("PUT", "/characteristics", "application/hap+json", bogus).first.contains("207"))
            check("and nothing moved", backend.commands == listOf(1 to 1))
            ctrl.unpair(http)
        } finally {
            server.stop()
            dir.deleteRecursively()
        }
        println(if (failures == 0) "ALL PASS" else "$failures FAILED")
        System.exit(if (failures == 0) 0 else 1)
    }
}
