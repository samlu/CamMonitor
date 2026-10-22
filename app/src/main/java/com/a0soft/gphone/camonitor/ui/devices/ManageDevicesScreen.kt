package com.a0soft.gphone.camonitor.ui.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.a0soft.gphone.camonitor.CamMonitorApp
import com.a0soft.gphone.camonitor.R
import com.a0soft.gphone.camonitor.data.model.PairedDevice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import com.a0soft.gphone.camonitor.data.security.SecurityRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageDevicesScreen(
    onPairNewViewer: () -> Unit,
    onPairNewCamera: () -> Unit,
    onBack: () -> Unit
) {
    val securityRepo = remember { CamMonitorApp.instance.securityRepository }
    var pairedList by remember { mutableStateOf(securityRepo.getPairedDevices()) }
    var deviceToRevoke by remember { mutableStateOf<PairedDevice?>(null) }
    var showPairOptionsDialog by remember { mutableStateOf(false) }

    val canPairViewer = pairedList.count { it.role != "CAMERA" } < SecurityRepository.MAX_PAIRED_VIEWERS
    val canPairCamera = pairedList.count { it.role != "VIEWER" } < SecurityRepository.MAX_PAIRED_CAMERAS

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.paired_devices)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        },
        floatingActionButton = {
            if (canPairCamera || canPairViewer) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (canPairCamera && !canPairViewer) {
                            onPairNewCamera()
                        } else if (!canPairCamera && canPairViewer) {
                            onPairNewViewer()
                        } else {
                            showPairOptionsDialog = true
                        }
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.manage_devices_pair_device)) }
                )
            }
        }
    ) { innerPadding ->
        if (pairedList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.manage_devices_empty_prompt),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(pairedList) { device ->
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = device.name,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                val dateStr = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                                    .format(Date(device.pairedTimestamp))
                                Text(
                                    text = stringResource(R.string.manage_devices_paired_info, dateStr, device.deviceId.take(8)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { deviceToRevoke = device }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.action_revoke),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (deviceToRevoke != null) {
        val target = deviceToRevoke!!
        AlertDialog(
            onDismissRequest = { deviceToRevoke = null },
            title = { Text(stringResource(R.string.dialog_revoke_title)) },
            text = {
                Text(stringResource(R.string.dialog_revoke_message, target.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        securityRepo.removePairedDevice(target.deviceId)
                        CamMonitorApp.instance.webRtcManager.closePeer(target.deviceId)
                        pairedList = securityRepo.getPairedDevices()
                        deviceToRevoke = null
                    }
                ) {
                    Text(stringResource(R.string.action_revoke), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToRevoke = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showPairOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showPairOptionsDialog = false },
            title = { Text(stringResource(R.string.dialog_pair_new_device_title)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (canPairCamera) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showPairOptionsDialog = false
                                    onPairNewCamera()
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QrCodeScanner,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                                Column {
                                    Text(stringResource(R.string.manage_devices_pair_camera_option), style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        stringResource(R.string.manage_devices_pair_camera_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (canPairViewer) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showPairOptionsDialog = false
                                    onPairNewViewer()
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QrCode,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(32.dp)
                                )
                                Column {
                                    Text(stringResource(R.string.manage_devices_pair_viewer_option), style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        stringResource(R.string.manage_devices_pair_viewer_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPairOptionsDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
