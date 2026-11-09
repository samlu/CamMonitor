# CamMonitor — AI Agent Working Guide (AGENTS.md)

Welcome to **CamMonitor**. This guide serves as the definitive reference for autonomous AI agents and coding assistants collaborating on this repository.

---

## 1. Core Operating Directives

1. **Mandatory Specification Review**:
   - [`Specification.md`](file:///E:/Antigravity/CamMonitor/Specification.md) is the **single authoritative source of truth** for CamMonitor's architecture, tech stack, directory structure, security protocols, data models, and UI flows.
   - Before designing, implementing, refactoring, or reviewing any code, agents **MUST read `Specification.md`** to fully understand the project architecture and domain constraints.
   - Any architectural change, UI adjustment, protocol update, or new feature MUST be reflected in [`Specification.md`](file:///E:/Antigravity/CamMonitor/Specification.md).
2. **Concrete Implementations Only**:
   - Never provide vague or high-level hand-waving. Provide exact code, file paths, diffs, and precise terminal commands.
   - Preserve existing code comments, docstrings, and formatting conventions unless explicitly asked to modify them.
3. **Strict Commit & Push Policy**:
   - **NEVER** execute `git commit` or `git push` automatically. Always ask or wait for explicit user approval before committing or pushing.
4. **Multilingual README Synchronization**:
   - Whenever user workflows, pairing procedures, UI controls, runtime permissions, or feature behaviors change, immediately update all public README files: [`README.md`](file:///E:/Antigravity/CamMonitor/README.md) (English), [`README_TW.md`](file:///E:/Antigravity/CamMonitor/README_TW.md) (Traditional Chinese), and [`README_JA.md`](file:///E:/Antigravity/CamMonitor/README_JA.md) (Japanese).
   - Keep feature descriptions, operational steps, and localized terminology aligned across all three documents.
5. **Mandatory Test Coverage & PR Verification**:
   - Write dedicated unit test cases for any new or modified logic, data models, crypto operations, protocol envelopes, or resource mappings (under `app/src/test/java/com/a0soft/gphone/camonitor/`).
   - Run `.\gradlew.bat testDebugUnitTest` to ensure 100% test pass rate.
   - When generating or opening a Pull Request (PR), execute the test suite and explicitly append the test execution command, total test count, and pass results directly into the PR description.

---

## 2. Internationalization (i18n) Rules

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

## 3. Contributor Tracking Workflow

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

## 4. Build, Test & Device Execution Commands

All commands run via PowerShell on Windows.

### Compilation & Unit Tests
```powershell
# Run all unit tests (97+ tests covering crypto, signaling, models, i18n parity)
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

## 5. Critical Domain & Architecture Gotchas

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
5. **Display Keep-Awake & Black Screen Power (Gotcha #1)**:
   - `FLAG_KEEP_SCREEN_ON` MUST be held ONLY while unlocked (`!isLocked`).
   - When entering the OLED Black Screen (`isLocked == true`), `FLAG_KEEP_SCREEN_ON` MUST be released. Even on OLED panels displaying pure black (`#000000`) at 0.01 brightness, keeping `FLAG_KEEP_SCREEN_ON` blocks Android display suspend (`mHoldingDisplaySuspendBlocker`), burning ~180-240 mA continuously and preventing the SoC from entering deep sleep.
   - The touch guard is enforced by Compose `pointerInput` (`BlackScreenUnlockPolicy`) and does NOT depend on keeping the display awake.
6. **Dynamic WifiLock Lifecycle (Gotcha #2)**:
   - `WifiLock` (`WIFI_MODE_FULL_LOW_LATENCY` / `HIGH_PERF`) MUST NOT be held continuously during 0-viewer standby.
   - Holding `WifiLock` disables 802.11 DTIM power save polling, forcing the Wi-Fi radio to stay awake 100% of the time (~60-80 mA continuous drain).
   - Acquire `WifiLock` ONLY when `activeViewersCount > 0` (active WebRTC video/audio streaming); release it when `activeViewersCount == 0`. MQTT TLS signaling keepalive (30s) functions reliably over standard 802.11 DTIM sleep.
