/**
 * NoTrace Messenger — Signaling Server
 *
 * Responsibilities (and ONLY these):
 *   1. Presence: track which numeric IDs are currently connected.
 *   2. Registration: enforce global uniqueness of numeric ID + username,
 *      store nothing but a public key mapping, in RAM.
 *   3. Handshake relay: forward WebRTC offer/answer/ICE-candidate blobs
 *      between two online peers so they can open a direct data channel.
 *   4. Offline mailbox: hold encrypted blobs for a recipient who is
 *      offline, for up to 72h, then discard unconditionally.
 *
 * This process never touches message plaintext, never writes to disk,
 * and never persists across restarts. STUN/TURN (NAT traversal) is
 * handled by a separate coturn instance — see coturn/turnserver.conf.example.
 */

import { WebSocketServer, WebSocket } from "ws";
import { Registry } from "./registry";
import { Mailbox } from "./mailbox";
import { PreKeyStore } from "./prekeys";
import { mintTurnCredentials } from "./utils/turnCredentials";
import type {
  ClientMessage,
  ServerMessage,
  RegisterMessage,
  LookupMessage,
  SignalMessage,
  MailboxDeliverMessage,
  UploadPreKeysMessage,
  FetchPreKeyBundleMessage,
} from "./types";

const PORT = Number(process.env.PORT ?? 8080);
const HEARTBEAT_INTERVAL_MS = 30_000;

// TURN (coturn) config — see coturn/turnserver.conf.example. The shared
// secret must match that file's `static-auth-secret`. Never log this value.
const TURN_URLS = (process.env.TURN_URLS ?? "turn:localhost:3478").split(",");
const TURN_SHARED_SECRET = process.env.TURN_SHARED_SECRET ?? "";

const registry = new Registry();
const mailbox = new Mailbox();
const preKeys = new PreKeyStore();

const wss = new WebSocketServer({ port: PORT });
console.log(`[notrace-signal] listening on :${PORT} (RAM-only, zero disk persistence)`);

// Track which numericId owns which socket, for cleanup on disconnect.
const socketOwner = new WeakMap<WebSocket, string>();

wss.on("connection", (socket: WebSocket) => {
  let alive = true;
  socket.on("pong", () => (alive = true));

  const heartbeat = setInterval(() => {
    if (!alive) {
      socket.terminate();
      return;
    }
    alive = false;
    socket.ping();
  }, HEARTBEAT_INTERVAL_MS);

  socket.on("message", (raw) => {
    let msg: ClientMessage;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      send(socket, { type: "error", code: "INVALID_MESSAGE", message: "Malformed JSON" });
      return;
    }
    handleMessage(socket, msg);
  });

  socket.on("close", () => {
    clearInterval(heartbeat);
    const numericId = socketOwner.get(socket);
    if (numericId) {
      // Mark offline but KEEP the registration (username/ID reservation
      // persists for the session's lifetime so it can't be squatted the
      // instant someone's phone loses signal) — only the socket is cleared.
      registry.markSocket(numericId, null);
      broadcastPresence(numericId, "offline");
    }
  });
});

function handleMessage(socket: WebSocket, msg: ClientMessage) {
  switch (msg.type) {
    case "register":
      return handleRegister(socket, msg);
    case "lookup":
      return handleLookup(socket, msg);
    case "presence": {
      const numericId = socketOwner.get(socket);
      if (numericId) broadcastPresence(numericId, msg.status === "online" ? "online" : "away");
      return;
    }
    case "signal":
      return handleSignal(socket, msg);
    case "mailboxDeliver":
      return handleMailboxDeliver(socket, msg);
    case "mailboxFetch": {
      const numericId = socketOwner.get(socket);
      if (!numericId) return send(socket, { type: "error", code: "NOT_REGISTERED", message: "Register first" });
      const items = mailbox.drain(numericId);
      send(socket, { type: "mailbox", items });
      return;
    }
    case "turnRequest": {
      const numericId = socketOwner.get(socket);
      if (!numericId) return send(socket, { type: "error", code: "NOT_REGISTERED", message: "Register first" });
      if (!TURN_SHARED_SECRET) {
        return send(socket, { type: "error", code: "INVALID_MESSAGE", message: "TURN not configured on this server" });
      }
      const creds = mintTurnCredentials(numericId, TURN_SHARED_SECRET);
      return send(socket, { type: "turnCredentials", urls: TURN_URLS, ...creds });
    }
    case "uploadPreKeys":
      return handleUploadPreKeys(socket, msg);
    case "fetchPreKeyBundle":
      return handleFetchPreKeyBundle(socket, msg);
    case "ping":
      return send(socket, { type: "pong" });
    default:
      send(socket, { type: "error", code: "INVALID_MESSAGE", message: "Unknown message type" });
  }
}

function handleRegister(socket: WebSocket, msg: RegisterMessage) {
  if (!msg.numericId || !msg.publicKey) {
    return send(socket, { type: "error", code: "INVALID_MESSAGE", message: "numericId and publicKey required" });
  }
  const result = registry.register(msg.numericId, msg.publicKey, socket, msg.username);
  if (!result.ok) {
    return send(socket, {
      type: "error",
      code: result.code,
      message: result.code === "ID_TAKEN" ? "Numeric ID already registered" : "Username already taken",
    });
  }
  socketOwner.set(socket, msg.numericId);
  send(socket, { type: "registered", numericId: msg.numericId, username: msg.username });
  broadcastPresence(msg.numericId, "online");

  // Deliver anything queued while this peer was offline.
  const queued = mailbox.drain(msg.numericId);
  if (queued.length > 0) {
    send(socket, { type: "mailbox", items: queued });
  }
}

function handleLookup(socket: WebSocket, msg: LookupMessage) {
  const peer = registry.lookup(msg.query);
  if (!peer) {
    return send(socket, { type: "lookupResult", found: false });
  }
  send(socket, {
    type: "lookupResult",
    found: true,
    numericId: peer.numericId,
    username: peer.username,
    publicKey: peer.publicKey,
    online: peer.socket !== null,
  });
}

function handleSignal(socket: WebSocket, msg: SignalMessage) {
  const from = socketOwner.get(socket);
  if (!from) return send(socket, { type: "error", code: "NOT_REGISTERED", message: "Register first" });

  const target = registry.get(msg.to);
  if (!target) return send(socket, { type: "error", code: "NOT_FOUND", message: "Recipient not found" });

  const envelope: SignalMessage = { ...msg, from };

  if (target.socket) {
    send(target.socket, envelope);
  } else if (msg.kind === "encrypted-envelope") {
    // Recipient offline and this is application-layer content (not raw
    // WebRTC signaling, which is meaningless once offline) — queue it.
    mailbox.enqueue(msg.to, from, msg.payload);
  }
  // Non-envelope signaling (offer/answer/ice) to an offline peer is simply
  // dropped — there's no live handshake to complete.
}

function handleMailboxDeliver(socket: WebSocket, msg: MailboxDeliverMessage) {
  const from = socketOwner.get(socket);
  if (!from) return send(socket, { type: "error", code: "NOT_REGISTERED", message: "Register first" });
  const target = registry.get(msg.to);
  if (!target) return send(socket, { type: "error", code: "NOT_FOUND", message: "Recipient not found" });

  if (target.socket) {
    send(target.socket, { type: "signal", to: msg.to, from, kind: "encrypted-envelope", payload: msg.payload });
  } else {
    mailbox.enqueue(msg.to, from, msg.payload);
  }
}

function handleUploadPreKeys(socket: WebSocket, msg: UploadPreKeysMessage) {
  const numericId = socketOwner.get(socket);
  if (!numericId) return send(socket, { type: "error", code: "NOT_REGISTERED", message: "Register first" });
  if (!msg.signedPreKey || !Array.isArray(msg.oneTimePreKeys)) {
    return send(socket, { type: "error", code: "INVALID_MESSAGE", message: "signedPreKey and oneTimePreKeys required" });
  }
  const remaining = preKeys.upload(
    numericId,
    msg.registrationId,
    msg.identityKey,
    msg.signedPreKey,
    msg.oneTimePreKeys,
  );
  send(socket, { type: "preKeysUploaded", remainingOneTimePreKeys: remaining });
}

function handleFetchPreKeyBundle(socket: WebSocket, msg: FetchPreKeyBundleMessage) {
  const from = socketOwner.get(socket);
  if (!from) return send(socket, { type: "error", code: "NOT_REGISTERED", message: "Register first" });

  const bundle = preKeys.consumeBundle(msg.numericId);
  if (!bundle) {
    return send(socket, { type: "preKeyBundleResult", found: false });
  }
  send(socket, {
    type: "preKeyBundleResult",
    found: true,
    numericId: msg.numericId,
    registrationId: bundle.registrationId,
    identityKey: bundle.identityKey,
    signedPreKey: bundle.signedPreKey,
    oneTimePreKey: bundle.oneTimePreKey,
  });
}

function broadcastPresence(numericId: string, status: "online" | "offline" | "away") {
  // Minimal fan-out: in a production build, only notify peers who have an
  // active session/contact relationship with `numericId`, tracked client-side.
  // This reference implementation notifies nobody by default to avoid
  // leaking a global "who's online" graph — wire up a contacts-aware
  // notify list here before shipping.
  void numericId;
  void status;
}

function send(socket: WebSocket, msg: ServerMessage) {
  if (socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(msg));
  }
}

process.on("SIGTERM", () => {
  console.log("[notrace-signal] shutting down — nothing to flush, RAM-only by design");
  wss.close(() => process.exit(0));
});
