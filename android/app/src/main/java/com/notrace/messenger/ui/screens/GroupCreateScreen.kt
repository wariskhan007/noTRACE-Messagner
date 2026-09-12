package com.notrace.messenger.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.notrace.messenger.NoTraceApplication
import com.notrace.messenger.data.ConversationPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Group Chat Creation — multi-select contacts, group name/avatar,
 * group-level disappearing timer" (Section 5, Core Screens #8).
 *
 * Candidates are the existing 1:1 conversations already in `conversations`
 * (is_group = 0) — Section 4's "Contact/QR Add" flow is what gets a peer
 * into that list in the first place (see ContactAddScreen); this screen
 * doesn't duplicate that lookup. Avatar picking and a per-creation
 * disappearing-timer choice are left for the group-level timer control
 * already on ChatThreadScreen's app bar (works identically for groups,
 * see that screen) — not re-implemented redundantly here.
 */
@Composable
fun GroupCreateScreen(onGroupCreated: (groupId: String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { (context.applicationContext as NoTraceApplication).container.messageRepository }
    val scope = rememberCoroutineScope()

    var contacts by remember { mutableStateOf(listOf<ConversationPreview>()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var groupName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        contacts = withContext(Dispatchers.IO) { repository.getConversations().filter { !it.isGroup } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New group") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Cancel") } },
                actions = {
                    TextButton(
                        enabled = groupName.isNotBlank() && selected.isNotEmpty() && !creating,
                        onClick = {
                            creating = true
                            scope.launch {
                                val groupId = repository.createGroup(groupName.trim(), selected.toList())
                                onGroupCreated(groupId)
                            }
                        },
                    ) { Text("Create") }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Group name") },
            )

            Text(
                "Members (${selected.size} selected)",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )

            if (contacts.isEmpty()) {
                Text(
                    "No contacts yet — add someone first from \"New chat\" on the chat list.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(contacts, key = { it.numericId }) { contact ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = contact.numericId in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + contact.numericId else selected - contact.numericId
                                },
                            )
                            Text(contact.displayName, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}
