package com.notrace.messenger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shown instead of the rest of the app whenever
 * `AppLockManager.shouldShowLockScreen()` is true. Deliberately shows no
 * chat content, previews, or even conversation names behind/around it —
 * the whole point is that a glance at the phone reveals nothing.
 */
@Composable
fun LockScreen(onUnlockRequested: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("NoTrace is locked", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.padding(top = 16.dp))
        Button(onClick = onUnlockRequested) {
            Text("Unlock")
        }
    }
}
