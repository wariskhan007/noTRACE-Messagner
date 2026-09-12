package com.notrace.messenger.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.notrace.messenger.NoTraceApplication
import com.notrace.messenger.network.webrtc.CallState
import org.webrtc.SurfaceViewRenderer

/**
 * Renders whichever [CallState] `CallManager` reports. This screen is
 * pushed onto the nav stack by whoever initiates/receives a call (see
 * ChatThreadScreen's call button, and MainActivity for the incoming-call
 * case) — it doesn't manage navigation itself, only presentation + the
 * accept/decline/hang-up/mute actions.
 */
@Composable
fun CallScreen(onCallEnded: () -> Unit) {
    val context = LocalContext.current
    val callManager = remember { (context.applicationContext as NoTraceApplication).container.callManager }
    val state by callManager.callState.collectAsState()
    val remoteVideoTrack by callManager.remoteVideoTrack.collectAsState()

    // BUGFIX (audit): calling onCallEnded() directly in the composable body
    // is a side effect during composition — a Compose anti-pattern that, on
    // this screen, could fire more than once per Idle state (any
    // unrelated recomposition while state == Idle would call it again,
    // e.g. popping the nav back stack repeatedly). LaunchedEffect keyed on
    // `state` makes this fire exactly once per transition into Idle.
    LaunchedEffect(state) {
        if (state == CallState.Idle) onCallEnded()
    }

    // BUGFIX (audit): accepting a call that turns out to be a video call
    // starts the camera (WebRtcManager.addLocalMedia(true) -> Camera2Enumerator)
    // without ever having requested the CAMERA runtime permission —
    // ChatThreadScreen's outgoing-call button only ever requests
    // RECORD_AUDIO, and outgoing calls are voice-only today, so this path
    // was previously unreachable in practice but would crash the moment
    // any caller (including a future version of this same app) placed a
    // video call. Request both permissions before accepting.
    val acceptPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val currentState = state
        if (currentState is CallState.Incoming && granted[Manifest.permission.RECORD_AUDIO] == true) {
            if (!currentState.video || granted[Manifest.permission.CAMERA] == true) {
                callManager.acceptCall(video = currentState.video && granted[Manifest.permission.CAMERA] == true)
            }
        }
    }

    if (state == CallState.Idle) {
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Remote video fills the screen when present; audio-only calls just
        // show the status text below over a plain background.
        if (remoteVideoTrack != null) {
            val eglContext = callManager.getEglContext()
            if (eglContext != null) {
                var rendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

                // BUGFIX (audit): the renderer's init()'d GL/EGL surface was
                // never released when this left composition (call ended,
                // screen navigated away) — a leak on every single video
                // call. DisposableEffect's onDispose is the correct place
                // for tearing down a resource an AndroidView factory creates.
                DisposableEffect(Unit) {
                    onDispose { rendererRef?.release() }
                }

                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).also { renderer ->
                            renderer.init(eglContext, null)
                            remoteVideoTrack?.addSink(renderer)
                            rendererRef = renderer
                        }
                    },
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom,
        ) {
            val currentState = state
            Text(
                text = when (currentState) {
                    is CallState.Outgoing -> "Calling ${currentState.peerId}…"
                    is CallState.Incoming -> "${currentState.peerId} is calling…"
                    is CallState.Active -> "In call with ${currentState.peerId}"
                    CallState.Idle -> ""
                },
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
            )

            Row(modifier = Modifier.padding(top = 24.dp)) {
                if (currentState is CallState.Incoming) {
                    Button(
                        onClick = {
                            val permissions = if (currentState.video) {
                                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                            } else {
                                arrayOf(Manifest.permission.RECORD_AUDIO)
                            }
                            acceptPermissions.launch(permissions)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(Icons.Filled.Call, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Accept")
                    }
                    Button(
                        onClick = { callManager.declineOrHangUp() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.padding(start = 12.dp),
                    ) {
                        Icon(Icons.Filled.CallEnd, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Decline")
                    }
                } else {
                    Button(
                        onClick = { callManager.declineOrHangUp() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Filled.CallEnd, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Hang up")
                    }
                }
            }
        }
    }
}
