package com.thesis.geckowifi.capability

import android.content.Context
import com.thesis.geckowifi.network.Association
import com.thesis.geckowifi.network.AssociationSource
import com.thesis.geckowifi.network.WifiInfoAssociation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Capabilities {
    /** Blocking (runs `su`): call off the main thread. */
    val hasRoot: Boolean by lazy { RootShell.isAvailable() }

    fun certificateSource(): CertificateSource =
        if (hasRoot) SupplicantCertSource() else InferredCertSource()

    /** wpa_supplicant with root, `WifiInfo` without; decided on each read, off the main thread ([hasRoot] blocks). */
    fun associationSource(context: Context): AssociationSource {
        val root = SupplicantAssociation()
        val nonRoot = WifiInfoAssociation(context)
        return object : AssociationSource {
            override suspend fun current(): Association? =
                withContext(Dispatchers.IO) { (if (hasRoot) root else nonRoot).current() }
        }
    }

    /** Root-only by design: GECKO is assumed to run at system level (see docs/lab-setup.md). */
    fun enforcer(): Enforcer = IptablesEnforcer()
}
