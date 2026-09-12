package com.notrace.messenger.data.db

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The SQLCipher passphrase is a random 256-bit value generated once on
 * first launch, then itself envelope-encrypted with a Keystore-resident
 * AES key — same envelope-encryption pattern used throughout
 * com.notrace.messenger.crypto.signal for key material at rest. The raw passphrase never touches disk
 * unencrypted and is held in memory only for the life of an open DB handle.
 */
class DatabaseKeyProvider(private val context: Context) {

    companion object {
        private const val KEYSTORE_ALIAS = "notrace_db_wrapping_key"
        private const val PREFS_FILE = "notrace_db_prefs"
        private const val PREF_KEY_ENC = "db_key_enc"
        private const val PREF_KEY_IV = "db_key_iv"
    }

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getOrCreatePassphrase(): ByteArray {
        val encExisting = prefs.getString(PREF_KEY_ENC, null)
        val ivExisting = prefs.getString(PREF_KEY_IV, null)
        if (encExisting != null && ivExisting != null) {
            return decrypt(Base64.decode(encExisting, Base64.NO_WRAP), Base64.decode(ivExisting, Base64.NO_WRAP))
        }

        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
        val encrypted = cipher.doFinal(passphrase)
        prefs.edit()
            .putString(PREF_KEY_ENC, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(PREF_KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
        return passphrase
    }

    private fun decrypt(encrypted: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrappingKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    private fun getOrCreateWrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
