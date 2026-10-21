package com.thesis.geckowifi.eduroam

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** An account as the UI sees it: never the password. */
data class AccountInfo(val id: String, val identity: String, val label: String? = null) {
    /** Lower-case realm of [identity] (`user@realm`), or null if there is none. */
    val realm: String? get() = identity.substringAfterLast('@', "").lowercase().ifBlank { null }
}

/**
 * The user's enterprise accounts, one per home provider (realm), any number
 * (docs/eduroam-decisions.md, D19). Passwords only leave the store inside an
 * [EnterpriseAccount], whose password only [EnterpriseProfileBuilder] can read.
 */
interface AccountStore {
    fun accounts(): List<AccountInfo>
    fun load(id: String): EnterpriseAccount?
    /** Adds an account, or replaces the password of the one with the same identity. */
    fun save(identity: String, password: String, label: String? = null): AccountInfo
    fun setLabel(id: String, label: String?)
    fun remove(id: String)
    /** The account the user picked for [networkKey] when several fitted (D19). */
    fun preferredAccount(networkKey: String): String?
    fun setPreferredAccount(networkKey: String, accountId: String)
}

/** For tests and previews: nothing leaves memory. */
class InMemoryAccountStore : AccountStore {
    private val entries = linkedMapOf<String, Pair<AccountInfo, String>>()
    private val preferred = mutableMapOf<String, String>()

    override fun accounts() = entries.values.map { it.first }
    override fun load(id: String) = entries[id]?.let { (info, pw) -> EnterpriseAccount(info.id, info.identity, pw, info.label) }
    override fun save(identity: String, password: String, label: String?): AccountInfo {
        val existing = entries.values.firstOrNull { it.first.identity.equals(identity.trim(), ignoreCase = true) }?.first
        val info = AccountInfo(existing?.id ?: UUID.randomUUID().toString(), identity.trim(), label ?: existing?.label)
        entries[info.id] = info to password
        return info
    }
    override fun setLabel(id: String, label: String?) {
        entries[id]?.let { (info, pw) -> entries[id] = info.copy(label = label) to pw }
    }
    override fun remove(id: String) { entries.remove(id); preferred.values.removeAll { it == id } }
    override fun preferredAccount(networkKey: String) = preferred[networkKey]?.takeIf { it in entries }
    override fun setPreferredAccount(networkKey: String, accountId: String) { preferred[networkKey] = accountId }
}

/**
 * Accounts in SharedPreferences, each password encrypted with an AES-GCM key
 * that never leaves the Android Keystore. Migrates the single account of the
 * first prototype (`eduroam_identity` / `eduroam_password`).
 */
class KeystoreAccountStore(private val prefs: SharedPreferences) : AccountStore {

    @Serializable
    private data class Stored(val id: String, val identity: String, val label: String? = null, val iv: String, val ciphertext: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Stored.serializer())

    init {
        migrateLegacy()
    }

    private fun read(): List<Stored> = prefs.getString(ACCOUNTS, null)
        ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
        .orEmpty()

    private fun write(list: List<Stored>) = prefs.edit { putString(ACCOUNTS, json.encodeToString(serializer, list)) }

    override fun accounts() = read().map { AccountInfo(it.id, it.identity, it.label) }

    override fun load(id: String): EnterpriseAccount? {
        val stored = read().firstOrNull { it.id == id } ?: return null
        val password = runCatching {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b64d(stored.iv)))
                String(doFinal(b64d(stored.ciphertext)), Charsets.UTF_8)
            }
        }.getOrNull() ?: return null // key gone (e.g. app data restored on another device): ask again
        return EnterpriseAccount(stored.id, stored.identity, password, stored.label)
    }

    override fun save(identity: String, password: String, label: String?): AccountInfo {
        val list = read()
        val existing = list.firstOrNull { it.identity.equals(identity.trim(), ignoreCase = true) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ciphertext = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val stored = Stored(existing?.id ?: UUID.randomUUID().toString(), identity.trim(), label ?: existing?.label, b64e(cipher.iv), b64e(ciphertext))
        write(list.filterNot { it.id == stored.id } + stored)
        return AccountInfo(stored.id, stored.identity, stored.label)
    }

    override fun setLabel(id: String, label: String?) = write(read().map { if (it.id == id) it.copy(label = label) else it })

    override fun remove(id: String) {
        write(read().filterNot { it.id == id })
        prefs.edit { prefs.all.keys.filter { it.startsWith(PREFERRED) && prefs.getString(it, null) == id }.forEach { remove(it) } }
    }

    override fun preferredAccount(networkKey: String) =
        prefs.getString(PREFERRED + networkKey, null)?.takeIf { id -> read().any { it.id == id } }

    override fun setPreferredAccount(networkKey: String, accountId: String) = prefs.edit { putString(PREFERRED + networkKey, accountId) }

    /** The first prototype stored one account under fixed keys; it becomes the first list entry. */
    private fun migrateLegacy() {
        val identity = prefs.getString(LEGACY_IDENTITY, null) ?: return
        val iv = prefs.getString(LEGACY_IV, null)
        val ciphertext = prefs.getString(LEGACY_PASSWORD, null)
        if (iv != null && ciphertext != null && read().none { it.identity.equals(identity, ignoreCase = true) }) {
            write(read() + Stored(UUID.randomUUID().toString(), identity, null, iv, ciphertext))
        }
        prefs.edit { remove(LEGACY_IDENTITY); remove(LEGACY_IV); remove(LEGACY_PASSWORD) }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        // The key alias of the first prototype: legacy ciphertexts stay decryptable.
        const val ALIAS = "gecko_eduroam_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ACCOUNTS = "enterprise_accounts"
        const val PREFERRED = "preferred_account:"
        const val LEGACY_IDENTITY = "eduroam_identity"
        const val LEGACY_IV = "eduroam_password_iv"
        const val LEGACY_PASSWORD = "eduroam_password"

        fun b64e(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
        fun b64d(text: String): ByteArray = Base64.getDecoder().decode(text)
    }
}
