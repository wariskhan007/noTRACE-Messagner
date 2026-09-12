package com.notrace.messenger.security

import android.content.Context
import java.security.KeyStore

/**
 * "Secure wipe: emergency data deletion" (Section 4). Deliberately blunt:
 * removes the SQLCipher database file (already `secure_delete`-enabled, so
 * its pages are overwritten, not just unlinked), every EncryptedSharedPreferences
 * file this app writes, and every AndroidKeyStore alias it created — after
 * this call, there is no key left anywhere on the device that could ever
 * decrypt anything that used to be here.
 *
 * This is intentionally NOT reversible and NOT "log out" — it's the panic
 * button. A real "log out and re-register" flow (keeping the option to
 * restore from a backup) is a different, separate feature to design later
 * if wanted; conflating the two here would be exactly the kind of subtle
 * privacy bug this project is trying to avoid.
 */
object SecureWipeManager {

    private val keystoreAliases = listOf(
        "notrace_identity_wrapping_key", // Phase 1, now superseded by signal_identity table but alias may still exist on upgraded installs
        "notrace_db_wrapping_key",
    )

    private val encryptedPrefsFiles = listOf(
        "notrace_identity_prefs",
        "notrace_db_prefs",
        "notrace_security_prefs",
    )

    fun wipeEverything(context: Context) {
        context.deleteDatabase("notrace.db")

        encryptedPrefsFiles.forEach { fileName ->
            context.deleteSharedPreferences(fileName)
        }

        // BUGFIX (Phase 1-4 audit): this used to stop at the DB + prefs +
        // Keystore aliases and never touch filesDir/attachments/ — so every
        // encrypted attachment blob AttachmentStore ever wrote (Phase 4)
        // silently survived a "secure wipe". Encrypted-at-rest is not the
        // same guarantee as "gone"; the whole point of this button is the
        // latter.
        wipeAttachments(context)

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keystoreAliases.forEach { alias ->
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }

        // WorkManager's own scheduling DB isn't secret material, but cancel
        // the sweep worker anyway so it doesn't immediately recreate a
        // fresh (empty) database file the instant it next runs.
        androidx.work.WorkManager.getInstance(context).cancelAllWork()

        // CRITICAL BUGFIX (audit): everything above deletes files on disk,
        // but AppContainer's `by lazy` properties — the open NoTraceDatabase
        // connection, the SqlIdentityKeyStore's cached IdentityKeyPair, the
        // in-memory SessionCipher state, all of it — stay alive and cached
        // in the current process, completely unaffected by deleting the
        // files they were originally loaded from. Without a real process
        // restart, the app keeps running against that in-memory state (an
        // open SQLite handle on Android generally keeps working against a
        // deleted-but-still-open file), and the "wiped" identity/session
        // material remains usable for the rest of this process's life —
        // silently defeating the entire purpose of a panic-wipe button.
        // `Intent.makeRestartActivityTask` + killing this process is the
        // standard Android idiom for "fully restart from a clean process"
        // (the same mechanism ActivityManager itself uses after a crash).
        restartApp(context)
    }

    private fun restartApp(context: Context) {
        val packageManager = context.packageManager
        val launchIntent = packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = launchIntent?.component
        if (componentName != null) {
            val restartIntent = android.content.Intent.makeRestartActivityTask(componentName)
            context.startActivity(restartIntent)
        }
        Runtime.getRuntime().exit(0)
    }

    /** Same zero-then-delete spirit as AttachmentStore.secureDelete, applied to every file in the attachments dir, then the dir itself. */
    private fun wipeAttachments(context: Context) {
        val dir = java.io.File(context.filesDir, "attachments")
        if (!dir.exists()) return
        dir.listFiles()?.forEach { file ->
            if (file.isFile) {
                val size = file.length()
                if (size > 0) file.writeBytes(ByteArray(size.toInt()))
            }
            file.delete()
        }
        dir.delete()
    }
}
