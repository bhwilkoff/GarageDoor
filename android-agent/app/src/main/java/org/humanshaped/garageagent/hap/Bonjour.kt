package org.humanshaped.garageagent.hap

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/** Advertises the accessory as _hap._tcp through Android's own mDNS responder. */
class Bonjour(context: Context, private val log: (String) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    @Synchronized
    fun advertise(name: String, port: Int, identity: HapIdentity) {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = "_hap._tcp"
            setPort(port)
            setAttribute("c#", identity.configNumber.toString())
            setAttribute("ff", "0")
            setAttribute("id", identity.deviceId)
            setAttribute("md", name)
            setAttribute("pv", "1.1")
            setAttribute("s#", "1")
            setAttribute("sf", if (identity.isPaired) "0" else "1")
            setAttribute("ci", "4")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) = log("Advertising '${s.serviceName}' (paired=${identity.isPaired})")
            override fun onRegistrationFailed(s: NsdServiceInfo, code: Int) = log("Bonjour registration failed: $code")
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, code: Int) {}
        }
        listener = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    @Synchronized
    fun stop() {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
    }
}
