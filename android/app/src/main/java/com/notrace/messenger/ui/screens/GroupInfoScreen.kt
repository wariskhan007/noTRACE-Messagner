package com.notrace.messenger.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.notrace.messenger.NoTraceApplication
import com.notrace.messenger.data.GroupInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Group-specific slice of "Settings" (Section 5, Core Screens #7 talks about
 * app-wide Settings; a group's own settings — membership, rename, leave —
 * naturally live on their own screen instead, reached from the group name
 * in ChatThreadScreen's app bar, the same way Signal itself splits these).
 */
@Composable
fun GroupInfoScreen(groupId: String, onBack: () -> Unit, onLeft: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { (context.applicationContext as NoTraceApplication).container.messageRepository }
    val scope = rememberCoroutineScope()

    var info by remember { mutableStateOf<GroupInfo?>(null) }
    var addMemberId by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        info = withContext(Dispatchers.IO) { repository.getGroupInfo(groupId) }
    }

    LaunchedEffect(groupId) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(info?.name ?: groupId) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("Members", style = MaterialTheme.typography.titleLarge)
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                items(info?.members ?: emptyList(), key = { it.numericId }) { member ->
                    Text(member.displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 6.dp))
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedTextField(
                    value = addMemberId,
                    onValueChange = { addMemberId = it; errorText = null },
                    modifier = Modifier.weight(1f),
                    label = { Text("Add by numeric ID") },
                )
                TextButton(
                    onClick = {
                        val target = addMemberId.trim()
                        if (target.isEmpty()) return@TextButton
                        scope.launch {
                            repository.addMemberToGroup(groupId, target)
                            addMemberId = ""
                            refresh()
                        }
                    },
                ) { Text("Add") }
            }
            errorText?.let { Text(it, color = Color.Red, style = MaterialTheme.typography.bodyLarge) }

            Button(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                onClick = {
                    scope.launch {
                        repository.leaveGroup(groupId)
                        onLeft()
                    }
                },
            ) { Text("Leave group") }
        }
    }
}
