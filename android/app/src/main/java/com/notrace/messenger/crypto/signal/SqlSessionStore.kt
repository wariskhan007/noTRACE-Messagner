package com.notrace.messenger.crypto.signal

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
import com.notrace.messenger.data.db.NoTraceDatabase
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore

class SqlSessionStore(private val db: NoTraceDatabase) : SessionStore {

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        db.openReadable().rawQuery(
            "SELECT record FROM signal_sessions WHERE address = ?",
            arrayOf(keyFor(address)),
        ).use { cursor ->
            if (cursor.moveToFirst()) return SessionRecord(cursor.getBlob(0))
        }
        return SessionRecord() // fresh/empty record — SessionBuilder fills it in via X3DH
    }

    override fun getSubDeviceSessions(name: String): MutableList<Int> {
        val out = mutableListOf<Int>()
        db.openReadable().rawQuery(
            "SELECT address FROM signal_sessions WHERE address LIKE ?",
            arrayOf("$name:%"),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val deviceId = cursor.getString(0).substringAfterLast(":").toIntOrNull()
                if (deviceId != null) out.add(deviceId)
            }
        }
        return out
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        val values = ContentValues().apply {
            put("address", keyFor(address))
            put("record", record.serialize())
        }
        db.openWritable().insertWithOnConflict("signal_sessions", null, values, CONFLICT_REPLACE)
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean {
        db.openReadable().rawQuery(
            "SELECT 1 FROM signal_sessions WHERE address = ?",
            arrayOf(keyFor(address)),
        ).use { return it.moveToFirst() }
    }

    override fun deleteSession(address: SignalProtocolAddress) {
        db.openWritable().delete("signal_sessions", "address = ?", arrayOf(keyFor(address)))
    }

    override fun deleteAllSessions(name: String) {
        db.openWritable().delete("signal_sessions", "address LIKE ?", arrayOf("$name:%"))
    }

    private fun keyFor(address: SignalProtocolAddress) = "${address.name}:${address.deviceId}"
}
