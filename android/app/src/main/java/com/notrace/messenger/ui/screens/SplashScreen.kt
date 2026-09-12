package com.notrace.messenger.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.notrace.messenger.NoTraceApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Splash/Key Generation — first-launch identity keypair generation with a
 * friendly progress animation" (Section 5, Core Screens #1).
 *
 * On first run this: generates the libsignal identity keypair (via
 * SignalSessionManager, persisted in the encrypted DB), registers the
 * derived numeric ID + public key with the signaling server, and uploads
 * a batch of prekeys so other users can start X3DH with this device even
 * while it's offline. On subsequent launches all of this resolves near
 * instantly since the identity and prekeys already exist.
 *
 * SECURITY/CORRECTNESS FIX (Phase 6): this used to fire `register()` /
 * `uploadPreKeys()` immediately, racing OkHttp's asynchronous WebSocket
 * handshake — on a slow or briefly-unreachable network, both calls could
 * silently no-op, leaving the device generated but never actually
 * registered with the server, with no error shown anywhere. This now
 * awaits `SignalingClient.awaitConnected()` first (15s timeout) and shows
 * which stage it's in, rather than assuming success.
 */
/** Below this many locally-held one-time prekeys, top up with a fresh batch (see the BUGFIX note in the LaunchedEffect below). */
private const val LOW_PREKEY_WATERMARK = 20

@Composable
fun SplashScreen(onIdentityReady: () -> Unit) {
    val context = LocalContext.current
    var statusText by remember { mutableStateOf("Generating your private identity…") }

    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            val container = (context.applicationContext as NoTraceApplication).container
            val sessionManager = container.sessionManager // triggers identity keygen on first access

            statusText = "Connecting…"
            val connected = container.signalingClient.awaitConnected()
            if (!connected) {
                // Proceed anyway rather than blocking forever — NetworkCoordinator's
                // reconnect-with-backoff loop keeps trying in the background, and
                // register()/uploadPreKeys() will simply no-op if still disconnected;
                // the person can still use the app locally and this resolves itself
                // once connectivity returns and they revisit a screen that re-sends.
                statusText = "Still trying to connect — continuing anyway…"
            } else {
                statusText = "Registering…"
            }

            container.signalingClient.register(
                numericId = container.myNumericId,
                publicKey = sessionManager.identityPublicKeyBase64,
            )

            // BUGFIX (audit): this used to generate + upload a fresh batch
            // of 100 one-time prekeys on EVERY launch, unconditionally. The
            // server's prekey pool only ever grows (see PreKeyStore.upload
            // in server/src/index.ts merging rather than replacing), so a
            // frequently-opened app would accumulate thousands of one-time
            // prekeys server-side and locally that will never be consumed.
            // Only top up when the local pool is actually running low —
            // exactly the "periodically top up... when running low"
            // behavior generatePreKeyBatchForUpload's own doc comment
            // already described as the intent.
            if (sessionManager.remainingOneTimePreKeyCount() < LOW_PREKEY_WATERMARK) {
                val batch = sessionManager.generatePreKeyBatchForUpload()
                container.signalingClient.uploadPreKeys(
                    registrationId = batch.registrationId,
                    identityKey = batch.identityKeyBase64,
                    signedPreKeyId = batch.signedPreKeyId,
                    signedPreKeyPublic = batch.signedPreKeyPublicBase64,
                    signedPreKeySignature = batch.signedPreKeySignatureBase64,
                    oneTimePreKeys = batch.oneTimePreKeys.map { it.keyId to it.publicKeyBase64 },
                )
            }
        }
        onIdentityReady()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = 24.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .alpha(alpha),
        ) {
            Column(modifier = Modifier.padding(28.dp)) {
                Text("N", color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
        }
        Text(statusText, style = MaterialTheme.typography.bodyLarge)
    }
}
