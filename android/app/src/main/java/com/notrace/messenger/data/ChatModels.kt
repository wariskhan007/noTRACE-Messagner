package com.notrace.messenger.data

data class AttachmentInfo(
    val localPath: String, // path to the ENCRYPTED blob on disk (data/media/AttachmentStore)
    val mimeType: String,
    val sizeBytes: Long,
    val mediaKeyBase64: String, // base64 of the 64-byte MediaCrypto key needed to decrypt it
)

data class ChatMessage(
    val id: Long,
    val conversationId: String,
    val senderId: String, // "me" or the sender's numericId
    val body: String,
    val sentAt: Long,
    val expiresAt: Long? = null, // null = no disappearing timer active for this message
    val attachment: AttachmentInfo? = null,
)

data class ConversationPreview(
    val numericId: String,
    val displayName: String,
    val isGroup: Boolean = false, // Phase 5
    val disappearingSeconds: Long? = null, // null = off; else 86400 / 604800 / 2592000 / 31536000
    val lastMessage: String?,
    val lastMessageAt: Long?,
)

/** Phase 5 (Groups). A group's `numeric_id` in `conversations` IS this groupId, and also libsignal's Sender Keys distributionId — see GroupSessionManager. */
data class GroupMember(
    val numericId: String,
    val displayName: String,
    val joinedAt: Long,
)

data class GroupInfo(
    val groupId: String,
    val name: String,
    val members: List<GroupMember>,
    val disappearingSeconds: Long? = null,
)

/** The four fixed disappearing-message options from the spec (Section 5), plus "off". */
enum class DisappearingOption(val seconds: Long?, val label: String) {
    OFF(null, "Off"),
    ONE_DAY(86_400L, "1 day"),
    ONE_WEEK(604_800L, "1 week"),
    ONE_MONTH(2_592_000L, "1 month"),
    ONE_YEAR(31_536_000L, "1 year"),
}
