package com.notrace.messenger.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.notrace.messenger.NoTraceApplication
import kotlinx.coroutines.launch

/**
 * "Contact/QR Add Screen — camera QR scanner + 'Show My Code' tab"
 * (Section 5, Core Screens #5). Flagged as a gap in every README since
 * Phase 2; this closes the search/add half of it.
 *
 * Search-by-ID/username uses the server's existing `lookup` wire message
 * (Section 4's "Discovery/search option" — `SignalingClient.requestLookup`,
 * already implemented since Phase 1, just never wired to any UI before now).
 *
 * KNOWN GAP, stated plainly rather than faked: this is manual-entry only —
 * there's no camera QR *scanner* here. Doing that properly needs a camera
 * permission + a barcode-decoding library (CameraX + ML Kit, or ZXing),
 * neither of which is in build.gradle.kts yet, and adding an unverified new
 * native dependency in the same pass as the Sender Keys crypto work this
 * phase is centered on isn't a good trade. "Show My Code" below shows the
 * numeric ID as *text* to read aloud or copy — genuinely useful today, just
 * not a scannable code yet. A real QR *image* is the natural next slice
 * once a barcode library is deliberately added (same "deliberately scoped-
 * down slice, not a bug" call Phase 4 made for video-call UI).
 */
@Composable
fun ContactAddScreen(onContactFound: (numericId: String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as NoTraceApplication).container }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add contact") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Find someone") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Show my code") })
            }

            if (tab == 0) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Text(
                        "Enter a numeric ID or username to look them up (Section 4: the server only " +
                            "ever holds the minimal id/username -> public key mapping needed for this — " +
                            "no contact lists, no message history).",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it; statusText = null },
                            modifier = Modifier.weight(1f),
                            label = { Text("Numeric ID or username") },
                        )
                        Button(
                            enabled = query.isNotBlank() && !searching,
                            onClick = {
                                val q = query.trim()
                                searching = true
                                scope.launch {
                                    val result = container.signalingClient.requestLookup(q)
                                    searching = false
                                    if (result.optBoolean("found", false)) {
                                        onContactFound(result.getString("numericId"))
                                    } else {
                                        statusText = "No one found for \"$q\"."
                                    }
                                }
                            },
                        ) { Text("Look up") }
                    }
                    statusText?.let {
                        Text(it, color = Color.Red, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            } else {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Text("Your numeric ID", style = MaterialTheme.typography.titleLarge)
                    Text(
                        container.myNumericId,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                    )
                    Text(
                        "Share this with someone so they can look you up and start a chat. " +
                            "A scannable QR code is a planned follow-up (see the gap noted on this screen).",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}
