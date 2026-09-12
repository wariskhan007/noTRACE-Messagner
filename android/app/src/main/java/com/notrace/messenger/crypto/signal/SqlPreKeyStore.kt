package com.notrace.messenger.crypto.signal

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
import com.notrace.messenger.data.db.NoTraceDatabase
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore

class SqlPreKeyStore(private val db: NoTraceDatabase) : PreKeyStore {

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        db.openReadable().rawQuery(
            "SELECT record FROM signal_prekeys WHERE key_id = ?",
            arrayOf(preKeyId.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) return PreKeyRecord(cursor.getBlob(0))
        }
        throw InvalidKeyIdException("No such one-time prekey: $preKeyId")
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        val values = ContentValues().apply {
            put("key_id", preKeyId)
            put("record", record.serialize())
        }
        db.openWritable().insertWithOnConflict("signal_prekeys", null, values, CONFLICT_REPLACE)
    }

    /** How many of our own one-time prekeys we're still holding locally (i.e. haven't yet been consumed by someone starting a session with us). Used to decide whether a fresh batch needs generating — see SignalSessionManager.remainingOneTimePreKeyCount. */
    fun count(): Int {
        db.openReadable().rawQuery("SELECT COUNT(*) FROM signal_prekeys", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    override fun containsPreKey(preKeyId: Int): Boolean {
        db.openReadable().rawQuery(
            "SELECT 1 FROM signal_prekeys WHERE key_id = ?",
            arrayOf(preKeyId.toString()),
        ).use { return it.moveToFirst() }
    }

    override fun removePreKey(preKeyId: Int) {
        // Called by SessionCipher automatically once a one-time prekey has
        // been consumed to establish a session — this is the correct,
        // expected forward-secrecy behavior, not a bug to "fix".
        db.openWritable().delete("signal_prekeys", "key_id = ?", arrayOf(preKeyId.toString()))
    }
}
