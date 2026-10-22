package com.a0soft.gphone.camonitor.ui.pairing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.a0soft.gphone.camonitor.CamMonitorApp
import com.a0soft.gphone.camonitor.R
import com.a0soft.gphone.camonitor.data.model.PairedDevice
import com.a0soft.gphone.camonitor.data.model.PairingPayload
import com.a0soft.gphone.camonitor.data.model.SignalingMessage
import com.a0soft.gphone.camonitor.data.security.CryptoUtils
import com.a0soft.gphone.camonitor.data.security.SecurityRepository
import com.a0soft.gphone.camonitor.log.AppLogger
import com.a0soft.gphone.camonitor.ui.common.QrUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraPairingScreen(
    onPairingSuccess: () -> Unit,
    onCancel: () -> Unit
) {
    val app = remember { CamMonitorApp.instance }
    val securityRepo = app.securityRepository
    val signalingClient = app.signalingClient
    val coroutineScope = rememberCoroutineScope()

    var otp by remember { mutableStateOf(securityRepo.generatePairingOtp()) }
    var secondsLeft by remember { mutableStateOf(300) } // 5 minutes
    var isPairingSuccessful by remember { mutableStateOf(false) }
    var confirmedDeviceId by remember { mutableStateOf<String?>(null) }
    var isNavigatingSuccess by remember { mutableStateOf(false) }

    val cameraName = remember { securityRepo.getDeviceName() }
    val deviceId = remember { securityRepo.getDeviceId() }

    val qrBitmap = remember(otp) {
        val payload = PairingPayload(
            deviceId = deviceId,
            name = cameraName,
            secret = otp
        )
        QrUtils.generateQrBitmap(payload.toJson(), 512)
    }

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
        }
    }

    // OTP 5-minute countdown timer (repeats continuously while screen is active)
    LaunchedEffect(Unit) {
        while (isActive) {
            while (secondsLeft > 0) {
                delay(1000L)
                secondsLeft--
            }
            // Regenerate OTP if expired and restart 5-minute countdown
            otp = securityRepo.generatePairingOtp()
            secondsLeft = 300
        }
    }

    // Listen for incoming PAIR_REQUEST over MQTT
    LaunchedEffect(Unit) {
        signalingClient.startListening()
        signalingClient.incomingMessages.collect { (senderId, msg) ->
            if (msg is SignalingMessage.PairRequest) {
                if (senderId != msg.pbDeviceId) {
                    AppLogger.w("CameraPairing", "Dropped PAIR_REQUEST: senderId mismatch ($senderId != ${msg.pbDeviceId})")
                    return@collect
                }
                val confirm = SignalingMessage.PairConfirm(
                    paDeviceId = deviceId,
                    paName = cameraName,
                    paPublicKeyBase64 = securityRepo.getDevicePublicKeyBase64()
                )
                val otpKey = CryptoUtils.hkdfSha256(
                    otp.toByteArray(Charsets.UTF_8),
                    ByteArray(0),
                    "CamMonitor-OTP".toByteArray(),
                    32
                )
                if (confirmedDeviceId == msg.pbDeviceId) {
                    // Retransmit PAIR_CONFIRM immediately on retry
                    val sent = signalingClient.sendSignalingMessage(msg.pbDeviceId, confirm, otpKey)
                    AppLogger.i("CameraPairing", "Retransmitted PAIR_CONFIRM to ${msg.pbDeviceId} upon retry (sent=$sent)")
                    return@collect
                }
                AppLogger.i("CameraPairing", "Received PAIR_REQUEST from ${msg.pbName} ($senderId)")
                val newViewer = PairedDevice(
                    deviceId = msg.pbDeviceId,
                    name = msg.pbName,
                    publicKeyBase64 = msg.pbPublicKeyBase64,
                    pairedTimestamp = System.currentTimeMillis(),
                    role = "VIEWER"
                )
                val added = securityRepo.addPairedDevice(newViewer, maxLimit = SecurityRepository.MAX_PAIRED_VIEWERS)
                if (added) {
                    confirmedDeviceId = msg.pbDeviceId
                    val sent = signalingClient.sendSignalingMessage(msg.pbDeviceId, confirm, otpKey)
                    if (sent) {
                        AppLogger.i("CameraPairing", "PAIR_CONFIRM transmitted successfully to ${msg.pbDeviceId}")
                    }
                    if (!isNavigatingSuccess) {
                        isNavigatingSuccess = true
                        isPairingSuccessful = true
                        launch {
                            delay(4000L) // Ample retry window for viewer while servicing retries concurrently
                            onPairingSuccess()
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
                title = { Text(stringResource(R.string.camera_pairing_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.camera_pairing_broadcasting_as, cameraName),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // High-contrast QR card
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 8.dp,
                modifier = Modifier.size(280.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap,
                            contentDescription = stringResource(R.string.cd_pairing_qr_code),
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Timer Pill
            val minutes = secondsLeft / 60
            val seconds = secondsLeft % 60
            SuggestionChip(
                onClick = {},
                label = { Text(stringResource(R.string.camera_pairing_otp_expires, minutes, seconds)) },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    labelColor = if (secondsLeft < 60) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            val currentCount = securityRepo.getPairedViewers().size
            Text(
                text = stringResource(R.string.camera_pairing_slot_available, currentCount + 1, SecurityRepository.MAX_PAIRED_VIEWERS),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.camera_pairing_instructions),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(
                onClick = {
                    securityRepo.clearPairingOtp()
                    onCancel()
                }
            ) {
                Text(stringResource(R.string.action_cancel_pairing))
            }
        }
    }
}
