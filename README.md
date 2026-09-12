# NoTrace Messenger — Phase 1 through Phase 5

```
notrace-messenger/
├── server/     Signaling/rendezvous server (Node.js + TypeScript + ws)
└── android/    Android client (Kotlin + Jetpack Compose)
```

## Phase 1 — Foundation
- Signaling server: registration, numeric-ID/username uniqueness, presence,
  WebRTC handshake relay, 72h TTL RAM-only offline mailbox, TURN credential
  minting (`utils/turnCredentials.ts`), coturn config template.
- Android: Compose UI shell, palette/typography from the spec, `FLAG_SECURE`
  screenshot blocking, Gradle scaffolding with Phase 2+ dependencies pre-declared.

## Phase 2 — Core Messaging (this pass)

### Signaling server additions
- `src/prekeys.ts` — RAM-only prekey bulletin board (signed prekey + a pool
  of one-time prekeys per device), exactly Signal's own prekey-server pattern.
- `uploadPreKeys` / `fetchPreKeyBundle` message types and handlers wired
  into `index.ts`, letting a device start X3DH with a peer asynchronously,
  even while that peer is offline.

### Android additions
- **`crypto/signal/`** — real libsignal-client integration:
  `SqlIdentityKeyStore`, `SqlPreKeyStore`, `SqlSignedPreKeyStore`,
  `SqlSessionStore` (SQLCipher-backed implementations of libsignal's store
  interfaces) and `SignalSessionManager`, the single entry point for X3DH
  session bootstrap + Double Ratchet encrypt/decrypt. This replaces Phase 1's
  placeholder random-bytes `IdentityKeyManager`, which has been removed.
- **`data/db/`** — `NoTraceDatabase` (SQLCipher schema: conversations,
  messages, and the four `signal_*` tables backing the stores above) and
  `DatabaseKeyProvider` (Keystore-wrapped random passphrase, same
  envelope-encryption pattern as the identity key in Phase 1).
- **`network/`** — `SignalingClient` (OkHttp WebSocket client, with
  request/response correlation for prekey-bundle fetches over an otherwise
  fire-and-forget socket), `NetworkCoordinator` (owns the one long-lived
  connection, routes incoming envelopes/mailbox drains to the repository),
  and `network/webrtc/WebRtcManager` (peer connection + data channel setup
  for direct P2P delivery, STUN-only by default — wire in your coturn TURN
  credentials via `SignalingClient` for the ~10-15% of connections that
  need a relay).
- **`data/MessageRepository.kt`** — the orchestration seam: on send, builds
  an X3DH session if none exists, Double-Ratchet-encrypts, tries the WebRTC
  data channel first and falls back to the signaling server's mailbox, then
  persists. On receive, reverses the process. Wire envelope format is
  `<1-byte type flag><ciphertext>`, base64'd as the opaque payload the
  server relays without ever inspecting.
- **`ui/screens/ChatThreadScreen.kt`** — new: bubble thread + input bar,
  wired to `MessageRepository.sendMessage`/`getMessages`.
- **`ui/screens/ChatListScreen.kt`** — now reads real conversations via
  `MessageRepository.getConversations()` instead of Phase 1's mock data.
- **`AppContainer.kt`** — small hand-rolled DI container (not a framework;
  swap for Hilt in Phase 6 if the graph grows) wiring the DB, session
  manager, signaling client, repository, and network coordinator as
  singletons off `NoTraceApplication`.

### To run locally
```bash
cd server && npm install && npm run dev
```
Then point the Android client at it: `AppContainer.SIGNALING_SERVER_URL`
defaults to `ws://10.0.2.2:8080` (the Android emulator's alias for your
host machine) — change it for a real device or deployed server.

## Phase 3 — Disappearing Messages & Privacy Controls (this pass)

### Disappearing messages
- `conversations.disappearing_seconds` (already in the Phase 1 schema) is
  now actually read/written: `ChatDao.setDisappearingSeconds` /
  `getDisappearingSeconds`, exposed via `MessageRepository.setDisappearingTimer`.
- `ChatDao.insertMessage` computes each message's `expires_at` from the
  conversation's *current* timer setting at send/receipt time — changing
  the timer only affects messages from that point forward, never retroactively.
- `DisappearingOption` enum holds the four fixed choices from the spec
  (1 day / 1 week / 1 month / 1 year) plus off; `ChatThreadScreen`'s app
  bar now has a dropdown to set it per-conversation.
- `workers/DisappearingMessageWorker.kt` — a WorkManager periodic job
  (every 15 minutes, WorkManager's minimum) that permanently deletes
  anything past its `expires_at`. Scheduled once from `NoTraceApplication.onCreate`.
- `NoTraceDatabase` now sets `PRAGMA secure_delete = ON` in `onConfigure`,
  so deleted rows are actually overwritten in place rather than just
  unlinked and left recoverable in freed SQLite pages — this is what makes
  the disappearing-message deletes (and the wipe below) mean what they claim.

### App lock
- `security/AppLockManager.kt` — tracks whether app-lock is enabled
  (EncryptedSharedPreferences) and whether the app is currently locked
  (in-memory only, never persisted — a fresh process always comes up
  locked if enabled, no "remember unlocked" state).
- `MainActivity` now shows `ui/screens/LockScreen.kt` instead of the nav
  graph whenever locked, re-arms the lock on every `onStop()` (no grace
  period, matching Signal's default), and drives a `BiometricPrompt`
  (biometric-or-device-credential) to unlock.
- `ui/screens/SettingsScreen.kt` (new) — toggle for app lock, plus the
  secure-wipe entry point below. Reachable from a "Settings" action on
  the chat list's top bar.

### Secure wipe
- `security/SecureWipeManager.kt` — the panic-button primitive: deletes
  the SQLCipher database file, every EncryptedSharedPreferences file this
  app writes, and every AndroidKeyStore alias it created. Deliberately
  blunt and irreversible — not a "log out", a "there is nothing left to
  recover" action. Wired to a confirm-twice button in Settings.

## Known gaps / honest caveats going into Phase 4
1. Everything from Phase 2's caveats still applies (libsignal API surface
   unverified against a real Gradle sync; registration race on first
   connect; WebRTC offer/answer signaling not yet wired to `NetworkCoordinator`).
2. **App lock's `DEVICE_CREDENTIAL` + `BIOMETRIC_STRONG` combined prompt**
   requires API 30+ under the hood for some device credential fallback
   paths — worth testing explicitly on an API 26-29 device/emulator given
   `minSdk = 26`, and adding a `KeyguardManager`-based fallback if needed.
3. **No per-message "time left" countdown UI** — messages with an active
   timer just show a static "Disappears" label; a live countdown (as
   Signal shows) is cosmetic polish, not core to the feature, so it's
   left for Phase 6.
4. **Group conversations** aren't threaded through the disappearing-timer
   or app-lock code paths yet — both were written against the
   single-peer `conversations` row Phase 1/2 already had; Phase 5 (Groups)
   will need to confirm nothing here assumes exactly one peer per conversation.
5. **No Contact/QR Add screen yet** (Section 5, Core Screens #5) — for now,
   start a thread by navigating to `chat_thread/{numericId}` directly once
   you know a peer's numeric ID (visible via `SignalSessionManager.deriveNumericId()`
   on their device, or a `lookup` call by username once that UI exists).

## Phase 4 — Rich Media & Calling (this pass)

### Closing the Phase 2 WebRTC signaling gap (prerequisite for calling)
- `network/webrtc/CallManager.kt` (new) — owns the offer/answer/ICE
  exchange and the resulting per-peer `WebRtcManager`, driven by `signal`
  messages the server already relayed since Phase 1 (`kind` in
  `offer`/`answer`/`ice-candidate`/`call-end`; `call-end` added to the
  server's `SignalMessage.kind` union this pass).
- `NetworkCoordinator` now routes non-`encrypted-envelope` `signal`
  messages to `CallManager` instead of dropping them.
- Once a call's data channel reaches `CONNECTED`, `CallManager` hands it to
  `MessageRepository.attachTransport` — so text messages start riding the
  direct P2P link too, not just calls. This is the "direct P2P fast path"
  Phase 2's README flagged as unfinished.

### Voice/video calling
- `WebRtcManager` extended with local audio/video track creation
  (Camera2 capturer for video) and remote-track callbacks.
- `ui/screens/CallScreen.kt` (new) — incoming/outgoing/active call UI,
  accept/decline/hang-up, renders remote video when present.
- Incoming calls are caught by a top-level `CallManager.callState`
  observer in `NavGraph` (not just a button press), so a ringing screen
  appears no matter where in the app you are — **except** while app-lock
  has the app locked (see caveats).
- The wired-up call button in `ChatThreadScreen` starts an **audio-only**
  call after requesting `RECORD_AUDIO` at runtime; the video-call code
  path exists in `WebRtcManager`/`CallManager` but nothing in the UI
  currently offers a "video call" button or requests `CAMERA` at runtime
  — a deliberately scoped-down slice, not a bug.

### Media attachments (images/files)
- `data/media/MediaCrypto.kt` — Signal's own attachment scheme: a fresh
  random 64-byte key per file (32-byte AES-256-CBC key + 32-byte HMAC-
  SHA256 key), never reused, never derived from the identity/session keys.
  Encrypt-then-MAC; `decrypt()` throws on any tampering before touching ciphertext.
- `data/media/AttachmentStore.kt` — encrypted blobs live under the app's
  private `filesDir/attachments/`; `secureDelete()` zeroes the file before
  unlinking it, same spirit as the DB's `secure_delete` pragma.
- `messages` table gained `attachment_local_path` / `attachment_mime_type`
  / `attachment_size_bytes` / `attachment_media_key` columns (DB bumped to
  version 2, with an `onUpgrade` migration for anyone who already ran a
  Phase 1-3 build).
- Wire protocol: every transport byte array now starts with a 1-byte flag
  (`0`/`1` = existing Double-Ratchet text flags, `2` = raw attachment
  chunk). An attachment transfer is always two messages: a small
  Double-Ratchet-encrypted "control" message (id/mime/size/key — can
  travel via mailbox like any text) plus the flag-2 raw encrypted bytes
  (WebRTC data channel ONLY, no mailbox fallback — see caveats).
  `MessageRepository` reconciles whichever half arrives first via
  `pendingControls`/`pendingChunks`.
- `ChatThreadScreen` gained an attach button (system photo picker, no
  extra runtime permission needed) and renders image attachments inline,
  decrypting on demand via `MessageRepository.decryptAttachment`.

## Known gaps / honest caveats going into Phase 5
1. Everything from Phase 2/3's caveats still applies unless noted fixed
   above (libsignal API surface unverified against a real Gradle sync;
   registration race on first connect).
2. **Attachment delivery requires the recipient online at send time** —
   the raw bytes only travel over an open data channel, with no durable
   retry queue (an in-memory-only "send when it opens" hook exists but is
   lost if the app process dies first). The control message (so the
   recipient at least learns an attachment exists) does use the mailbox
   and will arrive even if they're offline.
3. **Incoming calls don't ring through the lock screen** — `CallManager`
   itself keeps working in the background regardless of app-lock state,
   but the `CallScreen` navigation only fires once `NoTraceNavGraph` is
   composed, which app-lock prevents until unlocked. Worth fixing before
   relying on this for real calls.
4. **Video calling is architecturally wired but not exposed in the UI** —
   see the calling section above.
5. **No call notification / foreground service** — a real messenger needs
   a foreground service (and full-screen intent for locked-screen ringing)
   to keep a call's audio alive if the app backgrounds; not implemented here.
6. **Group conversations, Contact/QR Add screen**: still Phase 5 items,
   unchanged from before.

## Phase 5 — Groups (this pass)

No signaling-server changes this phase — deliberately. Group *messages* and
group *membership/key-distribution* both ride entirely over transport the
server already exposes (`mailboxDeliver`/`signal` per-recipient), which
keeps the "even the lightweight relay learns as little as possible"
principle (Section 3) intact: the server still never becomes aware that a
group exists at all, let alone who's in it.

### Cryptography: real Sender Keys, not naive N-way pairwise
- `crypto/signal/SqlSenderKeyStore.kt` (new) — SQLCipher-backed
  `SenderKeyStore` for libsignal, same pattern as Phase 2's `SqlSessionStore`.
- `crypto/signal/GroupSessionManager.kt` (new) — wraps libsignal's
  `GroupSessionBuilder`/`GroupCipher`. Every member generates ONE Sender Key
  per group and distributes it pairwise (over the *existing* 1:1 Double
  Ratchet channel) to every other member; from then on that member's group
  messages are encrypted ONCE and the identical ciphertext fans out to
  everyone — exactly Signal's real production design, not a simplified
  stand-in for it (Section 8.4).
- `data/db/NoTraceDatabase.kt` bumped to version 3: `signal_sender_keys`
  table (the store above), `group_members` table, and a
  `conversations.own_sender_key_distributed` flag — with an `onUpgrade`
  migration for anyone who already ran a Phase 1-4 build.

### Wire protocol additions (`data/MessageRepository.kt`)
- New wire flag `3` = a group message: `[3][16-byte groupId/distributionId
  UUID][GroupCipher ciphertext]`. Unlike the flag-2 attachment chunk it sits
  next to, this DOES have a mailbox fallback — there's no reason a group
  message should need every member online at once.
- Group *key distribution* piggybacks on the plain 1:1 text channel as
  prefixed control strings (`NOTRACE_GROUP_INVITE_V1:`,
  `NOTRACE_GROUP_SKDM_V1:`, `NOTRACE_GROUP_LEAVE_V1:`), the same idiom
  Phase 4's `NOTRACE_ATTACHMENT_V1:` already established. Membership stays
  eventually-consistent by design: an invite that names a member you didn't
  already know about triggers your own Sender Key (if you've sent one
  already) to be forwarded to them too, and receiving someone else's Sender
  Key for the first time triggers you to reciprocate yours back — full
  mesh, no coordinator.
- `data/GroupDao.kt` (new) — membership rows + the
  `own_sender_key_distributed` flag `sendGroupMessage` checks before every
  send. A group conversation is otherwise an ordinary `conversations` row
  (`is_group = 1`); `ChatDao` needed no changes to store/serve its message
  history, disappearing timer, or name — Phase 3's caveat #4 asking Phase 5
  to "confirm nothing here assumes exactly one peer per conversation" checks
  out clean.

### Android UI
- `ui/screens/GroupCreateScreen.kt` (new) — Section 5 Core Screen #8:
  multi-select existing 1:1 contacts, name the group, create.
- `ui/screens/GroupInfoScreen.kt` (new) — membership list, add member by
  numeric ID, leave group (local departure + best-effort notice to
  remaining members — there's no server-side group state to clean up).
- `ui/screens/ChatThreadScreen.kt` — now renders group conversations too:
  same bubble thread and disappearing-timer control (both already
  conversation-level), sends via `sendGroupMessage` instead of
  `sendMessage`, title opens Group Info instead of starting a call, and
  hides the call button and attach button for groups (see caveats).
- `ui/screens/ContactAddScreen.kt` (new) — closes Section 5 Core Screen #5,
  flagged as a gap since Phase 2: search-by-numeric-ID/username (wired to
  the `lookup` message the server has supported since Phase 1 — see
  `SignalingClient.requestLookup`, new) plus a "Show my code" tab. Groups
  need contacts to select from, so this was worth closing alongside Groups
  rather than after.
- `ui/screens/ChatListScreen.kt` — "New" menu (New chat -> Contact Add, New
  group -> Group Create) replaces the old "no chats yet" dead end; group
  rows get a distinct accent color + a people emoji until a real group
  avatar (Section 5) is designed.

## Known gaps / honest caveats going into Phase 6
1. Everything from Phase 2/3/4's caveats still applies unless noted fixed
   above (libsignal API surface unverified against a real Gradle sync —
   this now also covers the Sender Keys classes in this pass, see the
   caveat comment in `SqlSenderKeyStore.kt`; registration race on first
   connect; incoming calls not ringing through the lock screen; no call
   notification/foreground service).
2. **No key rotation on membership change.** A removed or departed member's
   already-distributed Sender Key isn't invalidated — they could still
   decrypt anything sent before remaining members independently rotate
   (see `GroupSessionManager`'s doc comment). Real-world Signal has itself
   iterated on exactly this problem; it's flagged here as a deliberate
   Phase 6 hardening item, not silently absent.
3. **No group attachments, calls, or read receipts/reactions/typing
   indicators for groups** — text only this pass. Attachments need
   `MediaCrypto`/`AttachmentStore` extended from "one blob to one peer" to
   "one blob fanned out to N members"; group calling needs a WebRTC mesh or
   SFU design decision Section 8 doesn't make for you.
4. **Contact Add has no camera QR scanner**, manual ID/username entry only
   — see the caveat comment in `ContactAddScreen.kt` for why (no barcode
   library in `build.gradle.kts` yet; not a good pairing with this pass's
   crypto work). "Show my code" is text, not an actual scannable QR image.
5. **Group membership sync is eventually consistent, not transactional** —
   if two members are simultaneously offline during an add/invite fan-out,
   they may briefly disagree on the member list until the next invite each
   of them sees. Harmless for a chat app (worst case: someone's key arrives
   a message or two late), but worth knowing.

## Post-Phase-5 audit fixes
A pass back over Phases 1-4 turned up two real bugs (not previously flagged
in any prior README) — fixed now rather than left open into Phase 6:
1. **App-lock didn't actually re-lock.** `MainActivity`'s locked/unlocked
   state was `remember`'d once at first composition; `onStop` updated
   `AppLockManager`'s internal field but nothing pushed that back into
   Compose state on resume, so after the first unlock the lock screen could
   never reappear. Fixed by hoisting the state to an Activity-level
   `MutableState` re-read in `onResume`.
2. **Secure wipe missed attachments.** `SecureWipeManager.wipeEverything()`
   deleted the DB, EncryptedSharedPreferences, and Keystore aliases, but
   never touched `filesDir/attachments/` — every encrypted attachment blob
   (Phase 4) silently survived the "panic button". Fixed with a
   zero-then-delete pass over that directory, same spirit as
   `AttachmentStore.secureDelete`.

## Phase 6 — Security Hardening (part 1: this pass)

Three items from the "Known gaps going into Phase 6" list above, closed —
UI/UX polish is deliberately a separate follow-up pass, not mixed in here.

### 1. Group Sender Key rotation on member departure (the big one)
Previously, `groupId` doubled as both the stable conversation id AND the
libsignal Sender Keys `distributionId` — so a departed/removed member's
already-distributed key stayed valid **forever**, for every future message,
which defeated the entire purpose of tracking group membership at all.

Fixed by decoupling the two:
- `data/db/NoTraceDatabase.kt` bumped to version 4: new `group_key_epochs`
  table (`group_id -> current epoch UUID`), with an `onUpgrade` migration.
- `data/GroupDao.kt` gained `getOrCreateCurrentEpoch` / `rotateEpoch` —
  rotation just mints a fresh random UUID and makes it current.
- `data/MessageRepository.kt`: every group crypto call (`createOwnDistributionMessage`,
  `encrypt`, `decrypt`, `processDistributionMessage`) now takes the current
  **epoch**, never the raw `groupId`. The flag-`3` wire format grew from
  `[3][16-byte groupId][ciphertext]` to `[3][16-byte groupId][16-byte epoch][ciphertext]`,
  and the `NOTRACE_GROUP_SKDM_V1:` control string grew a third `:`-delimited
  field for the epoch — both are breaking wire-format changes, fine since
  nothing's deployed yet.
- New `handleGroupLeave(groupId, departedMember)`: on receiving someone
  else's `NOTRACE_GROUP_LEAVE_V1` notice, every remaining member now
  removes them locally, **mints a new epoch, generates a fresh Sender Key
  under it, and redistributes to everyone still in the group** — the
  departed member never receives the new epoch, so their old key can't
  decrypt anything sent from that point on.

### 2. Registration race on first connect
`SplashScreen` used to fire `register()`/`uploadPreKeys()` the instant the
screen composed, racing OkHttp's asynchronous WebSocket handshake — on a
slow network, both calls could silently no-op with nothing surfaced
anywhere. `SignalingClient` now tracks real connection-open state
(`awaitConnected()`, backed by the WebSocketListener's `onOpen`), and
`SplashScreen` awaits it (15s timeout) before registering, showing which
stage it's in rather than assuming success.

### 3. Dropped connections were permanent
`NetworkCoordinator`'s collector used to just end — silently, forever —
the moment the WebSocket dropped for any reason (server restart, network
change, phone sleeping). It now retries with exponential backoff (1s, 2s,
4s... capped at 30s), resetting to 1s the moment a message is successfully
received again, for the process lifetime.

## Phase 6, Part 2 — UI/UX Polish (this pass)

- **Real icons everywhere.** Every placeholder text button (`Text("Back")`,
  `Text("Send")`, `Text("Call")`, `Text("Attach")`, `Text("Settings")`,
  `Text("New")`, `Text("Accept")`/`Text("Decline")`/`Text("Hang up")`) is
  now a proper Material icon (`ArrowBack`, `Send`, `Call`, `AttachFile`,
  `Settings`, `Add`, `CallEnd`), via the newly-added
  `androidx.compose.material:material-icons-extended` dependency. `Cancel`
  in `GroupCreateScreen` was deliberately left as text — a back-arrow icon
  would misleadingly suggest "go back" rather than "abandon this group
  you're creating," which is a different, more destructive action.
- **Incoming calls now ring through the lock screen — fixed.** This was
  the most-repeated caveat across three phases. `MainActivity` now watches
  `CallManager.callState` at the top level (previously only `NoTraceNavGraph`,
  which isn't composed while locked, ever saw it) and shows `CallScreen`
  over the lock screen for any in-progress call, the same way a phone's
  native dialer works. This does **not** weaken the lock: `CallScreen`
  only ever renders the caller's numeric id and call controls — never
  chat history, never any decrypted message content — so nothing the lock
  is actually protecting becomes visible.

## Known gaps / honest caveats going into Phase 7
1. No call notification / foreground service — a call's audio still won't
   survive backgrounding the app (Phase 4's caveat, unchanged).
2. The libsignal API surface (including the epoch-based Sender Keys calls
   from this Phase 6 security pass) is still unverified against a real
   Gradle sync — no network access in this environment to confirm it.
3. No group attachments/calls, no QR scanner for Contact Add (Phase 5
   caveats, unchanged).
4. No loading/error states beyond what already existed (e.g. the chat
   list's empty state) — a proper skeleton/spinner/retry pass wasn't done
   this round; flagging honestly rather than claiming it's covered by the
   icon work above, which is a different kind of polish.
5. Dark mode was already wired via `NoTraceTheme` following the system
   setting since Phase 1, but has never been visually verified on a real
   device/emulator — nothing in this pass changed that; it's a can't-verify
   from a sandbox with no display, not a can't-verify with a reason it
   was skipped.

## Suggested next step
The core phases (1 through 6) are now feature-complete on paper. What's
left is the split described at the top of this README: either continue
closing code-side gaps (foreground service for calls, the libsignal
verification, loading states), or — and this matters more at this point —
move to the parts that were never possible from inside a chat conversation:
a real Gradle sync and device build, actual two-device testing, and a
security review before anyone trusts this with real messages.
