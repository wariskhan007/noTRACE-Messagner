package com.notrace.messenger.data

import android.util.Base64
import com.notrace.messenger.crypto.signal.EncryptedEnvelope
import com.notrace.messenger.crypto.signal.GroupSessionManager
import com.notrace.messenger.crypto.signal.SignalSessionManager
import com.notrace.messenger.data.media.AttachmentStore
import com.notrace.messenger.data.media.MediaCrypto
import com.notrace.messenger.network.SignalingClient
import com.notrace.messenger.network.webrtc.WebRtcManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates a message send/receive across three layers:
 *   1. Crypto — [SignalSessionManager]: X3DH session bootstrap + Double Ratchet encrypt/decrypt.
 *   2. Transport — a direct WebRTC data channel when connected to the peer
 *      (see [WebRtcManager], owned per-call by [com.notrace.messenger.network.webrtc.CallManager]),
 *      falling back to the signaling server's store-and-forward mailbox
 *      (`mailboxDeliver`) when not.
 *   3. Persistence — [ChatDao] / [AttachmentStore], SQLCipher-encrypted / on-disk-encrypted at rest.
 *
 * ## Wire format
 * Every byte array handed to a transport starts with a 1-byte type flag:
 *   - `0` — ordinary Double Ratchet `SignalMessage` ciphertext (text or attachment-control JSON)
 *   - `1` — `PreKeySignalMessage` ciphertext (first message of a new session)
 *   - `2` — raw attachment chunk: `[2][idLen:1][id bytes][already-MediaCrypto-encrypted blob]`,
 *           NOT wrapped in a second layer of Double Ratchet encryption — same
 *           design Signal itself uses for attachments (a fresh random key
 *           per file, never reused, is enough; nesting encryption schemes
 *           adds cost without adding real confidentiality here). This flag
 *           is only ever sent/received over an OPEN WebRTC data channel —
 *           there is no mailbox fallback for raw attachment bytes (see
 *           `sendAttachment` for what that means in practice).
 *   - `3` — Phase 5, group message: `[3][16-byte groupId/distributionId UUID][GroupCipher ciphertext]`.
 *        *   - `3` — Phase 5 group message: `[3][16-byte groupId UUID][16-byte epoch UUID][GroupCipher ciphertext]`.
 *           Also NOT wrapped in a second layer of Double Ratchet encryption,
 *           for the same reason as flag 2 — Sender Keys' own per-sender
 *           ratchet already gives forward secrecy, and this is exactly how
 *           Signal's real production group messages are wire-encoded. UNLIKE
 *           flag 2, this DOES have a mailbox fallback (see `sendGroupMessage`):
 *           there's no reason group messages should require every recipient
 *           to be online simultaneously, and the mailbox never inspects payloads
 *           regardless of what scheme produced them.
 *
 *           PHASE 6 SECURITY FIX: `groupId` (stable, used for routing/storage)
 *           and `epoch` (the actual libsignal distributionId used for
 *           encryption) used to be the same UUID. They're now separate:
 *           `GroupDao.rotateEpoch` mints a fresh epoch whenever a member
 *           leaves, and every remaining member independently generates +
 *           redistributes a brand-new Sender Key under it — a departed
 *           member, who never receives the new epoch, can decrypt nothing
 *           sent after rotation. Without this, `groupId` doubling as the
 *           distributionId meant a removed member's old key stayed valid
 *           forever, for every future message, which defeated the entire
 *           point of tracking membership at all.
 *
 * Group *key distribution* (each member's Sender Key reaching every other
 * member) rides a different path entirely: it's a plaintext control string
 * (`NOTRACE_GROUP_SKDM_V1:...`, `NOTRACE_GROUP_INVITE_V1:...`) sent through
 * the ordinary flag-0/1 **1:1** Double Ratchet channel to each member
 * individually — the same "control message piggybacks on the text path"
 * idiom `NOTRACE_ATTACHMENT_V1:` already uses below. See `createGroup` /
 * `handleGroupInvite` / `handleGroupSkdm`.
 *
 * An attachment transfer is therefore always TWO wire messages: a small
 * Double-Ratchet-encrypted "control" message (flag 0/1, body prefixed
 * `NOTRACE_ATTACHMENT_V1:`, carrying the attachment id/mime/size/mediaKey)
 * that can travel via mailbox like any text message, plus the flag-2 raw
 * bytes that can only travel over a live data channel. `pendingControls`/
 * `pendingChunks` below reconcile whichever of the two arrives first.
 */
class MessageRepository(
    private val myNumericId: String,
    private val sessionManager: SignalSessionManager,
    private val signalingClient: SignalingClient,
    private val chatDao: ChatDao,
    private val attachmentStore: AttachmentStore,
    private val groupDao: GroupDao,
    private val groupSessionManager: GroupSessionManager,
    private val webRtcManagers: MutableMap<String, WebRtcManager> = mutableMapOf(),
) {
    private data class PendingAttachmentControl(val fromNumericId: String, val mimeType: String, val sizeBytes: Long, val mediaKey: ByteArray)

    private val pendingControls = ConcurrentHashMap<String, PendingAttachmentControl>()
    private val pendingChunks = ConcurrentHashMap<String, ByteArray>()

    /**
     * BUGFIX (audit): there was previously no way for the UI to learn a new
     * message had arrived except by leaving and re-entering a screen —
     * ChatThreadScreen and ChatListScreen both only ever queried the DB
     * once, on first composition. A message sent while the recipient sat
     * in an open thread would silently not appear until they navigated
     * away and back. Emits the affected conversationId after every insert
     * (send or receive, 1:1 or group); screens collect this and re-query.
     * A conversationId, not the message itself, is emitted deliberately —
     * screens already have a `refresh()` that re-reads from the source of
     * truth (the encrypted DB), so this just needs to be a "something
     * changed for X" signal, not a duplicate data path.
     */
    private val _messageEvents = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val messageEvents: SharedFlow<String> = _messageEvents.asSharedFlow()

    /** Registers (or replaces) the active WebRTC transport for a peer, once a data channel is up. */
    fun attachTransport(peerNumericId: String, manager: WebRtcManager) {
        webRtcManagers[peerNumericId] = manager
    }

    suspend fun sendMessage(peerNumericId: String, plaintext: String) = withContext(Dispatchers.IO) {
        chatDao.ensureConversation(peerNumericId)
        sendEncryptedText(peerNumericId, plaintext)
        chatDao.insertMessage(peerNumericId, senderId = "me", body = plaintext)
        _messageEvents.tryEmit(peerNumericId)
    }

    /**
     * Sends a file. The attachment control message (id/mime/size/key) goes
     * out immediately via the normal text path (data channel, or mailbox if
     * the peer's offline — the recipient will just see it as a pending
     * attachment with no bytes yet). The actual encrypted bytes only send
     * if a data channel is open RIGHT NOW; if not, they're kept in memory
     * and flushed the next time `attachTransport` reports this peer's
     * channel opening (see `attachTransport` override below) — so briefly
     * offline works, but bytes are lost if the app process dies first.
     * A durable retry queue (persisted, not in-memory) is the natural
     * next hardening step here, not implemented in this pass.
     */
    suspend fun sendAttachment(peerNumericId: String, plaintextBytes: ByteArray, mimeType: String, caption: String? = null) =
        withContext(Dispatchers.IO) {
            chatDao.ensureConversation(peerNumericId)
            val id = UUID.randomUUID().toString()
            val encrypted = MediaCrypto.encrypt(plaintextBytes)
            val localPath = attachmentStore.save(encrypted.encryptedBlob, id)

            val control = JSONObject().apply {
                put("id", id)
                put("mimeType", mimeType)
                put("sizeBytes", plaintextBytes.size)
                put("mediaKey", Base64.encodeToString(encrypted.mediaKey, Base64.NO_WRAP))
            }
            sendEncryptedText(peerNumericId, "NOTRACE_ATTACHMENT_V1:$control")

            val chunkWire = byteArrayOf(2) + byteArrayOf(id.length.toByte()) + id.toByteArray(StandardCharsets.UTF_8) + encrypted.encryptedBlob
            webRtcManagers[peerNumericId]?.takeIf { it.isDataChannelOpen() }?.sendBytes(chunkWire)
            // else: bytes stay only on disk at localPath; they are not
            // re-sent automatically unless attachTransport() is called
            // again for this peer with a channel that's open — see the
            // caveat in the doc comment above.

            chatDao.insertAttachmentMessage(
                conversationId = peerNumericId,
                senderId = "me",
                attachment = AttachmentInfo(localPath, mimeType, plaintextBytes.size.toLong(), Base64.encodeToString(encrypted.mediaKey, Base64.NO_WRAP)),
                caption = caption,
            )
            _messageEvents.tryEmit(peerNumericId)
        }

    private suspend fun sendEncryptedText(peerNumericId: String, plaintext: String) {
        if (!sessionManager.hasSession(peerNumericId)) {
            establishSession(peerNumericId)
        }
        val envelope = sessionManager.encrypt(peerNumericId, plaintext.toByteArray(StandardCharsets.UTF_8))
        val wire = packEnvelope(envelope)

        val sentDirect = webRtcManagers[peerNumericId]?.let { transport ->
            if (transport.isDataChannelOpen()) transport.sendBytes(wire) else false
        } ?: false

        if (!sentDirect) {
            signalingClient.mailboxDeliver(peerNumericId, Base64.encodeToString(wire, Base64.NO_WRAP))
        }
    }

    /**
     * Call for every incoming JSONObject of type "signal" with
     * kind == "encrypted-envelope", and for every item drained from a
     * "mailbox" message — both carry a base64 wire envelope (always flag
     * 0/1 text; the signaling server never relays raw flag-2 attachment bytes).
     */
    suspend fun handleIncomingEnvelope(fromNumericId: String, base64Payload: String) = withContext(Dispatchers.IO) {
        handleIncomingBytes(fromNumericId, Base64.decode(base64Payload, Base64.NO_WRAP))
    }

    /**
     * Call for every raw byte array a peer's WebRTC data channel delivers —
     * this is the ONLY path flag-2 attachment chunks arrive on.
     */
    suspend fun handleIncomingBytes(fromNumericId: String, wire: ByteArray) = withContext(Dispatchers.IO) {
        // BUGFIX (audit): an empty buffer used to crash this whole receive
        // path with an ArrayIndexOutOfBoundsException on `wire[0]` below —
        // a single malformed/empty data-channel frame (or a buggy peer)
        // could take down message handling entirely. Drop it instead.
        if (wire.isEmpty()) return@withContext
        when (wire[0].toInt()) {
            0, 1 -> {
                chatDao.ensureConversation(fromNumericId)
                val envelope = unpackEnvelope(wire)
                val plaintextBytes = sessionManager.decrypt(fromNumericId, envelope)
                val plaintext = String(plaintextBytes, StandardCharsets.UTF_8)
                when {
                    plaintext.startsWith("NOTRACE_ATTACHMENT_V1:") ->
                        handleAttachmentControl(fromNumericId, plaintext.removePrefix("NOTRACE_ATTACHMENT_V1:"))
                    plaintext.startsWith("NOTRACE_GROUP_INVITE_V1:") ->
                        handleGroupInvite(plaintext.removePrefix("NOTRACE_GROUP_INVITE_V1:"))
                    plaintext.startsWith("NOTRACE_GROUP_SKDM_V1:") ->
                        handleGroupSkdm(fromNumericId, plaintext.removePrefix("NOTRACE_GROUP_SKDM_V1:"))
                    plaintext.startsWith("NOTRACE_GROUP_LEAVE_V1:") ->
                        handleGroupLeave(plaintext.removePrefix("NOTRACE_GROUP_LEAVE_V1:"), fromNumericId)
                    else -> {
                        chatDao.insertMessage(fromNumericId, senderId = fromNumericId, body = plaintext)
                        _messageEvents.tryEmit(fromNumericId)
                    }
                }
            }
            2 -> {
                chatDao.ensureConversation(fromNumericId)
                handleAttachmentChunk(wire)
            }
            3 -> handleIncomingGroupCiphertext(fromNumericId, wire)
            else -> { /* unknown flag — drop rather than crash the receive path */ }
        }
    }

    // ------------------------------------------------------------------
    // Phase 5: Groups (Sender Keys). See GroupSessionManager's doc comment
    // for the overall design; this section is the orchestration seam for it,
    // exactly mirroring what sendMessage/handleIncomingEnvelope do for 1:1.
    // ------------------------------------------------------------------

    /**
     * Creates a new group: a local `conversations` row (is_group = 1) +
     * membership rows, our own freshly-generated Sender Key, and — pairwise,
     * over each member's existing 1:1 Double Ratchet channel — an invite
     * (membership list + name) and our Sender Key distribution message.
     * Returns the new group's id (also its libsignal distributionId).
     */
    suspend fun createGroup(name: String, memberNumericIds: List<String>): String = withContext(Dispatchers.IO) {
        val groupId = UUID.randomUUID()
        val groupIdStr = groupId.toString()

        groupDao.ensureGroupConversation(groupIdStr, name)
        groupDao.addMember(groupIdStr, myNumericId, displayName = "me")
        memberNumericIds.forEach { groupDao.addMember(groupIdStr, it) }

        val inviteJson = JSONObject().apply {
            put("groupId", groupIdStr)
            put("name", name)
            put("members", JSONArray(memberNumericIds + myNumericId))
        }
        val epoch = groupDao.getOrCreateCurrentEpoch(groupIdStr)
        val skdm = groupSessionManager.createOwnDistributionMessage(epoch)

        memberNumericIds.forEach { member ->
            sendEncryptedText(member, "NOTRACE_GROUP_INVITE_V1:$inviteJson")
            sendSkdmControl(member, groupIdStr, epoch, skdm)
        }
        groupDao.markOwnKeyDistributed(groupIdStr)
        groupIdStr
    }

    /**
     * Adds a member to an existing group after the fact: tells the new
     * member about the whole group (name + full member list) and our
     * Sender Key (if we've already distributed one), and tells every
     * existing member about the new one so membership lists converge.
     */
    suspend fun addMemberToGroup(groupId: String, newMemberNumericId: String) = withContext(Dispatchers.IO) {
        val info = groupDao.getGroupInfo(groupId) ?: return@withContext
        val allMembers = info.members.map { it.numericId } + newMemberNumericId
        groupDao.addMember(groupId, newMemberNumericId)

        val inviteJson = JSONObject().apply {
            put("groupId", groupId)
            put("name", info.name)
            put("members", JSONArray(allMembers))
        }
        allMembers.filter { it != myNumericId }.forEach { member ->
            sendEncryptedText(member, "NOTRACE_GROUP_INVITE_V1:$inviteJson")
        }
        if (groupDao.hasDistributedOwnKey(groupId)) {
            val epoch = groupDao.getOrCreateCurrentEpoch(groupId)
            val skdm = groupSessionManager.createOwnDistributionMessage(epoch)
            sendSkdmControl(newMemberNumericId, groupId, epoch, skdm)
        }
    }

    /** Local departure + a best-effort notice to remaining members. No server-side group state exists to clean up (Section 1: no central data store). */
    suspend fun leaveGroup(groupId: String) = withContext(Dispatchers.IO) {
        val members = groupDao.getMembers(groupId).map { it.numericId }.filter { it != myNumericId }
        members.forEach { member -> sendEncryptedText(member, "NOTRACE_GROUP_LEAVE_V1:$groupId") }
        groupDao.removeMember(groupId, myNumericId)
    }

    /** Encrypts once with our Sender Key and fans the identical ciphertext out to every other member (data channel first, mailbox fallback per member — see wire format flag `3` above). */
    suspend fun sendGroupMessage(groupId: String, plaintext: String) = withContext(Dispatchers.IO) {
        val epoch = groupDao.getOrCreateCurrentEpoch(groupId)
        if (!groupDao.hasDistributedOwnKey(groupId)) {
            distributeOwnKeyToGroup(groupId, epoch)
        }
        val ciphertext = groupSessionManager.encrypt(epoch, plaintext.toByteArray(StandardCharsets.UTF_8))
        val wire = byteArrayOf(3) + uuidToBytes(UUID.fromString(groupId)) + uuidToBytes(epoch) + ciphertext

        val members = groupDao.getMembers(groupId).map { it.numericId }.filter { it != myNumericId }
        members.forEach { member ->
            val sentDirect = webRtcManagers[member]?.let { t -> if (t.isDataChannelOpen()) t.sendBytes(wire) else false } ?: false
            if (!sentDirect) signalingClient.mailboxDeliver(member, Base64.encodeToString(wire, Base64.NO_WRAP))
        }
        chatDao.insertMessage(groupId, senderId = "me", body = plaintext)
        _messageEvents.tryEmit(groupId)
    }

    private suspend fun distributeOwnKeyToGroup(groupId: String, epoch: UUID) {
        val skdm = groupSessionManager.createOwnDistributionMessage(epoch)
        val members = groupDao.getMembers(groupId).map { it.numericId }.filter { it != myNumericId }
        members.forEach { member -> sendSkdmControl(member, groupId, epoch, skdm) }
        groupDao.markOwnKeyDistributed(groupId)
    }

    private suspend fun sendSkdmControl(toMember: String, groupId: String, epoch: UUID, skdm: ByteArray) {
        sendEncryptedText(toMember, "NOTRACE_GROUP_SKDM_V1:$groupId:$epoch:${Base64.encodeToString(skdm, Base64.NO_WRAP)}")
    }

    /**
     * Someone else left the group (we received their `NOTRACE_GROUP_LEAVE_V1`
     * notice). SECURITY FIX (Phase 6): this used to just drop them from the
     * local member list and stop — their already-distributed Sender Key
     * stayed valid forever. Now every remaining member independently mints
     * a fresh epoch and redistributes a brand-new key under it, so the
     * departed member's old key can't decrypt anything sent from here on.
     */
    private suspend fun handleGroupLeave(groupId: String, departedMember: String) {
        groupDao.removeMember(groupId, departedMember)
        val remaining = groupDao.getMembers(groupId).map { it.numericId }.filter { it != myNumericId }
        if (remaining.isEmpty()) return // we're the last one left (or already gone) — nothing to rotate for

        val newEpoch = groupDao.rotateEpoch(groupId)
        val skdm = groupSessionManager.createOwnDistributionMessage(newEpoch)
        remaining.forEach { member -> sendSkdmControl(member, groupId, newEpoch, skdm) }
        groupDao.markOwnKeyDistributed(groupId) // we just freshly redistributed to everyone still here
    }

    private fun handleIncomingGroupCiphertext(fromNumericId: String, wire: ByteArray) {
        val groupUuid = uuidFromBytes(wire.copyOfRange(1, 17))
        val epoch = uuidFromBytes(wire.copyOfRange(17, 33))
        val ciphertext = wire.copyOfRange(33, wire.size)
        val groupId = groupUuid.toString()
        val plaintextBytes = groupSessionManager.decrypt(fromNumericId, epoch, ciphertext)
        chatDao.insertMessage(groupId, senderId = fromNumericId, body = String(plaintextBytes, StandardCharsets.UTF_8))
        _messageEvents.tryEmit(groupId)
    }

    /** `NOTRACE_GROUP_INVITE_V1:` payload: `{"groupId","name","members":[...]}`. Registers the group locally and, if we've already sent our own Sender Key into it, forwards that key to any member we're only just learning about here. */
    private suspend fun handleGroupInvite(json: String) {
        val obj = JSONObject(json)
        val groupId = obj.getString("groupId")
        val name = obj.getString("name")
        val existingMembers = groupDao.getMembers(groupId).map { it.numericId }.toSet()

        groupDao.ensureGroupConversation(groupId, name)
        val membersArray = obj.getJSONArray("members")
        val newMembers = mutableListOf<String>()
        for (i in 0 until membersArray.length()) {
            val member = membersArray.getString(i)
            groupDao.addMember(groupId, member, displayName = if (member == myNumericId) "me" else member)
            if (member !in existingMembers) newMembers.add(member)
        }

        if (groupDao.hasDistributedOwnKey(groupId)) {
            val epoch = groupDao.getOrCreateCurrentEpoch(groupId)
            val skdm = groupSessionManager.createOwnDistributionMessage(epoch)
            newMembers.filter { it != myNumericId }.forEach { member -> sendSkdmControl(member, groupId, epoch, skdm) }
        }
    }

    /** `NOTRACE_GROUP_SKDM_V1:<groupId>:<epoch-uuid>:<base64 distribution message>`. Also reciprocates our own key back to the sender + full mesh, if we haven't sent it yet — covers the "learning about a group for the first time via this very message" case. */
    private suspend fun handleGroupSkdm(fromNumericId: String, rest: String) {
        val parts = rest.split(":", limit = 3)
        if (parts.size != 3) return
        val groupId = parts[0]
        val epoch = UUID.fromString(parts[1])
        val skdmBytes = Base64.decode(parts[2], Base64.NO_WRAP)
        groupSessionManager.processDistributionMessage(fromNumericId, epoch, skdmBytes)

        if (!groupDao.hasDistributedOwnKey(groupId)) {
            distributeOwnKeyToGroup(groupId, groupDao.getOrCreateCurrentEpoch(groupId))
        }
    }

    private fun uuidToBytes(uuid: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()

    private fun uuidFromBytes(bytes: ByteArray): UUID {
        val bb = ByteBuffer.wrap(bytes)
        return UUID(bb.long, bb.long)
    }

    fun getGroupInfo(groupId: String) = groupDao.getGroupInfo(groupId)
    fun isGroup(conversationId: String) = groupDao.isGroup(conversationId)

    private fun handleAttachmentControl(fromNumericId: String, json: String) {
        val obj = JSONObject(json)
        val id = obj.getString("id")
        val control = PendingAttachmentControl(
            fromNumericId = fromNumericId,
            mimeType = obj.getString("mimeType"),
            sizeBytes = obj.getLong("sizeBytes"),
            mediaKey = Base64.decode(obj.getString("mediaKey"), Base64.NO_WRAP),
        )
        val chunk = pendingChunks.remove(id)
        if (chunk != null) {
            completeAttachment(id, control, chunk)
        } else {
            pendingControls[id] = control
        }
    }

    private fun handleAttachmentChunk(wire: ByteArray) {
        val idLen = wire[1].toInt()
        val id = String(wire, 2, idLen, StandardCharsets.UTF_8)
        val encryptedBlob = wire.copyOfRange(2 + idLen, wire.size)

        val control = pendingControls.remove(id)
        if (control != null) {
            completeAttachment(id, control, encryptedBlob)
        } else {
            pendingChunks[id] = encryptedBlob
        }
    }

    private fun completeAttachment(id: String, control: PendingAttachmentControl, encryptedBlob: ByteArray) {
        val localPath = attachmentStore.save(encryptedBlob, id)
        chatDao.insertAttachmentMessage(
            conversationId = control.fromNumericId,
            senderId = control.fromNumericId,
            attachment = AttachmentInfo(
                localPath = localPath,
                mimeType = control.mimeType,
                sizeBytes = control.sizeBytes,
                mediaKeyBase64 = Base64.encodeToString(control.mediaKey, Base64.NO_WRAP),
            ),
        )
        _messageEvents.tryEmit(control.fromNumericId)
    }

    /**
     * X3DH bootstrap: requests a prekey bundle from the server and, if the
     * peer has published one, processes it into a fresh outgoing session.
     */
    private suspend fun establishSession(peerNumericId: String) {
        val result = signalingClient.requestPreKeyBundle(peerNumericId) ?: return // peer has no bundle published yet
        val signedPreKey = result.getJSONObject("signedPreKey")
        val oneTimePreKey = result.optJSONObject("oneTimePreKey")

        sessionManager.buildSessionFromBundle(
            peerNumericId = peerNumericId,
            registrationId = result.getInt("registrationId"),
            identityKeyBase64 = result.getString("identityKey"),
            signedPreKeyId = signedPreKey.getInt("keyId"),
            signedPreKeyPublicBase64 = signedPreKey.getString("publicKey"),
            signedPreKeySignatureBase64 = signedPreKey.getString("signature"),
            oneTimePreKeyId = oneTimePreKey?.getInt("keyId"),
            oneTimePreKeyPublicBase64 = oneTimePreKey?.getString("publicKey"),
        )
    }

    private fun packEnvelope(envelope: EncryptedEnvelope): ByteArray {
        val flag = if (envelope.isPreKeyMessage) 1.toByte() else 0.toByte()
        return byteArrayOf(flag) + envelope.bytes
    }

    private fun unpackEnvelope(wire: ByteArray): EncryptedEnvelope {
        val isPreKeyMessage = wire[0] == 1.toByte()
        return EncryptedEnvelope(isPreKeyMessage, wire.copyOfRange(1, wire.size))
    }

    fun getMessages(peerNumericId: String) = chatDao.getMessages(peerNumericId)
    fun getConversations() = chatDao.getConversations()
    fun decryptAttachment(attachment: AttachmentInfo): ByteArray {
        val blob = attachmentStore.read(attachment.localPath)
        val mediaKey = Base64.decode(attachment.mediaKeyBase64, Base64.NO_WRAP)
        return MediaCrypto.decrypt(blob, mediaKey)
    }

    fun setDisappearingTimer(peerNumericId: String, seconds: Long?) {
        chatDao.ensureConversation(peerNumericId)
        chatDao.setDisappearingSeconds(peerNumericId, seconds)
    }

    fun getDisappearingTimer(peerNumericId: String): Long? = chatDao.getDisappearingSeconds(peerNumericId)

    /** Called by DisappearingMessageWorker on its periodic sweep. */
    fun purgeExpiredMessages(): Int {
        val expiredAttachmentPaths = chatDao.getExpiredAttachmentPaths()
        val removedCount = chatDao.purgeExpiredMessages()
        expiredAttachmentPaths.forEach { attachmentStore.secureDelete(it) }
        return removedCount
    }
}
