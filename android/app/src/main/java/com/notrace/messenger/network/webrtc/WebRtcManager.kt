package com.notrace.messenger.network.webrtc

import android.content.Context
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * One instance per active peer connection. Handles THREE things a call/
 * chat session needs:
 *   1. A WebRTC data channel for direct P2P encrypted-envelope delivery
 *      (Section 3) — falls back to the signaling server's mailbox relay
 *      whenever this isn't open (peer offline, NAT traversal incomplete).
 *   2. Local audio/video capture + tracks, for voice/video calls (Phase 4).
 *   3. The raw PeerConnection plumbing (offer/answer/ICE) that
 *      [CallManager] drives using signal messages relayed by the server.
 *
 * ICE/STUN/TURN servers come from the coturn instance described in
 * server/coturn/turnserver.conf.example; fetch short-lived credentials
 * via SignalingClient before constructing [iceServers] in production
 * (a public STUN-only fallback is included here for bring-up/testing).
 */
class WebRtcManager(
    private val context: Context,
    private val iceServers: List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
    ),
    private val onIceCandidate: (IceCandidate) -> Unit,
    private val onDataChannelMessage: (ByteArray) -> Unit,
    private val onConnectionStateChanged: (PeerConnection.PeerConnectionState) -> Unit,
    private val onRemoteVideoTrack: (VideoTrack) -> Unit = {},
) {
    val eglBase: EglBase = EglBase.create()

    private val factory: PeerConnectionFactory = run {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions(),
        )
        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    fun createConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peerConnection = factory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) = onIceCandidate(candidate)
                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) =
                    onConnectionStateChanged(newState)
                override fun onDataChannel(channel: DataChannel) = observeDataChannel(channel)
                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                    (receiver.track() as? VideoTrack)?.let { track -> onRemoteVideoTrack(track) }
                }

                override fun onSignalingChange(p0: PeerConnection.SignalingState) {}
                override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState) {}
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState) {}
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>) {}
                override fun onAddStream(p0: MediaStream) {}
                override fun onRemoveStream(p0: MediaStream) {}
                override fun onRenegotiationNeeded() {}
            },
        )
    }

    /** Caller (the offer side) creates the data channel; the answer side receives it via onDataChannel. */
    fun createDataChannel(label: String = "notrace-messages") {
        val init = DataChannel.Init().apply { ordered = true }
        val channel = peerConnection?.createDataChannel(label, init) ?: return
        observeDataChannel(channel)
    }

    private fun observeDataChannel(channel: DataChannel) {
        dataChannel = channel
        channel.registerObserver(
            object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) {}
                override fun onStateChange() {}
                override fun onMessage(buffer: DataChannel.Buffer) {
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    onDataChannelMessage(bytes)
                }
            },
        )
    }

    fun sendBytes(bytes: ByteArray): Boolean {
        val channel = dataChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))
        return true
    }

    fun isDataChannelOpen(): Boolean = dataChannel?.state() == DataChannel.State.OPEN

    /** Always adds an audio track; video is opt-in since a plain voice call is the common case. */
    fun addLocalMedia(withVideo: Boolean, localRenderer: SurfaceViewRenderer? = null) {
        val pc = peerConnection ?: return

        audioSource = factory.createAudioSource(MediaConstraints())
        localAudioTrack = factory.createAudioTrack("notrace-audio", audioSource).also { pc.addTrack(it) }

        if (withVideo) {
            val enumerator = Camera2Enumerator(context)
            val cameraName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
                ?: return // no camera available — caller should fall back to audio-only
            videoCapturer = enumerator.createCapturer(cameraName, null)

            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
            videoSource = factory.createVideoSource(false)
            videoCapturer?.initialize(surfaceTextureHelper, context, videoSource!!.capturerObserver)
            videoCapturer?.startCapture(1280, 720, 30)

            localVideoTrack = factory.createVideoTrack("notrace-video", videoSource).also { pc.addTrack(it) }
            localRenderer?.let { renderer ->
                renderer.init(eglBase.eglBaseContext, null)
                localVideoTrack?.addSink(renderer)
            }
        }
    }

    fun initRemoteRenderer(renderer: SurfaceViewRenderer) {
        renderer.init(eglBase.eglBaseContext, null)
    }

    fun setMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun createOffer(onSdpReady: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints()
        peerConnection?.createOffer(
            object : SdpObserverAdapter() {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    peerConnection?.setLocalDescription(SdpObserverAdapter(), sdp)
                    onSdpReady(sdp)
                }
            },
            constraints,
        )
    }

    fun createAnswer(onSdpReady: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints()
        peerConnection?.createAnswer(
            object : SdpObserverAdapter() {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    peerConnection?.setLocalDescription(SdpObserverAdapter(), sdp)
                    onSdpReady(sdp)
                }
            },
            constraints,
        )
    }

    fun setRemoteDescription(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(SdpObserverAdapter(), sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun close() {
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        surfaceTextureHelper?.dispose()
        videoSource?.dispose()
        audioSource?.dispose()
        dataChannel?.close()
        peerConnection?.close()
        peerConnection = null
        dataChannel = null
        // BUGFIX (audit): eglBase.release() and factory.dispose() were never
        // called here — every single call (each gets its own WebRtcManager,
        // its own PeerConnectionFactory, its own EglBase) leaked a native EGL
        // context and the factory's internal threads/encoders/decoders for
        // the rest of the process lifetime. Over a session with several
        // calls this degrades (and can eventually crash) the app. Order
        // matters: dispose the factory before releasing the EGL base it was
        // built from.
        factory.dispose()
        eglBase.release()
    }

    companion object {
        /** Helper for encoding text payloads sent over the data channel, if ever needed outside encrypt/decrypt. */
        fun stringToBytes(s: String): ByteArray = s.toByteArray(StandardCharsets.UTF_8)
    }
}

private open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {}
    override fun onSetFailure(error: String) {}
}
