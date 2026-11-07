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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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

    // Locked = "black screen" + touch guard. Entering it is the ONLY way to turn
    // the display off, and every entry point goes through enterLocked() so the
    // manual button and the 60s idle timer can never diverge.
    var isLocked by rememberSaveable { mutableStateOf(false) }
    var idleSeconds by remember { mutableStateOf(0) }
    var cameraName by remember { mutableStateOf(securityRepo.getDeviceName()) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var localSurfaceRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    // Gesture gate: ignore anything that happens in the first 300ms after locking
    // (the click that locked the screen, or a fast double tap) and keep a 3s
    // cooldown after unlocking so a stray focus event cannot re-lock immediately.
    var lockArmedAt by remember { mutableStateOf(0L) }
    var unlockCooldownUntil by remember { mutableStateOf(0L) }
    var showUnlockHint by remember { mutableStateOf(false) }

    fun enterLocked() {
        if (!isLocked) {
            AppLogger.i("CameraModeScreen", "Entering locked black screen (touch guard on)")
        }
        isLocked = true
        idleSeconds = 0
        // A dialog is a separate window: it would float on top of the black
        // screen and its outside-tap dismissal would silently bypass the lock.
        showRenameDialog = false
        lockArmedAt = android.os.SystemClock.elapsedRealtime()
        showUnlockHint = true
    }

    fun unlockFromGesture() {
        isLocked = false
        idleSeconds = 0
        showUnlockHint = false
        unlockCooldownUntil = android.os.SystemClock.elapsedRealtime() + 3_000L
        AppLogger.i("CameraModeScreen", "Unlocked by swipe")
    }

    // The hint must fade out on its own: on an AMOLED panel a permanently drawn
    // hint would be a static bright element on an otherwise black screen.
    LaunchedEffect(showUnlockHint) {
        if (showUnlockHint) {
            delay(1_500L)
            showUnlockHint = false
        }
    }

    BackHandler {
        when {
            showRenameDialog -> showRenameDialog = false
            // Consume back while locked: a stray back press in a pocket would
            // otherwise drop the camera out of streaming mode entirely.
            isLocked -> AppLogger.d("CameraModeScreen", "Back press ignored while locked")
            else -> {
                val stopIntent = Intent(context, CameraMonitorService::class.java)
                context.stopService(stopIntent)
                onExitCameraMode()
            }
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

    // Keep the display awake for the whole camera session.
    //
    // The old build only dimmed the panel to 1% and never touched the screen-off
    // timer, so the system still slept after the configured timeout: on an AMOLED
    // that looks identical to a real screen-off, which is exactly why the device
    // was unreachable at 6am. FLAG_KEEP_SCREEN_ON is the only app-side way to stop
    // the system from entering Doze — and on AMOLED a fully black window at 1%
    // brightness costs almost nothing.
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AppLogger.i("CameraModeScreen", "Screen kept on for camera session")
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            AppLogger.i("CameraModeScreen", "Screen keep-on released")
        }
    }

    // Idle timer: 60s without a touch while streaming locks the display.
    LaunchedEffect(isLocked, streamingState, showRenameDialog) {
        if (!isLocked && !showRenameDialog && streamingState == CameraStreamingState.STREAMING) {
            idleSeconds = 0
            while (idleSeconds < 60) {
                delay(1000L)
                idleSeconds++
            }
            enterLocked()
        }
    }

    // Brightness + immersive mode follow the lock state.
    DisposableEffect(isLocked) {
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, view)
            val params = window.attributes
            if (isLocked) {
                params.screenBrightness = 0.01f
                window.attributes = params
                insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                // A monitoring device does not need a 100% panel: the preview is
                // pure OLED emission and the display is one of the top three heat
                // sources. 0.55 stays readable and still cuts a large share of it.
                params.screenBrightness = 0.55f
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

    // Best-effort re-arm of the touch guard when the window regains focus.
    //
    // Pulling the notification shade (or any overlay) takes focus away and can
    // leave the system bars visible on top of the locked screen. We cannot forbid
    // the shade without device-owner rights, but we can put the bars back.
    val lockedSnapshot by rememberUpdatedState(isLocked)
    val cooldownSnapshot by rememberUpdatedState(unlockCooldownUntil)
    DisposableEffect(window, view) {
        if (window == null) return@DisposableEffect onDispose {}
        val observer = android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus && lockedSnapshot &&
                android.os.SystemClock.elapsedRealtime() > cooldownSnapshot
            ) {
                view.postDelayed({
                    WindowCompat.getInsetsController(window, view)
                        .hide(WindowInsetsCompat.Type.systemBars())
                }, 250L)
            }
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(observer)
        onDispose {
            runCatching { view.viewTreeObserver.removeOnWindowFocusChangeListener(observer) }
        }
    }

    // Root UI Container
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(isLocked) {
                if (!isLocked) {
                    // Observe presses in the INITIAL pass and never consume them:
                    // HUD buttons keep working, but any touch still resets the 60s
                    // idle timer (a child button would otherwise swallow the tap
                    // and the screen could lock while the user is still tapping).
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            if (event.changes.any { it.pressed && !it.previousPressed }) {
                                idleSeconds = 0
                            }
                        }
                    }
                } else {
                    // Swipe-to-unlock. A tap only reveals the hint: on an AMOLED
                    // the screen is genuinely black, so a permanently drawn track
                    // would be invisible AND would burn in.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        showUnlockHint = true
                        // 30% of the screen width, but never less than 120px:/n                        // a zero-width first layout must not turn any touch into an unlock.
                        val needed = maxOf(size.width * 0.3f, 120f)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            change.consume()
                            val dx = kotlin.math.abs(change.position.x - down.position.x)
                            val dy = kotlin.math.abs(change.position.y - down.position.y)
                            val armed = android.os.SystemClock.elapsedRealtime() - lockArmedAt >= 300L
                            if (armed && maxOf(dx, dy) >= needed) {
                                unlockFromGesture()
                                break
                            }
                            if (!change.pressed) break
                        }
                    }
                }
            }
    ) {
        val isLandscape = (maxWidth > maxHeight) ||
            (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) ||
            (configuration.screenWidthDp > configuration.screenHeightDp)

        // Local Camera Live Viewfinder
        if (!isLocked && streamingState != CameraStreamingState.PAUSED_INACTIVITY) {
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

        if (streamingState == CameraStreamingState.PAUSED_INACTIVITY && !isLocked) {
            // Standby View after 60s of 0 viewers
            CameraPausedStandbyView(
                onResume = { service?.resumeStreamingFromPause() },
                onExit = {
                    val stopIntent = Intent(context, CameraMonitorService::class.java)
                    context.stopService(stopIntent)
                    onExitCameraMode()
                }
            )
        } else if (!isLocked) {
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
                        onClick = { enterLocked() },
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

        // Locked black screen: a barely visible hint that fades out on its own.
        // It must stay extremely dim and transient — a static bright element is
        // invisible on an AMOLED while still burning the panel over hours.
        if (isLocked && showUnlockHint) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(3.dp)
                            .background(Color(0xFF2A2A2A), RoundedCornerShape(2.dp))
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.camera_unlock_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF3A3A3A)
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
