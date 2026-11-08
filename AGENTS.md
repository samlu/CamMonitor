# CamMonitor — AI Agent Working Guide (AGENTS.md)

Welcome to **CamMonitor**. This guide serves as the definitive reference for autonomous AI agents and coding assistants collaborating on this repository.

---

## 1. Core Operating Directives

1. **Concrete Implementations Only**:
   - Never provide vague or high-level hand-waving. Provide exact code, file paths, diffs, and precise terminal commands.
   - Preserve existing code comments, docstrings, and formatting conventions unless explicitly asked to modify them.
2. **Strict Commit & Push Policy**:
   - **NEVER** execute `git commit` or `git push` automatically. Always ask or wait for explicit user approval before committing or pushing.
3. **Specification & Documentation Parity**:
   - CamMonitor maintains a single source of truth: [`Specification.md`](file:///E:/Antigravity/CamMonitor/Specification.md).
   - Any architectural change, UI adjustment, protocol update, or new feature MUST be reflected in [`Specification.md`](file:///E:/Antigravity/CamMonitor/Specification.md).
4. **Multilingual README Synchronization**:
   - Whenever user workflows, pairing procedures, UI controls, runtime permissions, or feature behaviors change, immediately update all public README files: [`README.md`](file:///E:/Antigravity/CamMonitor/README.md) (English), [`README_TW.md`](file:///E:/Antigravity/CamMonitor/README_TW.md) (Traditional Chinese), and [`README_JA.md`](file:///E:/Antigravity/CamMonitor/README_JA.md) (Japanese).
   - Keep feature descriptions, operational steps, and localized terminology aligned across all three documents.
5. **Mandatory Test Coverage & PR Verification**:
   - Write dedicated unit test cases for any new or modified logic, data models, crypto operations, protocol envelopes, or resource mappings (under `app/src/test/java/com/a0soft/gphone/camonitor/`).
   - Run `.\gradlew.bat testDebugUnitTest` to ensure 100% test pass rate.
   - When generating or opening a Pull Request (PR), execute the test suite and explicitly append the test execution command, total test count, and pass results directly into the PR description.

---

## 2. Project Architecture & Tech Stack

CamMonitor is a private, zero-account, peer-to-peer encrypted home surveillance monitor for Android devices (pairing a camera sender device with viewer receiver devices).

- **Language & Runtime**: Kotlin 2.0.21, Java 17, Android Min SDK 26 (Android 8.0 Oreo), Target SDK 35 (Android 15).
- **UI Framework**: Jetpack Compose with Material3, Single-Activity architecture (`MainActivity.kt`).
- **Media & Streaming**:
  - **CameraX 1.4.0** (`androidx.camera:*`) for camera capture, resolution selection, and Night mode extensions.
  - **WebRTC Android 1.2.2** (`io.getstream:stream-webrtc-android`) for low-latency P2P video/audio streaming and DataChannel messaging.
- **Signaling**:
  - **HiveMQ MQTT Client 1.3.3** (`com.hivemq:hivemq-mqtt-client`) connecting via TLS (port 8883) to a public MQTT broker.
  - End-to-end payload encryption using AES-GCM-256 with pre-shared keys generated during QR code pairing.
- **Security & Storage**:
  - `androidx.security:security-crypto:1.1.0-alpha06` (`EncryptedSharedPreferences`).
- **Foreground Service**:
  - `CameraMonitorService.kt` keeps streaming active in the background with WakeLock and WifiLock.

---

## 3. Directory & File Map

```text
CamMonitor/
├── AGENTS.md                                # This agent operational guide
├── Specification.md                         # Authoritative product & technical specification
├── README.md / README_TW.md / README_JA.md  # Multilingual public documentation
├── app/
│   ├── build.gradle.kts                     # App module configuration & dependencies
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml          # Permissions, foreground services, activity
│       │   ├── java/com/a0soft/gphone/camonitor/
│       │   │   ├── MainActivity.kt          # Compose host activity & navigation routing
│       │   │   ├── camera/
│       │   │   │   └── CameraStreamManager.kt  # CameraX binding, sensor mapping, Night extension
│       │   │   ├── webrtc/
│       │   │   │   └── WebRtcManager.kt     # WebRTC PeerConnection, SDP offer/answer, DataChannel
│       │   │   ├── mqtt/
│       │   │   │   └── SignalingClient.kt   # HiveMQ MQTT TLS signaling & encrypted exchange
│       │   │   ├── service/
│       │   │   │   └── CameraMonitorService.kt # Sticky foreground streaming service
│       │   │   ├── data/
│       │   │   │   ├── DeviceRepository.kt  # Paired devices CRUD & limits
│       │   │   │   ├── PrefsManager.kt      # Encrypted preference storage
│       │   │   │   ├── CryptoManager.kt     # AES-GCM-256 key generation & encryption
│       │   │   │   └── model/Models.kt      # Domain data classes, signaling events, LogLevel
│       │   │   ├── log/
│       │   │   │   └── AppLogger.kt         # Ring-buffer UI logs, rolling disk files, zip export
│       │   │   └── ui/
│       │   │       ├── home/HomeScreen.kt           # Mode selection, pairing summaries, top bar
│       │   │       ├── camera/CameraScreen.kt       # Camera mode preview, OLED blackout saver
│       │   │       ├── viewer/ViewerScreen.kt       # WebRTC video surface, overlay controls
│       │   │       ├── pairing/
│       │   │       │   ├── CameraPairingScreen.kt   # Displays QR code (PA sender)
│       │   │       │   └── ViewerPairingScreen.kt   # ZXing QR code scanner (PB receiver)
│       │   │       ├── devices/ManageDevicesScreen.kt # Paired devices list & revocation
│       │   │       ├── log/LogViewerScreen.kt       # In-app log viewer, filters, verbosity menu
│       │   │       ├── about/AboutScreen.kt         # App version, license, contributors card
│       │   │       └── theme/                       # Material3 Color, Type, Theme definitions
│       │   └── res/
│       │       ├── values/strings.xml               # English / Default strings
│       │       ├── values-b+zh+Hant/strings.xml     # Traditional Chinese (Taiwan/HK)
│       │       ├── values-b+zh+Hans/strings.xml     # Simplified Chinese
│       │       └── values-ja/strings.xml            # Japanese
│       └── test/java/com/a0soft/gphone/camonitor/   # Comprehensive JUnit tests
```

---

## 4. Internationalization (i18n) Rules

CamMonitor strictly supports four locales:
1. `app/src/main/res/values/strings.xml` (Default / English)
2. `app/src/main/res/values-b+zh+Hant/strings.xml` (Traditional Chinese)
3. `app/src/main/res/values-b+zh+Hans/strings.xml` (Simplified Chinese)
4. `app/src/main/res/values-ja/strings.xml` (Japanese)

> [!IMPORTANT]
> **Strict Key Parity Requirement**:
> Any string resource key added, modified, or removed in `values/strings.xml` MUST be updated across all 4 resource files.
> This parity is automatically checked and enforced by `LocaleResourcesTest.kt`. If any locale misses a key, the test suite will fail.

---

## 5. Contributor Tracking Workflow

When PRs are merged or new contributors contribute to CamMonitor:

1. **Extract Metadata**:
   - Inspect Git log or GitHub PR metadata: `git log --format='%aN <%aE>' | sort -u`.
   - Identify: Display Name, GitHub Handle (`@username`), GitHub Profile URL, and contribution area.
2. **Update Code & Resources**:
   - In [`AboutScreen.kt`](file:///E:/Antigravity/CamMonitor/app/src/main/java/com/a0soft/gphone/camonitor/ui/about/AboutScreen.kt): Append `ContributorInfo` to `CONTRIBUTORS` list.
   - In all 4 `strings.xml` files: Add localized `about_contributor_<username>_role`.
   - In [`Specification.md`](file:///E:/Antigravity/CamMonitor/Specification.md): Update Section 5.7 ("Contributors Card").
3. **Verify**:
   - Run `.\gradlew.bat testDebugUnitTest` to guarantee `LocaleResourcesTest` passes.

---

## 6. Build, Test & Device Execution Commands

All commands run via PowerShell on Windows.

### Compilation & Unit Tests
```powershell
# Run all unit tests (77+ tests covering crypto, signaling, models, i18n parity)
.\gradlew.bat testDebugUnitTest

# Assemble debug APK
.\gradlew.bat assembleDebug

# Output APK path pattern:
# app/build/outputs/apk/debug/Camonitor-v<VERSION>-<TIMESTAMP>-debug.apk
```

### ADB Device Operations
Default Android SDK platform-tools path: `E:\Android\Sdk\platform-tools\adb.exe`

```powershell
# Check connected devices
& "E:\Android\Sdk\platform-tools\adb.exe" devices

# Discover dynamic wireless ADB TLS port (e.g., Pixel 6 Pro)
& "E:\Android\Sdk\platform-tools\adb.exe" mdns services

# Install latest debug APK
$apk = Get-ChildItem app\build\outputs\apk\debug\*.apk | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
& "E:\Android\Sdk\platform-tools\adb.exe" -s <DEVICE_ID> install -r $apk

# Launch MainActivity
& "E:\Android\Sdk\platform-tools\adb.exe" -s <DEVICE_ID> shell am start -n com.a0soft.gphone.camonitor/.MainActivity
```

---

## 7. Critical Domain & Architecture Gotchas

1. **CameraX Sensor Coordinate Space**:
   - Resolution selection must account for sensor orientation (e.g. natural landscape sensor vs portrait device display). Never force 1:1 or narrow aspect ratios that collapse CameraX into fallback `192x108` mode.
2. **WebRTC Hardware Codec & Rotation**:
   - WebRTC frames are rotated via hardware OpenGL texture matrices in a proxy `VideoSink`.
   - Always call `SurfaceViewRenderer.release()` and unbind camera providers when composables are disposed (`DisposableEffect`).
3. **Foreground Service vs Activity Lifecycle**:
   - Camera streaming runs in `CameraMonitorService`. Tearing down the UI `MainActivity` or navigating back MUST NOT terminate camera broadcasting or clean up `AppLogger` disk logging while `CameraMonitorService.isRunning == true`.
4. **Log Verbosity & Thermal Control**:
   - The top bar bug icon (`Icons.Default.BugReport`) opens the verbosity menu (`Verbose (DEBUG)` vs `Standard (INFO)`).
   - High-rate frame logs must be checked against `AppLogger.minLevel` to avoid disk fill and thermal throttling on older devices.
