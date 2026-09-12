/**
 * Wire protocol between the Android client and the signaling server.
 *
 * IMPORTANT CONTRACT: the server never sees plaintext message content.
 * "payload" fields below are always opaque, already-encrypted blobs
 * produced by libsignal + Double Ratchet on-device. The server's job
 * is limited to routing tiny handshake/mailbox blobs by recipient ID —
 * it never inspects, logs, or persists them beyond the mailbox TTL.
 */

export type ClientMessage =
  | RegisterMessage
  | LookupMessage
  | PresenceMessage
  | SignalMessage
  | MailboxDeliverMessage
  | MailboxFetchMessage
  | TurnRequestMessage
  | UploadPreKeysMessage
  | FetchPreKeyBundleMessage
  | PingMessage;

export type ServerMessage =
  | RegisteredMessage
  | ErrorMessage
  | LookupResultMessage
  | PresenceUpdateMessage
  | SignalMessage
  | MailboxMessage
  | TurnCredentialsMessage
  | PreKeysUploadedMessage
  | PreKeyBundleResultMessage
  | PongMessage;

/** Client -> Server: claim a numeric ID + optional username, publish public key. */
export interface RegisterMessage {
  type: "register";
  numericId: string; // client-generated candidate; server only rejects on collision
  username?: string; // optional, globally unique, case-insensitive
  publicKey: string; // base64 identity public key (X25519/Ed25519), never a secret
}

export interface RegisteredMessage {
  type: "registered";
  numericId: string;
  username?: string;
}

export interface ErrorMessage {
  type: "error";
  code: "ID_TAKEN" | "USERNAME_TAKEN" | "NOT_REGISTERED" | "INVALID_MESSAGE" | "RATE_LIMITED" | "NOT_FOUND";
  message: string;
}

/** Client -> Server: look up a peer by numeric ID or username to get their pubkey + online status. */
export interface LookupMessage {
  type: "lookup";
  query: string; // numeric ID or username
}

export interface LookupResultMessage {
  type: "lookupResult";
  found: boolean;
  numericId?: string;
  username?: string;
  publicKey?: string;
  online?: boolean;
}

/** Client -> Server: heartbeat/online-status broadcast, no payload. */
export interface PresenceMessage {
  type: "presence";
  status: "online" | "away";
}

export interface PresenceUpdateMessage {
  type: "presenceUpdate";
  numericId: string;
  status: "online" | "offline" | "away";
}

/**
 * Generic signaling envelope: used for WebRTC SDP offers/answers and ICE
 * candidates during handshake. `payload` is opaque JSON from the WebRTC
 * stack — the server relays it verbatim to the target if they are online,
 * otherwise routes it to the mailbox (see MailboxMessage).
 */
export interface SignalMessage {
  type: "signal";
  to: string; // numericId of recipient
  from?: string; // filled in by server before relaying
  kind: "offer" | "answer" | "ice-candidate" | "call-end" | "encrypted-envelope";
  payload: string; // opaque (already encrypted where kind === "encrypted-envelope")
}

/** Client -> Server: hand off an encrypted blob for offline delivery. */
export interface MailboxDeliverMessage {
  type: "mailboxDeliver";
  to: string;
  payload: string; // opaque encrypted blob
}

/** Client -> Server: pull anything waiting since I was last online. */
export interface MailboxFetchMessage {
  type: "mailboxFetch";
}

export interface MailboxMessage {
  type: "mailbox";
  items: Array<{ from: string; payload: string; queuedAt: number }>;
}

export interface PingMessage {
  type: "ping";
}
export interface PongMessage {
  type: "pong";
}

/** Client -> Server: request short-lived STUN/TURN credentials before starting a WebRTC handshake. */
export interface TurnRequestMessage {
  type: "turnRequest";
}

export interface TurnCredentialsMessage {
  type: "turnCredentials";
  urls: string[];
  username: string;
  password: string;
  ttlSeconds: number;
}

/**
 * X3DH prekey bundle plumbing. The server acts purely as a public-key
 * bulletin board here — exactly like Signal's own prekey server — never
 * as a party to the key agreement itself. Everything below is public key
 * material (never a secret); the corresponding private keys never leave
 * the originating device.
 */

export interface SignedPreKeyDto {
  keyId: number;
  publicKey: string; // base64
  signature: string; // base64, Ed25519 signature by the identity key over publicKey
}

export interface OneTimePreKeyDto {
  keyId: number;
  publicKey: string; // base64
}

/** Client -> Server: publish (or top up) this device's prekey material. */
export interface UploadPreKeysMessage {
  type: "uploadPreKeys";
  registrationId: number; // libsignal per-device registration id
  identityKey: string; // base64 long-term identity public key (redundant w/ register, kept explicit for clarity)
  signedPreKey: SignedPreKeyDto;
  oneTimePreKeys: OneTimePreKeyDto[]; // batch of fresh one-time prekeys to top up the server's pool
}

export interface PreKeysUploadedMessage {
  type: "preKeysUploaded";
  remainingOneTimePreKeys: number;
}

/** Client -> Server: fetch a bundle to start X3DH with a peer (consumes one one-time prekey, if any remain). */
export interface FetchPreKeyBundleMessage {
  type: "fetchPreKeyBundle";
  numericId: string; // target peer
}

export interface PreKeyBundleResultMessage {
  type: "preKeyBundleResult";
  found: boolean;
  numericId?: string;
  registrationId?: number;
  identityKey?: string;
  signedPreKey?: SignedPreKeyDto;
  oneTimePreKey?: OneTimePreKeyDto; // absent if the pool is exhausted — X3DH degrades gracefully without one
}

