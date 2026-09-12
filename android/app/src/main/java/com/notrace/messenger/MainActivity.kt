package com.notrace.messenger

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.notrace.messenger.navigation.NoTraceNavGraph
import com.notrace.messenger.network.webrtc.CallState
import com.notrace.messenger.ui.screens.CallScreen
import com.notrace.messenger.ui.screens.LockScreen
import com.notrace.messenger.ui.theme.NoTraceTheme

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as NoTraceApplication).container }

    // BUGFIX (Phase 1-4 audit): this used to be `remember { mutableStateOf(...) }`
    // read ONCE inside setContent's first composition. AppLockManager.onAppBackgrounded()
    // (called from onStop below) updated the *manager's* internal field, but nothing
    // ever pushed that back into Compose state on the next resume — so after the
    // first unlock, the lock screen could never reappear no matter how many times
    // the app was backgrounded. Hoisting this to an Activity-level MutableState,
    // written from onResume, is what actually makes "no grace period, always
    // re-locks" (the doc comment below) true rather than aspirational.
    private val lockedState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // "Screen-security flag to block screenshots (matching Signal's default
        // behavior)" — Section 4. Also blocks the recents-list thumbnail —
        // which matters doubly once app-lock is on, since the thumbnail
        // would otherwise show chat content even while "locked".
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        lockedState.value = container.appLockManager.shouldShowLockScreen()

        setContent {
            val locked by lockedState
            // PHASE 6 FIX: a call reaching CallManager used to be invisible
            // until the app was unlocked — CallScreen only ever lived inside
            // NoTraceNavGraph, which isn't composed while locked. Real phones
            // let you answer a call without unlocking (you just can't read
            // messages), so that's the bar here too: CallScreen shows caller
            // id + accept/decline/mute/hangup and NOTHING from chat history,
            // so surfacing it through the lock doesn't leak message content —
            // only "someone is calling," same as the OS dialer already does.
            val callState by container.callManager.callState.collectAsState()

            NoTraceTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when {
                        locked && callState != CallState.Idle ->
                            CallScreen(onCallEnded = { /* state -> Idle triggers recomposition back to LockScreen below */ })
                        locked ->
                            LockScreen(onUnlockRequested = { showBiometricPrompt { lockedState.value = false } })
                        else -> NoTraceNavGraph()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-read every resume (app reopened from background, or the very
        // first launch): this is what actually re-arms the lock screen after
        // onStop below flips AppLockManager's internal flag. Cheap no-op
        // when app-lock is disabled or already unlocked.
        lockedState.value = container.appLockManager.shouldShowLockScreen()
    }

    override fun onStop() {
        super.onStop()
        // Signal-style: no grace period. Every backgrounding re-arms the lock
        // (only takes effect if the user has app-lock enabled at all) — see
        // onResume above for the other half of making this actually stick.
        container.appLockManager.onAppBackgrounded()
    }

    private fun showBiometricPrompt(onSuccess: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    container.appLockManager.unlock()
                    onSuccess()
                }
                // onAuthenticationError / onAuthenticationFailed: deliberately
                // left as BiometricPrompt's own default UI feedback — no
                // custom handling needed for a Phase 3 pass.
            },
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock NoTrace")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        prompt.authenticate(promptInfo)
    }
}
