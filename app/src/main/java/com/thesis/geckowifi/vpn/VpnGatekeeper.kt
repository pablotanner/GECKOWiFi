package com.thesis.geckowifi.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream

class VpnGatekeeper : VpnService() {
    private var tunInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification())
        tunInterface = Builder()
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .setSession("GECKO Verification")
            .establish()
        Thread { runPacketLoop() }.start()
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val channelId = "vpn_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "VPN Service"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(channelId, name, importance)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("GECKO VPN")
            .setContentText("Scanning for secure Wi-Fi portals...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    private fun runPacketLoop() {
        val input = FileInputStream(tunInterface?.fileDescriptor)
        val buffer = ByteArray(32767)
        while (true) {
            val length = input.read(buffer)
            if (length <= 0) continue
            val packet = IpPacket.parse(buffer, length) ?: continue
            if (packet.protocol == IpPacket.TCP && packet.dstPort == 443) {
                TlsSniParser.extractSni(packet.payload)?.let { host ->
                    // hand off to VerificationEngine on a coroutine,
                    // consult DecisionCache, then forward or drop
                }
            }
            // MVP: forward everything else unconditionally while you
            // build out per-flow blocking — a fully permissive tunnel
            // that only *observes* SNI is a legitimate first milestone
        }
    }
}