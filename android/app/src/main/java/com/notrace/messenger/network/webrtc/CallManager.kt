package com.notrace.messenger.network.webrtc

import android.content.Context
import com.notrace.messenger.data.MessageRepository
import com.notrace.messenger.network.SignalingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

sealed class CallState {
    object Idle : CallState()
    data class Outgoing(val peerId: String, val video: Boolean) : CallState()
    data class Incoming(val peerId: String, val video: Boolean) : CallState()
    data class Active(val peerId: String, val video: Boolean) : CallState()
}

/**
 * The call-signaling counterpart to [MessageRepository]: where that class
 * owns encrypted text/attachment content, this class owns the WebRTC
 * offer/answer/ICE dance and the resulting per-peer [WebRtcManager]. Both
 * ride the same "signal" message type the server already relays
 * (server/src/index.ts's `handleSignal`) — text uses `kind ==
 * "encrypted-envelope"`, calls use `kind` in {"offer","answer",
 * "ice-candidate","call-end"}.
 *
 * Once a call's data channel reaches CONNECTED, this hands the channel to
 * [MessageRepository.attachTransport] so ordinary chat messages start
 * riding the same direct P2P link too — closing the gap Phase 2 left open.
 */
class CallManager(
    private val context: Context,
    private val signalingClient: SignalingClient,
    private val messageRepository: MessageRepository,
    private val scope: CoroutineScope,
) {
    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack

    private var activeManager: WebRtcManager? = null
    private var activePeerId: String? = null
    private var pendingOfferSdp: String? = null

    // BUGFIX (audit): buildManager now does a network round trip (TURN
    // credential fetch, see its doc comment) before a WebRtcManager exists,
    // so there's a real window where an "ice-candidate" signal can arrive
    // for a call that's still being set up. These used to be silently
    // dropped via `activeManager?.addIceCandidate(...)` no-op'ing on a null
    // manager — a common real-world cause of calls that ring but never
    // actually connect audio. Buffered here and flushed once the manager
    // exists (see `attachManager`).
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    /** Needed by CallScreen to init() its SurfaceViewRenderer against the same shared GL context. */
    fun getEglContext(): org.webrtc.EglBase.Context? = activeManager?.eglBase?.eglBaseContext

    fun startCall(peerId: String, video: Boolean) {
        _callState.value = CallState.Outgoing(peerId, video)
        activePeerId = peerId
        // BUGFIX (audit): buildManager now fetches TURN credentials first —
        // a network round trip — so this has to happen on a coroutine
        // rather than synchronously on the caller's (UI) thread. Call state
        // is set above immediately so the UI shows "Calling…" right away
        // regardless of how long the TURN fetch takes.
        scope.launch {
            val manager = buildManager(peerId)
            attachManager(manager)
            manager.createConnection()
            manager.createDataChannel()
            manager.addLocalMedia(video)
            manager.createOffer { sdp ->
                val payload = JSONObject().apply { put("sdp", sdp.description); put("video", video) }
                signalingClient.signal(peerId, "offer", payload.toString())
            }
        }
    }

    /** Called by the UI once the user taps "Accept" on an incoming call. */
    fun acceptCall(video: Boolean) {
        val peerId = activePeerId ?: return
        val offerSdp = pendingOfferSdp ?: return
        val manager = activeManager ?: return

        manager.setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, offerSdp))
        manager.addLocalMedia(video)
        manager.createAnswer { sdp ->
            val payload = JSONObject().apply { put("sdp", sdp.description); put("video", video) }
            signalingClient.signal(peerId, "answer", payload.toString())
        }
    }

    fun declineOrHangUp() {
        val peerId = activePeerId
        if (peerId != null) signalingClient.signal(peerId, "call-end", "{}")
        teardown()
    }

    fun setMuted(muted: Boolean) {
        activeManager?.setMuted(muted)
    }

    /** Routed here by NetworkCoordinator for every "signal" message with a call-related kind. */
    fun handleSignalMessage(fromNumericId: String, kind: String, payload: String) {
        when (kind) {
            "offer" -> {
                val json = JSONObject(payload)
                pendingOfferSdp = json.getString("sdp")
                val video = json.optBoolean("video", false)
                activePeerId = fromNumericId
                _callState.value = CallState.Incoming(fromNumericId, video)
                // Same async-manager-build reasoning as startCall above —
                // don't block NetworkCoordinator's single message-dispatch
                // loop on a TURN fetch, or every other incoming signal
                // (including this call's own ICE candidates) queues up
                // behind it.
                scope.launch {
                    val manager = buildManager(fromNumericId)
                    attachManager(manager)
                    manager.createConnection()
                }
            }
            "answer" -> {
                val json = JSONObject(payload)
                activeManager?.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, json.getString("sdp")))
            }
            "ice-candidate" -> {
                val json = JSONObject(payload)
                val candidate = IceCandidate(json.getString("sdpMid"), json.getInt("sdpMLineIndex"), json.getString("candidate"))
                val manager = activeManager
                if (manager != null) manager.addIceCandidate(candidate) else pendingIceCandidates.add(candidate)
            }
            "call-end" -> teardown()
        }
    }

    /** Sets `activeManager` and flushes any ICE candidates that arrived while it was still being built. */
    private fun attachManager(manager: WebRtcManager) {
        activeManager = manager
        if (pendingIceCandidates.isNotEmpty()) {
            pendingIceCandidates.forEach { manager.addIceCandidate(it) }
            pendingIceCandidates.clear()
        }
    }

    /**
     * BUGFIX (audit): this used to construct WebRtcManager with no
     * `iceServers` argument at all, silently falling back to its
     * STUN-only default — even though the server has always supported
     * minting short-lived TURN credentials (server/src/index.ts's
     * "turnRequest" handler). Every call was therefore built without TURN,
     * so calls across the ~10-15% of network pairings that need a relay to
     * complete the handshake would ring but never actually connect.
     * `requestTurnCredentials()` returns null (after a 10s timeout) if the
     * server has no TURN_SHARED_SECRET configured — STUN-only is the
     * correct fallback in that case, not a bug, so this degrades gracefully
     * either way.
     */
    private suspend fun buildManager(peerId: String): WebRtcManager {
        val iceServers = resolveIceServers()
        return WebRtcManager(
            context = context,
            iceServers = iceServers,
            onIceCandidate = { candidate ->
                val payload = JSONObject().apply {
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                    put("candidate", candidate.sdp)
                }
                signalingClient.signal(peerId, "ice-candidate", payload.toString())
            },
            onDataChannelMessage = { bytes ->
                scope.launch { messageRepository.handleIncomingBytes(peerId, bytes) }
            },
            onConnectionStateChanged = { state ->
                if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                    val video = (_callState.value as? CallState.Outgoing)?.video
                        ?: (_callState.value as? CallState.Incoming)?.video ?: false
                    _callState.value = CallState.Active(peerId, video)
                    activeManager?.let { messageRepository.attachTransport(peerId, it) }
                } else if (state == PeerConnection.PeerConnectionState.FAILED || state == PeerConnection.PeerConnectionState.CLOSED) {
                    teardown()
                }
            },
            onRemoteVideoTrack = { track -> _remoteVideoTrack.value = track },
        )
    }

    private suspend fun resolveIceServers(): List<PeerConnection.IceServer> {
        val defaultStun = PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        val turn = signalingClient.requestTurnCredentials() ?: return listOf(defaultStun)
        val urlsJson = turn.optJSONArray("urls") ?: return listOf(defaultStun)
        val urls = (0 until urlsJson.length()).map { urlsJson.getString(it) }
        if (urls.isEmpty()) return listOf(defaultStun)
        val turnServer = PeerConnection.IceServer.builder(urls)
            .setUsername(turn.optString("username"))
            .setPassword(turn.optString("password"))
            .createIceServer()
        return listOf(defaultStun, turnServer)
    }

    private fun teardown() {
        activeManager?.close()
        activeManager = null
        activePeerId = null
        pendingOfferSdp = null
        pendingIceCandidates.clear()
        _remoteVideoTrack.value = null
        _callState.value = CallState.Idle
    }
}
