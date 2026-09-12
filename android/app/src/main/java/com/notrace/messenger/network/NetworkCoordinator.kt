package com.notrace.messenger.network

import com.notrace.messenger.data.MessageRepository
import com.notrace.messenger.network.webrtc.CallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Keeps exactly one active `SignalingClient.connect()` collector for the
 * process lifetime (so [SignalingClient]'s request/response correlation for
 * prekey bundles actually receives replies — see its `requestPreKeyBundle`)
 * and fans out everything else the message dispatch in `server/src/index.ts`
 * can send: relayed text/attachment/group envelopes and drained mailbox
 * items go to [MessageRepository]; call signaling (offer/answer/ICE/hangup)
 * goes to [CallManager].
 *
 * SECURITY/AVAILABILITY FIX (Phase 6): a dropped connection used to just
 * end the collector permanently — no messages, no calls, nothing, until
 * the app was killed and relaunched. This now retries with exponential
 * backoff (1s, 2s, 4s... capped at 30s), resetting to 1s after any message
 * is successfully received, for the process lifetime.
 */
class NetworkCoordinator(
    private val signalingClient: SignalingClient,
    private val repository: MessageRepository,
    private val callManager: CallManager,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            var backoffMs = 1_000L
            while (isActive) {
                try {
                    signalingClient.connect()
                        .collect { msg ->
                            handle(msg)
                            backoffMs = 1_000L // reset once the link is proven live again
                        }
                } catch (t: Throwable) {
                    // Expected on any drop (server restart, network change, etc.) —
                    // fall through to the backoff delay and try again below.
                }
                if (!isActive) break
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun handle(msg: JSONObject) {
        when (msg.optString("type")) {
            "signal" -> {
                val kind = msg.optString("kind")
                val from = msg.getString("from")
                if (kind == "encrypted-envelope") {
                    repository.handleIncomingEnvelope(from, msg.getString("payload"))
                } else {
                    callManager.handleSignalMessage(from, kind, msg.getString("payload"))
                }
            }
            "mailbox" -> {
                val items = msg.getJSONArray("items")
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    repository.handleIncomingEnvelope(item.getString("from"), item.getString("payload"))
                }
            }
            // "registered", "error", "lookupResult", "preKeysUploaded",
            // "turnCredentials", "presenceUpdate", "pong" — surfaced to the
            // UI layer as needed; no repository action required here.
        }
    }
}
