package com.thesis.geckowifi.eduroam

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The one eduroam account the demo holds (demo shortcut: a single account).
 * The password is encrypted with an AES-GCM key that never leaves the Android
 * Keystore; only [EduroamCredentials] ever carries it in memory.
 */
interface CredentialStore {
    fun load(): EduroamCredentials?
    /** The identity alone, for prefilling the form; never the password. */
    fun storedIdentity(): String?
    fun save(identity: String, password: String)
    fun clear()
}

class KeystoreCredentialStore(private val prefs: SharedPreferences) : CredentialStore {

    override fun load(): EduroamCredentials? {
        val identity = prefs.getString(IDENTITY, null) ?: return null
        val iv = prefs.getString(IV, null) ?: return null
        val ciphertext = prefs.getString(PASSWORD, null) ?: return null
        val password = runCatching {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b64d(iv)))
                String(doFinal(b64d(ciphertext)), Charsets.UTF_8)
            }
        }.getOrNull() ?: return null // key gone (e.g. app data restored elsewhere): ask again
        return EduroamCredentials(identity, password)
    }

    override fun storedIdentity(): String? = prefs.getString(IDENTITY, null)

    override fun save(identity: String, password: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ciphertext = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        prefs.edit {
            putString(IDENTITY, identity.trim())
            putString(IV, b64e(cipher.iv))
            putString(PASSWORD, b64e(ciphertext))
        }
    }

    override fun clear() = prefs.edit { remove(IDENTITY); remove(IV); remove(PASSWORD) }

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
        const val ALIAS = "gecko_eduroam_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IDENTITY = "eduroam_identity"
        const val IV = "eduroam_password_iv"
        const val PASSWORD = "eduroam_password"

        fun b64e(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
        fun b64d(text: String): ByteArray = Base64.getDecoder().decode(text)
    }
}
