# Specification & Proposal: Home Screen UI Redesign & Dashboard Enhancement

**Document:** `code_review/home_screen_redesign_20261110.md`  
**Date:** 2026-11-10  
**Status:** IMPLEMENTED. Authoritative behavior lives in `Specification.md` §5.1; this file is the decision record (rejected first proposal, adopted §7 design, §7.7 post-review fixes). Only `HomeViewModel` extraction remains open.  
**Target Repository:** [`samlu/CamMonitor`](https://github.com/samlu/CamMonitor)  
**Author:** AI Pair Programming Assistant  

---

## 1. Superseded First Proposal (§§1–6, summary)

The original draft (static "dashboard" with two big mode cards, duplicated security/trust badges, `● Ready` pills and `SuggestionChip` quick-connect) was **rejected** and its ~950 lines of design text and sample code were removed from this document. Its code was never shipped and contained non-compiling fragments, so it must not be used as a reference. What it got right and what was rejected:

**Diagnosis that still stands (Pixel 6 Pro, 411×914 dp, 20:9):**
- ~60% of the viewport was dead space on tall phones because two compact cards were centered vertically.
- Cards on `#1E1E1E` over `#121212` had ~1.15:1 contrast, nearly invisible on AMOLED (fixed by using `surfaceContainerHigh` `#2A2A2A` plus a 1dp outline).
- Bare icons, no explicit CTA, and an `AlertDialog` before viewing when several cameras were paired.

**Why the proposal was replaced by §7:**
| Rejected element | Reason |
|------------------|--------|
| Two redundant "E2E encrypted / zero cloud" badges (top and footer) | Marketing chrome added mainly to fill space; an operational tool should show operational data. |
| `● N Ready` status pills | CamMonitor has no server and no presence heartbeat, so the dot only meant "a whitelist record exists" and falsely implied the remote device was online. |
| 1–3 quick-connect `SuggestionChip`s in the Viewer card | Breaks down at 3–8 paired cameras and falls back to a dialog. |
| No hardware telemetry | Battery/charging and network are the real risks when deploying an old phone as a camera. |

**Kept from the first proposal:** `OutlinedCard` 20dp, `surfaceContainerHigh` surfaces with a 1dp `outlineVariant` border, squircle icon badges, tonal camera-name chip with pencil, explicit filled CTAs.

Authoritative current behavior: `Specification.md` §5.1 (Operational Command Center). The sections below record the adopted design (§7) and the post-implementation changes (§7.7).

---

## 7. Alternative Proposal: Operational Command Center & Direct Multi-Camera Hub

### 7.1 Problem with the Initial Proposal
1. **Marketing Chrome & Redundant Badges**:
   - The initial proposal placed two duplicate security badges (`🛡️ P2P ENCRYPTED • ZERO CLOUD` at the top and `🔒 End-to-End Encrypted (AES-256-GCM)...` at the bottom) primarily to fill dead space. This treats an operational utility like a marketing landing page.
2. **Misleading Presence States (`● Ready`)**:
   - CamMonitor operates without a centralized server or background heartbeat pings over MQTT. Showing a green dot with `"Ready"` misleadingly promises that a remote camera or viewer is actively online, when in reality it only confirms that a local whitelist record exists.
3. **Persistent Dialog Friction for Viewers**:
   - Placing 1–2 `SuggestionChips` inside the Viewer card fails when monitoring 3 to 8 cameras, dropping the user back into an `AlertDialog`. A surveillance dashboard should show cameras directly.
4. **Neglect of Deployment Hardware Telemetry**:
   - When deploying an old phone as a broadcaster camera (PA), the user's biggest real-world risks are:
     - Is the phone plugged in and charging? (A loose cable drains the battery in hours).
     - Is it connected to local Wi-Fi or burning mobile data?
   - The initial proposal omitted local hardware pre-flight metrics.

---

### 7.2 Architectural Principles & Key Enhancements
> **Note:** this section records the original intent. Where it differs from shipped behavior (3-line tile metadata, trash button, `Wi-Fi: <SSID>`), §7.7 and `Specification.md` §5.1 win.

1. **Hardware Pre-Flight Bar**:
   - Leverages `BatteryHelper.getBatteryInfo(context)` to display live local battery % and charging status (`⚡ 94% Charging` vs `⚠️ 15% Low Battery`).
   - Displays active network transport (`Wi-Fi: <SSID>` / `Cellular Data` / `Offline`).
2. **First-Class Direct Camera Tiles (Zero Dialogs & 3-Line Vertical Stack)**:
   - Every paired camera (up to 8) renders directly on the Home Screen as an interactive card.
   - Text metadata is structured into 3 distinct, dedicated vertical lines to eliminate horizontal truncation/ellipsis on compact viewports:
     - Line 1: Camera Name (`titleMedium`, `SemiBold`).
     - Line 2: Device ID prefix (`bodySmall`, `onSurfaceVariant`, `ID: %1$s...`).
     - Line 3: Paired timestamp (`bodySmall`, `onSurfaceVariant`, `Paired: <Date>`).
   - 1-tap `[Connect ▶]` launches streaming immediately.
   - Includes quick-action delete button (`[🗑️]`) with unpair confirmation dialog.
   - Empty state renders an inviting outlined card: `"+ Pair a Camera to Monitor"`.
3. **Direct-to-Black-Screen Stealth Launch**:
   - Broadcaster card provides dual launch actions:
     - `[▶ Start Camera]`: Standard launch with viewfinder active.
     - `[🌙 Start in Stealth]`: Launches camera streaming directly into the OLED Black Screen touch guard (`isLocked = true`, brightness `0.01f`, `FLAG_KEEP_SCREEN_ON` released). Prevents lighting up a dark bedroom or nursery upon activation.
4. **Role-Adaptive Layout Density**:
   - If the device has paired cameras and 0 viewers, the monitored camera list occupies primary focus while the broadcaster section collapses to a compact card.
   - If the device has paired viewers and 0 cameras, the broadcaster console takes primary focus with pre-flight diagnostics.

---

### 7.3 Visual Wireframe (current, default `BROADCASTER_FIRST` layout)

```
┌────────────────────────────────────────────────────────┐
│ CamMonitor                     [🌐] [📄] [📱] [ℹ️]     │ TopAppBar
├────────────────────────────────────────────────────────┤
│ [ ⚡ 94% Charging ]            [ 📶 Wi-Fi Ready ]      │ Hardware Pre-Flight Bar (live)
├────────────────────────────────────────────────────────┤
│ ┌────────────────────────────────────────────────────┐ │ cyan-tinted card + cyan border
│ │ 📹 BROADCASTER (THIS DEVICE)               [👤+]   │ │
│ │    2/2 Viewers Authorized                          │ │
│ │ [ 🏷 Camera Name: Nursery Cam            ✏️ ]      │ │
│ │ ⚠ Battery at 25% and not charging... (if <=30%)    │ │
│ │ [ 🌙 Stealth (Black) ]   [ ▶ Start Camera ]        │ │
│ └────────────────────────────────────────────────────┘ │
│                         28dp                           │
│  MONITORED CAMERAS (3)                    [ + Pair ]   │
│ ┌────────────────────────────────────────────────────┐ │ neutral tiles
│ │ 👁 Living Room Cam                    [ Connect ▶ ] │ │ tap tile = connect
│ │    Paired: Nov 8                                   │ │ long-press = unpair (confirm)
│ └────────────────────────────────────────────────────┘ │
│ ┌────────────────────────────────────────────────────┐ │
│ │ 👁 Front Door Cam                     [ Connect ▶ ] │ │
│ │    Paired: Oct 22                                  │ │
│ └────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────┘

Viewer-only device (cameras paired, 0 viewers) → CAMERAS_FIRST:
  MONITORED CAMERAS (3) ... tiles ...
  ┌ BROADCASTER (THIS DEVICE)                           ┐
  │ Camera Name: Nursery Cam ✏️   [ 👤+ Pair Viewer ]   │  compact row
  │ Pair a viewer to use this phone as a camera         │
  └─────────────────────────────────────────────────────┘
```

### 7.4 Implementation (see source)

The ~830 lines of sample `HomeScreen.kt` that used to live here were removed: they had drifted from the shipped code (trash button, `ID:` line, old network logic, old stealth flag) and are no longer a safe reference. The source of truth is:

| Concern | Location |
|---------|----------|
| Screen, sections, dialogs | `app/src/main/java/com/a0soft/gphone/camonitor/ui/home/HomeScreen.kt` |
| Pure logic (network classification, battery rules, launch route, stealth intent, layout choice) | `app/src/main/java/com/a0soft/gphone/camonitor/ui/home/HomeLogic.kt` |
| Navigation / stealth intent wiring | `app/src/main/java/com/a0soft/gphone/camonitor/MainActivity.kt` |
| Tests | `app/src/test/java/com/a0soft/gphone/camonitor/home/HomeLogicTest.kt` |
| Behavior spec | `Specification.md` §5.1 |

### 7.5 String Resources (see source)

The per-locale string tables that used to live here were removed (they were out of date after §7.7 added keys). Strings for the Home screen are the `home_*` keys in the four resource files, kept in strict key parity by `LocaleResourcesTest`:

- `app/src/main/res/values/strings.xml` (English / default)
- `app/src/main/res/values-b+zh+Hant/strings.xml` (Traditional Chinese, Taiwan terminology)
- `app/src/main/res/values-b+zh+Hans/strings.xml` (Simplified Chinese)
- `app/src/main/res/values-ja/strings.xml` (Japanese)

### 7.6 Device Verification & Layout Refinements
- **Date Truncation Resolution**: In multi-camera tiles (`DirectCameraTile`), placing the paired date inline after device ID caused ellipsis truncation on narrow viewports (e.g. Pixel 10 Pro). Split into 3 dedicated lines:
  1. Camera Name (`titleMedium`, `SemiBold`)
  2. Device ID prefix (`bodySmall`, `onSurfaceVariant`)
  3. Paired date (`bodySmall`, `onSurfaceVariant`, e.g. `配對日期：10月 22`)
- **Taiwan (zh-TW) Localization**: Harmonized all terminology to strict Taiwan local usage (`黑畫面啟動`, `鏡頭端 (本裝置)`, `行動網路`, `QR 碼`, full-width colons `：`).
- **Surface Elevation Harmonization (`surfaceContainerHigh` `#2A2A2A`)**:
  - Unified all card and container surfaces across screens to `surfaceContainerHigh` (`#2A2A2A`), stepping up from `#1E1E1E` to eliminate visual flatness against `#121212` (`DarkSurface`) window backgrounds:
    - **Home Screen**: `HardwarePreflightBar`, `BroadcasterConsoleCard`, `DirectCameraTile`, `EmptyMonitoredCamerasCard`.
    - **About Screen**: Info Card and Contributors Card.
    - **Manage Paired Devices Screen**: Paired device cards and pairing mode dialog cards.
- **Device Verification**: Verified with release build on Google Pixel 10 Pro (`5A111FDCH000GG`) and Pixel 6 Pro (`192.168.100.107:36103`). All unit tests passing (100% pass rate).


---

### 7.7 Post-Implementation Review Fixes (2026-11-10)
Authoritative behavior now lives in `Specification.md` §5.1; this section records *why* it changed.

| # | Finding | Resolution |
|---|---------|------------|
| 1 | `Wi-Fi Ready` shown for captive-portal / dead Wi-Fi; Ethernet and VPN-only links shown as `No Network` | `classifyNetwork()` (`HomeLogic.kt`) uses `NET_CAPABILITY_INTERNET` + `NET_CAPABILITY_VALIDATED`; new states `ETHERNET`, `NO_INTERNET` (amber). New strings `home_hw_ethernet`, `home_hw_no_internet` in all 4 locales. |
| 2 | Battery receiver registered without export flag | `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`; low-battery rule extracted to `isBatteryLow()`. |
| 3 | `startCameraInStealth` only reset in `onExitCameraMode`; system Back left it `true`, so the next pairing-success launch started locked | Replaced by `StealthLaunchIntent`; cleared in `navigateBack()` when popping `CAMERA_MODE` or `CAMERA_PAIRING`, and by explicit pair-viewer entry points. |
| 4 | Stealth tapped with 0 viewers silently became a bright normal launch after pairing | `HomeScreen` now always calls `onStartCameraMode(stealth)`; `MainActivity` records the intent, routes via `broadcasterLaunchRoute()`, and the intent survives the pairing detour. |
| 5 | Red trash button next to `Connect ▶` (costly mis-tap) and low-value `ID:` line on every tile | Removed both. Whole tile is `combinedClickable`: tap = connect, long-press = unpair (confirm dialog). IDs stay in Manage Devices. |
| 6 | Date format `"MMM d"` ignored locale ordering | `DateFormat.getBestDateTimePattern(locale, "MMMd")`. |

Tests: `home/HomeLogicTest.kt` (9 cases); full suite 164 tests, 0 failures (`.\gradlew.bat testDebugUnitTest`).

**Done in follow-up pass:** (a) portrait/landscape composable calls de-duplicated (each section declared once, placed by orientation); (b) role-adaptive ordering via `homeLayoutFor()` — viewer-only devices get cameras first and a `CompactBroadcasterRow` (+2 tests).

(c) inline low-battery hint (≤ 30% and not charging) above the launch buttons on the full Broadcaster card; (d) one-time first-run Stealth explanation dialog (`stealth_intro_seen` in `cammonitor_ui_prefs`). Tests: `HomeLogicTest` now 12 cases.

**Visual separation fix (Pixel 10 report: Broadcaster card too close to / confusable with camera tiles):** Broadcaster card (and compact row) now has a cyan-tinted surface + `primary` border; camera tiles use `Icons.Default.Visibility` instead of `Videocam`; portrait gap between the consoles raised from 16dp to 28dp.

**Compact row rename (Pixel 10 report):** in `CAMERAS_FIRST` layout the Broadcaster collapses to `CompactBroadcasterRow`, which had no way to rename the camera before starting. The name area is now tappable (pencil icon, "Rename" click label) and opens the same Rename dialog.

**Compact row simplified (Pixel 10 report):** `CompactBroadcasterRow` only exists when 0 viewers are paired, where Start/Stealth could only route to the pairing QR anyway (three buttons, one destination, and the first-run "Going dark" dialog fired before pairing). It now has one action, `Pair Viewer`, plus a hint line; rename stays. New string `home_compact_pair_hint` (4 locales).

**Still open:** `HomeViewModel` extraction (optional; suggested minimal scope: `callbackFlow` + `collectAsStateWithLifecycle()` for battery/network, and moving the update check out of the composable). Documentation cleanup is done: §§1–6 reduced to the §1 summary, §7.3 wireframe refreshed, §7.4/§7.5 replaced with pointers to source.
