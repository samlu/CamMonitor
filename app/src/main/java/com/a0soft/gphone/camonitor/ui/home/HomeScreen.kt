package com.a0soft.gphone.camonitor.ui.home

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.a0soft.gphone.camonitor.R
import com.a0soft.gphone.camonitor.data.model.PairedDevice
import com.a0soft.gphone.camonitor.data.security.SecurityRepository
import com.a0soft.gphone.camonitor.log.AppLogger
import com.a0soft.gphone.camonitor.ui.common.BatteryInfo
import com.a0soft.gphone.camonitor.ui.common.LocaleHelper
import com.a0soft.gphone.camonitor.ui.common.UpdateNotificationHelper
import com.a0soft.gphone.camonitor.ui.theme.StatusWarning
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    onStartCameraMode: (startInStealth: Boolean) -> Unit,
    onPairNewViewer: () -> Unit,
    onStartViewerMode: (deviceId: String) -> Unit,
    onPairNewCamera: () -> Unit,
    onOpenPairedDevices: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        UpdateNotificationHelper.dismissUpdateNotification(context)
        viewModel.onActivated()
        onPauseOrDispose { }
    }

    HomeScreen(
        uiState = uiState,
        currentLanguageTag = LocaleHelper.getCurrentLanguageTag(context),
        onRenameCamera = viewModel::renameCamera,
        onUnpairCamera = viewModel::unpairDevice,
        onMarkStealthIntroSeen = viewModel::markStealthIntroSeen,
        onSkipVersion = viewModel::skipVersion,
        onDismissUpdate = viewModel::dismissUpdateBanner,
        onOpenReleaseUrl = { url ->
            try {
                val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                AppLogger.w("HomeRoute", "Failed to open release URL: ${e.message}")
            }
        },
        onChangeLanguage = { tag ->
            LocaleHelper.setLanguage(context, tag)
        },
        onOpenSystemLanguageSettings = {
            LocaleHelper.openSystemLanguageSettings(context)
        },
        onStartCameraMode = onStartCameraMode,
        onPairNewViewer = onPairNewViewer,
        onStartViewerMode = onStartViewerMode,
        onPairNewCamera = onPairNewCamera,
        onOpenPairedDevices = onOpenPairedDevices,
        onOpenLogs = onOpenLogs,
        onOpenAbout = onOpenAbout,
        onRetry = { viewModel.onActivated() }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    currentLanguageTag: String,
    onRenameCamera: (String) -> Unit,
    onUnpairCamera: (String) -> Unit,
    onMarkStealthIntroSeen: () -> Unit,
    onSkipVersion: (String) -> Unit,
    onDismissUpdate: () -> Unit,
    onOpenReleaseUrl: (String) -> Unit,
    onChangeLanguage: (String) -> Unit,
    onOpenSystemLanguageSettings: () -> Unit,
    onStartCameraMode: (startInStealth: Boolean) -> Unit,
    onPairNewViewer: () -> Unit,
    onStartViewerMode: (deviceId: String) -> Unit,
    onPairNewCamera: () -> Unit,
    onOpenPairedDevices: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit,
    onRetry: () -> Unit = {}
) {
    if (!uiState.isLoaded) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    var showRenameDialog by remember { mutableStateOf(false) }
    var showStealthIntro by remember { mutableStateOf(false) }
    var cameraToUnpair by remember { mutableStateOf<PairedDevice?>(null) }
    var showLanguageDialog by remember { mutableStateOf(false) }
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
                (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = if (isLandscape) 24.dp else 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Hardware Pre-Flight Telemetry Bar
                HardwarePreflightBar(batteryInfo = uiState.batteryInfo, networkType = uiState.networkType)

                // Storage Load Error Banner (if partial read failed)
                if (uiState.loadError != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = stringResource(R.string.home_storage_load_error),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            TextButton(onClick = onRetry) {
                                Text(
                                    stringResource(R.string.action_retry),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                // 2. Update Banner Card (if available)
                uiState.availableRelease?.let { release ->
                    UpdateBannerCard(
                        release = release,
                        onViewUpdate = onOpenReleaseUrl,
                        onSkipVersion = onSkipVersion,
                        onDismiss = onDismissUpdate
                    )
                }

                // 3. Operational Consoles. Each section is declared once and placed by orientation/role.
                val layout = uiState.layout
                // First-ever Stealth launch explains the black screen / swipe-to-unlock before going dark.
                val startStealth: () -> Unit = {
                    if (uiState.isStealthIntroSeen) onStartCameraMode(true) else showStealthIntro = true
                }
                val broadcaster: @Composable (Modifier) -> Unit = { m ->
                    if (layout == HomeLayout.CAMERAS_FIRST) {
                        CompactBroadcasterRow(
                            cameraName = uiState.cameraName,
                            onRename = { showRenameDialog = true },
                            onPairViewer = onPairNewViewer,
                            modifier = m
                        )
                    } else {
                        BroadcasterConsoleCard(
                            cameraName = uiState.cameraName,
                            pairedViewers = uiState.pairedViewers,
                            batteryInfo = uiState.batteryInfo,
                            onRename = { showRenameDialog = true },
                            onStartNormal = { onStartCameraMode(false) },
                            onStartStealth = startStealth,
                            onPairViewer = onPairNewViewer,
                            modifier = m
                        )
                    }
                }
                val cameras: @Composable (Modifier) -> Unit = { m ->
                    MonitoredCamerasSection(
                        pairedCameras = uiState.pairedCameras,
                        lastConnectedMap = uiState.lastConnectedMap,
                        onConnect = onStartViewerMode,
                        onPairCamera = onPairNewCamera,
                        onConfirmRemoveCamera = { cameraToUnpair = it },
                        modifier = m
                    )
                }
                val first = if (layout == HomeLayout.CAMERAS_FIRST) cameras else broadcaster
                val second = if (layout == HomeLayout.CAMERAS_FIRST) broadcaster else cameras

                if (isLandscape) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        first(Modifier.weight(1f).fillMaxHeight())
                        second(Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    first(Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(12.dp)) // 16 + 12 = 28dp between the two consoles
                    second(Modifier.fillMaxWidth())
                }
            }
        }
    }

    // First-run Stealth explanation (shown once)
    if (showStealthIntro) {
        AlertDialog(
            onDismissRequest = { showStealthIntro = false },
            icon = { Icon(Icons.Default.DarkMode, contentDescription = null) },
            title = { Text(stringResource(R.string.home_stealth_intro_title)) },
            text = { Text(stringResource(R.string.home_stealth_intro_message)) },
            confirmButton = {
                Button(onClick = {
                    onMarkStealthIntroSeen()
                    showStealthIntro = false
                    onStartCameraMode(true)
                }) {
                    Text(stringResource(R.string.home_stealth_intro_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showStealthIntro = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Rename Camera Dialog
    if (showRenameDialog) {
        var tempName by remember { mutableStateOf(uiState.cameraName) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.dialog_rename_camera_title)) },
            text = {
                OutlinedTextField(
                    value = tempName,
                    onValueChange = { if (it.codePoints().count() <= 32) tempName = it },
                    label = { Text(stringResource(R.string.dialog_camera_name_label)) },
                    singleLine = true,
                    supportingText = { Text("${tempName.codePoints().count()}/32") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val sanitized = sanitizeDeviceName(tempName)
                        if (sanitized.isNotEmpty()) {
                            onRenameCamera(sanitized)
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

    // Unpair Camera Confirmation Dialog (Prevents accidental touchscreen mis-taps)
    cameraToUnpair?.let { targetCamera ->
        AlertDialog(
            onDismissRequest = { cameraToUnpair = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(stringResource(R.string.home_confirm_unpair_title)) },
            text = {
                Text(stringResource(R.string.home_confirm_unpair_message, targetCamera.name))
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUnpairCamera(targetCamera.deviceId)
                        cameraToUnpair = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.home_confirm_unpair_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { cameraToUnpair = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Language Selection Dialog
    if (showLanguageDialog) {
        var selectedTag by remember { mutableStateOf(currentLanguageTag) }

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
                                        onChangeLanguage(tag)
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
                                    onChangeLanguage(tag)
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
                                showLanguageDialog = false
                                onOpenSystemLanguageSettings()
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
                val isLow = isBatteryLow(batteryInfo.percentage, batteryInfo.isCharging)
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

            // Network Transport Status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val netTint = when (networkType) {
                    NetworkType.DISCONNECTED -> MaterialTheme.colorScheme.error
                    NetworkType.NO_INTERNET -> StatusWarning
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Icon(
                    imageVector = when (networkType) {
                        NetworkType.WIFI -> Icons.Default.Wifi
                        NetworkType.ETHERNET -> Icons.Default.SettingsEthernet
                        NetworkType.CELLULAR -> Icons.Default.SignalCellularAlt
                        NetworkType.NO_INTERNET -> Icons.Default.WifiOff
                        NetworkType.DISCONNECTED -> Icons.Default.WifiOff
                    },
                    contentDescription = null,
                    tint = netTint,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = when (networkType) {
                        NetworkType.WIFI -> stringResource(R.string.home_hw_wifi_ready)
                        NetworkType.ETHERNET -> stringResource(R.string.home_hw_ethernet)
                        NetworkType.CELLULAR -> stringResource(R.string.home_hw_cellular)
                        NetworkType.NO_INTERNET -> stringResource(R.string.home_hw_no_internet)
                        NetworkType.DISCONNECTED -> stringResource(R.string.home_hw_no_network)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = netTint
                )
            }
        }
    }
}

@Composable
private fun BroadcasterConsoleCard(
    cameraName: String,
    pairedViewers: List<PairedDevice>,
    batteryInfo: BatteryInfo,
    onRename: () -> Unit,
    onStartNormal: () -> Unit,
    onStartStealth: () -> Unit,
    onPairViewer: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = broadcasterContainerColor()),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = BROADCASTER_BORDER_ALPHA))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header: Broadcaster Icon + Section Title + Add Viewer Action
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

            // Editable Camera Name Chip
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
                            imageVector = Icons.AutoMirrored.Filled.Label,
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

            // Pre-flight nag: a camera left on battery dies overnight, so warn right next to the launch buttons.
            if (shouldWarnLowBatteryBeforeBroadcast(batteryInfo.percentage, batteryInfo.isCharging)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BatteryAlert,
                        contentDescription = null,
                        tint = StatusWarning,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.home_low_battery_hint, batteryInfo.percentage),
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusWarning
                    )
                }
            }

            // Dual Launch Buttons: Stealth (Black) vs Standard Camera
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

                // Standard Live Camera Launch
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

private const val BROADCASTER_BORDER_ALPHA = 0.45f

/** The 'this phone is the camera' card gets a faint cyan wash so it never reads like a camera tile. */
@Composable
private fun broadcasterContainerColor(): Color =
    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f).compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh)

/**
 * Shown only on viewer-only devices (no paired viewers), where launching the camera is impossible
 * until a viewer is paired. So the row offers exactly one action: pair a viewer. Rename stays available.
 */
@Composable
private fun CompactBroadcasterRow(
    cameraName: String,
    onRename: () -> Unit,
    onPairViewer: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = broadcasterContainerColor()),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = BROADCASTER_BORDER_ALPHA))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Name area is tappable so the camera can be renamed before it is ever started.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        onClickLabel = stringResource(R.string.action_rename),
                        onClick = onRename
                    ),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_section_broadcaster),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.home_camera_name_prefix, cameraName),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.action_rename),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.home_compact_pair_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(
                onClick = onPairViewer,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PersonAdd,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.home_pair_viewer_button),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}
@Composable
private fun MonitoredCamerasSection(
    pairedCameras: List<PairedDevice>,
    lastConnectedMap: Map<String, Long>,
    onConnect: (deviceId: String) -> Unit,
    onPairCamera: () -> Unit,
    onConfirmRemoveCamera: (PairedDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Section Header: Title + Pair Camera Button
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

        // Camera List or Empty State
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
                    lastConnectedTimestamp = lastConnectedMap[camera.deviceId],
                    onConnect = { onConnect(camera.deviceId) },
                    onRemove = { onConfirmRemoveCamera(camera) }
                )
            }
        }
    }
}

@Composable
private fun DirectCameraTile(
    camera: PairedDevice,
    lastConnectedTimestamp: Long?,
    onConnect: () -> Unit,
    onRemove: () -> Unit
) {
    val lastConnected = remember(lastConnectedTimestamp) {
        calculateLastConnectedTime(lastConnectedTimestamp)
    }
    val subtitleText = when (lastConnected) {
        is LastConnectedTime.Never -> stringResource(R.string.home_tile_never_connected)
        is LastConnectedTime.JustNow -> stringResource(R.string.home_tile_last_connected_just_now)
        is LastConnectedTime.MinutesAgo -> stringResource(R.string.home_tile_last_connected_minutes, lastConnected.minutes)
        is LastConnectedTime.HoursAgo -> stringResource(R.string.home_tile_last_connected_hours, lastConnected.hours)
        is LastConnectedTime.DaysAgo -> stringResource(R.string.home_tile_last_connected_days, lastConnected.days)
        is LastConnectedTime.ExactDate -> {
            val locale = LocalConfiguration.current.locales[0]
            val pattern = DateFormat.getBestDateTimePattern(locale, "MMMd")
            val dateStr = SimpleDateFormat(pattern, locale).format(Date(lastConnected.timestamp))
            stringResource(R.string.home_tile_last_connected_date, dateStr)
        }
    }

    // Whole tile connects (large touch target); long-press is the shortcut to unpair (confirm dialog follows).
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onConnect,
                onLongClick = onRemove,
                onLongClickLabel = stringResource(R.string.action_remove_camera)
            ),
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
                        imageVector = Icons.Default.Visibility,
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
                        text = subtitleText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            FilledTonalButton(
                onClick = onConnect,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
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
        }
    }
}
