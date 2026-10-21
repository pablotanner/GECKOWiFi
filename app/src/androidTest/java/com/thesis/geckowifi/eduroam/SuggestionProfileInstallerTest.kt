package com.thesis.geckowifi.eduroam

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thesis.geckowifi.R
import gecko.realm.RealmRecord
import gecko.realm.SignedRealmRecord
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant

/**
 * On a real device: does Android (here One UI) accept the pinned profile the
 * app builds (CA certificate + domain_suffix_match + anonymous identity,
 * WPA2-Enterprise suggestion)? Uses an SSID nobody broadcasts and removes the
 * suggestion again.
 *
 *   ./gradlew :app:connectedDeviceDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class SuggestionProfileInstallerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val ssid = "gecko-instrumented-test"

    private fun pins(): AuthenticatedEapPins {
        val ca = context.resources.openRawResource(R.raw.gecko_onboarding_ca).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val now = Instant.now()
        val record = RealmRecord.ADAPTER.encode(RealmRecord(
            federation = "eduroam", realm = "homeuni.test", eap_method = "PEAP", phase2 = "MSCHAPV2", outer_identity = "anonymous@homeuni.test",
            ca_der = listOf(ca.encoded.toByteString()), server_names = listOf("radius.homeuni.test"),
            issued_at = now.epochSecond - 60, expires_at = now.epochSecond + 3600
        ))
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(record); sign() }
        val signed = SignedRealmRecord.ADAPTER.encode(SignedRealmRecord(record.toByteString(), signature.toByteString()))
        return (AuthenticatedEapPins.fromSignedRealmRecord(signed, "eduroam", "homeuni.test", key.public, now) as PinResult.Ok).pins
    }

    @Test
    fun androidAcceptsThePinnedSuggestion() = runBlocking {
        val installer = SuggestionProfileInstaller(context)
        try {
            val outcome = EnterpriseProfileBuilder.install(
                pins(), EnterpriseAccount("test", "alice@homeuni.test", "not-a-real-password"), TargetNetwork(ssid, federation = "eduroam"), installer
            )
            assertTrue("install outcome: $outcome", outcome is InstallOutcome.Installed)
        } finally {
            installer.remove(ssid)
        }
    }
}
