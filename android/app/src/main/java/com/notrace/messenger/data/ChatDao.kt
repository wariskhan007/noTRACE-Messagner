package com.notrace.messenger.data

import android.content.ContentValues
import com.notrace.messenger.data.db.NoTraceDatabase

class ChatDao(private val db: NoTraceDatabase) {

    fun ensureConversation(numericId: String, displayName: String = numericId) {
        val exists = db.openReadable().rawQuery(
            "SELECT 1 FROM conversations WHERE numeric_id = ?",
            arrayOf(numericId),
        ).use { it.moveToFirst() }
        if (exists) return

        val values = ContentValues().apply {
            put("numeric_id", numericId)
            put("display_name", displayName)
            put("is_group", 0)
            put("created_at", System.currentTimeMillis())
        }
        db.openWritable().insert("conversations", null, values)
    }

    /** Null disappearingSeconds = off. One of 86400 / 604800 / 2592000 / 31536000 otherwise (Section 5 timer options). */
    fun setDisappearingSeconds(conversationId: String, disappearingSeconds: Long?) {
        val values = ContentValues().apply {
            if (disappearingSeconds == null) putNull("disappearing_seconds") else put("disappearing_seconds", disappearingSeconds)
        }
        db.openWritable().update("conversations", values, "numeric_id = ?", arrayOf(conversationId))
    }

    fun getDisappearingSeconds(conversationId: String): Long? {
        db.openReadable().rawQuery(
            "SELECT disappearing_seconds FROM conversations WHERE numeric_id = ?",
            arrayOf(conversationId),
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
        }
        return null
    }

    /**
     * Inserts a message and computes its expires_at from the conversation's
     * CURRENT disappearing-timer setting at the moment of send/receipt —
     * per the spec, changing the timer only affects messages from that
     * point forward, not retroactively.
     */
    fun insertMessage(conversationId: String, senderId: String, body: String, sentAt: Long = System.currentTimeMillis()): Long {
        val disappearingSeconds = getDisappearingSeconds(conversationId)
        val values = ContentValues().apply {
            put("conversation_id", conversationId)
            put("sender_id", senderId)
            put("body", body)
            put("sent_at", sentAt)
            if (disappearingSeconds != null) put("expires_at", sentAt + disappearingSeconds * 1000)
        }
        return db.openWritable().insert("messages", null, values)
    }

    /** Same expiry rules as insertMessage; `body` is left NULL for a pure attachment message. */
    fun insertAttachmentMessage(
        conversationId: String,
        senderId: String,
        attachment: AttachmentInfo,
        caption: String? = null,
        sentAt: Long = System.currentTimeMillis(),
    ): Long {
        val disappearingSeconds = getDisappearingSeconds(conversationId)
        val values = ContentValues().apply {
            put("conversation_id", conversationId)
            put("sender_id", senderId)
            put("body", caption)
            put("attachment_local_path", attachment.localPath)
            put("attachment_mime_type", attachment.mimeType)
            put("attachment_size_bytes", attachment.sizeBytes)
            put("attachment_media_key", attachment.mediaKeyBase64)
            put("sent_at", sentAt)
            if (disappearingSeconds != null) put("expires_at", sentAt + disappearingSeconds * 1000)
        }
        return db.openWritable().insert("messages", null, values)
    }

    fun getMessages(conversationId: String, limit: Int = 200): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        db.openReadable().rawQuery(
            """
            SELECT id, conversation_id, sender_id, body, sent_at, expires_at,
                   attachment_local_path, attachment_mime_type, attachment_size_bytes, attachment_media_key
            FROM messages WHERE conversation_id = ?
            ORDER BY sent_at ASC LIMIT ?
            """.trimIndent(),
            arrayOf(conversationId, limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val attachmentPath = cursor.getString(6)
                val attachment = if (attachmentPath != null) {
                    AttachmentInfo(
                        localPath = attachmentPath,
                        mimeType = cursor.getString(7) ?: "application/octet-stream",
                        sizeBytes = cursor.getLong(8),
                        mediaKeyBase64 = cursor.getString(9) ?: "",
                    )
                } else null

                out.add(
                    ChatMessage(
                        id = cursor.getLong(0),
                        conversationId = cursor.getString(1),
                        senderId = cursor.getString(2),
                        body = cursor.getString(3) ?: "",
                        sentAt = cursor.getLong(4),
                        expiresAt = if (cursor.isNull(5)) null else cursor.getLong(5),
                        attachment = attachment,
                    ),
                )
            }
        }
        return out
    }

    fun getConversations(): List<ConversationPreview> {
        val out = mutableListOf<ConversationPreview>()
        db.openReadable().rawQuery(
            """
            SELECT c.numeric_id, c.display_name, c.disappearing_seconds, c.is_group,
                   (SELECT COALESCE(body, '[Attachment]') FROM messages m WHERE m.conversation_id = c.numeric_id ORDER BY sent_at DESC LIMIT 1),
                   (SELECT sent_at FROM messages m WHERE m.conversation_id = c.numeric_id ORDER BY sent_at DESC LIMIT 1)
            FROM conversations c
            ORDER BY (SELECT MAX(sent_at) FROM messages m WHERE m.conversation_id = c.numeric_id) DESC
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(
                    ConversationPreview(
                        numericId = cursor.getString(0),
                        displayName = cursor.getString(1) ?: cursor.getString(0),
                        disappearingSeconds = if (cursor.isNull(2)) null else cursor.getLong(2),
                        isGroup = cursor.getInt(3) == 1,
                        lastMessage = cursor.getString(4),
                        lastMessageAt = if (cursor.isNull(5)) null else cursor.getLong(5),
                    ),
                )
            }
        }
        return out
    }

    /** Attachment file paths for messages about to be purged — call before purgeExpiredMessages so those files can be securely wiped too. */
    fun getExpiredAttachmentPaths(now: Long = System.currentTimeMillis()): List<String> {
        val out = mutableListOf<String>()
        db.openReadable().rawQuery(
            "SELECT attachment_local_path FROM messages WHERE expires_at IS NOT NULL AND expires_at <= ? AND attachment_local_path IS NOT NULL",
            arrayOf(now.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) out.add(cursor.getString(0))
        }
        return out
    }

    /** Permanently removes every message past its expiry. Returns the count removed, for logging/tests. */
    fun purgeExpiredMessages(now: Long = System.currentTimeMillis()): Int {
        return db.openWritable().delete("messages", "expires_at IS NOT NULL AND expires_at <= ?", arrayOf(now.toString()))
    }
}
