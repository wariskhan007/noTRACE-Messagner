package com.notrace.messenger.crypto.signal

import com.notrace.messenger.data.db.NoTraceDatabase
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.util.KeyHelper
import android.content.ContentValues
import android.database.Cursor

/**
 * Persists the device's own long-term identity keypair + per-device
 * registration id, and a trust cache of every peer identity key this
 * device has ever seen (used to detect safety-number / key changes).
 *
 * NOTE ON EXACT API SURFACE: libsignal-client's Java API has been very
 * stable, but always confirm method signatures (in particular
 * `getIdentity(SignalProtocolAddress)` vs. device-scoped variants, and
 * whether your pinned version expects `saveIdentity` to return a
 * `boolean` "changed?" flag) against the javadoc for the exact version
 * pinned in build.gradle.kts before shipping.
 */
class SqlIdentityKeyStore(private val db: NoTraceDatabase) : IdentityKeyStore {

    private val identityKeyPair: IdentityKeyPair by lazy { loadOrCreateIdentity().first }
    private val registrationId: Int by lazy { loadOrCreateIdentity().second }

    private fun loadOrCreateIdentity(): Pair<IdentityKeyPair, Int> {
        val readable = db.openReadable()
        readable.rawQuery(
            "SELECT registration_id, identity_key_pair FROM signal_identity WHERE _singleton = 0",
            null,
        ).use { cursor: Cursor ->
            if (cursor.moveToFirst()) {
                val regId = cursor.getInt(0)
                val bytes = cursor.getBlob(1)
                return IdentityKeyPair(bytes) to regId
            }
        }

        // First launch: generate once, persist, never regenerate.
        val newPair = IdentityKeyPair.generate()
        val newRegId = KeyHelper.generateRegistrationId(false)
        val writable = db.openWritable()
        val values = ContentValues().apply {
            put("_singleton", 0)
            put("registration_id", newRegId)
            put("identity_key_pair", newPair.serialize())
        }
        writable.insert("signal_identity", null, values)
        return newPair to newRegId
    }

    override fun getIdentityKeyPair(): IdentityKeyPair = identityKeyPair

    override fun getLocalRegistrationId(): Int = registrationId

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): Boolean {
        val addressKey = addressKeyFor(address)
        val existing = getIdentity(address)
        val changed = existing != null && existing != identityKey

        val values = ContentValues().apply {
            put("address", addressKey)
            put("identity_key", identityKey.serialize())
        }
        db.openWritable().insertWithOnConflict(
            "signal_trusted_identities",
            null,
            values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
        return changed
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        val existing = getIdentity(address) ?: return true // trust-on-first-use, matches Signal's default UX
        // A real UI should surface a "safety number changed" warning here
        // (Phase 6 hardening) rather than silently trusting a new key.
        return existing == identityKey
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        db.openReadable().rawQuery(
            "SELECT identity_key FROM signal_trusted_identities WHERE address = ?",
            arrayOf(addressKeyFor(address)),
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return IdentityKey(cursor.getBlob(0), 0)
            }
        }
        return null
    }

    private fun addressKeyFor(address: SignalProtocolAddress) = "${address.name}:${address.deviceId}"
}
