package com.notrace.messenger.data.db

import android.content.Context
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteOpenHelper

/**
 * Every table here is encrypted at rest by SQLCipher using a key derived
 * from the device Keystore (see [DatabaseKeyProvider]) — never a hardcoded
 * or user-typed value alone. Two categories of data live here:
 *
 *  1. Signal Protocol state (identities, sessions, prekeys, signed_prekeys):
 *     required for libsignal's stores to persist across app restarts.
 *     This is key material, not message content, but it's exactly as
 *     sensitive — treat schema changes here with the same care as crypto code.
 *  2. Conversations/messages: actual chat history. Plaintext is only ever
 *     held here after on-device decryption; it never arrives from the
 *     network in this form.
 */
class NoTraceDatabase(context: Context, passphrase: ByteArray) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "notrace.db"
        const val DB_VERSION = 4
    }

    // SQLCipher requires its native libs loaded once per process —
    // call SQLiteDatabase.loadLibs(context) in NoTraceApplication.onCreate().
    private val key = passphrase

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // Disappearing messages and account wipe are meaningless if deleted
        // rows just sit in freed SQLite pages until overwritten by chance.
        // secure_delete forces SQLite to zero the freed space immediately.
        db.execSQL("PRAGMA secure_delete = ON")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE conversations (
                numeric_id TEXT PRIMARY KEY NOT NULL,
                display_name TEXT,
                is_group INTEGER NOT NULL DEFAULT 0,
                disappearing_seconds INTEGER, -- NULL = off; else 86400 / 604800 / 2592000 / 31536000
                own_sender_key_distributed INTEGER NOT NULL DEFAULT 0, -- Phase 5, groups only: have we sent OUR Sender Key to every member yet?
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        // --- Phase 5: group membership. A group is just an is_group=1 row in
        // `conversations` above (id = the libsignal Sender Keys distributionId,
        // see GroupSessionManager) plus its member list here.
        db.execSQL(
            """
            CREATE TABLE group_members (
                group_id TEXT NOT NULL,
                member_numeric_id TEXT NOT NULL,
                display_name TEXT,
                joined_at INTEGER NOT NULL,
                PRIMARY KEY (group_id, member_numeric_id)
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id TEXT NOT NULL,
                sender_id TEXT NOT NULL, -- numeric_id of sender ('me' for own messages)
                body TEXT, -- decrypted plaintext; NULL for attachment-only messages
                attachment_local_path TEXT, -- path under filesDir/attachments/ to the ENCRYPTED blob, never plaintext
                attachment_mime_type TEXT,
                attachment_size_bytes INTEGER,
                attachment_media_key TEXT, -- base64 of the 64-byte (AES key || HMAC key) MediaCrypto used, see data/media/
                sent_at INTEGER NOT NULL,
                delivered_at INTEGER,
                read_at INTEGER,
                expires_at INTEGER, -- computed from conversation's disappearing_seconds at receipt time
                FOREIGN KEY (conversation_id) REFERENCES conversations(numeric_id)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_messages_conversation ON messages(conversation_id, sent_at)")
        db.execSQL("CREATE INDEX idx_messages_expiry ON messages(expires_at)") // Phase 3 sweep will scan this

        // --- libsignal SignalProtocolStore-backing tables ---
        db.execSQL(
            """
            CREATE TABLE signal_identity (
                _singleton INTEGER PRIMARY KEY CHECK (_singleton = 0),
                registration_id INTEGER NOT NULL,
                identity_key_pair BLOB NOT NULL -- serialized IdentityKeyPair, itself only ever stored here (encrypted DB)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE signal_trusted_identities (
                address TEXT PRIMARY KEY NOT NULL, -- "<numericId>:<deviceId>"
                identity_key BLOB NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE signal_prekeys (
                key_id INTEGER PRIMARY KEY NOT NULL,
                record BLOB NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE signal_signed_prekeys (
                key_id INTEGER PRIMARY KEY NOT NULL,
                record BLOB NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE signal_sessions (
                address TEXT PRIMARY KEY NOT NULL, -- "<numericId>:<deviceId>"
                record BLOB NOT NULL
            )
            """.trimIndent(),
        )

        // --- Phase 5: libsignal SenderKeyStore-backing table (Sender Keys
        // group encryption — see crypto/signal/SqlSenderKeyStore.kt) ---
        db.execSQL(
            """
            CREATE TABLE signal_sender_keys (
                address TEXT PRIMARY KEY NOT NULL, -- "<senderNumericId>:<deviceId>:<distributionId-uuid>"
                record BLOB NOT NULL
            )
            """.trimIndent(),
        )

        // --- Phase 6 SECURITY FIX: Sender Key rotation on membership change ---
        // The libsignal distributionId used for a group's crypto is now
        // DECOUPLED from the group's stable conversation id (`group_id`
        // above). Rotating (see GroupDao.rotateEpoch) just mints a new
        // random UUID here and every remaining member independently
        // generates + redistributes a fresh Sender Key under it — a
        // departed member, who never receives the new epoch, can decrypt
        // nothing sent after rotation. Every group message on the wire
        // now carries this epoch id explicitly (see MessageRepository's
        // flag-3 wire format) so the groupId itself can stay stable for
        // routing/storage while the crypto identity underneath it rotates.
        db.execSQL(
            """
            CREATE TABLE group_key_epochs (
                group_id TEXT PRIMARY KEY NOT NULL,
                epoch_id TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN attachment_local_path TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN attachment_mime_type TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN attachment_size_bytes INTEGER")
            db.execSQL("ALTER TABLE messages ADD COLUMN attachment_media_key TEXT")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN own_sender_key_distributed INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                """
                CREATE TABLE group_members (
                    group_id TEXT NOT NULL,
                    member_numeric_id TEXT NOT NULL,
                    display_name TEXT,
                    joined_at INTEGER NOT NULL,
                    PRIMARY KEY (group_id, member_numeric_id)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE signal_sender_keys (
                    address TEXT PRIMARY KEY NOT NULL,
                    record BLOB NOT NULL
                )
                """.trimIndent(),
            )
        }
        if (oldVersion < 4) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS group_key_epochs (
                    group_id TEXT PRIMARY KEY NOT NULL,
                    epoch_id TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    fun openWritable(): SQLiteDatabase = getWritableDatabase(passphraseString())
    fun openReadable(): SQLiteDatabase = getReadableDatabase(passphraseString())

    private fun passphraseString(): String = String(key, Charsets.ISO_8859_1)
}
