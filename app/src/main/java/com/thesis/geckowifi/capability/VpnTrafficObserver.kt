package com.thesis.geckowifi.capability

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.thesis.geckowifi.vpn.VpnGatekeeper

class VpnTrafficObserver(private val context: Context) : TrafficObserver {

    override var isActive = false
        private set

    /** Null when already authorised; otherwise start this for result before calling start(). */
    fun consentIntent(): Intent? = VpnService.prepare(context)

    override fun start(onHost: (String) -> Unit) {
        TrafficBus.attach(onHost)
        context.startService(Intent(context, VpnGatekeeper::class.java))
        isActive = true
    }

    override fun stop() {
        TrafficBus.detach()
        context.stopService(Intent(context, VpnGatekeeper::class.java))
        isActive = false
    }
}