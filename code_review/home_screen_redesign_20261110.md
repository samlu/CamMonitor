# Specification & Proposal: Home Screen UI Redesign & Dashboard Enhancement

**Document:** `code_review/home_screen_redesign_20261110.md`  
**Date:** 2026-11-10  
**Status:** APPROVED & IMPLEMENTED (Section 7 Operational Command Center Adopted)  
**Target Repository:** [`samlu/CamMonitor`](https://github.com/samlu/CamMonitor)  
**Author:** AI Pair Programming Assistant  

---

## 1. Executive Summary & Problem Diagnosis

### 1.1 Live Device Inspection (Google Pixel 6 Pro)
Inspection of the current Home Screen running on Pixel 6 Pro (`192.168.100.107:36103`, Android 16 / SDK 36, viewport $411 \times 914\text{ dp}$, resolution $1080 \times 2400\text{ px}$, 20:9 aspect ratio) revealed critical visual and interaction deficiencies:

```
┌────────────────────────────────────────────────────────┐
│ CamMonitor                     [🌐] [📄] [📱] [ℹ️]     │ TopAppBar (56dp)
├────────────────────────────────────────────────────────┤
│                                                        │
│                    EMPTY VOID                          │ ~260dp Dead Space
│                  (Black Pixels)                        │ (No branding, no status)
│                                                        │
├────────────────────────────────────────────────────────┤
│ ┌────────────────────────────────────────────────────┐ │
│ │ [📹]  啟動鏡頭模式                                 │ │ Camera Card (140dp)
│ │       2 個觀看端已配對 (可隨時串流)                │ │ Flat #1E1E1E surface
│ │  鏡頭名稱: 4F [✏️]                                 │ │ Bare vector icon
│ │  已配對觀看端 (2/2)                                │ │ No primary CTA button
│ └────────────────────────────────────────────────────┘ │
│                                                        │ 24dp spacing
│ ┌────────────────────────────────────────────────────┐ │
│ │ [👁️]  啟動觀看模式                                 │ │ Viewer Card (120dp)
│ │       尚未配對鏡頭 (輕觸進行配對)                  │ │ Bare vector icon
│ └────────────────────────────────────────────────────┘ │
│                                                        │
│                    EMPTY VOID                          │ ~250dp Dead Space
│                  (Black Pixels)                        │ (Unanchored layout)
│                                                        │
└────────────────────────────────────────────────────────┘
```

### 1.2 Core Issues
1. **~61% Vertical Dead Space**: On tall 20:9 modern phone displays, `Box(contentAlignment = Alignment.Center)` groups two compact cards (~320dp combined height) in the vertical center of an 830dp body area. Over 510dp of the viewport is completely vacant.
2. **Visual Flatness & Contrast Deficit**: Cards use `DarkSurfaceContainer` (`#1E1E1E`) sitting on `DarkSurface` (`#121212`). Without elevation shadows, borders, or gradient highlights, the contrast ratio is only 1.15:1—nearly invisible on high-gamut AMOLED panels.
3. **Unadorned Naked Icons**: Icons (`Icons.Default.Videocam` and `Icons.Default.Visibility`) float uncontained next to text, lacking the visual depth of modern Material 3 expressive squircle badges.
4. **Ambiguous Tap Affordance**: The entire card is wrapped in `.clickable { ... }`, but there is no visual arrow, launch button, or Call-to-Action (CTA) signaling what will happen.
5. **No System Status or Security Feedback**: CamMonitor's key differentiators (P2P zero-cloud architecture, local encryption, signaling health) are invisible on the primary entry screen.
6. **Friction in Multi-Camera Viewing**: If multiple cameras are paired, tapping "Start Viewer Mode" forces the user into an `AlertDialog` before playback begins, rather than providing direct 1-tap quick connect chips.

---

## 2. Proposed Architecture & UI Layout

```
┌────────────────────────────────────────────────────────┐
│ CamMonitor                     [🌐] [📄] [📱] [ℹ️]     │ TopAppBar (56dp)
├────────────────────────────────────────────────────────┤
│ 🛡️ P2P ENCRYPTED • ZERO CLOUD          ● SIGNAL READY  │ System Status Bar (36dp)
├────────────────────────────────────────────────────────┤
│ ┌────────────────────────────────────────────────────┐ │
│ │ ┌──────┐ CAMERA MODE            ┌────────────────┐ │ │
│ │ │ [📹] │ Broadcast video stream │ ● 2 Ready      │ │ │ Card 1: Broadcaster
│ │ └──────┘                        └────────────────┘ │ │ 20dp Rounded Corner
│ │                                                    │ │ 1dp Outline Variant
│ │  ┌─────────────────────────┐                       │ │
│ │  │ 🏷️ Camera Name: 4F  [✏️] │                       │ │ Tonal Device Chip
│ │  └─────────────────────────┘                       │ │
│ │                                                    │ │
│ │  Paired Viewers (2/2)     [ Launch Camera ➔ ]      │ │ Filled Primary CTA
│ └────────────────────────────────────────────────────┘ │
│                                                        │ 16dp spacing
│ ┌────────────────────────────────────────────────────┐ │
│ │ ┌──────┐ VIEWER MODE            ┌────────────────┐ │ │
│ │ │ [👁️] │ Watch paired live feed │ ● 1 Ready      │ │ │ Card 2: Monitor
│ │ └──────┘                        └────────────────┘ │ │
│ │                                                    │ │
│ │  Quick Connect:                                    │ │ 1-Tap Direct Launch
│ │  [ ▶ 4F ]   [ ▶ Front Door ]                       │ │ Filter/SuggestionChips
│ │                                                    │ │
│ │  Paired Cameras (2/8)     [ Open Viewer ➔ ]        │ │ Filled Tonal CTA
│ └────────────────────────────────────────────────────┘ │
├────────────────────────────────────────────────────────┤
│ 🔒 End-to-End Encrypted (AES-256-GCM) • Direct WebRTC │ Security Trust Footer
└────────────────────────────────────────────────────────┘
```

---

## 3. Detailed Component Specifications

### 3.1 Trust & System Status Bar
- **Location:** Directly below TopAppBar or `UpdateBannerCard`.
- **Styling:** Compact horizontal row (`padding(horizontal = 20.dp, vertical = 6.dp)`).
- **Contents:**
  - Left: Shield icon (`Icons.Default.Shield`, 14dp) + `"End-to-End Encrypted (P2P)"` in `typography.labelSmall`.
  - Right: Live indicator pill (`●`) + `"Zero Cloud Storage"`.
- **Purpose:** Anchors the top of the content area, immediately establishing trust and security context.

### 3.2 Material 3 Expressive Card Containers
- **Card Type:** `OutlinedCard` with `shape = RoundedCornerShape(20.dp)`.
- **Container Color:** `MaterialTheme.colorScheme.surfaceContainerHigh` (`#2A2A2A`).
- **Border:** `BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))`.
- **Icon Squircles:**
  - Camera Mode: 52dp $\times$ 52dp squircle (`shape = RoundedCornerShape(16.dp)`), background `MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)`, icon tint `CyanPrimary` (`#00E5FF`).
  - Viewer Mode: 52dp $\times$ 52dp squircle, background `MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)`, icon tint `MaterialTheme.colorScheme.secondary`.

### 3.3 Live Status Badges
- Displayed in the top-right corner of each card:
  - **Camera Card:**
    - If `pairedViewers.isNotEmpty()`: Green dot (`#00E676`) + `"<count> Ready"` in `StatusLive.copy(alpha = 0.15f)` container.
    - If empty: Gray dot + `"Standby"`.
  - **Viewer Card:**
    - If `pairedCameras.isNotEmpty()`: Green dot + `"<count> Ready"`.
    - If empty: Amber dot (`#FFD600`) + `"Needs Pairing"`.

### 3.4 Camera Name Chip & Action CTAs
- **Camera Name:** Encapsulated in a dedicated tonal container (`surfaceContainerHighest`) with rounded corners (10dp), clear typography, and a 16dp pencil icon button.
- **Explicit Action Buttons:**
  - **Camera Mode:** `Button` (Filled, `CyanPrimary`, `RoundedCornerShape(12.dp)`) with `"Start Broadcast"` / `"Pair Viewer"` text and `Icons.AutoMirrored.Filled.ArrowForward`.
  - **Viewer Mode:** `FilledTonalButton` with `"Open Viewer"` / `"Pair Camera"`.

### 3.5 Quick-Connect Direct Stream Chips (Viewer Mode)
- When 1 to 3 cameras are paired, render direct `SuggestionChip` buttons (`[▶ Camera Name]`).
- Tapping a chip immediately invokes `onStartViewerMode(cam.deviceId)`—saving the user a modal dialog tap.

### 3.6 Security & Architecture Footer
- Placed at the bottom of the scrollable column:
  - Shield / Lock icon + `"End-to-End Encrypted (ECDH + AES-256-GCM) • Direct P2P • Zero Cloud"`.
  - Visual color: `onSurfaceVariant.copy(alpha = 0.6f)`.
  - Fills the awkward bottom empty void with technical reassurance.

---

## 4. Exact Implementation (`HomeScreen.kt`)

```kotlin
package com.a0soft.gphone.camonitor.ui.home

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.a0soft.gphone.camonitor.BuildConfig
import com.a0soft.gphone.camonitor.CamMonitorApp
import com.a0soft.gphone.camonitor.R
import com.a0soft.gphone.camonitor.data.security.SecurityRepository
import com.a0soft.gphone.camonitor.data.update.ReleaseCheckResult
import com.a0soft.gphone.camonitor.data.update.ReleaseChecker
import com.a0soft.gphone.camonitor.data.update.ReleaseInfo
import com.a0soft.gphone.camonitor.data.update.UpdatePreferenceManager
import com.a0soft.gphone.camonitor.log.AppLogger
import com.a0soft.gphone.camonitor.ui.common.LocaleHelper
import com.a0soft.gphone.camonitor.ui.common.UpdateNotificationHelper
import com.a0soft.gphone.camonitor.ui.devices.revokePairedDevice
import com.a0soft.gphone.camonitor.ui.theme.StatusLive
import com.a0soft.gphone.camonitor.ui.theme.StatusWarning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onStartCameraMode: () -> Unit,
    onPairNewViewer: () -> Unit,
    onStartViewerMode: (deviceId: String) -> Unit,
    onPairNewCamera: () -> Unit,
    onOpenPairedDevices: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val context = LocalContext.current
    val securityRepo = remember { CamMonitorApp.instance.securityRepository }
    var cameraName by remember { mutableStateOf(securityRepo.getDeviceName()) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showSelectCameraDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var pairedViewers by remember { mutableStateOf(securityRepo.getPairedViewers()) }
    var pairedCameras by remember { mutableStateOf(securityRepo.getPairedCameras()) }

    val updatePrefs = remember { UpdatePreferenceManager(context) }
    var availableRelease by remember { mutableStateOf<ReleaseInfo?>(null) }

    LaunchedEffect(Unit) {
        UpdateNotificationHelper.dismissUpdateNotification(context)

        val cached = updatePrefs.getCachedRelease()
        if (cached != null && !updatePrefs.isVersionSkipped(cached.versionName)) {
            val eval = ReleaseChecker.evaluateRelease(cached, BuildConfig.VERSION_NAME)
            if (eval is ReleaseCheckResult.UpdateAvailable) {
                availableRelease = cached
            } else {
                updatePrefs.clearCachedRelease()
            }
        }

        if (updatePrefs.shouldCheck()) {
            val result = ReleaseChecker.checkLatestRelease()
            updatePrefs.recordCheck()
            if (result is ReleaseCheckResult.UpdateAvailable) {
                if (!updatePrefs.isVersionSkipped(result.release.versionName)) {
                    updatePrefs.saveCachedRelease(result.release)
                    availableRelease = result.release
                }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                pairedViewers = securityRepo.getPairedViewers()
                pairedCameras = securityRepo.getPairedCameras()
                cameraName = securityRepo.getDeviceName()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val scrollState = rememberScrollState()

    @Composable
    fun CameraCard(isLandscape: Boolean, modifier: Modifier = Modifier) {
        OutlinedCard(
            modifier = modifier
                .clickable {
                    val viewers = securityRepo.getPairedViewers()
                    pairedViewers = viewers
                    if (viewers.isEmpty()) onPairNewViewer() else onStartCameraMode()
                },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = if (isLandscape) {
                    Modifier.fillMaxSize().padding(16.dp)
                } else {
                    Modifier.fillMaxWidth().padding(20.dp)
                },
                verticalArrangement = if (isLandscape) Arrangement.SpaceBetween else Arrangement.spacedBy(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Header: Squircle Icon + Title + Status Pill
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(if (isLandscape) 44.dp else 52.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                        shape = RoundedCornerShape(16.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Videocam,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(if (isLandscape) 24.dp else 28.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = stringResource(R.string.start_camera_mode),
                                    style = if (isLandscape) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.home_camera_mode_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Live Status Pill
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (pairedViewers.isNotEmpty()) StatusLive.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(
                                            color = if (pairedViewers.isNotEmpty()) StatusLive else MaterialTheme.colorScheme.onSurfaceVariant,
                                            shape = CircleShape
                                        )
                                Thin
                                )
                                Text(
                                    text = if (pairedViewers.isNotEmpty()) {
                                        stringResource(R.string.home_card_status_ready, pairedViewers.size)
                                    } else {
                                        stringResource(R.string.home_card_status_standby)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (pairedViewers.isNotEmpty()) StatusLive else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // Tonal Camera Name Chip
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showRenameDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Label,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(R.string.home_camera_name_prefix, cameraName),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.action_rename),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Action Footer Row: Paired count & Explicit Launch Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.home_paired_viewers_count, pairedViewers.size, SecurityRepository.MAX_PAIRED_VIEWERS),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = {
                            if (pairedViewers.isEmpty()) onPairNewViewer() else onStartCameraMode()
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (pairedViewers.isEmpty()) stringResource(R.string.home_pair_viewer_button) else stringResource(R.string.home_action_start_broadcast),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = if (pairedViewers.isEmpty()) Icons.Default.Add else Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun ViewerCard(isLandscape: Boolean, modifier: Modifier = Modifier) {
        OutlinedCard(
            modifier = modifier
                .clickable {
                    val cameras = securityRepo.getPairedCameras()
                    pairedCameras = cameras
                    when {
                        cameras.isEmpty() -> onPairNewCamera()
                        cameras.size == 1 -> onStartViewerMode(cameras.first().deviceId)
                        else -> showSelectCameraDialog = true
                    }
                },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = if (isLandscape) {
                    Modifier.fillMaxSize().padding(16.dp)
                } else {
                    Modifier.fillMaxWidth().padding(20.dp)
                },
                verticalArrangement = if (isLandscape) Arrangement.SpaceBetween else Arrangement.spacedBy(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Header: Squircle Icon + Title + Status Pill
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(if (isLandscape) 44.dp else 52.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                                        shape = RoundedCornerShape(16.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(if (isLandscape) 24.dp else 28.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = stringResource(R.string.start_viewer_mode),
                                    style = if (isLandscape) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.home_viewer_mode_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Live Status Pill
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (pairedCameras.isNotEmpty()) StatusLive.copy(alpha = 0.15f) else StatusWarning.copy(alpha = 0.15f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(
                                            color = if (pairedCameras.isNotEmpty()) StatusLive else StatusWarning,
                                            shape = CircleShape
                                        )
                                )
                                Text(
                                    text = if (pairedCameras.isNotEmpty()) {
                                        stringResource(R.string.home_card_status_ready, pairedCameras.size)
                                    } else {
                                        stringResource(R.string.home_card_status_unpaired)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (pairedCameras.isNotEmpty()) StatusLive else StatusWarning,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // Direct Quick-Connect Chips for Paired Cameras
                    if (pairedCameras.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.home_quick_connect_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            pairedCameras.take(2).forEach { cam ->
                                SuggestionChip(
                                    onClick = { onStartViewerMode(cam.deviceId) },
                                    label = {
                                        Text(
                                            text = cam.name,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                )
                            }
                        }
                    }
                }

                // Action Footer Row: Paired count & Explicit Launch Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.home_paired_cameras_count, pairedCameras.size, SecurityRepository.MAX_PAIRED_CAMERAS),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FilledTonalButton(
                        onClick = {
                            val cameras = securityRepo.getPairedCameras()
                            pairedCameras = cameras
                            when {
                                cameras.isEmpty() -> onPairNewCamera()
                                cameras.size == 1 -> onStartViewerMode(cameras.first().deviceId)
                                else -> showSelectCameraDialog = true
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (pairedCameras.isEmpty()) stringResource(R.string.home_pair_camera_button) else stringResource(R.string.home_action_open_viewer),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = if (pairedCameras.isEmpty()) Icons.Default.Add else Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = { showLanguageDialog = true }) {
                        Icon(Icons.Default.Language, contentDescription = stringResource(R.string.cd_language_settings))
                    }
                    IconButton(onClick = onOpenLogs) {
                        Icon(Icons.Default.Description, contentDescription = stringResource(R.string.system_logs))
                    }
                    IconButton(onClick = onOpenPairedDevices) {
                        Icon(Icons.Default.Devices, contentDescription = stringResource(R.string.paired_devices))
                    }
                    IconButton(onClick = onOpenAbout) {
                        Icon(Icons.Default.Info, contentDescription = stringResource(R.string.about_title))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val configuration = LocalConfiguration.current
            val isLandscape = (maxWidth > maxHeight) ||
                (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) ||
                (configuration.screenWidthDp > configuration.screenHeightDp)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = if (isLandscape) 24.dp else 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Trust & Status Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = stringResource(R.string.home_status_e2ee),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = stringResource(R.string.home_status_zero_cloud),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }

                // Update Banner Card (if available)
                availableRelease?.let { release ->
                    UpdateBannerCard(
                        release = release,
                        onViewUpdate = { url ->
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                AppLogger.w("HomeScreen", "Failed to open release URL: ${e.message}")
                            }
                        },
                        onSkipVersion = { version ->
                            updatePrefs.skipVersion(version)
                            availableRelease = null
                        },
                        onDismiss = {
                            availableRelease = null
                        }
                    )
                }

                // Cards Layout (Adaptive Portrait vs Landscape)
                if (isLandscape) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CameraCard(isLandscape = true, modifier = Modifier.weight(1f).fillMaxHeight())
                        ViewerCard(isLandscape = true, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    CameraCard(isLandscape = false, modifier = Modifier.fillMaxWidth())
                    ViewerCard(isLandscape = false, modifier = Modifier.fillMaxWidth())
                }

                Spacer(modifier = Modifier.weight(1f, fill = false))

                // Bottom Security & Architecture Trust Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.home_security_footer),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }

    // Dialogs remain fully wired: Rename, Select Camera, Language picker
    if (showRenameDialog) {
        var tempName by remember { mutableStateOf(cameraName) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.dialog_rename_camera_title)) },
            text = {
                OutlinedTextField(
                    value = tempName,
                    onValueChange = { if (it.length <= 32) tempName = it },
                    label = { Text(stringResource(R.string.dialog_camera_name_label)) },
                    singleLine = true,
                    supportingText = { Text("${tempName.length}/32") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = tempName.trim()
                        if (trimmed.isNotEmpty()) {
                            securityRepo.setDeviceName(trimmed)
                            cameraName = trimmed
                        }
                        showRenameDialog = false
                    }
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showSelectCameraDialog) {
        AlertDialog(
            onDismissRequest = { showSelectCameraDialog = false },
            title = { Text(stringResource(R.string.dialog_select_camera_title)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.dialog_choose_camera_stream),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(pairedCameras) { device ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                showSelectCameraDialog = false
                                                onStartViewerMode(device.deviceId)
                                            }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Videocam,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(32.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = device.name,
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            Text(
                                                text = stringResource(R.string.device_id_format, device.deviceId.take(8)),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = stringResource(R.string.action_connect),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            revokePairedDevice(
                                                deviceId = device.deviceId,
                                                removeRecord = securityRepo::removePairedDevice,
                                                closePeer = { CamMonitorApp.instance.webRtcManager.closePeer(it) }
                                            )
                                            pairedCameras = securityRepo.getPairedCameras()
                                            if (pairedCameras.isEmpty()) {
                                                showSelectCameraDialog = false
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.action_remove_camera),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (pairedCameras.size < SecurityRepository.MAX_PAIRED_CAMERAS) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                showSelectCameraDialog = false
                                onPairNewCamera()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.home_pair_another_camera, pairedCameras.size, SecurityRepository.MAX_PAIRED_CAMERAS))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSelectCameraDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showLanguageDialog) {
        val currentTag = remember { LocaleHelper.getCurrentLanguageTag(context) }
        var selectedTag by remember { mutableStateOf(currentTag) }

        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.language_picker_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val options = listOf(
                        LocaleHelper.LANGUAGE_FOLLOW_SYSTEM to stringResource(R.string.language_system_default),
                        LocaleHelper.LANGUAGE_ZH_CN to stringResource(R.string.language_simplified_chinese),
                        LocaleHelper.LANGUAGE_ZH_TW to stringResource(R.string.language_traditional_chinese),
                        LocaleHelper.LANGUAGE_EN to stringResource(R.string.language_english),
                        LocaleHelper.LANGUAGE_JA to stringResource(R.string.language_japanese)
                    )

                    options.forEach { (tag, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedTag == tag,
                                    onClick = {
                                        selectedTag = tag
                                        showLanguageDialog = false
                                        LocaleHelper.setLanguage(context, tag)
                                    }
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedTag == tag,
                                onClick = {
                                    selectedTag = tag
                                    showLanguageDialog = false
                                    LocaleHelper.setLanguage(context, tag)
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        TextButton(
                            onClick = {
                                LocaleHelper.openSystemLanguageSettings(context)
                                showLanguageDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.language_open_system_settings))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
```

---

## 5. String Resource Synchronization (Strict 4-Locale Parity)

In accordance with Section 2 of `AGENTS.md`, the following keys must be added to all four string resource files:

### 5.1 English (`app/src/main/res/values/strings.xml`)
```xml
    <!-- Home Screen Redesign -->
    <string name="home_status_e2ee">End-to-End Encrypted (P2P)</string>
    <string name="home_status_zero_cloud">Zero Cloud Storage</string>
    <string name="home_card_status_ready">%1$d Ready</string>
    <string name="home_card_status_standby">Standby</string>
    <string name="home_card_status_unpaired">Needs Pairing</string>
    <string name="home_camera_mode_desc">Broadcast live video &amp; audio feed</string>
    <string name="home_viewer_mode_desc">Monitor paired camera feeds</string>
    <string name="home_action_start_broadcast">Start Broadcast</string>
    <string name="home_action_open_viewer">Open Viewer</string>
    <string name="home_quick_connect_label">Quick Connect</string>
    <string name="home_security_footer">Direct P2P • End-to-End Encrypted (AES-256-GCM) • Zero Cloud</string>
```

### 5.2 Traditional Chinese (`app/src/main/res/values-b+zh+Hant/strings.xml`)
```xml
    <!-- Home Screen Redesign -->
    <string name="home_status_e2ee">端到端加密 (P2P)</string>
    <string name="home_status_zero_cloud">零雲端儲存</string>
    <string name="home_card_status_ready">%1$d 個就緒</string>
    <string name="home_card_status_standby">待命中</string>
    <string name="home_card_status_unpaired">尚未配對</string>
    <string name="home_camera_mode_desc">廣播即時影音串流至已配對端</string>
    <string name="home_viewer_mode_desc">即時監看已配對鏡頭畫面</string>
    <string name="home_action_start_broadcast">開始廣播</string>
    <string name="home_action_open_viewer">開啟監看</string>
    <string name="home_quick_connect_label">快速連線</string>
    <string name="home_security_footer">點對點直連 • 端到端加密 (AES-256-GCM) • 零雲端儲存</string>
```

### 5.3 Simplified Chinese (`app/src/main/res/values-b+zh+Hans/strings.xml`)
```xml
    <!-- Home Screen Redesign -->
    <string name="home_status_e2ee">端到端加密 (P2P)</string>
    <string name="home_status_zero_cloud">零云端存储</string>
    <string name="home_card_status_ready">%1$d 个就绪</string>
    <string name="home_card_status_standby">待命中</string>
    <string name="home_card_status_unpaired">尚未配对</string>
    <string name="home_camera_mode_desc">广播实时音视频流至已配对端</string>
    <string name="home_viewer_mode_desc">实时监看已配对镜头画面</string>
    <string name="home_action_start_broadcast">开始广播</string>
    <string name="home_action_open_viewer">打开监看</string>
    <string name="home_quick_connect_label">快速连接</string>
    <string name="home_security_footer">点对点直连 • 端到端加密 (AES-256-GCM) • 零云端存储</string>
```

### 5.4 Japanese (`app/src/main/res/values-ja/strings.xml`)
```xml
    <!-- Home Screen Redesign -->
    <string name="home_status_e2ee">エンドツーエンド暗号化 (P2P)</string>
    <string name="home_status_zero_cloud">クラウド保存ゼロ</string>
    <string name="home_card_status_ready">%1$d台準備完了</string>
    <string name="home_card_status_standby">待機中</string>
    <string name="home_card_status_unpaired">未ペアリング</string>
    <string name="home_camera_mode_desc">ペアリング端末へ映像・音声をリアルタイム配信</string>
    <string name="home_viewer_mode_desc">ペアリング済みカメラの映像をリアルタイム監視</string>
    <string name="home_action_start_broadcast">配信開始</string>
    <string name="home_action_open_viewer">監視画面を開く</string>
    <string name="home_quick_connect_label">クイック接続</string>
    <string name="home_security_footer">P2P直接接続 • エンドツーエンド暗号化 (AES-256-GCM) • クラウド不使用</string>
```

---

## 6. Verification & Test Plan

1. **Locale Parity Check**:
   - Run `.\gradlew.bat testDebugUnitTest --tests com.a0soft.gphone.camonitor.i18n.LocaleResourcesTest`.
   - Verifies that all 11 new keys exist identically across all 4 locales with zero missing keys.
2. **Build & APK Assembly**:
   - Run `.\gradlew.bat assembleDebug`.
3. **Live Device Deployment (Pixel 6 Pro)**:
   - Install to Pixel 6 Pro via wireless ADB:
     ```powershell
     $apk = Get-ChildItem app\build\outputs\apk\debug\*.apk | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
     & "E:\Android\Sdk\platform-tools\adb.exe" -s 192.168.100.107:36103 install -r $apk
     & "E:\Android\Sdk\platform-tools\adb.exe" -s 192.168.100.107:36103 shell am start -n com.a0soft.gphone.camonitor/.MainActivity
     ```
   - Capture before-and-after screenshot comparisons.


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

### 7.3 Visual Wireframe

```
┌────────────────────────────────────────────────────────┐
│ CamMonitor                     [🌐] [📄] [📱] [ℹ️]     │ TopAppBar (56dp)
├────────────────────────────────────────────────────────┤
│ [ ⚡ 94% Charging ]          [ 📶 Wi-Fi: Home-5G ]     │ Hardware Pre-Flight Bar
├────────────────────────────────────────────────────────┤
│  BROADCASTER (THIS DEVICE)                             │ Section Header
│ ┌────────────────────────────────────────────────────┐ │
│ │ 📹 Camera Name: [ Nursery Cam ✏️ ]                  │ │ Broadcaster Card
│ │    Paired Viewers: 2/2 Authorized                  │ │
│ │                                                    │ │
│ │  [ 🌙 Start Stealth (Black) ]  [ ▶ Start Camera ]  │ │ Dual Launch Actions
│ └────────────────────────────────────────────────────┘ │
├────────────────────────────────────────────────────────┤
│  MONITORED CAMERAS (3)                    [ + Pair ]   │ Section Header + Action
│ ┌────────────────────────────────────────────────────┐ │
│ │ 📹 Living Room Cam                                 │ │ Direct Camera Tile 1
│ │    ID: 7fc9ef3e...                   [ Connect ▶ ] │ │ (1-Tap Direct Launch)
│ │    Paired: Nov 8                               [🗑️] │ │ (Dedicated Line Date)
│ └────────────────────────────────────────────────────┘ │
│ ┌────────────────────────────────────────────────────┐ │
│ │ 📹 Front Door Cam                                  │ │ Direct Camera Tile 2
│ │    ID: 3a10b912...                   [ Connect ▶ ] │ │ (1-Tap Direct Launch)
│ │    Paired: Oct 22                              [🗑️] │ │ (Dedicated Line Date)
│ └────────────────────────────────────────────────────┘ │
│ ┌────────────────────────────────────────────────────┐ │
│ │ 📹 Garage Cam                                      │ │ Direct Camera Tile 3
│ │    ID: e4d120a1...                   [ Connect ▶ ] │ │ (1-Tap Direct Launch)
│ │    Paired: Oct 16                              [🗑️] │ │ (Dedicated Line Date)
│ └────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────┘
```

---

### 7.4 Concrete Implementation (`HomeScreen.kt`)

```kotlin
package com.a0soft.gphone.camonitor.ui.home

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.a0soft.gphone.camonitor.BuildConfig
import com.a0soft.gphone.camonitor.CamMonitorApp
import com.a0soft.gphone.camonitor.R
import com.a0soft.gphone.camonitor.data.model.PairedDevice
import com.a0soft.gphone.camonitor.data.security.SecurityRepository
import com.a0soft.gphone.camonitor.data.update.ReleaseCheckResult
import com.a0soft.gphone.camonitor.data.update.ReleaseChecker
import com.a0soft.gphone.camonitor.data.update.ReleaseInfo
import com.a0soft.gphone.camonitor.data.update.UpdatePreferenceManager
import com.a0soft.gphone.camonitor.log.AppLogger
import com.a0soft.gphone.camonitor.ui.common.BatteryHelper
import com.a0soft.gphone.camonitor.ui.common.BatteryInfo
import com.a0soft.gphone.camonitor.ui.common.LocaleHelper
import com.a0soft.gphone.camonitor.ui.common.UpdateNotificationHelper
import com.a0soft.gphone.camonitor.ui.devices.revokePairedDevice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onStartCameraMode: (startInStealth: Boolean) -> Unit,
    onPairNewViewer: () -> Unit,
    onStartViewerMode: (deviceId: String) -> Unit,
    onPairNewCamera: () -> Unit,
    onOpenPairedDevices: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val context = LocalContext.current
    val securityRepo = remember { CamMonitorApp.instance.securityRepository }
    var cameraName by remember { mutableStateOf(securityRepo.getDeviceName()) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var pairedViewers by remember { mutableStateOf(securityRepo.getPairedViewers()) }
    var pairedCameras by remember { mutableStateOf(securityRepo.getPairedCameras()) }
    var batteryInfo by remember { mutableStateOf(BatteryHelper.getBatteryInfo(context)) }
    var networkType by remember { mutableStateOf(getNetworkStatus(context)) }

    val updatePrefs = remember { UpdatePreferenceManager(context) }
    var availableRelease by remember { mutableStateOf<ReleaseInfo?>(null) }

    LaunchedEffect(Unit) {
        UpdateNotificationHelper.dismissUpdateNotification(context)

        val cached = updatePrefs.getCachedRelease()
        if (cached != null && !updatePrefs.isVersionSkipped(cached.versionName)) {
            val eval = ReleaseChecker.evaluateRelease(cached, BuildConfig.VERSION_NAME)
            if (eval is ReleaseCheckResult.UpdateAvailable) {
                availableRelease = cached
            } else {
                updatePrefs.clearCachedRelease()
            }
        }

        if (updatePrefs.shouldCheck()) {
            val result = ReleaseChecker.checkLatestRelease()
            updatePrefs.recordCheck()
            if (result is ReleaseCheckResult.UpdateAvailable) {
                if (!updatePrefs.isVersionSkipped(result.release.versionName)) {
                    updatePrefs.saveCachedRelease(result.release)
                    availableRelease = result.release
                }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                pairedViewers = securityRepo.getPairedViewers()
                pairedCameras = securityRepo.getPairedCameras()
                cameraName = securityRepo.getDeviceName()
                batteryInfo = BatteryHelper.getBatteryInfo(context)
                networkType = getNetworkStatus(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val scrollState = rememberScrollState()

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = { showLanguageDialog = true }) {
                        Icon(Icons.Default.Language, contentDescription = stringResource(R.string.cd_language_settings))
                    }
                    IconButton(onClick = onOpenLogs) {
                        Icon(Icons.Default.Description, contentDescription = stringResource(R.string.system_logs))
                    }
                    IconButton(onClick = onOpenPairedDevices) {
                        Icon(Icons.Default.Devices, contentDescription = stringResource(R.string.paired_devices))
                    }
                    IconButton(onClick = onOpenAbout) {
                        Icon(Icons.Default.Info, contentDescription = stringResource(R.string.about_title))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val configuration = LocalConfiguration.current
            val isLandscape = (maxWidth > maxHeight) ||
                (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) ||
                (configuration.screenWidthDp > configuration.screenHeightDp)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = if (isLandscape) 24.dp else 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Hardware Pre-Flight Telemetry Bar
                HardwarePreflightBar(batteryInfo = batteryInfo, networkType = networkType)

                // 2. Update Banner Card (if available)
                availableRelease?.let { release ->
                    UpdateBannerCard(
                        release = release,
                        onViewUpdate = { url ->
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                AppLogger.w("HomeScreen", "Failed to open release URL: ${e.message}")
                            }
                        },
                        onSkipVersion = { version ->
                            updatePrefs.skipVersion(version)
                            availableRelease = null
                        },
                        onDismiss = {
                            availableRelease = null
                        }
                    )
                }

                if (isLandscape) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Left Column: Broadcaster Console
                        BroadcasterConsoleCard(
                            cameraName = cameraName,
                            pairedViewers = pairedViewers,
                            onRename = { showRenameDialog = true },
                            onStartNormal = {
                                if (pairedViewers.isEmpty()) onPairNewViewer() else onStartCameraMode(false)
                            },
                            onStartStealth = {
                                if (pairedViewers.isEmpty()) onPairNewViewer() else onStartCameraMode(true)
                            },
                            onPairViewer = onPairNewViewer,
                            modifier = Modifier.weight(1f)
                        )

                        // Right Column: Monitored Cameras
                        MonitoredCamerasSection(
                            pairedCameras = pairedCameras,
                            onConnect = onStartViewerMode,
                            onPairCamera = onPairNewCamera,
                            onRemoveCamera = { deviceId ->
                                revokePairedDevice(
                                    deviceId = deviceId,
                                    removeRecord = securityRepo::removePairedDevice,
                                    closePeer = { CamMonitorApp.instance.webRtcManager.closePeer(it) }
                                )
                                pairedCameras = securityRepo.getPairedCameras()
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    // Portrait: Stacked Sections
                    BroadcasterConsoleCard(
                        cameraName = cameraName,
                        pairedViewers = pairedViewers,
                        onRename = { showRenameDialog = true },
                        onStartNormal = {
                            if (pairedViewers.isEmpty()) onPairNewViewer() else onStartCameraMode(false)
                        },
                        onStartStealth = {
                            if (pairedViewers.isEmpty()) onPairNewViewer() else onStartCameraMode(true)
                        },
                        onPairViewer = onPairNewViewer,
                        modifier = Modifier.fillMaxWidth()
                    )

                    MonitoredCamerasSection(
                        pairedCameras = pairedCameras,
                        onConnect = onStartViewerMode,
                        onPairCamera = onPairNewCamera,
                        onRemoveCamera = { deviceId ->
                            revokePairedDevice(
                                deviceId = deviceId,
                                removeRecord = securityRepo::removePairedDevice,
                                closePeer = { CamMonitorApp.instance.webRtcManager.closePeer(it) }
                            )
                            pairedCameras = securityRepo.getPairedCameras()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    if (showRenameDialog) {
        var tempName by remember { mutableStateOf(cameraName) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.dialog_rename_camera_title)) },
            text = {
                OutlinedTextField(
                    value = tempName,
                    onValueChange = { if (it.length <= 32) tempName = it },
                    label = { Text(stringResource(R.string.dialog_camera_name_label)) },
                    singleLine = true,
                    supportingText = { Text("${tempName.length}/32") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = tempName.trim()
                        if (trimmed.isNotEmpty()) {
                            securityRepo.setDeviceName(trimmed)
                            cameraName = trimmed
                        }
                        showRenameDialog = false
                    }
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showLanguageDialog) {
        val currentTag = remember { LocaleHelper.getCurrentLanguageTag(context) }
        var selectedTag by remember { mutableStateOf(currentTag) }

        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.language_picker_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val options = listOf(
                        LocaleHelper.LANGUAGE_FOLLOW_SYSTEM to stringResource(R.string.language_system_default),
                        LocaleHelper.LANGUAGE_ZH_CN to stringResource(R.string.language_simplified_chinese),
                        LocaleHelper.LANGUAGE_ZH_TW to stringResource(R.string.language_traditional_chinese),
                        LocaleHelper.LANGUAGE_EN to stringResource(R.string.language_english),
                        LocaleHelper.LANGUAGE_JA to stringResource(R.string.language_japanese)
                    )

                    options.forEach { (tag, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedTag == tag,
                                    onClick = {
                                        selectedTag = tag
                                        showLanguageDialog = false
                                        LocaleHelper.setLanguage(context, tag)
                                    }
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedTag == tag,
                                onClick = {
                                    selectedTag = tag
                                    showLanguageDialog = false
                                    LocaleHelper.setLanguage(context, tag)
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        TextButton(
                            onClick = {
                                LocaleHelper.openSystemLanguageSettings(context)
                                showLanguageDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.language_open_system_settings))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun HardwarePreflightBar(
    batteryInfo: BatteryInfo,
    networkType: NetworkType
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Battery Status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val isLow = batteryInfo.percentage in 0..20 && !batteryInfo.isCharging
                Icon(
                    imageVector = if (batteryInfo.isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryStd,
                    contentDescription = null,
                    tint = if (isLow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = when {
                        batteryInfo.isCharging -> stringResource(R.string.home_hw_charging, batteryInfo.percentage)
                        isLow -> stringResource(R.string.home_hw_battery_low_warning, batteryInfo.percentage)
                        else -> stringResource(R.string.home_hw_battery, batteryInfo.percentage)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isLow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
            }

            // Network Status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = when (networkType) {
                        NetworkType.WIFI -> Icons.Default.Wifi
                        NetworkType.CELLULAR -> Icons.Default.SignalCellularAlt
                        NetworkType.DISCONNECTED -> Icons.Default.WifiOff
                    },
                    contentDescription = null,
                    tint = if (networkType == NetworkType.DISCONNECTED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = when (networkType) {
                        NetworkType.WIFI -> stringResource(R.string.home_hw_wifi_ready)
                        NetworkType.CELLULAR -> stringResource(R.string.home_hw_cellular)
                        NetworkType.DISCONNECTED -> stringResource(R.string.home_hw_no_network)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (networkType == NetworkType.DISCONNECTED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BroadcasterConsoleCard(
    cameraName: String,
    pairedViewers: List<PairedDevice>,
    onRename: () -> Unit,
    onStartNormal: () -> Unit,
    onStartStealth: () -> Unit,
    onPairViewer: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.home_section_broadcaster),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (pairedViewers.isNotEmpty()) {
                                stringResource(R.string.home_broadcaster_viewers_authorized, pairedViewers.size, SecurityRepository.MAX_PAIRED_VIEWERS)
                            } else {
                                stringResource(R.string.home_broadcaster_no_viewers)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (pairedViewers.size < SecurityRepository.MAX_PAIRED_VIEWERS) {
                    IconButton(onClick = onPairViewer) {
                        Icon(
                            imageVector = Icons.Default.PersonAdd,
                            contentDescription = stringResource(R.string.home_pair_viewer_button),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Editable Name Chip
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRename() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Label,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.home_camera_name_prefix, cameraName),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.action_rename),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Dual Launch Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Stealth Black Screen Direct Launch
                FilledTonalButton(
                    onClick = onStartStealth,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DarkMode,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.home_broadcaster_stealth_btn),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Standard Live Launch
                Button(
                    onClick = onStartNormal,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.home_broadcaster_start_btn),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun MonitoredCamerasSection(
    pairedCameras: List<PairedDevice>,
    onConnect: (deviceId: String) -> Unit,
    onPairCamera: () -> Unit,
    onRemoveCamera: (deviceId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Section Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.home_section_monitored_cameras, pairedCameras.size),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (pairedCameras.size < SecurityRepository.MAX_PAIRED_CAMERAS) {
                TextButton(
                    onClick = onPairCamera,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.home_pair_camera_button), style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // Camera List
        if (pairedCameras.isEmpty()) {
            OutlinedCard(
                onClick = onPairCamera,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = stringResource(R.string.home_pair_camera_card),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.home_pair_camera_card_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            pairedCameras.forEach { camera ->
                DirectCameraTile(
                    camera = camera,
                    onConnect = { onConnect(camera.deviceId) },
                    onRemove = { onRemoveCamera(camera.deviceId) }
                )
            }
        }
    }
}

@Composable
private fun DirectCameraTile(
    camera: PairedDevice,
    onConnect: () -> Unit,
    onRemove: () -> Unit
) {
    val dateStr = remember(camera.pairedTimestamp) {
        val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
        sdf.format(Date(camera.pairedTimestamp))
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = camera.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.device_id_format, camera.deviceId.take(8)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.home_tile_paired_date, dateStr),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilledTonalButton(
                    onClick = onConnect,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.home_tile_connect_btn),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                }

                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = stringResource(R.string.action_remove_camera),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

enum class NetworkType {
    WIFI,
    CELLULAR,
    DISCONNECTED
}

private fun getNetworkStatus(context: Context): NetworkType {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return NetworkType.DISCONNECTED
    val activeNetwork = cm.activeNetwork ?: return NetworkType.DISCONNECTED
    val caps = cm.getNetworkCapabilities(activeNetwork) ?: return NetworkType.DISCONNECTED
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
        else -> NetworkType.DISCONNECTED
    }
}
```

---

### 7.5 String Resource Synchronization (Strict 4-Locale Parity)

The following keys support the Operational Command Center architecture across all 4 required locales:

#### English (`app/src/main/res/values/strings.xml`)
```xml
    <!-- Operational Home Command Center -->
    <string name="home_hw_charging">%1$d%% ⚡ Charging</string>
    <string name="home_hw_battery">%1$d%% Battery</string>
    <string name="home_hw_battery_low_warning">%1$d%% ⚠️ Low Battery</string>
    <string name="home_hw_wifi_ready">Wi-Fi Ready</string>
    <string name="home_hw_cellular">Cellular Data</string>
    <string name="home_hw_no_network">No Network</string>
    <string name="home_section_broadcaster">BROADCASTER (THIS DEVICE)</string>
    <string name="home_section_monitored_cameras">MONITORED CAMERAS (%1$d)</string>
    <string name="home_broadcaster_stealth_btn">Stealth (Black)</string>
    <string name="home_broadcaster_start_btn">Start Camera</string>
    <string name="home_broadcaster_viewers_authorized">%1$d/%2$d Viewers Authorized</string>
    <string name="home_broadcaster_no_viewers">No viewers paired yet</string>
    <string name="home_tile_connect_btn">Connect</string>
    <string name="home_tile_paired_date">Paired: %1$s</string>
    <string name="home_pair_camera_card">Pair a New Camera</string>
    <string name="home_pair_camera_card_desc">Tap to scan QR code from another CamMonitor device</string>
    <string name="home_confirm_unpair_title">Unpair Camera</string>
    <string name="home_confirm_unpair_message">Are you sure you want to unpair \"%1$s\"? You will need to scan its QR code again to reconnect.</string>
    <string name="home_confirm_unpair_action">Unpair</string>
```

#### Traditional Chinese (`app/src/main/res/values-b+zh+Hant/strings.xml`)
```xml
    <!-- Operational Home Command Center -->
    <string name="home_hw_charging">%1$d%% ⚡ 充電中</string>
    <string name="home_hw_battery">%1$d%% 電量</string>
    <string name="home_hw_battery_low_warning">%1$d%% ⚠️ 電量過低</string>
    <string name="home_hw_wifi_ready">Wi-Fi 已連線</string>
    <string name="home_hw_cellular">行動網路</string>
    <string name="home_hw_no_network">無網路連線</string>
    <string name="home_section_broadcaster">鏡頭端 (本裝置)</string>
    <string name="home_section_monitored_cameras">監看鏡頭 (%1$d)</string>
    <string name="home_broadcaster_stealth_btn">黑畫面啟動</string>
    <string name="home_broadcaster_start_btn">啟動鏡頭</string>
    <string name="home_broadcaster_viewers_authorized">已授權觀看端 (%1$d/%2$d)</string>
    <string name="home_broadcaster_no_viewers">尚未配對任何觀看端</string>
    <string name="home_tile_connect_btn">連線</string>
    <string name="home_tile_paired_date">配對日期：%1$s</string>
    <string name="home_pair_camera_card">配對新鏡頭</string>
    <string name="home_pair_camera_card_desc">輕觸以掃描另一台 CamMonitor 裝置的 QR 碼</string>
    <string name="home_confirm_unpair_title">解除鏡頭配對</string>
    <string name="home_confirm_unpair_message">確定要解除與「%1$s」的配對嗎？解除後需重新掃描 QR 碼才能連線。</string>
    <string name="home_confirm_unpair_action">解除配對</string>
```

#### Simplified Chinese (`app/src/main/res/values-b+zh+Hans/strings.xml`)
```xml
    <!-- Operational Home Command Center -->
    <string name="home_hw_charging">%1$d%% ⚡ 充电中</string>
    <string name="home_hw_battery">%1$d%% 电量</string>
    <string name="home_hw_battery_low_warning">%1$d%% ⚠️ 电量过低</string>
    <string name="home_hw_wifi_ready">Wi-Fi 已连接</string>
    <string name="home_hw_cellular">移动网络</string>
    <string name="home_hw_no_network">无网络连接</string>
    <string name="home_section_broadcaster">广播端 (本设备)</string>
    <string name="home_section_monitored_cameras">监控镜头列表 (%1$d)</string>
    <string name="home_broadcaster_stealth_btn">极致黑屏</string>
    <string name="home_broadcaster_start_btn">启动镜头</string>
    <string name="home_broadcaster_viewers_authorized">%1$d/%2$d 个已授权观看端</string>
    <string name="home_broadcaster_no_viewers">尚未配对任何观看端</string>
    <string name="home_tile_connect_btn">连接</string>
    <string name="home_tile_paired_date">配对时间: %1$s</string>
    <string name="home_pair_camera_card">配对新镜头</string>
    <string name="home_pair_camera_card_desc">轻触以扫描另一台 CamMonitor 设备的二维码</string>
    <string name="home_confirm_unpair_title">解除镜头配对</string>
    <string name="home_confirm_unpair_message">确定要解除与“%1$s”的配对吗？解除后需重新扫描二维码才能连接。</string>
    <string name="home_confirm_unpair_action">解除配对</string>
```

#### Japanese (`app/src/main/res/values-ja/strings.xml`)
```xml
    <!-- Operational Home Command Center -->
    <string name="home_hw_charging">%1$d%% ⚡ 充電中</string>
    <string name="home_hw_battery">%1$d%% バッテリー</string>
    <string name="home_hw_battery_low_warning">%1$d%% ⚠️ バッテリー低下</string>
    <string name="home_hw_wifi_ready">Wi-Fi 接続済み</string>
    <string name="home_hw_cellular">モバイル通信</string>
    <string name="home_hw_no_network">ネットワーク未接続</string>
    <string name="home_section_broadcaster">配信端末 (このデバイス)</string>
    <string name="home_section_monitored_cameras">監視カメラ一覧 (%1$d)</string>
    <string name="home_broadcaster_stealth_btn">消灯ステルス</string>
    <string name="home_broadcaster_start_btn">カメラ起動</string>
    <string name="home_broadcaster_viewers_authorized">%1$d/%2$d台 認証済み</string>
    <string name="home_broadcaster_no_viewers">未ペアリング</string>
    <string name="home_tile_connect_btn">接続</string>
    <string name="home_tile_paired_date">ペアリング: %1$s</string>
    <string name="home_pair_camera_card">新しいカメラをペアリング</string>
    <string name="home_pair_camera_card_desc">タップして別のCamMonitor端末のQRコードをスキャン</string>
    <string name="home_confirm_unpair_title">カメラのペアリング解除</string>
    <string name="home_confirm_unpair_message">「%1$s」のペアリングを解除してもよろしいですか？再接続するにはQRコードを再度スキャンする必要があります。</string>
    <string name="home_confirm_unpair_action">ペアリング解除</string>
```

---

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
