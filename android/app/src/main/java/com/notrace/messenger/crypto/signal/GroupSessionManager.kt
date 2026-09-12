package com.notrace.messenger.crypto.signal

import com.notrace.messenger.data.db.NoTraceDatabase
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import java.util.UUID

/**
 * Group messaging entry point, parallel to [SignalSessionManager] but for
 * Signal's **Sender Keys** algorithm (Section 8.4 of the plan: "using
 * Signal's proven Sender Keys algorithm avoids reinventing fragile custom
 * crypto" — this is that algorithm, not a simplified stand-in for it).
 *
 * ## Design
 * Every group member independently generates ONE Sender Key per group and
 * distributes it — pairwise, over the *existing* 1:1 Double Ratchet channel
 * (see `MessageRepository`'s `NOTRACE_GROUP_SKDM_V1` control message) — to
 * every other member. From then on, that member's group messages are
 * encrypted ONCE with [GroupCipher] and the identical ciphertext bytes are
 * fanned out to every member (each still delivered over that member's own
 * 1:1 transport/mailbox — the *encryption* is one-to-many, the *transport*
 * stays pairwise; this is the same distinction Signal's own production
 * protocol draws, not a shortcut taken here).
 *
 * DOC FIX (audit): every parameter below is named/typed `epoch: UUID`, not
 * `groupId`, because it is NOT the app's stable group identifier — it's
 * libsignal's `distributionId`, and the two are deliberately DIFFERENT
 * values. `GroupDao` tracks a group's CURRENT epoch (its live
 * distributionId) separately from its stable `groupId`, and
 * `GroupDao.rotateEpoch` mints a fresh one whenever a member leaves; every
 * remaining member then independently regenerates and redistributes a
 * brand-new Sender Key under that new epoch (see
 * `MessageRepository.handleGroupLeave`). A departed member, who never
 * receives the new epoch, cannot decrypt anything sent after rotation —
 * their old Sender Key is scoped to the old (now-abandoned) epoch only.
 *
 * This class itself is deliberately epoch-agnostic — it has no opinion on
 * what a UUID "means", it just does Sender Keys correctly for whatever
 * distributionId it's given. The rotation POLICY lives one layer up, in
 * `GroupDao`/`MessageRepository`, which is exactly why an earlier version
 * of this comment (still visible in old diffs/history) describing
 * key-rotation-on-leave as unsolved was misleading by the time
 * `MessageRepository` actually implemented it: it was true of THIS class in
 * isolation, but not of the system as a whole, and stale enough to risk a
 * future maintainer "fixing" something already fixed, or reverting the real
 * fix because this comment looked authoritative. Renaming the parameters
 * from `groupId` to `epoch` throughout makes the actual contract obvious
 * from the signatures alone, without relying on a comment staying in sync.
 */
class GroupSessionManager(db: NoTraceDatabase, private val myNumericId: String) {

    private val senderKeyStore = SqlSenderKeyStore(db)
    private val sessionBuilder = GroupSessionBuilder(senderKeyStore)

    private val myAddress get() = SignalProtocolAddress(myNumericId, 1)

    /**
     * Generates (or re-fetches, if already created for this epoch) this
     * device's own Sender Key. Call before the first send under a given
     * epoch, and again — same call, libsignal returns the existing key
     * material if one already exists for this (address, distributionId)
     * pair — whenever a newly-learned member needs to be brought up to date.
     */
    fun createOwnDistributionMessage(epoch: UUID): ByteArray =
        sessionBuilder.create(myAddress, epoch).serialize()

    /** Processes a peer's distribution message so we can decrypt THEIR future group messages under this epoch. */
    fun processDistributionMessage(peerNumericId: String, epoch: UUID, distributionMessageBytes: ByteArray) {
        val peerAddress = SignalProtocolAddress(peerNumericId, 1)
        sessionBuilder.process(peerAddress, SenderKeyDistributionMessage(distributionMessageBytes))
    }

    /** Encrypts with OUR OWN Sender Key for this epoch — `createOwnDistributionMessage` must have been called (and distributed) for it at least once first. */
    fun encrypt(epoch: UUID, plaintext: ByteArray): ByteArray =
        GroupCipher(senderKeyStore, myAddress).encrypt(epoch, plaintext).serialize()

    /** Decrypts a message sent by `fromNumericId` under their Sender Key for this epoch. */
    fun decrypt(fromNumericId: String, epoch: UUID, ciphertext: ByteArray): ByteArray {
        val senderAddress = SignalProtocolAddress(fromNumericId, 1)
        return GroupCipher(senderKeyStore, senderAddress).decrypt(ciphertext)
    }
}
