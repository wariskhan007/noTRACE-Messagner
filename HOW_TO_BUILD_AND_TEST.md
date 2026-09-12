# Building and Testing NoTrace — Step by Step

This assumes no coding background. It's split into two halves: **Part 1**
gets you an installable app file (APK) using GitHub's free build servers.
**Part 2** gets a server running so the app actually works, not just opens.

You need Part 1 AND Part 2 before you can send a real message.

---

## Part 1 — Build the app on GitHub

### Step 1: Create a GitHub account
Go to github.com, sign up (free). Skip if you already have one.

### Step 2: Create a new repository
- Click the "+" in the top right → "New repository"
- Name it something like `notrace-messenger`
- Leave it **empty** — don't check any of the "add a README" boxes
- Click "Create repository"

### Step 3: Upload the code
The easiest way with no command-line: install **GitHub Desktop**
(desktop.github.com) — it's a free app with buttons, not commands.
- Open GitHub Desktop, sign in with your GitHub account
- "File" → "Add local repository" → pick the unzipped `notrace-messenger`
  folder you got from me
- It'll ask to publish/create a repository — pick the empty one you made
  in Step 2, or let it create one
- Click "Publish repository"

That's it — your code is now on GitHub.

### Step 4: Watch it build
- On github.com, open your repository
- Click the **"Actions"** tab near the top
- You should see a build running (a yellow dot), or about to start —
  if nothing's there, click "workflow_dispatch" / "Run workflow" to
  trigger it manually
- Wait for it to finish. A **green checkmark** means it built successfully.
  A **red X** means something failed — see "If the build fails" below.

### Step 5: Download the APK
- Click into the finished (green) run
- Scroll down to "Artifacts"
- Download `notrace-debug-apk` — it's a zip containing the installable
  `.apk` file

### Step 6: Install it on your phone
- Get the APK onto your Android phone (email it to yourself, Google Drive,
  USB cable — any way you'd normally move a file)
- Tap the file to open it
- Android will ask permission to "install unknown apps" the first time —
  allow it for whichever app you used to open the file
- Install, open

**At this point the app will open but won't really work yet** — it needs
a server (Part 2) to talk to. It'll sit on "Connecting…" or similar.

### If the build fails
Click into the red X, then into the failed step, and read the error text.
This is genuinely useful information — copy the error text and either:
- paste it back to me and I'll help fix the code, or
- hand it to a developer if you've brought one in

A first-ever build revealing real errors is normal, not a sign something
went wrong with this process — this code has never been compiled before
now, anywhere.

---

## Part 2 — Get the server running

The server (`server/` folder) is small and doesn't store your messages —
it just helps two phones find each other and holds encrypted messages
briefly if someone's offline. Recommended for a first test: **Render**
(render.com) — free to start, and it handles the encrypted-connection
(TLS) part automatically, which matters (see the box below).

### Step 1: Create a Render account
Go to render.com, sign up — you can sign up directly with your GitHub
account, which also makes Step 2 easier.

### Step 2: Create a new Web Service
- Dashboard → "New" → "Web Service"
- Connect it to the same GitHub repository from Part 1
- Under "Root Directory," enter: `server`
- Render should auto-detect the `Dockerfile` already in that folder —
  if it asks, choose "Docker" as the environment
- Instance type: the free tier is fine for testing
- Click "Create Web Service"

### Step 3: Wait for deploy, then copy the URL
Render will build and start it — takes a couple of minutes. Once it's
live, Render shows a URL like:

```
https://notrace-messenger.onrender.com
```

### Step 4: Turn that into your app's server address
The app needs a `wss://` address (secure WebSocket), not `https://`.
Just swap the beginning:

```
wss://notrace-messenger.onrender.com
```

### Step 5: Put that address into the app and rebuild
This one line needs to change in the code:

**File:** `android/app/src/main/java/com/notrace/messenger/AppContainer.kt`
**Find:** `const val SIGNALING_SERVER_URL = "ws://10.0.2.2:8080"`
**Replace with:** `const val SIGNALING_SERVER_URL = "wss://notrace-messenger.onrender.com"`
(using YOUR actual Render URL)

Edit that file right on github.com (open the file, click the pencil/edit
icon, change the line, commit) — no need to reopen GitHub Desktop for a
one-line change. Committing the change automatically triggers a new build
(Part 1, Step 4 again) — download the new APK once it's green, and
reinstall it on your phone(s).

---

## Testing with two phones

1. Install the APK (with the real server address baked in) on **two**
   phones.
2. Open the app on both — each one generates its own identity and
   registers with the server (needs internet).
3. On Phone A: Settings → your numeric ID is shown there, with a "Copy"
   button. Send that number to whoever has Phone B (text it, say it out
   loud, however).
4. On Phone B: tap "New" → "New chat" → enter Phone A's numeric ID.
5. Send a message. If Part 1 and Part 2 both worked, it should arrive on
   Phone A.

If it doesn't arrive: check both phones actually have internet, and
double check the `SIGNALING_SERVER_URL` change in Step 5 above was saved
and a fresh APK (built AFTER that change) is what's installed on both
phones — an easy step to accidentally skip.

---

## What this does NOT cover yet
- **Calling** between phones on different networks needs a TURN server
  (`server/coturn/`) — not required for basic text messaging, worth
  coming back to once messaging itself works.
- **Publishing to the Play Store** is a separate, later process (signing
  key, store listing, privacy policy, Google's review) — nothing above
  gets you there, it only gets you a working app you and others can test.
- **Security review** — still genuinely recommended before anyone but you
  trusts this with real messages, same as I've said throughout.

## A quick word on why this matters
`wss://` (not `ws://`) is required for anything other than local testing:
Android blocks unencrypted connections by default since 2019, and — more
importantly — it should be encrypted anyway, in an app whose entire point
is privacy. Render's free tier gives you this automatically, which is
exactly why it's the easiest first option, not just a random pick.
