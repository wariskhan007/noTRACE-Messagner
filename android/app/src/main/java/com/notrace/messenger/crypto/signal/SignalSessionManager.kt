package com.notrace.messenger.crypto.signal

import com.notrace.messenger.data.db.NoTraceDatabase
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.security.SecureRandom
import android.util.Base64

/**
 * The single entry point the rest of the app should use for anything
 * cryptographic (Section 6: X3DH + Double Ratchet). Wraps libsignal's
 * SessionBuilder (X3DH handshake) and SessionCipher (Double Ratchet
 * send/receive) over the SQLCipher-backed stores in this package.
 *
 * Every peer device is a [SignalProtocolAddress] of "<numericId>:<deviceId>".
 * Phase 2 assumes deviceId = 1 for every user (single-device); multi-device
 * linking is out of scope for this phase.
 */
class SignalSessionManager(db: NoTraceDatabase) {

    private val identityStore = SqlIdentityKeyStore(db)
    private val preKeyStore = SqlPreKeyStore(db)
    private val signedPreKeyStore = SqlSignedPreKeyStore(db)
    private val sessionStore = SqlSessionStore(db)

    val registrationId: Int get() = identityStore.localRegistrationId
    val identityPublicKeyBase64: String
        get() = Base64.encodeToString(identityStore.identityKeyPair.publicKey.serialize(), Base64.NO_WRAP)

    /**
     * Derives the numeric ID shown to other users, deterministically from
     * the public identity key. Purely a display/lookup convenience — the
     * server only enforces uniqueness of whatever ID a client presents,
     * it never assigns one.
     */
    fun deriveNumericId(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(identityStore.identityKeyPair.publicKey.serialize())
        val numeric = StringBuilder()
        for (byte in digest) {
            if (numeric.length >= 9) break
            numeric.append((byte.toInt() and 0xFF) % 10)
        }
        return numeric.toString()
    }

    /**
     * Generates a fresh signed prekey + a batch of one-time prekeys to
     * upload to the signaling server's prekey bulletin board. Call this
     * on first launch, and periodically top up (e.g. when the server
     * reports the one-time pool is running low) — never reuse key IDs.
     */
    fun generatePreKeyBatchForUpload(oneTimeCount: Int = 100): PreKeyUploadBatch {
        val identityKeyPair = identityStore.identityKeyPair

        val signedPreKeyId = nextUnusedId { signedPreKeyStore.containsSignedPreKey(it) }
        val signedPreKeyPair = Curve.generateKeyPair()
        val signedPreKeySignature = Curve.calculateSignature(
            identityKeyPair.privateKey,
            signedPreKeyPair.publicKey.serialize(),
        )
        val signedPreKey = SignedPreKeyRecord(
            signedPreKeyId,
            System.currentTimeMillis(),
            signedPreKeyPair,
            signedPreKeySignature,
        )
        signedPreKeyStore.storeSignedPreKey(signedPreKeyId, signedPreKey)

        val oneTimeKeys = ArrayList<PreKeyRecord>(oneTimeCount)
        repeat(oneTimeCount) {
            val keyId = nextUnusedId { preKeyStore.containsPreKey(it) }
            val keyPair = Curve.generateKeyPair()
            val record = PreKeyRecord(keyId, keyPair)
            preKeyStore.storePreKey(keyId, record)
            oneTimeKeys += record
        }

        return PreKeyUploadBatch(
            registrationId = registrationId,
            identityKeyBase64 = identityPublicKeyBase64,
            signedPreKeyId = signedPreKeyId,
            signedPreKeyPublicBase64 = Base64.encodeToString(signedPreKey.keyPair.publicKey.serialize(), Base64.NO_WRAP),
            signedPreKeySignatureBase64 = Base64.encodeToString(signedPreKey.signature, Base64.NO_WRAP),
            oneTimePreKeys = oneTimeKeys.map {
                OneTimePreKeyUpload(it.id, Base64.encodeToString(it.keyPair.publicKey.serialize(), Base64.NO_WRAP))
            },
        )
    }

    /**
     * X3DH: builds an outgoing session from a peer's prekey bundle (fetched
     * from the server via `fetchPreKeyBundle`). Must be called before the
     * first `encrypt()` to a peer with no existing session.
     */
    fun buildSessionFromBundle(
        peerNumericId: String,
        registrationId: Int,
        identityKeyBase64: String,
        signedPreKeyId: Int,
        signedPreKeyPublicBase64: String,
        signedPreKeySignatureBase64: String,
        oneTimePreKeyId: Int?,
        oneTimePreKeyPublicBase64: String?,
    ) {
        val address = SignalProtocolAddress(peerNumericId, 1)
        val identityKey = IdentityKey(Base64.decode(identityKeyBase64, Base64.NO_WRAP), 0)
        val signedPreKeyPublic = Curve.decodePoint(Base64.decode(signedPreKeyPublicBase64, Base64.NO_WRAP), 0)

        val bundle = PreKeyBundle(
            registrationId,
            1, // deviceId
            oneTimePreKeyId ?: -1,
            if (oneTimePreKeyId != null && oneTimePreKeyPublicBase64 != null) {
                Curve.decodePoint(Base64.decode(oneTimePreKeyPublicBase64, Base64.NO_WRAP), 0)
            } else null,
            signedPreKeyId,
            signedPreKeyPublic,
            Base64.decode(signedPreKeySignatureBase64, Base64.NO_WRAP),
            identityKey,
        )

        SessionBuilder(sessionStore, preKeyStore, signedPreKeyStore, identityStore, address)
            .process(bundle)
    }

    private fun nextUnusedId(isUsed: (Int) -> Boolean): Int {
        val random = SecureRandom()
        var candidate = random.nextInt(MAX_PREKEY_ID) + 1
        repeat(MAX_PREKEY_ID) {
            if (!isUsed(candidate)) return candidate
            candidate = if (candidate == MAX_PREKEY_ID) 1 else candidate + 1
        }
        throw IllegalStateException("No unused Signal pre-key ID available")
    }

    /**
     * BUGFIX (audit): added so callers (see SplashScreen) can skip
     * regenerating + re-uploading a full prekey batch on every single app
     * launch. Before this, `generatePreKeyBatchForUpload()` +
     * `uploadPreKeys()` ran unconditionally on every launch — the server's
     * `PreKeyStore.upload()` MERGES rather than replaces, so this grew the
     * server-side one-time-prekey pool for this account without bound,
     * forever, along with matching bloat in the local `signal_prekeys`
     * table (100 new key pairs generated and persisted every single time
     * the app was opened, most of which would never be consumed).
     */
    fun remainingOneTimePreKeyCount(): Int = preKeyStore.count()

    fun hasSession(peerNumericId: String): Boolean =
        sessionStore.containsSession(SignalProtocolAddress(peerNumericId, 1))

    /** Double Ratchet encrypt. Returns the raw ciphertext bytes + whether it's a first-message (PreKey) type. */
    fun encrypt(peerNumericId: String, plaintext: ByteArray): EncryptedEnvelope {
        val address = SignalProtocolAddress(peerNumericId, 1)
        val cipher = SessionCipher(sessionStore, preKeyStore, signedPreKeyStore, null, identityStore, address)
        val message = cipher.encrypt(plaintext)
        return EncryptedEnvelope(
            isPreKeyMessage = message.type == CiphertextMessage.PREKEY_TYPE,
            bytes = message.serialize(),
        )
    }

    /** Double Ratchet decrypt. Handles both the first PreKeySignalMessage and ordinary SignalMessages. */
    fun decrypt(peerNumericId: String, envelope: EncryptedEnvelope): ByteArray {
        val address = SignalProtocolAddress(peerNumericId, 1)
        val cipher = SessionCipher(sessionStore, preKeyStore, signedPreKeyStore, null, identityStore, address)
        return if (envelope.isPreKeyMessage) {
            cipher.decrypt(PreKeySignalMessage(envelope.bytes))
        } else {
            cipher.decrypt(SignalMessage(envelope.bytes))
        }
    }
    companion object {
        // libsignal's legacy Signal pre-key ID space is 1..16380.
        private const val MAX_PREKEY_ID = 16_380
    }
}

data class OneTimePreKeyUpload(val keyId: Int, val publicKeyBase64: String)

data class PreKeyUploadBatch(
    val registrationId: Int,
    val identityKeyBase64: String,
    val signedPreKeyId: Int,
    val signedPreKeyPublicBase64: String,
    val signedPreKeySignatureBase64: String,
    val oneTimePreKeys: List<OneTimePreKeyUpload>,
)

data class EncryptedEnvelope(val isPreKeyMessage: Boolean, val bytes: ByteArray)
