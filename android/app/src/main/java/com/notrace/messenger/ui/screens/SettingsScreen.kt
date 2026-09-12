package com.notrace.messenger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.notrace.messenger.NoTraceApplication
import com.notrace.messenger.security.SecureWipeManager

/**
 * "Settings — app lock, disappearing message defaults, secure wipe"
 * (Section 4/5). Per-conversation disappearing timers live on the
 * ChatThreadScreen's app bar (Section 5, Core Screens #3) since that's
 * where the spec places them; this screen covers the device-wide controls.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onWipedAndRestart: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as NoTraceApplication).container }
    val appLockManager = remember { container.appLockManager }
    val clipboardManager: ClipboardManager = LocalClipboardManager.current
    var appLockEnabled by remember { mutableStateOf(appLockManager.isEnabled) }
    var showWipeConfirm by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            // This was previously nowhere in the app — there was no way to
            // learn your OWN numeric id to give to someone else, which the
            // Contact Add screen's manual-entry flow depends on entirely
            // (there's no QR scanner yet — see README). Without this,
            // nobody could ever actually add anybody.
            Text("Your ID", style = MaterialTheme.typography.titleLarge)
            Text(
                "Share this with someone so they can add you as a contact.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(container.myNumericId, style = MaterialTheme.typography.titleLarge)
                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(container.myNumericId))
                        copied = true
                    },
                    modifier = Modifier.padding(start = 12.dp),
                ) {
                    Text(if (copied) "Copied!" else "Copy")
                }
            }

            Spacer(modifier = Modifier.padding(top = 32.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("App lock", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Require biometric/device unlock every time NoTrace is opened",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Switch(
                    checked = appLockEnabled,
                    onCheckedChange = {
                        appLockEnabled = it
                        appLockManager.isEnabled = it
                    },
                )
            }

            Spacer(modifier = Modifier.padding(top = 32.dp))

            Text("Danger zone", style = MaterialTheme.typography.titleLarge)
            Text(
                "Permanently deletes every message, key, and identity on this device. " +
                    "This cannot be undone and there is no backup to restore from.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            if (!showWipeConfirm) {
                Button(
                    onClick = { showWipeConfirm = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Wipe all data")
                }
            } else {
                Column {
                    Text("Are you sure? This is permanent.", style = MaterialTheme.typography.bodyLarge)
                    Row(modifier = Modifier.padding(top = 8.dp)) {
                        Button(
                            onClick = {
                                // BUGFIX (audit): wipeEverything() now kills
                                // and restarts the whole process itself (see
                                // its doc comment for why that's required),
                                // so onWipedAndRestart() would never actually
                                // run — the process is gone before this
                                // click handler could return. Left
                                // unreachable-but-harmless would be
                                // misleading to a future reader; removed.
                                SecureWipeManager.wipeEverything(context.applicationContext)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) {
                            Text("Yes, wipe everything")
                        }
                        TextButton(onClick = { showWipeConfirm = false }, modifier = Modifier.padding(start = 8.dp)) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}
