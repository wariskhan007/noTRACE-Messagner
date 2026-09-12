package com.notrace.messenger.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * "App lock: PIN/biometric authentication to open the app" (Section 4).
 *
 * This class only tracks *state* (is app-lock enabled, is the app
 * currently locked) — the actual biometric/device-credential prompt is a
 * platform UI concern handled by BiometricPrompt in MainActivity, which
 * calls back into [AppLockManager.unlock] on success.
 *
 * Default behavior mirrors Signal: if enabled, lock immediately on every
 * backgrounding — no grace period — since a grace period is a common
 * source of "why did my messages show on my friend's lock screen" bugs.
 */
class AppLockManager(context: Context) {

    companion object {
        private const val PREFS_FILE = "notrace_security_prefs"
        private const val PREF_LOCK_ENABLED = "app_lock_enabled"
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

    var isEnabled: Boolean
        get() = prefs.getBoolean(PREF_LOCK_ENABLED, false) // opt-in by default; flip true if you want it on out of the box
        set(value) {
            prefs.edit().putBoolean(PREF_LOCK_ENABLED, value).apply()
            if (!value) locked = false
        }

    /**
     * In-memory only — deliberately NOT persisted. A process restart (e.g.
     * after being killed in the background) should always come up locked
     * whenever `isEnabled` is true; there's no "remember unlocked" state.
     */
    var locked: Boolean = isEnabled
        private set

    fun onAppBackgrounded() {
        if (isEnabled) locked = true
    }

    fun unlock() {
        locked = false
    }

    fun shouldShowLockScreen(): Boolean = isEnabled && locked
}
