# CamMonitor Browser-Based Viewer — User Requirements & Functional Specification

**Date:** 2026-11-25  
**Target Release:** CamMonitor Android (v1.2.1 baseline; upcoming v1.3.0) + CamMonitor Web Viewer v1.0  
**Hosting Model:** Static PWA on GitHub Pages (`https://samlu.github.io/CamMonitor/viewer/` via `main / docs`, with `docs/index.html` redirect preserving `#pair=` & `docs/.nojekyll`)  
**Backend Infrastructure:** None (100% static client + existing public MQTT WSS + P2P WebRTC)

---

## 1. Executive Summary & Objectives

Enable **iPhone/iPad (iOS Safari / PWA)**, **Laptop (Windows / macOS / Linux)**, and **Android Browser** users to pair with and watch an Android **CamMonitor Camera (PA)** directly from a web browser — with **no App Store install, no user accounts, no cloud servers, and no self-hosting required by end users**.

### Core Goals
1. **Zero-friction pairing (Flow A):** Scanning PA's QR code with the native iPhone Camera app — or clicking a shared pairing link on a laptop — opens the hosted CamMonitor Web Viewer and pairs automatically.
2. **Strict Zero-Trust & E2EE parity:** The browser implements the exact same ECDSA/ECDH P-256 + HKDF-SHA256 + AES-256-GCM signaling envelope and DTLS-SRTP media encryption as the native Android Viewer. GitHub Pages only serves static HTML/JS and never sees keys, OTPs, or video.
3. **Full Viewer Feature Parity:** Live 720p video & audio, 4-step connection progress telemetry, 15s `CameraTimeout` + auto-recovery, remote Torch toggle, 3-way Night Mode (`Auto` / `Night` / `Normal`), Push-to-Talk (PTT) intercom, 90° rotation, Fit/Fill toggle, PA battery & FPS HUD, real-time camera rename sync, in-app diagnostics logs, and up to 8 paired cameras.
4. **Minimal Android Footprint:** Keep Android PA/PB changes tiny and strictly backward-compatible (`appVersionName` remains `1.2.1` until release bump).

### Non-Goals (Out of Scope for v1.0)
- **Browser as Camera Sender (PA):** The web app is **Viewer-only (PB)**.
- **Cloud TURN Relay:** Symmetric-to-symmetric WAN NAT remains out of scope (same STUN + LAN host candidate model as Android v1.2.1).
- **Background streaming / Push notifications on iOS:** iOS Safari suspends WebSockets when backgrounded; the web viewer is an active-foreground viewing client.
- **Exporting an existing Android Viewer's hardware key to a browser:** AndroidKeyStore private keys are non-exportable; each browser instance pairs as its own viewer slot on PA.

---

## 2. Target Platforms & Browser Support Matrix

| Platform | Supported Browsers | Pairing Method | Persistence |
|---|---|---|---|
| **iOS / iPadOS (15+)** | Safari & Home Screen PWA | Native Camera QR (`#pair=` link) or in-app QR scanner | Permanent in Home Screen PWA; 7-day ITP eviction warning in regular Safari tab |
| **Android (9+)** | Chrome, Edge, Firefox | Native Camera / Lens QR (`#pair=` link) or in-app QR scanner | Permanent (`IndexedDB`, shared between tab and PWA) |
| **Desktop (Win / macOS / Linux)** | Chrome, Edge, Safari, Firefox | Click shared `#pair=` link, paste link/JSON, or webcam QR scanner | Permanent (`IndexedDB`) |

---

## 3. User Personas & End-to-End Journeys

### Journey 1 — iPhone User (First-Time & Returning)
1. **Pair:** On Android Camera (PA), user opens **Pair New Viewer** (QR code shown). User opens the **iPhone Camera app**, points at the QR code, and taps the Safari link banner.
2. **Auto-Handshake:** Safari opens `https://samlu.github.io/CamMonitor/viewer/#pair=<base64url>`. The page immediately scrubs `#pair=...` from the address bar, generates a local P-256 identity keypair (non-extractable handles), runs `PAIR_REQUEST` → `PAIR_CONFIRM` over MQTT-WSS (~2 seconds), and starts streaming live video.
3. **Make Permanent (iOS PWA Guidance):** Because iOS Safari isolates `IndexedDB` between Safari tabs and Home Screen PWAs (and evicts Safari tab storage after 7 days of inactivity), the web viewer detects `isIOS && !isStandalone` and displays a dismissible banner:
   > *"To keep this camera saved permanently on your iPhone, tap **Share → Add to Home Screen**, then pair once inside the Home Screen app."*
4. **Returning User:** User taps the CamMonitor icon on the iPhone Home Screen → taps the camera tile → live video connects.

### Journey 2 — Laptop User (Windows / macOS / Linux)
1. **Share Link:** On Android Camera (PA)'s **Pair New Viewer** screen, user taps **`Share Link`** or **`Copy Link`** and sends the link to themselves (Line, Slack, Email, AirDrop, Phone Link, etc.).
2. **Auto-Handshake:** User clicks the link on their laptop browser → auto-pairs in ~2 seconds → live video starts immediately.
3. **Webcam Fallback:** Alternatively, user opens the Web Viewer URL on their laptop, clicks **`+ Pair Camera`**, and holds the Android PA's QR code up to the laptop webcam (or pastes the pairing link/JSON).

### Journey 3 — Existing Android CamMonitor Viewer
1. User opens Android CamMonitor (builds including `PairingPayload.fromQrContent()`) → **Pair Camera** → scans the new `#pair=` QR code from PA.
2. Android's `PairingPayload.fromQrContent()` transparently extracts and decodes the `#pair=` payload (as well as legacy raw JSON from older PA devices), preserving 100% native pairing UX.

---

## 4. Functional Requirements

### FR-1: Universal Pairing Link & QR Format
- **URL Format:**
  ```text
  https://samlu.github.io/CamMonitor/viewer/#pair=<base64url-no-padding(UTF8(PairingPayload.toJson()))>
  ```
- **Root Redirect (`docs/index.html` + `docs/.nojekyll`):** Redirects `https://samlu.github.io/CamMonitor/` to `./viewer/` while preserving `window.location.search` and `window.location.hash`.
- **Security Property:** Because the OTP `secret` resides solely in the URL `#fragment`, browsers **never transmit it in HTTP requests** to GitHub Pages.
- **Immediate Address-Bar Scrub:** Upon reading `window.location.hash`, the web viewer immediately calls `history.replaceState(null, "", window.location.pathname + window.location.search)` so the OTP does not linger in browser history or screenshots.
- **Android PA Pairing Screen (`CameraPairingScreen.kt`):**
  - QR code encodes the Universal Pairing Link (`#pair=`) by default.
  - `QrUtils.encodeQrMatrix()` / `generateQrBitmap()` configures `EncodeHintType.ERROR_CORRECTION = ErrorCorrectionLevel.M`, `EncodeHintType.MARGIN = 1`, and `EncodeHintType.CHARACTER_SET = "UTF-8"` to keep the ~240-char QR code crisp and fast to scan at 280dp.
  - Vertically scrollable layout so actions never clip on compact or landscape screens.
  - Link generation binds to `remember(otp, deviceId, cameraName)` so 5-minute OTP rotation refreshes both the QR code and the shared/copied URL.
  - Adds **`Share Link`** (`Intent.ACTION_SEND`) and **`Copy Link`** (`ClipboardManager`) action buttons below the instructions (with brief security note: *"Single-use link expires in 5 minutes"*). On Android 13+ (`API 33+`), `Copy Link` sets `ClipDescription.EXTRA_IS_SENSITIVE = true` to mask the OTP in the OS clipboard preview.
  - *Backward-compatibility note:* Native Android Viewers require `PairingPayload.fromQrContent(raw: String)` (which supports both `#pair=<base64url>` URLs and legacy raw JSON) to scan the new QR codes.
- **Expired / Re-clicked `#pair=` Link Fallback:** If pairing times out for a camera ID already present in `paired_cameras`, the web viewer offers a direct *"Connect to `<name>`"* action instead of leaving the user stuck.

### FR-2: Browser Identity, Device Naming & Local Persistence
- **Storage Engine:** `IndexedDB` database `cammonitor_web_db` with object stores `identity`, `paired_cameras`, and `settings`:
  - `identity`: `{ deviceId: UUIDv4, deviceName: String, ecdsaPrivateKey: CryptoKey (non-extractable), ecdhPrivateKey: CryptoKey (non-extractable), publicKeySpkiBase64: String, createdAt: Long }` (generated extractable once in memory, imported as two non-extractable `CryptoKey` handles, raw `pkcs8` discarded immediately; `pkcs8` fallback used only if `IndexedDB.put()` throws `DataCloneError` in test shims).
  - `paired_cameras`: `{ deviceId, name, publicKeyBase64, pairedTimestamp, lastConnectedAt, connectDurationsMs: number[], audioEnabled: boolean, nightModeSetting: "AUTO"|"NIGHT"|"NORMAL" }`
  - `settings`: key-value store for `locale`, etc.
- **Default Browser Viewer Name:** Auto-detected friendly label (e.g., `"iPhone Safari"`, `"iPad Safari"`, `"Mac Chrome"`, `"Windows Edge"`, `"Android Chrome"`, handling iPadOS `MacIntel` + `maxTouchPoints > 1`), editable by the user before/after pairing (sanitized to 1–32 chars, no ISO control chars) so it displays cleanly in PA's **Manage Paired Devices** list.
- **Capacity:** Up to **8 paired cameras** (`MAX_PAIRED_CAMERAS = 8`), matching Android Viewer. Pre-checked **before** transmitting `PAIR_REQUEST` for a new camera ID so a full browser does not consume a PA pairing slot (re-pairing an already-stored `deviceId` is allowed).
- **Storage Persistence:** Request `navigator.storage?.persist?.()` on first pairing.

### FR-3: Cryptographic Interop with Android (`CryptoUtils.kt`)
The web viewer MUST match [CryptoUtils.kt](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/security/CryptoUtils.kt) bit-for-bit:

| Primitive | Android (`CryptoUtils.kt`) | Web Viewer (`crypto.js` via WebCrypto) |
|---|---|---|
| **Identity Key** | EC NIST P-256 (`secp256r1`) | `ECDH` P-256 generated extractable once, imported as two non-extractable `CryptoKey` handles (`ECDSA` for sign/verify, `ECDH` for `deriveBits`), raw PKCS#8 discarded and `CryptoKey` handles persisted in `IndexedDB`. |
| **Public Key Wire Format** | Base64 X.509 (`X509EncodedKeySpec`) | `crypto.subtle.exportKey("spki", pub)` / `importKey("spki", ...)` → standard Base64. |
| **OTP Key Derivation** | `hkdfSha256(utf8(otp), emptySalt, "CamMonitor-OTP", 32)` | **Custom HKDF-Expand** (`HMAC-SHA256(key = utf8(otp), data = "CamMonitor-OTP" \|\| 0x01)`). *Do NOT use WebCrypto `HKDF`, which runs HKDF-Extract on empty salt.* |
| **ECDH Shared Key** | `hkdfSha256(ecdhSecret, emptySalt, "CamMonitor-E2EE", 32)` | `deriveBits(ECDH, 256)` → **Custom HKDF-Expand** (`HMAC-SHA256(key = sharedBits, data = "CamMonitor-E2EE" \|\| 0x01)`). |
| **Envelope Encryption** | AES-256-GCM, 12-byte random IV, 16-byte tag split into `ciphertext` + `tag` | `AES-GCM` (`tagLength: 128`), slice last 16 bytes into `tag` on encrypt; concatenate `ciphertext + tag` on decrypt. |
| **Envelope Signature** | `SHA256withECDSA` over `senderId + recipientId + timestamp + nonce + ciphertext` (ASN.1 DER) | WebCrypto `ECDSA` SHA-256 produces/expects **IEEE P1363** (64 bytes). Convert `p1363ToDer()` before sending and `derToP1363()` before verifying. |
| **Anti-Replay & PoP** | `recipientId == self`, `timestamp` within `±120s`, in-memory nonce set (120s TTL, rollback on auth/decrypt failure), Offer monotonic timestamp (`> lastAcceptedOfferTs`), PoP check on `PAIR_CONFIRM` | Identical checks implemented in `signaling.js` (`EnvelopeProcessor`), including nonce rollback on signature/decrypt failure and monotonic Offer timestamps (`Math.max(Date.now(), lastOfferTs + 1)`). |
| **Public Key Immutability Guard** | Reject `PAIR_CONFIRM` if `paDeviceId` already paired with a different `publicKeyBase64` | Enforced in `pairing.js` before overwriting `paired_cameras`. |

### FR-4: Signaling Transport (MQTT over WSS)
- **Broker Policy (Aligned with Android `MqttSignalingClient`):**
  - Default: `wss://broker.hivemq.com:8884/mqtt` (matching Android PA's `PRIMARY_BROKER_HOST = "broker.hivemq.com"`).
  - If `PairingPayload.broker` is explicitly specified (and allowlisted: `broker.hivemq.com` or `broker.emqx.io`), use that broker; do **not** silently fail over to `broker.emqx.io` while PA remains on `broker.hivemq.com`.
- **MQTT Options:** `protocolVersion: 4` (MQTT 3.1.1, matching Android `useMqttVersion3()`), `clean: true`, `keepalive: 30`, `reconnectPeriod: 0` (managed by generation-guarded exponential backoff `2s → 4s → 8s → 15s`).
- **Topics:**
  - Subscribe: `cammonitor/<browserDeviceId>/rx` (QoS 1)
  - Publish: `cammonitor/<targetCameraId>/rx` (QoS 1)
- **Lifecycle:**
  - Connect on demand when pairing or opening a camera stream; disconnect cleanly when exiting viewer mode or closing the tab (`pagehide`).
  - Auto-reconnect on `window` `online` event and `document.visibilitychange` → `visible` (recovering from iOS Safari background freeze).

### FR-5: WebRTC Live Video, Audio & 4-Step Connection Pipeline
- **ICE Configuration:** `stun:stun.l.google.com:19302`, `stun:stun1.l.google.com:19302` (`iceCandidatePoolSize: 2`).
- **Mandatory Unified Plan Transceivers & DataChannel Setup (BEFORE `createOffer()`):**
  1. `pc.addTransceiver("video", { direction: "recvonly" })` (mid `0`)
  2. `pc.addTransceiver("audio", { direction: "sendrecv" })` (mid `1`, **no track attached initially** so no microphone permission prompt fires on viewer load; PA does not support mid-session SDP renegotiation)
  3. `pc.createDataChannel("control", { ordered: true })` (ensures SCTP `m=application` exists in the Offer SDP)
  4. Also bind `pc.ondatachannel` so both the browser-created and PA-created `"control"` DataChannels receive messages, and outbound commands use whichever channel is `open`.
- **4-Step Connection Telemetry** (matching `ViewerConnectionProgress` in [Models.kt](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/data/model/Models.kt)):
  1. `CONNECTING_NETWORK`: Connect MQTT WSS & subscribe.
  2. `REACHING_CAMERA`: Send `OFFER` every 3s (up to 5 attempts / 15s timeout). Reveal countdown bar (`Attempt n/5 · Xs left`) at `≥2s` elapsed or `attempt > 1`; reveal slow hint at `≥5s` unless `CAMERA_WAKING` ACK was received (*"Waking up camera..."*). Handle `REJECT("MAX_CAPACITY_REACHED")`. Transition to `CAMERA_TIMEOUT` at 15s (with automatic retry after 10s).
  3. `ESTABLISHING_LINK`: Apply `ANSWER` (update camera name & FPS immediately), exchange trickle `ICE_CANDIDATE`s with pre-SDP buffering, composite deduplication (`sdpMid:sdpMLineIndex:sdp`), and local candidate re-send on Offer retry and Answer receipt.
  4. `STARTING_VIDEO`: Transition to `LIVE` on `<video>` `loadeddata` / `playing` (or 1.5s fallback after `connectionState === "connected"`). Record rolling connection duration average (last 3 samples, clamped `[100ms, 30000ms]`).
- **Disconnection & Error Recovery (Parity with `ViewerModeScreen.kt`):**
  - `connectionState === "disconnected"`: schedule automatic reconnect after 3s (while staying in viewer mode).
  - `connectionState === "failed"`: display Symmetric NAT traversal error card with manual Retry / Exit actions.
  - MQTT signaling restored while in `DISCONNECTED` / `FAILED` / `CAMERA_TIMEOUT`: trigger immediate reconnect.
- **Video Rendering (iOS Safari Safe):**
  - `<video autoplay playsinline muted>` inside a responsive full-viewport container.
  - Aspect-Fit / Aspect-Fill toggle (`object-fit: contain` vs `cover`), plus **double-tap** on video surface to toggle Fit/Fill.
  - **90° Rotation Toggle** (`0° → 90° → 180° → 270°`): `computeRotatedVideoLayout()` dynamically computes container-fitted dimensions and CSS `transform: rotate(Ndeg)` so rotated video fills the viewport cleanly without clipping.
- **Screen Wake Lock:** Request `navigator.wakeLock?.request("screen")` while in Viewer Mode (re-acquiring on `visibilitychange` → `visible`), and release on exit.
- **Audio Playback:**
  - Muted by default (persisted per camera). Unmuting inside a user tap handler sets `video.muted = false` and calls `video.play()` to satisfy iOS Safari autoplay policy.

### FR-6: Remote Controls & DataChannel Protocol
- Ordered DataChannel labeled `"control"` (handling both local and PA-created channels).
- On DataChannel `open`, + 500ms & 2000ms bursts after `connected`, + every 15s polling interval, send `{"cmd":"QUERY_BATTERY"}` to receive PA's `BATTERY`, `RENAME`, `NIGHT_MODE_REPORT`, `FPS_REPORT`, `TORCH_REPORT`.
- Supported UI controls & HUD badges (auto-hiding overlay after 5s, single-tap video to toggle):
  - **Top HUD:** Back button, Camera Name (live-updated on `RENAME`), Live badge (`720p <fps>fps`), PA Battery pill (`<pct>%` + charging bolt).
  - **Persistent Floating Battery Pill:** Compact translucent badge (`📷 <pct>%`) remains visible in top-right when HUD auto-hides.
  - **Bottom Control Bar:**
    - **Mute / Unmute** audio toggle.
    - **Torch (Flashlight)** toggle (`TORCH` ↔ `TORCH_REPORT`).
    - **Night Mode** 3-way cycle (`AUTO` / `NIGHT` / `NORMAL` via `SET_NIGHT_MODE` ↔ `NIGHT_MODE_REPORT`, showing active indicator when PA's Night mode is engaged).
    - **Rotate 90°** button.
    - **Fit / Fill** toggle (and double-tap on video).
    - **Hold-to-Talk (PTT)** button: On `pointerdown`, requests mic if needed (`getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } })`), attaches track via `audioTransceiver.sender.replaceTrack(track)` (no SDP renegotiation), calls `video.play().catch(()=>{})` (iOS audio-session recovery), ducks local `<video>` playback while holding, sends `PTT_START`; on `pointerup` / `pointercancel` / `pointerleave` (including if `pointercancel` fires during initial iOS mic permission prompt!), cancels pending activation or disables track, restores audio, and sends `PTT_STOP`. Releases/stops mic hardware track after 15s idle or session exit. (Supports Spacebar hold on desktop!)
    - **Fullscreen** toggle (Desktop / Android browsers).

### FR-7: Multi-Camera Home, Device Management & Diagnostics
- **Home View:**
  - Lists all paired cameras (up to 8) as clickable tiles showing camera name and relative last-connected time (`"Connected just now"`, `"2 hr ago"`, `"Not connected yet"`).
  - **`+ Pair Camera`** button opens:
    - Tab 1: **Scan QR Code** (`getUserMedia({ video: { facingMode: "environment" } })` + vendored `jsQR`).
    - Tab 2: **Paste Pairing Link / Code** (for desktop users who copied the link or JSON).
  - **Unpair / Remove Camera:** Confirmation modal wipes the camera record from `IndexedDB` and closes any active session.
  - **Device Name Setting:** Editable viewer name displayed on PA.
  - **In-App Diagnostics Log Drawer:** Bounded 300-entry ring buffer with level filtering and one-tap **Copy Logs** for mobile/iOS troubleshooting without desktop DevTools.

### FR-8: UI Design, PWA & Localization
- **Visual Theme:** Dark-first OLED theme matching CamMonitor Android (`#121212` surface, `#1E1E1E` cards, `#00E5FF` cyan primary, `#00E676` live emerald, `#FFD600` amber warning, `#FF5252` error).
- **PWA Manifest (`manifest.webmanifest`) + Service Worker (`sw.js`):**
  - `display: "standalone"`, `theme_color: "#121212"`, `background_color: "#121212"`, Apple touch icon (`180×180`) + `<meta name="apple-mobile-web-app-capable" content="yes">`.
  - Caches static app shell for instant cold-open on iPhone Home Screen.
- **Localization (4 Languages):** Auto-detect `navigator.languages` (with Android-parity `normalizeChineseLocale`) with manual language selector matching CamMonitor's 4 locales:
  - English (`en`), Traditional Chinese (`zh-Hant`), Simplified Chinese (`zh-Hans`), Japanese (`ja`).

---

## 5. Security & Privacy Architecture

1. **Zero Server Exposure of Secrets:** `#pair=<base64url>` is strictly client-side; `history.replaceState` scrubs it before any async I/O.
2. **No Third-Party CDN Runtime Risk:** Vendor `mqtt.min.js` and `jsQR.js` directly inside `docs/viewer/vendor/` (pinned versions, zero external script loads, works offline in PWA cache).
3. **Strict Content Security Policy (CSP):**
   ```html
   <meta http-equiv="Content-Security-Policy"
     content="default-src 'self'; script-src 'self'; worker-src 'self' blob:; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src wss://broker.hivemq.com:8884 wss://broker.emqx.io:8884; media-src 'self' mediastream: blob:;">
   ```
4. **Protocol-Level Parity:** All unauthorized MQTT envelopes are silently dropped; `PAIR_CONFIRM` requires valid OTP decryption + `senderId == paDeviceId` + Proof-of-Possession ECDSA signature verification + public key immutability check before persisting PA's public key.

---

## 6. Android App Changes Required (Minimal & Scoped)

| File | Change |
|---|---|
| `data/model/Models.kt` | Add `PairingPayload.toPairingUrl(baseUrl)` and `PairingPayload.fromQrContent(raw)` supporting both `#pair=<base64url>` and legacy raw JSON (rejecting blank required fields). |
| `ui/common/QrUtils.kt` | Extract `encodeQrMatrix()` and pass `ERROR_CORRECTION = M`, `MARGIN = 1`, `CHARACTER_SET = UTF-8` hints to `QRCodeWriter`. |
| `ui/pairing/CameraPairingScreen.kt` | Render `payload.toPairingUrl()` in QR bitmap (reactive to OTP rotation); make column scrollable; add `Share Link` (`Intent.ACTION_SEND`) / `Copy Link` (`ClipboardManager` with `EXTRA_IS_SENSITIVE = true` on API 33+). |
| `ui/pairing/ViewerPairingScreen.kt` | Replace `PairingPayload.fromJson(rawText)` with `PairingPayload.fromQrContent(rawText)`. |
| `res/values*/strings.xml` (all 4 locales) | Add localized strings for share/copy pairing link & updated QR instructions (verified by `LocaleResourcesTest.kt`). |
| `Specification.md` & `README*.md` | Document browser/iPhone viewer support, `#pair=` link format, and GitHub Pages URL. |
| `app/src/test/...` | Add `PairingUrlTest.kt`, `QrUtilsTest.kt`, and bidirectional `WebViewerCryptoInteropTest.kt`. |

---

## 7. Web Viewer Repository Layout (`docs/`)

Zero-build-step ES modules directly servable by GitHub Pages (`Settings → Pages → main / docs`):

```text
docs/
├── .nojekyll
├── index.html                   # Redirects / -> ./viewer/ preserving window.location.search + hash
└── viewer/
    ├── index.html               # App shell, CSP, iOS PWA meta tags, Home, Viewer & Log Drawer
    ├── manifest.webmanifest     # PWA manifest (standalone, icons, dark theme)
    ├── sw.js                    # Service worker (app-shell cache, network-first HTML)
    ├── styles.css               # Dark-first OLED responsive styles (mobile + desktop)
    ├── icons/
    │   ├── icon.svg             # Source vector icon
    │   ├── icon-180.png         # Apple Touch Icon (iOS Home Screen)
    │   ├── icon-192.png
    │   └── icon-512.png
    ├── vendor/
    │   ├── mqtt.min.js          # Pinned mqtt@5.16.1 browser bundle (WSS)
    │   ├── jsQR.js              # Pinned jsqr@1.4.0 for iOS/desktop QR scanning
    │   └── VERSIONS.txt         # Pinned versions, SHA-256 digests, licenses
    ├── src/
    │   ├── app.js               # Router, Home screen, #pair= bootstrap, WakeLock, iOS PWA banner
    │   ├── crypto.js            # Non-extractable P-256 keys, custom HKDF, DER<->P1363, AES-GCM
    │   ├── storage.js           # IndexedDB + MemoryStore (identity + cameras + settings)
    │   ├── signaling.js         # MQTT-over-WSS client, EnvelopeProcessor, anti-replay, PoP
    │   ├── pairing.js           # #pair= parser, PAIR_REQUEST/PAIR_CONFIRM, QrWebcamScanner
    │   ├── webrtc.js            # RTCPeerConnection, dual DataChannel, 4-step progress, PTT
    │   ├── i18n.js              # en / zh-Hant / zh-Hans / ja translations + normalization
    │   ├── ui.js                # Rotation/Fit layout math, UA device name, relative time, badges
    │   └── logger.js            # 300-entry ring-buffer diagnostic logger
    └── test/
        ├── interop-bridge.mjs   # Bidirectional Node.js <-> JVM CryptoUtils test bridge
        ├── crypto.test.js       # HKDF, DER<->P1363, AES-GCM, SPKI/PKCS8
        ├── signaling.test.js    # Anti-replay, nonce rollback, PoP, monotonic Offer, broker URL
        ├── pairing.test.js      # #pair= parser, 8-camera limit, pubkey immutability, timeout
        ├── webrtc.test.js       # Transceiver/DC order, dual DC, 4-step, timeout, ICE, PTT
        └── ui-i18n.test.js      # Rotation math, UA detection, 4-locale parity & placeholders
```

---

## 8. Acceptance Criteria & Verification Plan

### Automated Verification
1. **Android Unit Tests (`.\gradlew.bat testDebugUnitTest`):**
   - `PairingUrlTest.kt`: `toPairingUrl()` / `fromQrContent()` round-trip (ASCII, CJK, punctuation, optional `broker`), legacy raw JSON, bare `#pair=` / `pair=`, padded & `%3D`-encoded base64url, whitespace/newlines, malformed/blank inputs returning `null`.
   - `QrUtilsTest.kt`: Encode a full ~240-char `#pair=` URL via `QrUtils.encodeQrMatrix()` (`EC=M`, `MARGIN=1`) and decode back to exact string via ZXing `QRCodeReader`.
   - `LocaleResourcesTest.kt`: 100% key and placeholder parity across all 4 `strings.xml` files.
   - `WebViewerCryptoInteropTest.kt`: **Bidirectional** live JVM `CryptoUtils` ↔ Node.js `docs/viewer/src/crypto.js` & `signaling.js` test verifying OTP HKDF, ECDH HKDF, SPKI public keys, AES-256-GCM split tag, ASN.1 DER ↔ P1363 ECDSA signatures, and signed `PAIR_REQUEST`, `PAIR_CONFIRM`, `OFFER`, and `ICE_CANDIDATE` envelopes in both directions.
2. **Web Viewer Unit & i18n Parity Tests (`node --test docs/viewer/test/*.test.js`):**
   - `crypto.test.js`: Custom HKDF-Expand, `p1363ToDer`/`derToP1363` edge cases (`0x80` sign padding, leading zeros, <32-byte ints, malformed/trailing DER, 100 random signatures), AES-GCM tamper detection, dual non-extractable ECDSA/ECDH keypair.
   - `signaling.test.js`: Malformed JSON, `recipientId` mismatch, `±120s` clock bounds, nonce dedup + TTL + rollback on failure, unauthorized sender drop, OTP message-type gating, `PAIR_CONFIRM` PoP verification, monotonic Offer timestamp, `resolveBrokerUrl()` (defaults to `hivemq`, honors allowlisted `PairingPayload.broker`, no unsolicited `emqx` failover).
   - `pairing.test.js`: `parsePairingInput()`, `scrubPairHashFromUrl()`, `MAX_PAIRED_CAMERAS = 8` pre-check, public key immutability guard, `PairingSession` success/retry/timeout/already-paired fallback.
   - `webrtc.test.js`: Unified Plan transceiver & `"control"` DataChannel creation order, dual DataChannel RX/TX, all 10 `ControlCommand` serializers/parsers, 4-step `ViewerConnectionProgress` + countdown (`≥2s`/`attempt>1`) + slow hint (`≥5s`, suppressed by `CAMERA_WAKING`), `REJECT`, 15s `CameraTimeout`, ICE pre-SDP buffering & dedup, PTT ducking & `pointercancel` safety.
   - `ui-i18n.test.js`: `computeRotatedVideoLayout` (0°/90°/180°/270° × Fit/Fill), `detectDefaultViewerName`, `sanitizeDeviceName`, `detectIosSafariBanner`, `formatRelativeLastConnected`, and 100% 4-locale `i18n.js` key & `{placeholder}` parity.

### Manual End-to-End Test Matrix
| # | Scenario | Expected Result |
|---|---|---|
| 1 | iPhone native Camera scans Android PA QR | Opens Safari → `#pair=` scrubbed → pairs in ≤5s → live 720p video renders inline (`playsinline`). |
| 2 | iPhone Add to Home Screen (PWA) → pair inside PWA → force-close & reopen | Paired camera persists; tapping tile reconnects and wakes sleeping PA (`CAMERA_WAKING` → `LIVE`). |
| 3 | Laptop Chrome clicks shared `#pair=` link | Auto-pairs without webcam → live video + double-tap Fit/Fill + Spacebar PTT + Torch + Night Mode + 90° Rotate + Fullscreen all work. |
| 4 | PA renamed while browser is watching | Browser HUD title updates immediately and persists on reload. |
| 5 | PA revokes browser viewer in Manage Devices | Live WebRTC stream drops immediately; browser reconnect attempts are silently dropped by PA. |
| 6 | Android Viewer (with `fromQrContent`) scans new `#pair=` QR | Pairs identically to v1.2.1; no regression in native Android flow. |
| 7 | iPhone Safari backgrounded for 60s then foregrounded | `visibilitychange` triggers clean reconnect back to `LIVE` and re-acquires Screen Wake Lock. |
| 8 | First-time iOS PTT permission prompt interrupts `pointerdown` (`pointercancel`) | PTT aborts cleanly without sticking in `Transmitting...` and without leaving `PTT_START` active on PA; 2nd press works and `<video>` playback resumes. |
| 9 | Expired or re-clicked `#pair=` link when camera is already paired | Pairing times out cleanly and offers *"Connect to `<name>`"* button to open existing camera. |
| 10 | Browser already has 8 cameras paired; user tries to pair a 9th | Browser blocks before sending `PAIR_REQUEST` and displays 8-camera limit error without consuming a PA slot. |
| 11 | PA already streaming to 2 viewers; 3rd browser connects | Browser displays clear `MAX_CAPACITY_REACHED` rejection status. |
| 12 | Offline PWA cold-open on Home Screen | Service worker serves cached app shell immediately (`manifest` + `sw.js`). |
| 13 | Re-pair browser after PA revokes it | Same browser `deviceId` re-pairs cleanly via new OTP; if PA's key differs for an already-stored `deviceId`, browser rejects `PAIR_CONFIRM` via immutability guard. |
| 14 | Android 13+ PA `Copy Link` button | Clipboard receives `#pair=` URL with `EXTRA_IS_SENSITIVE = true` (masked preview on Android 13+). |
| 15 | Screen Wake Lock during Viewer streaming | Display remains awake while watching (`wakeLock` acquired) and releases cleanly on exit. |
