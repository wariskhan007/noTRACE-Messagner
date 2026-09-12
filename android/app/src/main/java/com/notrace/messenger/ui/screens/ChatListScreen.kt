package com.notrace.messenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.notrace.messenger.NoTraceApplication
import com.notrace.messenger.data.ConversationPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Chat List — avatar, name, last message preview, timestamp, unread badge,
 * online-dot indicator" (Section 5, Core Screens #2).
 *
 * Now backed by the real SQLCipher-persisted conversation list via
 * MessageRepository (Phase 2) instead of Phase 1's mock data. Unread
 * badges, timestamps formatting, and the online-dot indicator need a
 * presence feed wired up (Section 4 "Discovery/search" + presence
 * broadcast) — left as a clearly-scoped follow-up, not faked here.
 */
@Composable
fun ChatListScreen(
    onOpenChat: (numericId: String) -> Unit,
    onOpenSettings: () -> Unit,
    onAddContact: () -> Unit,
    onNewGroup: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { (context.applicationContext as NoTraceApplication).container.messageRepository }
    var conversations by remember { mutableStateOf(listOf<ConversationPreview>()) }
    var newMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        conversations = withContext(Dispatchers.IO) { repository.getConversations() }
    }

    // BUGFIX (audit): same live-update gap as ChatThreadScreen — this list
    // previously only loaded once, so a new incoming message (which
    // changes the last-message preview and ordering) didn't show up while
    // the user was sitting on this screen, only after navigating away and
    // back. Any conversationId change is enough reason to re-run the whole
    // list query, since it re-sorts by most recent activity.
    LaunchedEffect(Unit) {
        repository.messageEvents.collect {
            conversations = withContext(Dispatchers.IO) { repository.getConversations() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chats") },
                actions = {
                    Box {
                        IconButton(onClick = { newMenuExpanded = true }) { Icon(Icons.Filled.Add, contentDescription = "New") }
                        DropdownMenu(expanded = newMenuExpanded, onDismissRequest = { newMenuExpanded = false }) {
                            DropdownMenuItem(text = { Text("New chat") }, onClick = { newMenuExpanded = false; onAddContact() })
                            DropdownMenuItem(text = { Text("New group") }, onClick = { newMenuExpanded = false; onNewGroup() })
                        }
                    }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { padding ->
        if (conversations.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "No chats yet. Tap \"New\" above to look someone up by numeric ID/username " +
                        "(Contact Add) or start a group once you have a contact or two.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(conversations, key = { it.numericId }) { conversation ->
                ConversationRow(conversation, onClick = { onOpenChat(conversation.numericId) })
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: ConversationPreview, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                // Groups get the primary-accent color instead of secondary —
                // a cheap, real visual distinction until a proper group
                // avatar (Section 5's "group name/avatar") is designed.
                .background(
                    if (conversation.isGroup) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    CircleShape,
                ),
        )
        Column(modifier = Modifier.padding(start = 12.dp).fillMaxWidth()) {
            Text(
                (if (conversation.isGroup) "\uD83D\uDC65 " else "") + conversation.displayName,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                conversation.lastMessage ?: "No messages yet",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
