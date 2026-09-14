package com.thesis.geckowifi.verification

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

class CertProbe {
    private val permissiveTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    fun fetchCertificate(host: String, port: Int = 443): X509Certificate? {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(permissiveTrustManager), SecureRandom())
        return try {
            (context.socketFactory.createSocket(host, port) as SSLSocket).use { socket ->
                socket.startHandshake()
                socket.session.peerCertificates.firstOrNull() as? X509Certificate
            }
        } catch (e: Exception) { null }
    }

    fun spkiHash(cert: X509Certificate?): String? {
        cert ?: return null
        val spki = cert.publicKey.encoded
        return Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(spki), Base64.NO_WRAP)
    }
}