package org.humanshaped.garageagent.hap

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList

/** What the garage door accessory needs from the rest of the agent. */
interface GarageBackend {
    /** The door slots Home sees, ascending. Slot 0 is the first door and keeps the original identifiers. */
    fun doorSlots(): List<Int>
    fun doorName(slot: Int): String

    /** The name Home shows for a door (ConfiguredName); Home writes it when someone renames the tile. */
    fun doorLabel(slot: Int): String
    fun setDoorLabel(slot: Int, label: String)

    /** HomeKit CurrentDoorState (0 open, 1 closed, 2 opening, 3 closing, 4 stopped), or null when unknown. */
    fun currentDoorState(slot: Int): Int?
    fun targetDoorState(slot: Int): Int
    fun obstructionDetected(slot: Int): Boolean

    /** Blocks until the command is accepted or refused; returns a HAP status (0 = success). */
    fun setTargetDoorState(slot: Int, target: Int): Int
    fun identify()
}

object Iid {
    const val AID = 1
    const val IDENTIFY = 2
    const val MANUFACTURER = 3
    const val MODEL = 4
    const val NAME = 5
    const val SERIAL = 6
    const val FIRMWARE = 7
    const val PROTOCOL_VERSION = 9
    const val DOOR_SERVICE = 10
    const val CURRENT_DOOR = 11
    const val TARGET_DOOR = 12
    const val OBSTRUCTION = 13
    const val DOOR_NAME = 14
    const val CONFIGURED_NAME = 15

    /**
     * Each door is one Garage Door Opener service. Slot 0 uses 10 to 14, exactly as
     * before doors could be more than one, so an existing Home setup never changes;
     * slot n uses n*100 + 10 to 14.
     */
    fun door(slot: Int, local: Int) = slot * 100 + local
    fun slotOf(iid: Int) = iid / 100
    fun localOf(iid: Int) = iid % 100
}

object HapStatus {
    const val SUCCESS = 0
    const val INSUFFICIENT_PRIVILEGES = -70401
    const val COMMUNICATION_FAILURE = -70402
    const val READ_ONLY = -70404
    const val WRITE_ONLY = -70405
    const val NO_NOTIFICATION = -70406
    const val NOT_FOUND = -70409
    const val INVALID_VALUE = -70410
}

class HapServer(
    private val identity: HapIdentity,
    private val backend: GarageBackend,
    private val name: String,
    private val firmware: String,
    val port: Int,
    private val log: (String) -> Unit,
    private val onPairingChanged: () -> Unit,
) {
    private val connections = CopyOnWriteArrayList<Connection>()
    private var serverSocket: ServerSocket? = null
    @Volatile private var pairSetupOwner: Connection? = null
    @Volatile private var failedSetupAttempts = 0

    fun accessoryDatabase(includeValues: Boolean = true): JSONObject {
        fun ch(iid: Int, type: String, perms: List<String>, format: String, value: Any? = null) =
            JSONObject().put("iid", iid).put("type", type).put("perms", JSONArray(perms)).put("format", format)
                .also { o ->
                    val v = value ?: if (includeValues && "pr" in perms) readValue(iid).first else null
                    if (v != null) o.put("value", v)
                }

        val info = JSONObject().put("iid", 1).put("type", "3E").put("characteristics", JSONArray(listOf(
            ch(Iid.IDENTIFY, "14", listOf("pw"), "bool"),
            ch(Iid.MANUFACTURER, "20", listOf("pr"), "string", "GarageDoor"),
            ch(Iid.MODEL, "21", listOf("pr"), "string", "myQ Agent"),
            ch(Iid.NAME, "23", listOf("pr"), "string", name),
            ch(Iid.SERIAL, "30", listOf("pr"), "string", identity.deviceId.replace(":", "")),
            ch(Iid.FIRMWARE, "52", listOf("pr"), "string", firmware),
        )))
        val protocol = JSONObject().put("iid", 8).put("type", "A2").put("characteristics", JSONArray(listOf(
            ch(Iid.PROTOCOL_VERSION, "37", listOf("pr"), "string", "1.1.0"),
        )))
        fun doorService(slot: Int): JSONObject {
            fun d(local: Int) = Iid.door(slot, local)
            val current = ch(d(Iid.CURRENT_DOOR), "E", listOf("pr", "ev"), "uint8")
                .put("minValue", 0).put("maxValue", 4).put("minStep", 1)
                .put("valid-values", JSONArray(listOf(0, 1, 2, 3, 4)))
            val target = ch(d(Iid.TARGET_DOOR), "32", listOf("pr", "pw", "ev"), "uint8")
                .put("minValue", 0).put("maxValue", 1).put("minStep", 1)
                .put("valid-values", JSONArray(listOf(0, 1)))
            return JSONObject().put("iid", d(Iid.DOOR_SERVICE)).put("type", "41").put("primary", slot == 0)
                .put("characteristics", JSONArray(listOf(
                    current, target,
                    ch(d(Iid.OBSTRUCTION), "24", listOf("pr", "ev"), "bool"),
                    ch(d(Iid.DOOR_NAME), "23", listOf("pr"), "string", if (slot == 0) name else backend.doorName(slot)),
                    // Home labels services of a multi-door accessory by ConfiguredName, not Name.
                    ch(d(Iid.CONFIGURED_NAME), "E3", listOf("pr", "pw", "ev"), "string", backend.doorLabel(slot)),
                )))
        }
        val services = listOf(info, protocol) + backend.doorSlots().map { doorService(it) }
        val accessory = JSONObject().put("aid", Iid.AID).put("services", JSONArray(services))
        return JSONObject().put("accessories", JSONArray(listOf(accessory)))
    }

    /** Value of a readable characteristic, or a HAP status error. */
    private fun doorSlot(iid: Int): Int? = Iid.slotOf(iid).takeIf { Iid.localOf(iid) in Iid.CURRENT_DOOR..Iid.CONFIGURED_NAME && it in backend.doorSlots() }

    private fun readValue(iid: Int): Pair<Any?, Int> {
        val slot = doorSlot(iid)
        if (slot != null) return when (Iid.localOf(iid)) {
            Iid.CURRENT_DOOR -> backend.currentDoorState(slot)?.let { it to 0 } ?: (null to HapStatus.COMMUNICATION_FAILURE)
            Iid.TARGET_DOOR -> backend.targetDoorState(slot) to 0
            Iid.OBSTRUCTION -> backend.obstructionDetected(slot) to 0
            Iid.CONFIGURED_NAME -> backend.doorLabel(slot) to 0
            else -> (if (slot == 0) name else backend.doorName(slot)) to 0
        }
        return accessoryValue(iid)
    }

    private fun accessoryValue(iid: Int): Pair<Any?, Int> = when (iid) {
        Iid.MANUFACTURER -> "GarageDoor" to 0
        Iid.MODEL -> "myQ Agent" to 0
        Iid.NAME -> name to 0
        Iid.SERIAL -> identity.deviceId.replace(":", "") to 0
        Iid.FIRMWARE -> firmware to 0
        Iid.PROTOCOL_VERSION -> "1.1.0" to 0
        Iid.IDENTIFY -> null to HapStatus.WRITE_ONLY
        else -> null to HapStatus.NOT_FOUND
    }

    fun start() {
        val ss = ServerSocket(port)
        ss.reuseAddress = true
        serverSocket = ss
        Thread({
            while (!ss.isClosed) {
                try {
                    val s = ss.accept()
                    s.tcpNoDelay = true
                    s.keepAlive = true
                    val c = Connection(s)
                    connections.add(c)
                    Thread(c, "hap-conn-${s.inetAddress.hostAddress}").start()
                } catch (e: IOException) {
                    if (!ss.isClosed) log("HAP accept failed: ${e.message}")
                }
            }
        }, "hap-accept").start()
        log("HAP server listening on port $port")
    }

    fun stop() {
        serverSocket?.close()
        connections.forEach { it.close() }
    }

    /** Pushes a changed value to every verified controller that subscribed. */
    fun notifyChanged(iid: Int, value: Any, except: Connection? = null) {
        val body = JSONObject().put("characteristics", JSONArray(listOf(
            JSONObject().put("aid", Iid.AID).put("iid", iid).put("value", value),
        ))).toString().toByteArray()
        val msg = "EVENT/1.0 200 OK\r\nContent-Type: application/hap+json\r\nContent-Length: ${body.size}\r\n\r\n"
            .toByteArray() + body
        for (c in connections) {
            if (c !== except && c.isVerified && iid in c.subscriptions) c.send(msg)
        }
    }

    fun connectionCount(): Int = connections.count { it.isVerified }

    inner class Connection(private val socket: Socket) : Runnable {
        private val rawIn = BufferedInputStream(socket.getInputStream())
        private val rawOut: OutputStream = socket.getOutputStream()
        private var input: InputStream = rawIn
        @Volatile var isVerified = false
        val subscriptions: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        var controllerId: String? = null
        private var readKey: ByteArray? = null   // accessory to controller
        private var writeKey: ByteArray? = null  // controller to accessory
        private var outCounter = 0L
        private var inCounter = 0L

        private var srp: SrpServer? = null
        private var verifyPriv: ByteArray? = null
        private var verifyPub: ByteArray? = null
        private var controllerVerifyPub: ByteArray? = null
        private var verifyShared: ByteArray? = null
        private val peer = socket.inetAddress.hostAddress
        private var preparedId: String? = null
        private var preparedUntil = 0L

        override fun run() {
            try {
                while (true) {
                    val req = readRequest() ?: break
                    handle(req)
                }
            } catch (e: Exception) {
                if (e !is SocketException) log("HAP connection $peer ended: ${e.javaClass.simpleName} ${e.message}")
            } finally {
                log("HAP connection closed: ${controllerId?.take(8) ?: peer} ($peer)")
                close()
            }
        }

        fun close() {
            connections.remove(this)
            if (pairSetupOwner === this) pairSetupOwner = null
            try { socket.close() } catch (_: IOException) {}
        }

        @Synchronized
        fun send(plain: ByteArray) {
            try {
                val key = readKey
                if (key == null) {
                    rawOut.write(plain)
                } else {
                    var off = 0
                    val out = ByteArrayOutputStream()
                    while (off < plain.size) {
                        val n = minOf(1024, plain.size - off)
                        val aad = byteArrayOf((n and 0xFF).toByte(), (n shr 8).toByte())
                        out.write(aad)
                        out.write(Aead.seal(key, Aead.nonce(outCounter++), plain.copyOfRange(off, off + n), aad))
                        off += n
                    }
                    rawOut.write(out.toByteArray())
                }
                rawOut.flush()
            } catch (e: IOException) {
                close()
            }
        }

        private inner class DecryptingStream : InputStream() {
            private var buf = ByteArray(0)
            private var pos = 0
            override fun read(): Int {
                if (pos >= buf.size && !fill()) return -1
                return buf[pos++].toInt() and 0xFF
            }

            private fun fill(): Boolean {
                val aad = readExactly(rawIn, 2) ?: return false
                val n = (aad[0].toInt() and 0xFF) or ((aad[1].toInt() and 0xFF) shl 8)
                val sealed = readExactly(rawIn, n + 16) ?: return false
                buf = Aead.open(writeKey!!, Aead.nonce(inCounter++), sealed, aad)
                    ?: throw IOException("frame failed authentication")
                pos = 0
                return true
            }
        }

        private fun readRequest(): Request? {
            val head = ByteArrayOutputStream()
            var last = 0
            while (true) {
                val b = input.read()
                if (b < 0) return null
                head.write(b)
                last = (last shl 8) or b
                if (last == 0x0D0A0D0A) break
                if (head.size() > 16384) throw IOException("header too large")
            }
            val lines = head.toString("ISO-8859-1").split("\r\n")
            val parts = lines[0].split(" ")
            val headers = lines.drop(1).filter { it.contains(":") }
                .associate { it.substringBefore(":").trim().lowercase() to it.substringAfter(":").trim() }
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (len > 0) readExactly(input, len) ?: return null else ByteArray(0)
            return Request(parts[0], parts.getOrElse(1) { "/" }, headers, body)
        }

        private fun respond(status: String, contentType: String?, body: ByteArray) {
            if (!status.startsWith("200")) log("HAP -> $status ${if (contentType?.contains("json") == true) String(body).take(200) else ""}")
            val sb = StringBuilder("HTTP/1.1 $status\r\n")
            if (contentType != null) sb.append("Content-Type: $contentType\r\n")
            sb.append("Content-Length: ${body.size}\r\n\r\n")
            send(sb.toString().toByteArray() + body)
        }

        private fun respondTlv(body: ByteArray) = respond("200 OK", "application/pairing+tlv8", body)
        private fun respondJson(status: String, json: JSONObject) =
            respond(status, "application/hap+json", json.toString().toByteArray())
        private fun respondNoContent() = send("HTTP/1.1 204 No Content\r\n\r\n".toByteArray())

        private fun handle(req: Request) {
            val path = req.target.substringBefore("?")
            if (path != "/pair-verify" && path != "/pair-setup") {
                val body = if (req.body.isNotEmpty() && req.headers["content-type"]?.contains("json") == true) " ${String(req.body).take(200)}" else ""
                log("HAP ${controllerId?.take(8) ?: peer} ${req.method} ${req.target}$body")
            }
            when {
                req.method == "POST" && path == "/pair-setup" -> pairSetup(Tlv8.decode(req.body))
                req.method == "POST" && path == "/pair-verify" -> pairVerify(Tlv8.decode(req.body))
                req.method == "POST" && path == "/identify" -> {
                    if (identity.isPaired) respondJson("400 Bad Request", JSONObject().put("status", HapStatus.INSUFFICIENT_PRIVILEGES))
                    else { backend.identify(); respondNoContent() }
                }
                !isVerified -> respondJson("470 Connection Authorization Required", JSONObject().put("status", HapStatus.INSUFFICIENT_PRIVILEGES))
                req.method == "GET" && path == "/accessories" -> respondJson("200 OK", accessoryDatabase())
                req.method == "GET" && path == "/characteristics" -> getCharacteristics(req.target)
                req.method == "PUT" && path == "/prepare" -> prepare(JSONObject(String(req.body)))
                req.method == "PUT" && path == "/characteristics" -> putCharacteristics(JSONObject(String(req.body)))
                req.method == "POST" && path == "/pairings" -> pairings(Tlv8.decode(req.body))
                else -> respond("404 Not Found", null, ByteArray(0))
            }
        }

        // ---- Pair Setup (HAP section 5.6) ----

        private fun setupError(state: Int, code: Int) =
            respondTlv(Tlv8.encode(Tlv8.STATE to Tlv8.byte(state), Tlv8.ERROR to Tlv8.byte(code)))

        private fun pairSetup(tlv: Map<Int, ByteArray>) {
            val state = tlv[Tlv8.STATE]?.get(0)?.toInt() ?: return setupError(2, Tlv8.ERR_UNKNOWN)
            when (state) {
                1 -> {
                    if (identity.isPaired) return setupError(2, Tlv8.ERR_UNAVAILABLE)
                    if (failedSetupAttempts >= 100) return setupError(2, Tlv8.ERR_MAX_TRIES)
                    val owner = pairSetupOwner
                    if (owner != null && owner !== this && connections.contains(owner)) return setupError(2, Tlv8.ERR_BUSY)
                    pairSetupOwner = this
                    val s = SrpServer(identity.setupCode)
                    srp = s
                    log("Pair setup started by $peer")
                    respondTlv(Tlv8.encode(Tlv8.STATE to Tlv8.byte(2), Tlv8.PUBLIC_KEY to s.publicKey(), Tlv8.SALT to s.salt))
                }
                3 -> {
                    val s = srp ?: return setupError(4, Tlv8.ERR_UNKNOWN)
                    val a = tlv[Tlv8.PUBLIC_KEY]; val m1 = tlv[Tlv8.PROOF]
                    if (a == null || m1 == null || !s.setClientPublic(a)) return setupError(4, Tlv8.ERR_AUTHENTICATION)
                    val m2 = s.verify(m1)
                    if (m2 == null) {
                        failedSetupAttempts++
                        log("Pair setup: wrong setup code from $peer")
                        srp = null; pairSetupOwner = null
                        return setupError(4, Tlv8.ERR_AUTHENTICATION)
                    }
                    respondTlv(Tlv8.encode(Tlv8.STATE to Tlv8.byte(4), Tlv8.PROOF to m2))
                }
                5 -> {
                    val s = srp ?: return setupError(6, Tlv8.ERR_UNKNOWN)
                    val k = s.sessionKey ?: return setupError(6, Tlv8.ERR_UNKNOWN)
                    val encKey = Hkdf.sha512(k, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
                    val sealed = tlv[Tlv8.ENCRYPTED_DATA] ?: return setupError(6, Tlv8.ERR_AUTHENTICATION)
                    val plain = Aead.open(encKey, Aead.nonce("PS-Msg05"), sealed) ?: return setupError(6, Tlv8.ERR_AUTHENTICATION)
                    val sub = Tlv8.decode(plain)
                    val ctrlId = sub[Tlv8.IDENTIFIER]; val ctrlLtpk = sub[Tlv8.PUBLIC_KEY]; val sig = sub[Tlv8.SIGNATURE]
                    if (ctrlId == null || ctrlLtpk == null || sig == null) return setupError(6, Tlv8.ERR_AUTHENTICATION)
                    val ctrlX = Hkdf.sha512(k, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
                    if (!Ed25519.verify(ctrlLtpk, ctrlX + ctrlId + ctrlLtpk, sig)) return setupError(6, Tlv8.ERR_AUTHENTICATION)
                    identity.upsert(Pairing(String(ctrlId), ctrlLtpk, admin = true))

                    val accX = Hkdf.sha512(k, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
                    val accId = identity.deviceId.toByteArray()
                    val accSig = Ed25519.sign(identity.secretSeed, accX + accId + identity.publicKey)
                    val subOut = Tlv8.encode(Tlv8.IDENTIFIER to accId, Tlv8.PUBLIC_KEY to identity.publicKey, Tlv8.SIGNATURE to accSig)
                    val enc = Aead.seal(encKey, Aead.nonce("PS-Msg06"), subOut)
                    respondTlv(Tlv8.encode(Tlv8.STATE to Tlv8.byte(6), Tlv8.ENCRYPTED_DATA to enc))
                    srp = null; pairSetupOwner = null; failedSetupAttempts = 0
                    log("Paired with controller ${String(ctrlId)}")
                    onPairingChanged()
                }
                else -> setupError(state + 1, Tlv8.ERR_UNKNOWN)
            }
        }

        // ---- Pair Verify (HAP section 5.7) ----

        private fun pairVerify(tlv: Map<Int, ByteArray>) {
            val state = tlv[Tlv8.STATE]?.get(0)?.toInt() ?: return setupError(2, Tlv8.ERR_UNKNOWN)
            when (state) {
                1 -> {
                    val ctrlPub = tlv[Tlv8.PUBLIC_KEY] ?: return setupError(2, Tlv8.ERR_UNKNOWN)
                    val priv = randomBytes(32)
                    val pub = X25519.publicKey(priv)
                    val shared = X25519.scalarMult(priv, ctrlPub)
                    verifyPriv = priv; verifyPub = pub; controllerVerifyPub = ctrlPub; verifyShared = shared
                    val accId = identity.deviceId.toByteArray()
                    val sig = Ed25519.sign(identity.secretSeed, pub + accId + ctrlPub)
                    val sessionKey = Hkdf.sha512(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
                    val enc = Aead.seal(sessionKey, Aead.nonce("PV-Msg02"), Tlv8.encode(Tlv8.IDENTIFIER to accId, Tlv8.SIGNATURE to sig))
                    respondTlv(Tlv8.encode(Tlv8.STATE to Tlv8.byte(2), Tlv8.PUBLIC_KEY to pub, Tlv8.ENCRYPTED_DATA to enc))
                }
                3 -> {
                    val shared = verifyShared ?: return setupError(4, Tlv8.ERR_AUTHENTICATION)
                    val sessionKey = Hkdf.sha512(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
                    val sealed = tlv[Tlv8.ENCRYPTED_DATA] ?: return setupError(4, Tlv8.ERR_AUTHENTICATION)
                    val plain = Aead.open(sessionKey, Aead.nonce("PV-Msg03"), sealed) ?: return setupError(4, Tlv8.ERR_AUTHENTICATION)
                    val sub = Tlv8.decode(plain)
                    val ctrlId = sub[Tlv8.IDENTIFIER]?.let { String(it) } ?: return setupError(4, Tlv8.ERR_AUTHENTICATION)
                    val sig = sub[Tlv8.SIGNATURE] ?: return setupError(4, Tlv8.ERR_AUTHENTICATION)
                    val pairing = identity.find(ctrlId)
                    if (pairing == null) {
                        log("Pair verify: unknown controller $ctrlId")
                        return setupError(4, Tlv8.ERR_AUTHENTICATION)
                    }
                    val info = controllerVerifyPub!! + ctrlId.toByteArray() + verifyPub!!
                    if (!Ed25519.verify(pairing.publicKey, info, sig)) return setupError(4, Tlv8.ERR_AUTHENTICATION)
                    respondTlv(Tlv8.encode(Tlv8.STATE to Tlv8.byte(4)))
                    readKey = Hkdf.sha512(shared, "Control-Salt", "Control-Read-Encryption-Key")
                    writeKey = Hkdf.sha512(shared, "Control-Salt", "Control-Write-Encryption-Key")
                    input = DecryptingStream()
                    controllerId = ctrlId
                    isVerified = true
                    verifyPriv = null
                    log("Session verified for ${ctrlId.take(8)} from $peer")
                }
                else -> setupError(state + 1, Tlv8.ERR_UNKNOWN)
            }
        }

        // ---- Characteristics (HAP section 6.7) ----

        private fun getCharacteristics(target: String) {
            val query = target.substringAfter("?", "")
            val params = query.split("&").filter { it.contains("=") }
                .associate { it.substringBefore("=") to java.net.URLDecoder.decode(it.substringAfter("="), "UTF-8") }
            val ids = params["id"]?.split(",").orEmpty()
            val results = JSONArray()
            var anyError = false
            for (id in ids) {
                val aid = id.substringBefore(".").toIntOrNull()
                val iid = id.substringAfter(".").toIntOrNull() ?: -1
                val (value, status) = if (aid == Iid.AID) readValue(iid) else (null to HapStatus.NOT_FOUND)
                val item = JSONObject().put("aid", aid ?: 0).put("iid", iid)
                if (status == 0) item.put("value", value) else { item.put("status", status); anyError = true }
                if (params["ev"] == "1") item.put("ev", iid in subscriptions)
                results.put(item)
            }
            if (anyError) {
                for (i in 0 until results.length()) {
                    val o = results.getJSONObject(i)
                    if (!o.has("status")) o.put("status", 0)
                }
                respondJson("207 Multi-Status", JSONObject().put("characteristics", results))
            } else {
                respondJson("200 OK", JSONObject().put("characteristics", results))
            }
        }

        /** Timed write, step 1 (HAP 6.7.2.4): Home sends this before every garage door command. */
        private fun prepare(body: JSONObject) {
            val pid = body.opt("pid")?.toString()
            val ttl = body.optLong("ttl", 0L)
            if (pid == null || ttl <= 0) {
                respondJson("400 Bad Request", JSONObject().put("status", HapStatus.INVALID_VALUE))
                return
            }
            preparedId = pid
            preparedUntil = System.currentTimeMillis() + ttl
            respondJson("200 OK", JSONObject().put("status", 0))
        }

        private fun putCharacteristics(body: JSONObject) {
            val items = body.optJSONArray("characteristics") ?: JSONArray()
            val pid = body.opt("pid")?.toString()
            if (pid != null) {
                val valid = pid == preparedId && System.currentTimeMillis() <= preparedUntil
                preparedId = null
                if (!valid) {
                    log("Timed write rejected: pid unknown or expired")
                    val results = JSONArray()
                    for (i in 0 until items.length()) {
                        val o = items.getJSONObject(i)
                        results.put(JSONObject().put("aid", o.optInt("aid")).put("iid", o.optInt("iid")).put("status", HapStatus.INVALID_VALUE))
                    }
                    respondJson("207 Multi-Status", JSONObject().put("characteristics", results))
                    return
                }
            }
            val results = JSONArray()
            var anyError = false
            var wantResponse = false
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                val aid = o.optInt("aid"); val iid = o.optInt("iid")
                var status = 0
                val result = JSONObject().put("aid", aid).put("iid", iid)
                if (aid != Iid.AID) status = HapStatus.NOT_FOUND
                if (status == 0 && o.has("ev")) {
                    val on = o.optBoolean("ev", false)
                    if (doorSlot(iid) != null && Iid.localOf(iid) in setOf(Iid.CURRENT_DOOR, Iid.TARGET_DOOR, Iid.OBSTRUCTION, Iid.CONFIGURED_NAME)) {
                        if (on) subscriptions.add(iid) else subscriptions.remove(iid)
                    } else status = HapStatus.NO_NOTIFICATION
                }
                if (status == 0 && o.has("value")) {
                    val raw = o.get("value")
                    val intValue = when (raw) {
                        is Boolean -> if (raw) 1 else 0
                        is Number -> raw.toInt()
                        is String -> raw.toIntOrNull()
                        else -> null
                    }
                    val slot = doorSlot(iid)
                    status = when {
                        slot != null && Iid.localOf(iid) == Iid.CONFIGURED_NAME -> {
                            val label = (raw as? String)?.trim()?.take(64)
                            if (label.isNullOrEmpty()) HapStatus.INVALID_VALUE
                            else {
                                backend.setDoorLabel(slot, label)
                                notifyChanged(iid, label, except = this)
                                0
                            }
                        }
                        slot != null && Iid.localOf(iid) == Iid.TARGET_DOOR -> {
                            if (intValue == null || intValue !in 0..1) HapStatus.INVALID_VALUE
                            else {
                                val which = if (slot == 0) "" else " ${backend.doorName(slot)}"
                                log("Controller ${controllerId?.take(8)} asked to ${if (intValue == 0) "OPEN" else "CLOSE"}$which")
                                val st = backend.setTargetDoorState(slot, intValue)
                                if (st == 0) notifyChanged(iid, backend.targetDoorState(slot), except = this)
                                st
                            }
                        }
                        iid == Iid.IDENTIFY -> { backend.identify(); 0 }
                        else -> HapStatus.READ_ONLY
                    }
                    if (o.optBoolean("r", false)) {
                        wantResponse = true
                        if (status == 0) result.put("value", readValue(iid).first)
                    }
                }
                if (status != 0) anyError = true
                result.put("status", status)
                results.put(result)
            }
            if (anyError || wantResponse) respondJson("207 Multi-Status", JSONObject().put("characteristics", results))
            else respondNoContent()
        }

        // ---- Pairings management (HAP section 5.10-5.12) ----

        private fun pairings(tlv: Map<Int, ByteArray>) {
            val me = controllerId?.let { identity.find(it) }
            if (me == null || !me.admin) return setupError(2, Tlv8.ERR_AUTHENTICATION)
            when (tlv[Tlv8.METHOD]?.get(0)?.toInt()) {
                3 -> {
                    val id = tlv[Tlv8.IDENTIFIER]?.let { String(it) }; val pk = tlv[Tlv8.PUBLIC_KEY]
                    val admin = (tlv[Tlv8.PERMISSIONS]?.get(0)?.toInt() ?: 0) == 1
                    if (id == null || pk == null) return setupError(2, Tlv8.ERR_UNKNOWN)
                    val existing = identity.find(id)
                    if (existing != null && !existing.publicKey.contentEquals(pk)) return setupError(2, Tlv8.ERR_UNKNOWN)
                    identity.upsert(Pairing(id, pk, admin))
                    log("Added controller ${id.take(8)} (admin=$admin)")
                    respondTlv(Tlv8.encode(Tlv8.STATE to Tlv8.byte(2)))
                    onPairingChanged()
                }
                4 -> {
                    val id = tlv[Tlv8.IDENTIFIER]?.let { String(it) } ?: return setupError(2, Tlv8.ERR_UNKNOWN)
                    identity.remove(id)
                    log("Removed controller ${id.take(8)}; paired=${identity.isPaired}")
                    respondTlv(Tlv8.encode(Tlv8.STATE to Tlv8.byte(2)))
                    for (c in connections) {
                        if (c.controllerId != null && identity.find(c.controllerId!!) == null) c.close()
                    }
                    onPairingChanged()
                }
                5 -> {
                    val items = mutableListOf<Pair<Int, ByteArray>>(Tlv8.STATE to Tlv8.byte(2))
                    identity.pairings.forEachIndexed { i, p ->
                        if (i > 0) items.add(Tlv8.SEPARATOR to ByteArray(0))
                        items.add(Tlv8.IDENTIFIER to p.id.toByteArray())
                        items.add(Tlv8.PUBLIC_KEY to p.publicKey)
                        items.add(Tlv8.PERMISSIONS to Tlv8.byte(if (p.admin) 1 else 0))
                    }
                    respondTlv(Tlv8.encode(items))
                }
                else -> setupError(2, Tlv8.ERR_UNKNOWN)
            }
        }
    }

    data class Request(val method: String, val target: String, val headers: Map<String, String>, val body: ByteArray)
}

fun readExactly(input: InputStream, n: Int): ByteArray? {
    val buf = ByteArray(n)
    var off = 0
    while (off < n) {
        val r = input.read(buf, off, n - off)
        if (r < 0) return null
        off += r
    }
    return buf
}
