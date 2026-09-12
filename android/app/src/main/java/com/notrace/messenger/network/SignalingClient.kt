package com.notrace.messenger.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Thin client for the NoTrace signaling server (see /server in this repo).
 * Handles connect/reconnect and JSON (de)serialization of the wire protocol
 * defined in server/src/types.ts. Business logic (what to do with a
 * decoded message) lives in [com.notrace.messenger.data.MessageRepository]
 * — this class is intentionally "dumb transport".
 */
class SignalingClient(private val serverUrl: String) {

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS) // matches server's HEARTBEAT_INTERVAL_MS
        .build()

    private var webSocket: WebSocket? = null
    private val pendingBundleRequests = ConcurrentHashMap<String, CompletableDeferred<JSONObject?>>()

    /**
     * SECURITY/CORRECTNESS FIX (Phase 6): `register()`/`uploadPreKeys()` used
     * to be called immediately on app launch with no guarantee the
     * underlying WebSocket had actually finished connecting yet — OkHttp's
     * `newWebSocket()` returns instantly but connects asynchronously, so
     * `webSocket?.send(...)` could silently no-op if called too early,
     * leaving a device unregistered / without published prekeys with no
     * error surfaced anywhere. `connectionOpen` + `awaitConnected()` below
     * close that gap; see SplashScreen for the call site.
     */
    private val connectionOpen = MutableStateFlow(false)

    // Phase 5 (Contact/QR Add screen): `lookupResult` (server/src/types.ts)
    // doesn't echo the original query, so — like requestPreKeyBundle below —
    // only one lookup can be in flight at a time; that's all a single search
    // field needs. A single-slot queue rather than a map, unlike
    // pendingBundleRequests, precisely because there's no key to correlate on.
    private val pendingLookup = java.util.concurrent.atomic.AtomicReference<CompletableDeferred<JSONObject>?>(null)

    // BUGFIX (audit): the server has always supported minting short-lived
    // TURN credentials (see server/src/index.ts's "turnRequest" handler +
    // utils/turnCredentials.ts), but nothing on the client ever asked for
    // them — every call was built with STUN-only ICE servers, so calls
    // silently failed for the ~10-15% of networks that need a TURN relay
    // to complete the handshake at all. This adds the missing request/
    // response correlation, same single-slot pattern as `pendingLookup`
    // (only ever one call being set up at a time).
    private val pendingTurnRequest = java.util.concurrent.atomic.AtomicReference<CompletableDeferred<JSONObject>?>(null)

    /** Emits decoded server messages as JSONObject; callers switch on `type`. */
    fun connect(): Flow<JSONObject> = callbackFlow {
        val request = Request.Builder().url(serverUrl).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                connectionOpen.value = true
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val json = JSONObject(text)
                // Prekey-bundle responses are correlated directly with their
                // request (see requestPreKeyBundle below) rather than routed
                // through the general flow — nothing else needs to see them.
                if (json.optString("type") == "preKeyBundleResult") {
                    val numericId = json.optString("numericId", null)
                    pendingBundleRequests.remove(numericId)?.complete(
                        if (json.optBoolean("found", false)) json else null,
                    )
                    return
                }
                if (json.optString("type") == "lookupResult") {
                    pendingLookup.getAndSet(null)?.complete(json)
                    return
                }
                if (json.optString("type") == "turnCredentials") {
                    pendingTurnRequest.getAndSet(null)?.complete(json)
                    return
                }
                trySend(json)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                connectionOpen.value = false
                close(t) // let NetworkCoordinator's reconnect-with-backoff loop take it from here
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                connectionOpen.value = false
                close()
            }
        }
        webSocket = client.newWebSocket(request, listener)
        awaitClose { webSocket?.close(1000, "client closing"); webSocket = null; connectionOpen.value = false }
    }

    /**
     * Suspends until the WebSocket has actually finished its handshake (or
     * the timeout elapses, returning false). Requires an active `connect()`
     * collector elsewhere in the app — same requirement every other
     * suspend function here has (see NetworkCoordinator, which starts
     * exactly one such collector for the process lifetime).
     */
    suspend fun awaitConnected(timeoutMs: Long = 15_000): Boolean {
        if (connectionOpen.value) return true
        return withTimeoutOrNull(timeoutMs) { connectionOpen.filter { it }.first() } != null
    }

    /**
     * Sends `fetchPreKeyBundle` and suspends for the matching
     * `preKeyBundleResult` (or null on a 10s timeout / not-found response).
     * Requires an active `connect()` collector elsewhere in the app for the
     * underlying WebSocketListener to be receiving messages at all.
     */
    suspend fun requestPreKeyBundle(numericId: String): JSONObject? {
        val deferred = CompletableDeferred<JSONObject?>()
        pendingBundleRequests[numericId] = deferred
        fetchPreKeyBundle(numericId)
        return withTimeoutOrNull(10_000) { deferred.await() } ?: run {
            pendingBundleRequests.remove(numericId)
            null
        }
    }

    fun send(json: JSONObject) {
        webSocket?.send(json.toString())
    }

    // --- Convenience builders for each ClientMessage variant (see types.ts) ---

    fun register(numericId: String, publicKey: String, username: String? = null) {
        send(
            JSONObject().apply {
                put("type", "register")
                put("numericId", numericId)
                put("publicKey", publicKey)
                username?.let { put("username", it) }
            },
        )
    }

    fun lookup(query: String) {
        send(JSONObject().apply { put("type", "lookup"); put("query", query) })
    }

    /**
     * Suspends for the matching `lookupResult` (or `found=false` after a 10s
     * timeout), for the Contact/QR Add screen's search-by-ID/username
     * (Section 4: "Discovery/search option"). Requires an active `connect()`
     * collector elsewhere — same requirement `requestPreKeyBundle` has.
     */
    suspend fun requestLookup(query: String): JSONObject {
        val deferred = CompletableDeferred<JSONObject>()
        pendingLookup.set(deferred)
        lookup(query)
        return withTimeoutOrNull(10_000) { deferred.await() }
            ?: JSONObject().apply { put("type", "lookupResult"); put("found", false) }
    }

    /**
     * Suspends for the matching `turnCredentials` (or null on a 10s timeout,
     * e.g. the server has no TURN_SHARED_SECRET configured — see
     * server/src/index.ts). Requires an active `connect()` collector
     * elsewhere, same requirement every other suspend function here has.
     */
    suspend fun requestTurnCredentials(): JSONObject? {
        val deferred = CompletableDeferred<JSONObject>()
        pendingTurnRequest.set(deferred)
        send(JSONObject().apply { put("type", "turnRequest") })
        return withTimeoutOrNull(10_000) { deferred.await() } ?: run {
            pendingTurnRequest.compareAndSet(deferred, null)
            null
        }
    }

    fun uploadPreKeys(
        registrationId: Int,
        identityKey: String,
        signedPreKeyId: Int,
        signedPreKeyPublic: String,
        signedPreKeySignature: String,
        oneTimePreKeys: List<Pair<Int, String>>,
    ) {
        send(
            JSONObject().apply {
                put("type", "uploadPreKeys")
                put("registrationId", registrationId)
                put("identityKey", identityKey)
                put(
                    "signedPreKey",
                    JSONObject().apply {
                        put("keyId", signedPreKeyId)
                        put("publicKey", signedPreKeyPublic)
                        put("signature", signedPreKeySignature)
                    },
                )
                put(
                    "oneTimePreKeys",
                    JSONArray().apply {
                        oneTimePreKeys.forEach { (keyId, publicKey) ->
                            put(JSONObject().apply { put("keyId", keyId); put("publicKey", publicKey) })
                        }
                    },
                )
            },
        )
    }

    fun fetchPreKeyBundle(numericId: String) {
        send(JSONObject().apply { put("type", "fetchPreKeyBundle"); put("numericId", numericId) })
    }

    /** kind: "offer" | "answer" | "ice-candidate" | "encrypted-envelope" */
    fun signal(to: String, kind: String, payload: String) {
        send(
            JSONObject().apply {
                put("type", "signal")
                put("to", to)
                put("kind", kind)
                put("payload", payload)
            },
        )
    }

    fun mailboxDeliver(to: String, payload: String) {
        send(JSONObject().apply { put("type", "mailboxDeliver"); put("to", to); put("payload", payload) })
    }

    fun mailboxFetch() {
        send(JSONObject().apply { put("type", "mailboxFetch") })
    }

    fun presence(status: String) {
        send(JSONObject().apply { put("type", "presence"); put("status", status) })
    }
}
