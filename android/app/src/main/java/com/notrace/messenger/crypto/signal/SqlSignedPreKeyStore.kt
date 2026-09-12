package com.notrace.messenger.crypto.signal

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
import com.notrace.messenger.data.db.NoTraceDatabase
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore

class SqlSignedPreKeyStore(private val db: NoTraceDatabase) : SignedPreKeyStore {

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        db.openReadable().rawQuery(
            "SELECT record FROM signal_signed_prekeys WHERE key_id = ?",
            arrayOf(signedPreKeyId.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) return SignedPreKeyRecord(cursor.getBlob(0))
        }
        throw InvalidKeyIdException("No such signed prekey: $signedPreKeyId")
    }

    override fun loadSignedPreKeys(): MutableList<SignedPreKeyRecord> {
        val out = mutableListOf<SignedPreKeyRecord>()
        db.openReadable().rawQuery("SELECT record FROM signal_signed_prekeys", null).use { cursor ->
            while (cursor.moveToNext()) out.add(SignedPreKeyRecord(cursor.getBlob(0)))
        }
        return out
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        val values = ContentValues().apply {
            put("key_id", signedPreKeyId)
            put("record", record.serialize())
        }
        db.openWritable().insertWithOnConflict("signal_signed_prekeys", null, values, CONFLICT_REPLACE)
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean {
        db.openReadable().rawQuery(
            "SELECT 1 FROM signal_signed_prekeys WHERE key_id = ?",
            arrayOf(signedPreKeyId.toString()),
        ).use { return it.moveToFirst() }
    }

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        db.openWritable().delete("signal_signed_prekeys", "key_id = ?", arrayOf(signedPreKeyId.toString()))
    }
}
