# Hosting the CamMonitor Web Viewer on GitHub Pages

This guide explains how to host your own copy of the **CamMonitor Browser-Based Web Viewer** (`docs/viewer/`) on **GitHub Pages** and point your Android build at it.

No backend server, database, Node build step, or cloud account is required — `docs/viewer/` is 100% static ES modules with vendored dependencies (`mqtt@5.16.1` and `jsqr@1.4.0`). Pairing secrets live solely inside the `#pair=<base64url>` URL fragment and are scrubbed via `history.replaceState` before any network call, so GitHub Pages never receives keys, OTPs, or media.

---

## Prerequisites

- A GitHub account and a fork (or clone) of `https://github.com/samlu/CamMonitor`
- **Public repository** (GitHub Free requires public repos for GitHub Pages; GitHub Pro / Team / Enterprise supports private Pages)
- Optional for local testing: **Python 3** (for local HTTP server) and **Node.js 20+** (for `node --test`)
- Optional for rebuilding the Android APK with your own URL: **JDK 17** and Android SDK

---

## Step 1: Fork or Push `docs/` to Your Repository

Make sure your `main` branch contains the `docs/` directory:

```text
docs/
├── .nojekyll                # Disables Jekyll processing so static assets serve directly
├── index.html               # Redirects / -> ./viewer/ while preserving search + #pair= hash
└── viewer/
    ├── index.html
    ├── manifest.webmanifest
    ├── sw.js
    ├── styles.css
    ├── icons/
    ├── src/
    └── vendor/
```

If you forked `samlu/CamMonitor`, `docs/` is already present on `main`.

---

## Step 2: Enable GitHub Pages (`main` / `/docs`)

### Option A — GitHub Web UI
1. Sign in to GitHub as the repository owner/admin. *(Note: GitHub returns **404** on `/settings/pages` if you are logged out or signed into a non-admin account.)*
2. Open `https://github.com/<YOUR_USERNAME>/<YOUR_REPO>/settings/pages`.
3. Under **Build and deployment**:
   - **Source:** `Deploy from a branch`
   - **Branch:** `main` · **Folder:** `/docs`
   - Click **Save**.
4. Wait ~30–90 seconds for the **`pages build and deployment`** workflow under the **Actions** tab to turn green.
5. Check **Enforce HTTPS** once GitHub finishes provisioning the TLS certificate.

### Option B — GitHub CLI (`gh`)
```bash
gh api -X POST repos/<YOUR_USERNAME>/<YOUR_REPO>/pages \
  -f "source[branch]=main" \
  -f "source[path]=/docs"
```

Your viewer will be live at:
```text
https://<YOUR_USERNAME>.github.io/<YOUR_REPO>/viewer/
```
(`https://<YOUR_USERNAME>.github.io/<YOUR_REPO>/` automatically redirects to `./viewer/` while preserving `#pair=...`.)

---

## Step 3: Point Your Android Build to Your GitHub Pages URL

By default, Android PA generates QR codes and Share/Copy links pointing to `https://samlu.github.io/CamMonitor/viewer/`.

To make your own Android builds point to **your** hosted viewer:

1. Edit `app/src/main/java/com/a0soft/gphone/camonitor/data/model/Models.kt`:
   ```kotlin
   companion object {
       const val DEFAULT_WEB_VIEWER_BASE_URL = "https://<YOUR_USERNAME>.github.io/<YOUR_REPO>/viewer/"
       const val PAIR_FRAGMENT_PREFIX = "#pair="
   ```
2. *(Optional)* Update the paste-input placeholder URL in `docs/viewer/src/i18n.js` (`pair_paste_placeholder` across `en`, `zh-Hant`, `zh-Hans`, `ja`) and the unit test assertion in `app/src/test/java/com/a0soft/gphone/camonitor/model/PairingUrlTest.kt` (note: `PairingUrlTest` references `PairingPayload.DEFAULT_WEB_VIEWER_BASE_URL` dynamically, so it passes automatically).
3. Rebuild and install the APK:
   ```powershell
   # Windows
   .\gradlew.bat testDebugUnitTest assembleDebug

   # macOS / Linux
   ./gradlew testDebugUnitTest assembleDebug
   ```

---

## Step 4: Optional Customizations

### Custom Domain
1. Add a `docs/CNAME` file containing your domain (e.g., `cam.example.com`) and configure DNS per GitHub Pages docs.
2. Update `DEFAULT_WEB_VIEWER_BASE_URL` in `Models.kt` to `https://cam.example.com/viewer/`.

### Custom MQTT-over-WSS Broker
Both Android PA and the Web Viewer must use the **same** MQTT broker (default: `broker.hivemq.com`). To switch brokers:
1. **Android PA/PB:** Update `PRIMARY_BROKER_HOST` in `app/src/main/java/com/a0soft/gphone/camonitor/data/signaling/SignalingClient.kt`.
2. **Web Viewer signaling:** Update `DEFAULT_BROKER_URL` and `ALLOWED_BROKER_HOSTS` in `docs/viewer/src/signaling.js`.
3. **Web Viewer CSP:** Add your broker's `wss://<host>:<port>` origin to `connect-src` in `docs/viewer/index.html`:
   ```html
   <meta http-equiv="Content-Security-Policy"
     content="default-src 'self'; script-src 'self'; worker-src 'self' blob:; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src wss://broker.hivemq.com:8884 wss://broker.emqx.io:8884; media-src 'self' mediastream: blob:;">
   ```

### Refreshing Cached Clients After Updates (Service Worker)
Whenever you modify files under `docs/viewer/`, bump `CACHE_NAME` in `docs/viewer/sw.js` (e.g. `cammonitor-viewer-v2` → `cammonitor-viewer-v3`) before pushing so installed PWAs and mobile browsers purge stale cached assets.

---

## Step 5: Local Development & Automated Tests

### Serve Locally
```bash
python -m http.server 8080 -d docs
```
Open `http://localhost:8080/viewer/`.

> **Important:** WebCrypto (`crypto.subtle`) and `getUserMedia` require a **secure context**. `http://localhost` works, but accessing your dev machine over plain LAN IP (`http://192.168.x.x:8080`) will fail unless served over HTTPS.

### Run Automated Tests
```powershell
# 1. Web Viewer unit & 4-locale i18n parity tests (27 tests)
node --test docs/viewer/test/*.test.js

# 2. Android unit tests + live bidirectional JVM <-> WebCrypto interop test (197 tests)
.\gradlew.bat testDebugUnitTest
```

---

## Step 6: Verification & Troubleshooting

Verify the live deployment returns `200 OK`:
```bash
curl -I https://<YOUR_USERNAME>.github.io/<YOUR_REPO>/viewer/
curl -I https://<YOUR_USERNAME>.github.io/<YOUR_REPO>/viewer/manifest.webmanifest
curl -I https://<YOUR_USERNAME>.github.io/<YOUR_REPO>/viewer/vendor/mqtt.min.js
```

| Symptom | Cause & Fix |
|---|---|
| `/settings/pages` returns `404` | Browser is not signed in as the repo owner/admin, or repo is private on GitHub Free. |
| `/viewer/` returns `404` right after enabling | Wait ~30–90s for **Actions → `pages build and deployment`** to complete, and ensure `docs/.nojekyll` is committed. |
| Pairing fails immediately on `http://...` | Always use **`https://`** (with trailing `/viewer/`). Plain HTTP disables `crypto.subtle` and microphone/camera access. |
| Mobile browser still shows an older UI after pushing | Bump `CACHE_NAME` in `docs/viewer/sw.js`, push, wait for Pages deploy, and refresh twice (or clear site data). |
| iPhone Safari forgets paired cameras after 7 days | Safari evicts tab `IndexedDB` after 7 days of inactivity. Tap **Share → Add to Home Screen** and pair once inside the Home Screen PWA. |
| Console logs CSP error on `blob:` worker | Keep `worker-src 'self' blob:;` in `docs/viewer/index.html` (used by `mqtt.min.js`'s keepalive timer worker). |
