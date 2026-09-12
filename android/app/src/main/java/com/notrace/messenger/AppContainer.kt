package com.notrace.messenger

import android.content.Context
import com.notrace.messenger.crypto.signal.GroupSessionManager
import com.notrace.messenger.crypto.signal.SignalSessionManager
import com.notrace.messenger.data.ChatDao
import com.notrace.messenger.data.GroupDao
import com.notrace.messenger.data.MessageRepository
import com.notrace.messenger.data.db.DatabaseKeyProvider
import com.notrace.messenger.data.db.NoTraceDatabase
import com.notrace.messenger.data.media.AttachmentStore
import com.notrace.messenger.network.NetworkCoordinator
import com.notrace.messenger.network.SignalingClient
import com.notrace.messenger.network.webrtc.CallManager
import com.notrace.messenger.security.AppLockManager
import kotlinx.coroutines.CoroutineScope

/**
 * Deliberately not a DI framework (Hilt/Koin) — the project is small enough
 * that a hand-rolled lazy container is easier to read end-to-end. Swap for
 * Hilt in Phase 6 if the dependency graph grows past this.
 */
class AppContainer(private val context: Context, private val applicationScope: CoroutineScope) {

    companion object {
        // TODO: point at your deployed server/ instance (see server/README / Dockerfile).
        // "10.0.2.2" is the Android emulator's alias for the host machine's localhost —
        // only works for local emulator testing, and only because
        // res/xml/network_security_config.xml carves out that one address as an
        // exception to Android's default cleartext block. A real deployment MUST
        // use wss:// (not ws://) — nothing else needs that cleartext exception, and
        // it should never be widened to cover any address you'd use in production.
        const val SIGNALING_SERVER_URL = "wss://notrace1-c51xakzz.b4a.run"
    }

    val db: NoTraceDatabase by lazy {
        val passphrase = DatabaseKeyProvider(context).getOrCreatePassphrase()
        NoTraceDatabase(context, passphrase)
    }

    val sessionManager: SignalSessionManager by lazy { SignalSessionManager(db) }
    val chatDao: ChatDao by lazy { ChatDao(db) }
    val attachmentStore: AttachmentStore by lazy { AttachmentStore(context) }
    val signalingClient: SignalingClient by lazy { SignalingClient(SIGNALING_SERVER_URL) }
    val appLockManager: AppLockManager by lazy { AppLockManager(context) }

    /** Deterministic from the identity public key — see SignalSessionManager.deriveNumericId(). */
    val myNumericId: String by lazy { sessionManager.deriveNumericId() }

    // --- Phase 5: Groups (Sender Keys) ---
    val groupDao: GroupDao by lazy { GroupDao(db) }
    val groupSessionManager: GroupSessionManager by lazy { GroupSessionManager(db, myNumericId) }

    val messageRepository: MessageRepository by lazy {
        MessageRepository(
            myNumericId = myNumericId,
            sessionManager = sessionManager,
            signalingClient = signalingClient,
            chatDao = chatDao,
            attachmentStore = attachmentStore,
            groupDao = groupDao,
            groupSessionManager = groupSessionManager,
        )
    }

    val callManager: CallManager by lazy {
        CallManager(context, signalingClient, messageRepository, applicationScope)
    }

    private val networkCoordinator: NetworkCoordinator by lazy {
        NetworkCoordinator(signalingClient, messageRepository, callManager, applicationScope)
    }

    /** Call once, after first touching the container (e.g. from NoTraceApplication.onCreate). */
    fun startNetworking() {
        networkCoordinator.start()
    }
}
