package com.thesis.geckowifi.verification

import android.net.Network
import android.util.Base64
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

private const val TAG = "CertProbe"

class CertProbe {
    /**
     * The network to probe over - the WiFi the app joined via
     * `WifiNetworkSpecifier`, which is never the default network (it has no
     * INTERNET capability), so an unbound probe would go out over whatever
     * else the device is on and see a different server entirely. `null`
     * uses the default network.
     */
    @Volatile var network: Network? = null

    private val permissiveTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    fun fetchCertificate(host: String, port: Int = 443): X509Certificate? {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(permissiveTrustManager), SecureRandom())
        return try {
            val bound = network
            val socket = if (bound != null) {
                // Network's own factory resolves DNS and connects over that network; TLS layered on top.
                val raw = bound.socketFactory.createSocket(host, port)
                context.socketFactory.createSocket(raw, host, port, true)
            } else {
                context.socketFactory.createSocket(host, port)
            }
            (socket as SSLSocket).use {
                it.startHandshake()
                (it.session.peerCertificates.firstOrNull() as? X509Certificate)
                    .also { cert -> Log.i(TAG, "$host:$port over $bound presented ${cert?.subjectX500Principal}") }
            }
        } catch (e: Exception) {
            // Still mapped to "no key presented" by the caller, but the cause matters when debugging the lab setup.
            Log.w(TAG, "TLS probe of $host:$port over $network failed", e)
            null
        }
    }

    fun spkiHash(cert: X509Certificate?): String? {
        cert ?: return null
        val spki = cert.publicKey.encoded
        return Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(spki), Base64.NO_WRAP)
            .also { Log.i(TAG, "presented SPKI sha256: $it") }
    }
}
