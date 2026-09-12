package com.notrace.messenger.data.media

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Stores attachment blobs that are ALREADY encrypted by [MediaCrypto] —
 * this class never sees or writes plaintext. Lives under the app's
 * private `filesDir`, which on a non-rooted device is inaccessible to
 * other apps regardless; encryption at rest here is defense in depth
 * (and what makes "secure wipe" / disappearing-attachment deletion mean
 * something even if the device is later compromised or backed up).
 */
class AttachmentStore(context: Context) {

    private val dir = File(context.filesDir, "attachments").apply { mkdirs() }

    fun save(encryptedBlob: ByteArray, id: String = UUID.randomUUID().toString()): String {
        val file = File(dir, "$id.enc")
        file.writeBytes(encryptedBlob)
        return file.absolutePath
    }

    fun read(localPath: String): ByteArray = File(localPath).readBytes()

    /** Overwrites the file with zeros before deleting — same spirit as the DB's secure_delete pragma. */
    fun secureDelete(localPath: String) {
        val file = File(localPath)
        if (!file.exists()) return
        val size = file.length()
        file.writeBytes(ByteArray(size.toInt()))
        file.delete()
    }
}
