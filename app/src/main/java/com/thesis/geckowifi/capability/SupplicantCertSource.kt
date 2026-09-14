package com.thesis.geckowifi.capability

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class SupplicantCertSource : CertificateSource {
    override val isGroundTruth = true

    private val socketPaths = listOf(
        "/data/vendor/wifi/wpa/sockets",   // Android 9+
        "/data/misc/wifi/sockets"          // older
    )

    private fun controlSocketDir(): String? = socketPaths.firstOrNull { path ->
        RootShell.exec("ls $path 2>/dev/null").isNotBlank()
    }

    override suspend fun presentedCertificate(ssid: String): X509Certificate? =
        withContext(Dispatchers.IO) {
            val dir = controlSocketDir() ?: return@withContext null
            // wpa_supplicant emits CTRL-EVENT-EAP-PEER-CERT with depth + hash + cert
            val events = RootShell.exec(
                "timeout 10 wpa_cli -p $dir -i wlan0 -a /system/bin/true 2>/dev/null | grep EAP-PEER-CERT"
            )
            parsePeerCert(events)
        }

    private fun parsePeerCert(raw: String): X509Certificate? {
        // CTRL-EVENT-EAP-PEER-CERT depth=0 subject='...' hash=<sha256> cert=<base64 DER>
        val certB64 = Regex("cert=([A-Za-z0-9+/=]+)").find(raw)?.groupValues?.get(1)
            ?: return null
        val der = Base64.decode(certB64, Base64.DEFAULT)
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as? X509Certificate
    }
}