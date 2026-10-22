package com.a0soft.gphone.camonitor.ui.pairing

import android.content.Context
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.a0soft.gphone.camonitor.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.a0soft.gphone.camonitor.CamMonitorApp
import com.a0soft.gphone.camonitor.data.model.PairedDevice
import com.a0soft.gphone.camonitor.data.model.PairingPayload
import com.a0soft.gphone.camonitor.data.model.SignalingMessage
import com.a0soft.gphone.camonitor.data.security.CryptoUtils
import com.a0soft.gphone.camonitor.log.AppLogger
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerPairingScreen(
    onPairingSuccess: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val app = remember { CamMonitorApp.instance }
    val securityRepo = app.securityRepository
    val signalingClient = app.signalingClient

    var isScanning by remember { mutableStateOf(true) }
    var isPairingSuccessful by remember { mutableStateOf(false) }
    val defaultPairingStatus = stringResource(R.string.viewer_pairing_align_qr)
    var pairingStatus by remember { mutableStateOf<String?>(null) }
    var cameraControl by remember { mutableStateOf<androidx.camera.core.CameraControl?>(null) }
    var isTorchEnabled by remember { mutableStateOf(false) }
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var analyzerExecutor by remember { mutableStateOf<java.util.concurrent.ExecutorService?>(null) }

    BackHandler {
        onCancel()
    }

    DisposableEffect(Unit) {
        onDispose {
            securityRepo.clearPairingOtp()
            if (!isPairingSuccessful) {
                coroutineScope.launch {
                    try {
                        signalingClient.stopListening()
                    } catch (_: Exception) {}
                }
            }
            try {
                cameraControl?.enableTorch(false)
                cameraProviderRef?.unbindAll()
                analyzerExecutor?.shutdown()
            } catch (e: Exception) {
                AppLogger.e("ViewerPairing", "Error unbinding CameraX on dispose", e)
            }
            try {
                val future = ProcessCameraProvider.getInstance(context)
                if (future.isDone) {
                    future.get().unbindAll()
                } else {
                    future.addListener({
                        try {
                            future.get().unbindAll()
                        } catch (_: Exception) {}
                    }, ContextCompat.getMainExecutor(context))
                }
            } catch (_: Exception) {}
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.viewer_pairing_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        isTorchEnabled = !isTorchEnabled
                        cameraControl?.enableTorch(isTorchEnabled)
                    }) {
                        Icon(Icons.Default.FlashOn, contentDescription = stringResource(R.string.cd_torch))
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // CameraX Viewfinder
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        cameraProviderRef = cameraProvider
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                        analyzerExecutor = executor
                        val reader = MultiFormatReader()

                        imageAnalysis.setAnalyzer(executor) { imageProxy ->
                            if (!isScanning) {
                                imageProxy.close()
                                return@setAnalyzer
                            }

                            val result = scanQrFromImageProxy(imageProxy, reader)
                            imageProxy.close()

                            if (result != null) {
                                isScanning = false
                                try {
                                    cameraControl?.enableTorch(false)
                                    cameraProvider.unbindAll()
                                    executor.shutdown()
                                } catch (_: Exception) {}
                                coroutineScope.launch {
                                    handleScannedQr(
                                        context,
                                        result,
                                        app,
                                        securityRepo,
                                        signalingClient,
                                        onSuccess = { pairedCamId ->
                                            isPairingSuccessful = true
                                            onPairingSuccess(pairedCamId)
                                        },
                                        onRetry = { isScanning = true }
                                    ) { status ->
                                        pairingStatus = status
                                    }
                                }
                            }
                        }

                        try {
                            cameraProvider.unbindAll()
                            val cam = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageAnalysis
                            )
                            cameraControl = cam.cameraControl
                        } catch (e: Exception) {
                            AppLogger.e("ViewerPairing", "CameraX binding failed", e)
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                onRelease = {
                    try {
                        cameraControl?.enableTorch(false)
                        cameraProviderRef?.unbindAll()
                        analyzerExecutor?.shutdown()
                    } catch (_: Exception) {}
                },
                modifier = Modifier.fillMaxSize()
            )

            // Scanning Reticle Overlay
            Canvas(modifier = Modifier.fillMaxSize()) {
                val boxSize = 260.dp.toPx()
                val left = (size.width - boxSize) / 2
                val top = (size.height - boxSize) / 2

                val backgroundPath = Path().apply {
                    addRect(Rect(0f, 0f, size.width, size.height))
                }
                val cutoutPath = Path().apply {
                    addRoundRect(
                        RoundRect(
                            rect = Rect(left, top, left + boxSize, top + boxSize),
                            cornerRadius = CornerRadius(24.dp.toPx())
                        )
                    )
                }

                val overlayPath = Path.combine(
                    operation = androidx.compose.ui.graphics.PathOperation.Difference,
                    path1 = backgroundPath,
                    path2 = cutoutPath
                )
                drawPath(overlayPath, Color.Black.copy(alpha = 0.6f))
            }

            // Bottom Prompt
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
                    .fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f)
            ) {
                Text(
                    text = pairingStatus ?: defaultPairingStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

private fun scanQrFromImageProxy(image: ImageProxy, reader: MultiFormatReader): String? {
    val plane = image.planes[0]
    val buffer: ByteBuffer = plane.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    val width = image.width
    val height = image.height
    val source = PlanarYUVLuminanceSource(bytes, width, height, 0, 0, width, height, false)
    val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

    return try {
        val result = reader.decodeWithState(binaryBitmap)
        result.text
    } catch (e: Exception) {
        null
    }
}

private suspend fun handleScannedQr(
    context: Context,
    qrText: String,
    app: CamMonitorApp,
    securityRepo: com.a0soft.gphone.camonitor.data.security.SecurityRepository,
    signalingClient: com.a0soft.gphone.camonitor.data.signaling.SignalingClient,
    onSuccess: (String) -> Unit,
    onRetry: () -> Unit,
    updateStatus: (String) -> Unit
) {
    val payload = PairingPayload.fromJson(qrText)
    if (payload == null) {
        updateStatus(context.getString(R.string.viewer_pairing_invalid_qr))
        delay(2000L)
        onRetry()
        return
    }

    // Set active pairing OTP so SignalingClient can decrypt PAIR_CONFIRM
    securityRepo.setActivePairingOtp(payload.secret)

    updateStatus(context.getString(R.string.viewer_pairing_connecting_broker))

    val connected = signalingClient.startListening()
    if (!connected) {
        updateStatus(context.getString(R.string.viewer_pairing_broker_failed))
    }

    val otpKey = CryptoUtils.hkdfSha256(
        payload.secret.toByteArray(Charsets.UTF_8),
        ByteArray(0),
        "CamMonitor-OTP".toByteArray(),
        32
    )

    val request = SignalingMessage.PairRequest(
        pbDeviceId = securityRepo.getDeviceId(),
        pbName = securityRepo.getDeviceName(),
        pbPublicKeyBase64 = securityRepo.getDevicePublicKeyBase64()
    )

    updateStatus(context.getString(R.string.viewer_pairing_authenticating, payload.name))

    var isConfirmed = false

    withTimeoutOrNull(20_000L) {
        val retryJob = launch(Dispatchers.IO) {
            var attempts = 0
            while (isActive && !isConfirmed && attempts < 15) {
                attempts++
                AppLogger.i("ViewerPairing", "Sending PAIR_REQUEST to ${payload.deviceId} (Attempt #$attempts)")
                signalingClient.sendSignalingMessage(payload.deviceId, request, otpKey)
                delay(1200L)
            }
        }

        try {
            signalingClient.incomingMessages.collect { (senderId, msg) ->
                if (msg is SignalingMessage.PairConfirm && senderId == payload.deviceId) {
                    if (senderId != msg.paDeviceId) {
                        AppLogger.w("ViewerPairing", "Dropped PAIR_CONFIRM: senderId mismatch ($senderId != ${msg.paDeviceId})")
                        return@collect
                    }
                    isConfirmed = true
                    retryJob.cancel()
                    securityRepo.clearPairingOtp()
                    AppLogger.i("ViewerPairing", "Received PAIR_CONFIRM from ${msg.paName}")
                    val pairedCam = PairedDevice(
                        deviceId = msg.paDeviceId,
                        name = msg.paName,
                        publicKeyBase64 = msg.paPublicKeyBase64,
                        pairedTimestamp = System.currentTimeMillis(),
                        role = "CAMERA"
                    )
                    val added = securityRepo.addPairedDevice(pairedCam, maxLimit = com.a0soft.gphone.camonitor.data.security.SecurityRepository.MAX_PAIRED_CAMERAS)
                    if (added) {
                        updateStatus(context.getString(R.string.viewer_pairing_success))
                        delay(300L)
                        onSuccess(msg.paDeviceId)
                    } else {
                        AppLogger.w("ViewerPairing", "Failed to add paired camera: maximum limit of ${com.a0soft.gphone.camonitor.data.security.SecurityRepository.MAX_PAIRED_CAMERAS} reached")
                        securityRepo.clearPairingOtp()
                        updateStatus(context.getString(R.string.viewer_pairing_limit_reached, com.a0soft.gphone.camonitor.data.security.SecurityRepository.MAX_PAIRED_CAMERAS))
                        delay(2500L)
                        signalingClient.stopListening()
                        onRetry()
                    }
                    return@collect
                }
            }
        } finally {
            retryJob.cancel()
        }
    }

    if (!isConfirmed) {
        AppLogger.w("ViewerPairing", "Pairing timed out with ${payload.name}")
        securityRepo.clearPairingOtp()
        updateStatus(context.getString(R.string.viewer_pairing_timeout))
        delay(2500L)
        signalingClient.stopListening()
        onRetry()
    }
}
