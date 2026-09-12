package com.notrace.messenger.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
import android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
import com.notrace.messenger.data.db.NoTraceDatabase
import java.util.UUID

/**
 * Group-conversation metadata (Phase 5). A group conversation is an
 * ordinary row in `conversations` with `is_group = 1` — [ChatDao] already
 * handles its name, disappearing timer, and message storage/history as-is,
 * exactly like a 1:1 conversation. This class only adds what's specific to
 * groups: the member list, and the "have I already sent my own Sender Key
 * to everyone in this group" flag `MessageRepository.sendGroupMessage`
 * checks before every send.
 */
class GroupDao(private val db: NoTraceDatabase) {

    fun ensureGroupConversation(groupId: String, name: String) {
        val exists = db.openReadable().rawQuery(
            "SELECT 1 FROM conversations WHERE numeric_id = ?",
            arrayOf(groupId),
        ).use { it.moveToFirst() }
        if (exists) return
        val values = ContentValues().apply {
            put("numeric_id", groupId)
            put("display_name", name)
            put("is_group", 1)
            put("created_at", System.currentTimeMillis())
        }
        db.openWritable().insert("conversations", null, values)
    }

    fun renameGroup(groupId: String, name: String) {
        val values = ContentValues().apply { put("display_name", name) }
        db.openWritable().update("conversations", values, "numeric_id = ?", arrayOf(groupId))
    }

    fun addMember(groupId: String, memberNumericId: String, displayName: String = memberNumericId, joinedAt: Long = System.currentTimeMillis()) {
        val values = ContentValues().apply {
            put("group_id", groupId)
            put("member_numeric_id", memberNumericId)
            put("display_name", displayName)
            put("joined_at", joinedAt)
        }
        // CONFLICT_IGNORE: re-processing an invite/re-add for a member we
        // already know about is a routine no-op, not an error.
        db.openWritable().insertWithOnConflict("group_members", null, values, CONFLICT_IGNORE)
    }

    fun removeMember(groupId: String, memberNumericId: String) {
        db.openWritable().delete("group_members", "group_id = ? AND member_numeric_id = ?", arrayOf(groupId, memberNumericId))
    }

    fun getMembers(groupId: String): List<GroupMember> {
        val out = mutableListOf<GroupMember>()
        db.openReadable().rawQuery(
            "SELECT member_numeric_id, display_name, joined_at FROM group_members WHERE group_id = ? ORDER BY joined_at ASC",
            arrayOf(groupId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(GroupMember(cursor.getString(0), cursor.getString(1) ?: cursor.getString(0), cursor.getLong(2)))
            }
        }
        return out
    }

    fun isGroup(conversationId: String): Boolean {
        db.openReadable().rawQuery(
            "SELECT is_group FROM conversations WHERE numeric_id = ?",
            arrayOf(conversationId),
        ).use { cursor -> if (cursor.moveToFirst()) return cursor.getInt(0) == 1 }
        return false
    }

    fun hasDistributedOwnKey(groupId: String): Boolean {
        db.openReadable().rawQuery(
            "SELECT own_sender_key_distributed FROM conversations WHERE numeric_id = ?",
            arrayOf(groupId),
        ).use { cursor -> if (cursor.moveToFirst()) return cursor.getInt(0) == 1 }
        return false
    }

    fun markOwnKeyDistributed(groupId: String) {
        val values = ContentValues().apply { put("own_sender_key_distributed", 1) }
        db.openWritable().update("conversations", values, "numeric_id = ?", arrayOf(groupId))
    }

    fun getGroupInfo(groupId: String): GroupInfo? {
        db.openReadable().rawQuery(
            "SELECT display_name, disappearing_seconds FROM conversations WHERE numeric_id = ? AND is_group = 1",
            arrayOf(groupId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return GroupInfo(
                groupId = groupId,
                name = cursor.getString(0) ?: groupId,
                members = getMembers(groupId),
                disappearingSeconds = if (cursor.isNull(1)) null else cursor.getLong(1),
            )
        }
    }

    // --- Phase 6 SECURITY FIX: Sender Key epoch (rotating distributionId) ---
    // Decoupled from `groupId` on purpose: the conversation/routing id stays
    // stable forever, but the crypto identity underneath it can rotate
    // (see `rotateEpoch`) whenever membership changes, without touching
    // any stored message history. See the doc comment on `group_key_epochs`
    // in NoTraceDatabase for why this table exists at all.

    fun getCurrentEpoch(groupId: String): UUID? {
        db.openReadable().rawQuery(
            "SELECT epoch_id FROM group_key_epochs WHERE group_id = ?",
            arrayOf(groupId),
        ).use { cursor -> if (cursor.moveToFirst()) return UUID.fromString(cursor.getString(0)) }
        return null
    }

    private fun setCurrentEpoch(groupId: String, epoch: UUID) {
        val values = ContentValues().apply { put("group_id", groupId); put("epoch_id", epoch.toString()) }
        db.openWritable().insertWithOnConflict("group_key_epochs", null, values, CONFLICT_REPLACE)
    }

    /** Call once per group (creation, or lazily for any pre-Phase-6 group that predates this table). */
    fun getOrCreateCurrentEpoch(groupId: String): UUID =
        getCurrentEpoch(groupId) ?: UUID.randomUUID().also { setCurrentEpoch(groupId, it) }

    /**
     * Mints a brand-new epoch and makes it current. The caller (see
     * MessageRepository's leave/removal handling) is responsible for then
     * generating a fresh Sender Key under this new epoch and distributing
     * it to every REMAINING member — a departed member never receives it,
     * so their old Sender Key becomes useless for anything sent from now on.
     */
    fun rotateEpoch(groupId: String): UUID = UUID.randomUUID().also { setCurrentEpoch(groupId, it) }
}
