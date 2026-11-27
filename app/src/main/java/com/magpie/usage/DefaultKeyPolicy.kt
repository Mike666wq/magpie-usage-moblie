package com.magpie.usage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A key can be attached only to an explicit, saved-panel navigation. In
 * particular, arbitrary redirects and links never call [appendForSavedPanel].
 */
internal object DefaultKeyPolicy {
    fun appendForSavedPanel(
        panel: PanelEntry,
        cleanUrl: String,
        key: String?,
        reuseEnabled: Boolean,
    ): String? {
        if (!reuseEnabled || key.isNullOrBlank()) return null
        val target = try {
            PanelUrl.parse(cleanUrl)
        } catch (_: IllegalArgumentException) {
            return null
        }
        // A full paste with its own k must not be rewritten by the default-key
        // feature; the navigation policy separately confines it to its origin.
        if (target.origin != panel.origin || target.redirectAfterLoad) return null
        return PanelUrl.withDefaultKey(target.cleanUrl, key)
    }

    fun reusableKey(
        key: String?,
        reuseEnabled: Boolean,
        captureEnabled: Boolean,
    ): String? = if (reuseEnabled || captureEnabled) key?.takeIf { it.isNotBlank() } else null
}

/**
 * AES-256-GCM under an Android Keystore key. SharedPreferences receives only
 * ciphertext, IV and format version; plaintext k never crosses that boundary.
 */
internal class DefaultKeyStore(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isPresent(): Boolean = preferences.contains(CIPHERTEXT)

    fun save(key: String) {
        require(key.isNotBlank()) { "A default key must not be blank." }
        val secretKey = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, SecureRandom())
        val encrypted = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        val editor = preferences.edit()
            .putInt(VERSION, FORMAT_VERSION)
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
        check(editor.commit()) { "Unable to save the encrypted default key." }
    }

    fun load(): String? {
        if (!isPresent()) return null
        return try {
            require(preferences.getInt(VERSION, -1) == FORMAT_VERSION)
            val iv = Base64.decode(preferences.getString(IV, null), Base64.NO_WRAP)
            val encrypted = Base64.decode(preferences.getString(CIPHERTEXT, null), Base64.NO_WRAP)
            require(iv.isNotEmpty() && encrypted.isNotEmpty())
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val secretKey = runCatching { getOrCreateKey() }.getOrNull()
                ?: return null
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8).takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            // Keystore invalidation and corrupt records fail closed. The caller
            // can manually save a new key; no secret or sensitive error is logged.
            null
        }
    }

    fun clear() {
        check(preferences.edit().remove(IV).remove(CIPHERTEXT).remove(VERSION).commit()) {
            "Unable to clear the encrypted default key."
        }
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private fun getOrCreateKey(): SecretKey {
        val store = keyStore()
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.ALGORITHM, KEYSTORE_PROVIDER)
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    internal companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "magpie_usage_default_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE = 256
        const val GCM_TAG_BITS = 128
        const val FORMAT_VERSION = 1
        const val VERSION = "default_key_format_version"
        const val IV = "default_key_iv"
        const val CIPHERTEXT = "default_key_ciphertext"
    }
}

internal object DefaultKeyPreferences {
    const val REUSE_ENABLED = "default_key_reuse_enabled"
    const val CAPTURE_ENABLED = "default_key_capture_enabled"
    const val CAPTURE_ORIGIN = "default_key_capture_origin"
    const val CAPTURE_CONFIRMED_AT = "default_key_capture_confirmed_at"

    fun setCaptureOrigin(preferences: SharedPreferences, origin: String?) {
        preferences.edit().apply {
            if (origin == null) {
                remove(CAPTURE_ORIGIN)
                remove(CAPTURE_CONFIRMED_AT)
            } else {
                putString(CAPTURE_ORIGIN, origin)
                remove(CAPTURE_CONFIRMED_AT)
            }
        }.apply()
    }
}
