package com.a0soft.gphone.camonitor.ui.home

import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.a0soft.gphone.camonitor.CamMonitorApp
import com.a0soft.gphone.camonitor.R
import com.a0soft.gphone.camonitor.data.security.SecurityRepository
import com.a0soft.gphone.camonitor.ui.common.LocaleHelper

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
        ElevatedCard(
            modifier = modifier
                .clickable {
                    val viewers = securityRepo.getPairedViewers()
                    pairedViewers = viewers
                    if (viewers.isEmpty()) {
                        onPairNewViewer()
                    } else {
                        onStartCameraMode()
                    }
                },
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = if (isLandscape) {
                    Modifier.fillMaxSize().padding(16.dp)
                } else {
                    Modifier.fillMaxWidth().padding(24.dp)
                },
                verticalArrangement = if (isLandscape) Arrangement.SpaceBetween else Arrangement.Top
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(if (isLandscape) 40.dp else 48.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.start_camera_mode),
                                style = if (isLandscape) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = when {
                                    pairedViewers.isEmpty() -> stringResource(R.string.home_no_viewers_paired)
                                    pairedViewers.size == 1 -> stringResource(R.string.home_paired_with, pairedViewers.first().name)
                                    else -> stringResource(R.string.home_viewers_paired_ready, pairedViewers.size)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(if (isLandscape) 12.dp else 16.dp))

                    // Camera Name & Rename action
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showRenameDialog = true },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.home_camera_name_prefix, cameraName),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.action_rename),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (pairedViewers.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(if (isLandscape) 10.dp else 16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.home_paired_viewers_count, pairedViewers.size, SecurityRepository.MAX_PAIRED_VIEWERS),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (pairedViewers.size < SecurityRepository.MAX_PAIRED_VIEWERS) {
                            TextButton(
                                onClick = onPairNewViewer,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.home_pair_viewer_button), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ViewerCard(isLandscape: Boolean, modifier: Modifier = Modifier) {
        ElevatedCard(
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
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = if (isLandscape) {
                    Modifier.fillMaxSize().padding(16.dp)
                } else {
                    Modifier.fillMaxWidth().padding(24.dp)
                },
                verticalArrangement = if (isLandscape) Arrangement.SpaceBetween else Arrangement.Top
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(if (isLandscape) 40.dp else 48.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.start_viewer_mode),
                                style = if (isLandscape) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = when {
                                    pairedCameras.isEmpty() -> stringResource(R.string.home_no_cameras_paired)
                                    pairedCameras.size == 1 -> stringResource(R.string.home_paired_with, pairedCameras.first().name)
                                    else -> stringResource(R.string.home_cameras_paired_choose, pairedCameras.size)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (pairedCameras.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(if (isLandscape) 12.dp else 16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.home_paired_cameras_count, pairedCameras.size, SecurityRepository.MAX_PAIRED_CAMERAS),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (pairedCameras.size < SecurityRepository.MAX_PAIRED_CAMERAS) {
                            TextButton(
                                onClick = onPairNewCamera,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.home_pair_camera_button), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge) },
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

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                contentAlignment = Alignment.Center
            ) {
                if (isLandscape) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Max)
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CameraCard(isLandscape = true, modifier = Modifier.weight(1f).fillMaxHeight())
                        ViewerCard(isLandscape = true, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CameraCard(isLandscape = false, modifier = Modifier.fillMaxWidth())
                        ViewerCard(isLandscape = false, modifier = Modifier.fillMaxWidth())
                    }
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

    if (showSelectCameraDialog) {
        AlertDialog(
            onDismissRequest = { showSelectCameraDialog = false },
            title = { Text(stringResource(R.string.dialog_select_camera_title)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
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
                                            securityRepo.removePairedDevice(device.deviceId)
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
