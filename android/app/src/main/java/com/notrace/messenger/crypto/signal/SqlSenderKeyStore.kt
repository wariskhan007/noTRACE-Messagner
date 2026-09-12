package com.notrace.messenger.crypto.signal

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
import com.notrace.messenger.data.db.NoTraceDatabase
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.groups.state.SenderKeyStore
import java.util.UUID

/**
 * SQLCipher-backed implementation of libsignal's SenderKeyStore — the
 * group-encryption analog of [SqlSessionStore], same storage pattern.
 * Backs Phase 5's Sender Keys group messaging (see [GroupSessionManager]).
 *
 * CAVEAT (same honest flag Phase 2 raised for the 1:1 stores): this is
 * written against libsignal-client's documented Sender Keys API shape for
 * the 0.5x series pinned in build.gradle.kts — `SenderKeyStore.storeSenderKey`
 * / `loadSenderKey` keyed by (SignalProtocolAddress, UUID distributionId) —
 * but has not been verified against a real Gradle sync. Confirm the exact
 * method signatures compile before relying on this in a real build; if the
 * pinned version's group API differs, this is the one file to adjust.
 */
class SqlSenderKeyStore(private val db: NoTraceDatabase) : SenderKeyStore {

    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
        val values = ContentValues().apply {
            put("address", keyFor(sender, distributionId))
            put("record", record.serialize())
        }
        db.openWritable().insertWithOnConflict("signal_sender_keys", null, values, CONFLICT_REPLACE)
    }

    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? {
        db.openReadable().rawQuery(
            "SELECT record FROM signal_sender_keys WHERE address = ?",
            arrayOf(keyFor(sender, distributionId)),
        ).use { cursor ->
            if (cursor.moveToFirst()) return SenderKeyRecord(cursor.getBlob(0))
        }
        return null
    }

    private fun keyFor(sender: SignalProtocolAddress, distributionId: UUID) =
        "${sender.name}:${sender.deviceId}:$distributionId"
}
