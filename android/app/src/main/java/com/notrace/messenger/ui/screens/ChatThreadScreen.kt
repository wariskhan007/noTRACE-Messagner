package com.notrace.messenger.ui.screens

import android.Manifest
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.notrace.messenger.NoTraceApplication
import com.notrace.messenger.data.AttachmentInfo
import com.notrace.messenger.data.ChatMessage
import com.notrace.messenger.data.DisappearingOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Individual Chat Screen — bubble thread, input bar with attach/voice/send
 * icons, disappearing-timer icon in the app bar" (Section 5, Core Screens #3).
 * Attach (image) and voice-call icons are wired here in Phase 4; text-only
 * "voice" would be a mic-to-text feature and isn't part of this pass —
 * the call button starts a real WebRTC audio call via CallManager.
 *
 * Phase 5: also renders GROUP conversations (`conversationId` is a group id
 * when `MessageRepository.isGroup` says so) — same bubble thread and
 * disappearing-timer control (both already conversation-level, not
 * peer-specific, so they need no changes), but sends via
 * `sendGroupMessage` instead of `sendMessage`, shows the group name +
 * member count instead of a numeric ID, opens Group Info instead of a call
 * on the title tap, and hides the call button (group calling — an N-way
 * WebRTC mesh or SFU — is out of scope for this pass, not silently dropped:
 * Signal itself shipped 1:1 calling and group text long before group calls).
 */
@Composable
fun ChatThreadScreen(conversationId: String, onBack: () -> Unit, onOpenGroupInfo: (groupId: String) -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as NoTraceApplication).container }
    val repository = container.messageRepository
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var draft by remember { mutableStateOf("") }
    var currentTimerSeconds by remember { mutableStateOf<Long?>(null) }
    var timerMenuExpanded by remember { mutableStateOf(false) }
    var isGroup by remember { mutableStateOf(false) }
    var titleText by remember { mutableStateOf(conversationId) }

    suspend fun refresh() {
        messages = withContext(Dispatchers.IO) { repository.getMessages(conversationId) }
    }

    LaunchedEffect(conversationId) {
        refresh()
        currentTimerSeconds = withContext(Dispatchers.IO) { repository.getDisappearingTimer(conversationId) }
        isGroup = withContext(Dispatchers.IO) { repository.isGroup(conversationId) }
        titleText = if (isGroup) {
            val info = withContext(Dispatchers.IO) { repository.getGroupInfo(conversationId) }
            info?.let { "${it.name} (${it.members.size})" } ?: conversationId
        } else {
            conversationId
        }
    }

    // BUGFIX (audit): previously the only way this screen re-queried the DB
    // was on first composition (above) or right after this device sent
    // something — an incoming message from a peer while this thread was
    // open just silently didn't appear until you left and came back.
    // MessageRepository.messageEvents emits the affected conversationId on
    // every insert (see its doc comment); collect and refresh on a match.
    LaunchedEffect(conversationId) {
        repository.messageEvents.collect { changedConversationId ->
            if (changedConversationId == conversationId) refresh()
        }
    }

    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            repository.sendAttachment(conversationId, bytes, mimeType)
            refresh()
        }
    }

    val callPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            container.callManager.startCall(conversationId, video = false)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isGroup) {
                        TextButton(onClick = { onOpenGroupInfo(conversationId) }) { Text(titleText) }
                    } else {
                        Text(titleText)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (!isGroup) {
                        IconButton(onClick = { callPermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }) {
                            Icon(Icons.Filled.Call, contentDescription = "Call")
                        }
                    }
                    Box {
                        TextButton(onClick = { timerMenuExpanded = true }) {
                            Text(DisappearingOption.entries.firstOrNull { it.seconds == currentTimerSeconds }?.label ?: "Off")
                        }
                        DropdownMenu(expanded = timerMenuExpanded, onDismissRequest = { timerMenuExpanded = false }) {
                            DisappearingOption.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        timerMenuExpanded = false
                                        currentTimerSeconds = option.seconds
                                        scope.launch(Dispatchers.IO) {
                                            repository.setDisappearingTimer(conversationId, option.seconds)
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Group attachments aren't wired this pass — MediaCrypto/AttachmentStore
                // are per-peer 1:1 today; fanning an encrypted blob out to every group
                // member (like sendGroupMessage does for text) is a clean follow-up,
                // not implemented here rather than silently broken.
                if (!isGroup) {
                    IconButton(onClick = { attachmentPicker.launch("image/*") }) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Attach")
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                )
                IconButton(
                    onClick = {
                        val text = draft.trim()
                        if (text.isEmpty()) return@IconButton
                        draft = ""
                        scope.launch {
                            if (isGroup) repository.sendGroupMessage(conversationId, text) else repository.sendMessage(conversationId, text)
                            refresh()
                        }
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isMine = message.senderId == "me"
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    color = if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(18.dp), // "18dp corner radius" per Section 5
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Column {
                message.attachment?.let { attachment ->
                    if (attachment.mimeType.startsWith("image/")) {
                        AttachmentImage(attachment)
                    } else {
                        Text(
                            text = "\uD83D\uDCCE ${attachment.mimeType} (${attachment.sizeBytes / 1024} KB)",
                            color = if (isMine) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                if (message.body.isNotEmpty()) {
                    Text(
                        text = message.body,
                        color = if (isMine) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (message.expiresAt != null) {
                    Text(
                        text = "Disappears",
                        color = (if (isMine) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                            .copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentImage(attachment: AttachmentInfo) {
    val context = LocalContext.current
    val repository = remember { (context.applicationContext as NoTraceApplication).container.messageRepository }
    var bitmap by remember(attachment.localPath) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    LaunchedEffect(attachment.localPath) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val plainBytes = repository.decryptAttachment(attachment)
                BitmapFactory.decodeByteArray(plainBytes, 0, plainBytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }

    val loaded = bitmap
    if (loaded != null) {
        Image(bitmap = loaded, contentDescription = null, modifier = Modifier.height(200.dp))
    } else {
        Text("Loading image…", style = MaterialTheme.typography.bodyLarge)
    }
}
