package com.a0soft.gphone.camonitor.ui.camera

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.ServiceConnection
import android.os.IBinder
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.a0soft.gphone.camonitor.R
import com.a0soft.gphone.camonitor.log.AppLogger
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.a0soft.gphone.camonitor.CamMonitorApp
import com.a0soft.gphone.camonitor.service.CameraMonitorService
import com.a0soft.gphone.camonitor.service.CameraStreamingState
import com.a0soft.gphone.camonitor.ui.theme.StatusLive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@Composable
fun CameraModeScreen(
    onExitCameraMode: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val activity = context as? Activity
    val view = LocalView.current
    val window = activity?.window

    val app = remember { CamMonitorApp.instance }
    val securityRepo = app.securityRepository

    var service by remember { mutableStateOf<CameraMonitorService?>(null) }
    var streamingState by remember { mutableStateOf(CameraStreamingState.IDLE) }
    var isPttReceiving by remember { mutableStateOf(false) }
    val activeViewers by app.webRtcManager.activeViewersCount.collectAsState()
    val isTorchOn by app.torchManager.isTorchOn.collectAsState()
    val isNightModeActive by app.webRtcManager.isNightModeActive.collectAsState()
    val streamFps by app.webRtcManager.streamFps.collectAsState()

    var isBlackScreenActive by remember { mutableStateOf(false) }
    var idleSeconds by remember { mutableStateOf(0) }
    var cameraName by remember { mutableStateOf(securityRepo.getDeviceName()) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var localSurfaceRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    fun wakeUpDisplay() {
        isBlackScreenActive = false
        idleSeconds = 0
    }

    BackHandler {
        if (showRenameDialog) {
            showRenameDialog = false
        } else if (isBlackScreenActive) {
            wakeUpDisplay()
        } else {
            val stopIntent = Intent(context, CameraMonitorService::class.java)
            context.stopService(stopIntent)
            onExitCameraMode()
        }
    }

    // Bind CameraMonitorService
    DisposableEffect(Unit) {
        val serviceIntent = Intent(context, CameraMonitorService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val localBinder = binder as? CameraMonitorService.LocalBinder
                val s = localBinder?.getService()
                service = s
                s?.startStreaming()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        context.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        onDispose {
            context.unbindService(connection)
            localSurfaceRenderer?.let { renderer ->
                localSurfaceRenderer = null
                app.webRtcManager.detachLocalRenderer(renderer)
                try {
                    renderer.release()
                } catch (e: Exception) {
                    AppLogger.w("CameraModeScreen", "Error releasing SurfaceViewRenderer in onDispose: ${e.message}")
                }
            }
        }
    }

    // Observe service streaming state and PTT intercom state
    LaunchedEffect(service) {
        val s = service ?: return@LaunchedEffect
        launch {
            s.streamingState.collect { state ->
                streamingState = state
            }
        }
        launch {
            s.isPttReceiving.collect { receiving ->
                isPttReceiving = receiving
            }
        }
    }

    // 60-Second local inactivity timer for OLED black screen
    LaunchedEffect(isBlackScreenActive, streamingState) {
        if (!isBlackScreenActive && streamingState == CameraStreamingState.STREAMING) {
            idleSeconds = 0
            while (idleSeconds < 60) {
                delay(1000L)
                idleSeconds++
            }
            isBlackScreenActive = true
        }
    }

    // Adjust system brightness and system bars when black screen toggles
    DisposableEffect(isBlackScreenActive) {
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, view)
            if (isBlackScreenActive) {
                val params = window.attributes
                params.screenBrightness = 0.01f
                window.attributes = params
                insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                val params = window.attributes
                params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = params
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (window != null) {
                val params = window.attributes
                params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = params
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Root UI Container
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (isBlackScreenActive) wakeUpDisplay() else idleSeconds = 0
            }
    ) {
        val isLandscape = (maxWidth > maxHeight) ||
            (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) ||
            (configuration.screenWidthDp > configuration.screenHeightDp)

        // Local Camera Live Viewfinder
        if (!isBlackScreenActive && streamingState != CameraStreamingState.PAUSED_INACTIVITY) {
            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        init(app.webRtcManager.eglBase.eglBaseContext, null)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        setEnableHardwareScaler(false)
                        localSurfaceRenderer = this
                        app.webRtcManager.attachLocalRenderer(this)
                    }
                },
                update = { renderer ->
                    localSurfaceRenderer = renderer
                    app.webRtcManager.attachLocalRenderer(renderer)
                },
                onRelease = { renderer ->
                    localSurfaceRenderer = null
                    app.webRtcManager.detachLocalRenderer(renderer)
                    try {
                        renderer.release()
                    } catch (e: Exception) {
                        AppLogger.w("CameraModeScreen", "Error releasing SurfaceViewRenderer in onRelease: ${e.message}")
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (streamingState == CameraStreamingState.PAUSED_INACTIVITY) {
            // Standby View after 60s of 0 viewers
            CameraPausedStandbyView(
                onResume = { service?.resumeStreamingFromPause() },
                onExit = {
                    val stopIntent = Intent(context, CameraMonitorService::class.java)
                    context.stopService(stopIntent)
                    onExitCameraMode()
                }
            )
        } else if (!isBlackScreenActive) {
            // Active Streaming HUD
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top HUD Bar
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Pulsing Live Indicator
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(StatusLive, CircleShape)
                            )
                            Text(
                                text = stringResource(R.string.camera_live_badge, streamFps),
                                style = MaterialTheme.typography.labelLarge,
                                color = StatusLive
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Viewer Count
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                                    val viewersText = if (isLandscape) {
                                        stringResource(R.string.camera_viewers_count, activeViewers, 2)
                                    } else {
                                        stringResource(R.string.camera_viewers_count_short, activeViewers, 2)
                                    }
                                    Text(viewersText, style = MaterialTheme.typography.labelMedium)
                                }
                            }

                            // Night mode badge
                            if (isNightModeActive) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Nightlight,
                                            contentDescription = stringResource(R.string.camera_night_mode_active),
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            stringResource(R.string.camera_night_mode_active),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }

                            // Torch status
                            Icon(
                                imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                contentDescription = null,
                                tint = if (isTorchOn) MaterialTheme.colorScheme.primary else Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Middle Info
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AnimatedVisibility(
                        visible = isPttReceiving,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = Color.White)
                                Text(
                                    text = stringResource(R.string.camera_intercom_live),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    Text(
                        text = cameraName,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(onClick = { showRenameDialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.dialog_rename_camera_title))
                    }
                }

                // Bottom Control Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    FilledTonalButton(
                        onClick = { isBlackScreenActive = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Bedtime, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.camera_black_screen))
                    }

                    Button(
                        onClick = {
                            val stopIntent = Intent(context, CameraMonitorService::class.java)
                            context.stopService(stopIntent)
                            onExitCameraMode()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.camera_exit_button))
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
                            service?.broadcastRename(trimmed)
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
}

@Composable
private fun CameraPausedStandbyView(
    onResume: () -> Unit,
    onExit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.PauseCircleOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(72.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.camera_paused_title),
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.camera_paused_description),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onResume,
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            Text(stringResource(R.string.start_camera_mode))
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onExit,
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            Text(stringResource(R.string.exit_camera_mode))
        }
    }
}
