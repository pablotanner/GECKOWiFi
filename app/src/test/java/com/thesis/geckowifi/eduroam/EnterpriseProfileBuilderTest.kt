package com.thesis.geckowifi.eduroam

import com.thesis.geckowifi.eduroam.EduroamFixtures.pins
import com.thesis.geckowifi.eduroam.EduroamFixtures.record
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/** Recording installer: what an installer would be given, and with which password. */
class RecordingInstaller(private val outcome: InstallOutcome = InstallOutcome.Installed("ok"), private val connects: Boolean = true) :
    EnterpriseProfileInstaller {
    val installed = mutableListOf<Pair<ProfilePlan, String>>()
    override suspend fun install(plan: ProfilePlan, password: String): InstallOutcome = outcome.also { installed += plan to password }
    override suspend fun remove(ssid: String) = Unit
    override suspend fun awaitConnected(ssid: String, timeoutMs: Long) = connects
}

/** The credential invariant (eduroam-brief.md, section 2) and its one code path. */
class EnterpriseProfileBuilderTest {
    private val alice = EduroamCredentials("alice@homeuni.test", "s3cret")
    private val network = TargetNetwork("eduroam-lab")

    @Test
    fun install_passesThePasswordOnlyWithTheVerifiedPins() = runTest {
        val pins = pins()
        val installer = RecordingInstaller()
        EnterpriseProfileBuilder.install(pins, alice, network, installer)

        val (plan, password) = installer.installed.single()
        assertEquals("s3cret", password)
        assertEquals(pins.caCertificates, plan.caCertificates)
        assertEquals(pins.serverNames, plan.serverNames)
        assertEquals("anonymous@homeuni.test", plan.outerIdentity)
        assertEquals("alice@homeuni.test", plan.identity)
        assertEquals("eduroam-lab", plan.ssid)
    }

    @Test
    fun pinsForAnotherRealm_areRefusedBeforeAnyInstaller() = runTest {
        val otherRealm = pins(record(realm = "other.test", outer = "anonymous@other.test"))
        val installer = RecordingInstaller()
        val thrown = runCatching { EnterpriseProfileBuilder.install(otherRealm, alice, network, installer) }.exceptionOrNull()
        assertTrue(thrown is CredentialsRefused)
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun identityWithoutRealm_isRefused() {
        val thrown = runCatching { EnterpriseProfileBuilder.plan(pins(), EduroamCredentials("alice", "s3cret"), network) }.exceptionOrNull()
        assertTrue(thrown is CredentialsRefused)
    }

    @Test
    fun emptyPassword_isRefused() {
        val thrown = runCatching { EnterpriseProfileBuilder.plan(pins(), EduroamCredentials("alice@homeuni.test", ""), network) }.exceptionOrNull()
        assertTrue(thrown is CredentialsRefused)
    }

    @Test
    fun planCarriesNoPassword() {
        val plan = EnterpriseProfileBuilder.plan(pins(), alice, network)
        assertFalse(plan.toString().contains("s3cret"))
    }

    @Test
    fun credentialsNeverPrintThePassword() {
        assertFalse(alice.toString().contains("s3cret"))
    }

    /**
     * Structural half of the invariant: no public member of [EduroamCredentials]
     * gives the password out. If this fails, someone added a getter; route it
     * through [EnterpriseProfileBuilder] instead.
     */
    @Test
    fun noPublicMemberOfTheCredentialsReturnsThePassword() {
        val leaks = EduroamCredentials::class.java.methods
            .filter { it.parameterCount == 0 && Modifier.isPublic(it.modifiers) && it.declaringClass == EduroamCredentials::class.java }
            .filter { runCatching { it.invoke(alice) }.getOrNull()?.toString()?.contains("s3cret") == true }
        assertEquals(emptyList<String>(), leaks.map { it.name })
        assertNull(EduroamCredentials::class.java.fields.firstOrNull { it.name == "password" })
    }

    /**
     * The only way to get [AuthenticatedEapPins] is through the verifying
     * factories. (Kotlin also emits a synthetic constructor so the companion
     * can call the private one; source code can't call synthetic members.)
     */
    @Test
    fun pinsHaveNoPublicConstructor() {
        assertTrue(AuthenticatedEapPins::class.java.constructors.none { !it.isSynthetic })
    }
}
