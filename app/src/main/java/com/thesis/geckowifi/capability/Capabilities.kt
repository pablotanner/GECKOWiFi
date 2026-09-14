package com.thesis.geckowifi.capability

object Capabilities {
    val hasRoot: Boolean by lazy { RootShell.isAvailable() }

    fun certificateSource(): CertificateSource =
        if (hasRoot) SupplicantCertSource() else InferredCertSource()

    fun enforcer(): Enforcer =
        if (hasRoot) IptablesEnforcer() else VpnDropEnforcer()
}