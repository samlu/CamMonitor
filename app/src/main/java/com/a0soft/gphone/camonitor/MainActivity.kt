package com.a0soft.gphone.camonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.a0soft.gphone.camonitor.ui.about.AboutScreen
import com.a0soft.gphone.camonitor.ui.camera.CameraModeScreen
import com.a0soft.gphone.camonitor.ui.devices.ManageDevicesScreen
import com.a0soft.gphone.camonitor.ui.home.HomeScreen
import com.a0soft.gphone.camonitor.ui.log.LogViewerScreen
import com.a0soft.gphone.camonitor.ui.pairing.CameraPairingScreen
import com.a0soft.gphone.camonitor.ui.pairing.ViewerPairingScreen
import com.a0soft.gphone.camonitor.ui.theme.CamMonitorTheme
import com.a0soft.gphone.camonitor.ui.viewer.ViewerModeScreen

enum class AppScreen {
    HOME,
    CAMERA_PAIRING,
    CAMERA_MODE,
    VIEWER_PAIRING,
    VIEWER_MODE,
    MANAGE_DEVICES,
    LOGS,
    ABOUT
}

class MainActivity : ComponentActivity() {

    var deniedPermissions by mutableStateOf<List<String>>(emptyList())
    var showPermissionRationaleDialog by mutableStateOf(false)
    var isPermanentlyDenied by mutableStateOf(false)
    var pendingPermissionAction: (() -> Unit)? = null
    private var appWentToBackground = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val denied = result.filterValues { !it }.keys.toList()
        if (denied.isEmpty()) {
            showPermissionRationaleDialog = false
            deniedPermissions = emptyList()
            val action = pendingPermissionAction
            pendingPermissionAction = null
            action?.invoke()
        } else {
            deniedPermissions = denied
            isPermanentlyDenied = denied.any { perm ->
                !ActivityCompat.shouldShowRequestPermissionRationale(this@MainActivity, perm)
            }
            showPermissionRationaleDialog = true
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.a0soft.gphone.camonitor.ui.common.LocaleHelper.getLocalizedContext(newBase))
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            com.a0soft.gphone.camonitor.ui.common.LocaleHelper.applyCurrentLocale(this)
        }
    }

    private var currentLanguageTag: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentLanguageTag = com.a0soft.gphone.camonitor.ui.common.LocaleHelper.getCurrentLanguageTag(this)
        enableEdgeToEdge()

        checkStartupPermissions()

        setContent {
            CamMonitorTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CamMonitorAppContent()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        appWentToBackground = true
    }

    override fun onResume() {
        super.onResume()
        val latestTag = com.a0soft.gphone.camonitor.ui.common.LocaleHelper.getCurrentLanguageTag(this)
        if (latestTag != currentLanguageTag) {
            currentLanguageTag = latestTag
            recreate()
            return
        }

        // Re-check permissions when reopening app from background
        if (appWentToBackground) {
            appWentToBackground = false
            val hadPending = pendingPermissionAction
            checkStartupPermissions()
            if (deniedPermissions.isEmpty() && hadPending != null) {
                pendingPermissionAction = null
                hadPending.invoke()
            }
        } else if (deniedPermissions.isNotEmpty()) {
            val stillDenied = deniedPermissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            deniedPermissions = stillDenied
            if (stillDenied.isEmpty()) {
                showPermissionRationaleDialog = false
                val action = pendingPermissionAction
                pendingPermissionAction = null
                action?.invoke()
            }
        }
    }

    fun getCameraPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions
    }

    fun checkStartupPermissions() {
        val needed = getCameraPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            deniedPermissions = needed
            val permanentlyDenied = needed.any { perm ->
                !ActivityCompat.shouldShowRequestPermissionRationale(this, perm)
            }
            isPermanentlyDenied = permanentlyDenied
            if (permanentlyDenied) {
                showPermissionRationaleDialog = true
            } else {
                permissionLauncher.launch(needed.toTypedArray())
            }
        } else {
            deniedPermissions = emptyList()
            showPermissionRationaleDialog = false
        }
    }

    fun checkAndRequestPermissions(
        required: List<String>,
        onGranted: () -> Unit
    ) {
        val needed = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            onGranted()
        } else {
            pendingPermissionAction = onGranted
            deniedPermissions = needed
            val permanentlyDenied = needed.any { perm ->
                !ActivityCompat.shouldShowRequestPermissionRationale(this, perm)
            }
            isPermanentlyDenied = permanentlyDenied
            if (permanentlyDenied) {
                showPermissionRationaleDialog = true
            } else {
                permissionLauncher.launch(needed.toTypedArray())
            }
        }
    }

    @Composable
    private fun CamMonitorAppContent() {
        val backStack = remember { mutableStateListOf(AppScreen.HOME) }
        val currentScreen = backStack.lastOrNull() ?: AppScreen.HOME
        val securityRepo = remember { CamMonitorApp.instance.securityRepository }
        var selectedCameraDeviceId by remember { mutableStateOf<String?>(null) }

        fun navigateTo(screen: AppScreen) {
            backStack.add(screen)
        }

        fun navigateBack() {
            if (backStack.size > 1) {
                val popped = backStack.removeAt(backStack.size - 1)
                if (popped == AppScreen.CAMERA_MODE) {
                    val stopIntent = android.content.Intent(this@MainActivity, com.a0soft.gphone.camonitor.service.CameraMonitorService::class.java)
                    stopService(stopIntent)
                } else if (popped == AppScreen.VIEWER_MODE) {
                    CamMonitorApp.instance.webRtcManager.closeAll()
                }
            } else {
                finish()
            }
        }

        androidx.activity.compose.BackHandler(enabled = backStack.size > 1) {
            navigateBack()
        }

        when (currentScreen) {
            AppScreen.HOME -> {
                HomeScreen(
                    onStartCameraMode = {
                        checkAndRequestPermissions(getCameraPermissions()) {
                            val viewerCount = securityRepo.getPairedViewers().size
                            if (viewerCount == 0) {
                                navigateTo(AppScreen.CAMERA_PAIRING)
                            } else {
                                navigateTo(AppScreen.CAMERA_MODE)
                            }
                        }
                    },
                    onPairNewViewer = {
                        checkAndRequestPermissions(getCameraPermissions()) {
                            navigateTo(AppScreen.CAMERA_PAIRING)
                        }
                    },
                    onStartViewerMode = { deviceId ->
                        selectedCameraDeviceId = deviceId
                        navigateTo(AppScreen.VIEWER_MODE)
                    },
                    onPairNewCamera = {
                        checkAndRequestPermissions(listOf(Manifest.permission.CAMERA)) {
                            navigateTo(AppScreen.VIEWER_PAIRING)
                        }
                    },
                    onOpenPairedDevices = { navigateTo(AppScreen.MANAGE_DEVICES) },
                    onOpenLogs = { navigateTo(AppScreen.LOGS) },
                    onOpenAbout = { navigateTo(AppScreen.ABOUT) }
                )
            }
            AppScreen.CAMERA_PAIRING -> {
                CameraPairingScreen(
                    onPairingSuccess = {
                        if (backStack.isNotEmpty()) backStack.removeAt(backStack.size - 1)
                        backStack.add(AppScreen.CAMERA_MODE)
                    },
                    onCancel = { navigateBack() }
                )
            }
            AppScreen.CAMERA_MODE -> {
                CameraModeScreen(
                    onExitCameraMode = { navigateBack() }
                )
            }
            AppScreen.VIEWER_PAIRING -> {
                ViewerPairingScreen(
                    onPairingSuccess = { pairedCamId ->
                        selectedCameraDeviceId = pairedCamId
                        if (backStack.isNotEmpty()) backStack.removeAt(backStack.size - 1)
                        backStack.add(AppScreen.VIEWER_MODE)
                    },
                    onCancel = { navigateBack() }
                )
            }
            AppScreen.VIEWER_MODE -> {
                ViewerModeScreen(
                    selectedDeviceId = selectedCameraDeviceId,
                    onExitViewerMode = { navigateBack() }
                )
            }
            AppScreen.MANAGE_DEVICES -> {
                ManageDevicesScreen(
                    onPairNewViewer = {
                        checkAndRequestPermissions(getCameraPermissions()) {
                            navigateTo(AppScreen.CAMERA_PAIRING)
                        }
                    },
                    onPairNewCamera = {
                        checkAndRequestPermissions(listOf(Manifest.permission.CAMERA)) {
                            navigateTo(AppScreen.VIEWER_PAIRING)
                        }
                    },
                    onBack = { navigateBack() }
                )
            }
            AppScreen.LOGS -> {
                LogViewerScreen(
                    onBack = { navigateBack() }
                )
            }
            AppScreen.ABOUT -> {
                AboutScreen(
                    onBack = { navigateBack() }
                )
            }
        }

        if (showPermissionRationaleDialog && deniedPermissions.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = {
                    showPermissionRationaleDialog = false
                    pendingPermissionAction = null
                },
                title = {
                    Text(stringResource(R.string.permission_dialog_title))
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (deniedPermissions.contains(Manifest.permission.CAMERA)) {
                            Text(stringResource(R.string.permission_camera_reason))
                        }
                        if (deniedPermissions.contains(Manifest.permission.RECORD_AUDIO)) {
                            Text(stringResource(R.string.permission_audio_reason))
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            deniedPermissions.contains(Manifest.permission.POST_NOTIFICATIONS)
                        ) {
                            Text(stringResource(R.string.permission_notification_reason))
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (isPermanentlyDenied) {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", packageName, null)
                                }
                                startActivity(intent)
                            } else {
                                permissionLauncher.launch(deniedPermissions.toTypedArray())
                            }
                        }
                    ) {
                        Text(
                            if (isPermanentlyDenied) stringResource(R.string.permission_open_settings)
                            else stringResource(R.string.permission_grant)
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showPermissionRationaleDialog = false
                            pendingPermissionAction = null
                        }
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }
}
