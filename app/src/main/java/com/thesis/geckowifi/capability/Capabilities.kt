package com.thesis.geckowifi.capability

object Capabilities {
    val hasRoot: Boolean by lazy { RootShell.isAvailable() }

    fun certificateSource(): CertificateSource =
        if (hasRoot) SupplicantCertSource() else InferredCertSource()

    /** Root-only by design: GECKO is assumed to run at system level (see LAB_SETUP.md). */
    fun enforcer(): Enforcer = IptablesEnforcer()
}