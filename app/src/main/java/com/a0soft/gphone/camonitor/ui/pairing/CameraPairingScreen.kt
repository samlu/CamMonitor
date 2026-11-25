package com.a0soft.gphone.camonitor.ui.pairing

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
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
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraPairingScreen(
    onPairingSuccess: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val app = remember { CamMonitorApp.instance }
    val securityRepo = app.securityRepository
    val signalingClient = app.signalingClient
    val coroutineScope = rememberCoroutineScope()

    var otp by remember { mutableStateOf(securityRepo.generatePairingOtp()) }
    var secondsLeft by remember { mutableIntStateOf(300) } // 5 minutes
    var isPairingSuccessful by remember { mutableStateOf(false) }
    var confirmedDeviceId by remember { mutableStateOf<String?>(null) }
    var isNavigatingSuccess by remember { mutableStateOf(false) }
    var linkCopied by remember { mutableStateOf(false) }

    val cameraName = remember { securityRepo.getDeviceName() }
    val deviceId = remember { securityRepo.getDeviceId() }

    val pairingUrl = remember(otp, deviceId, cameraName) {
        PairingPayload(
            deviceId = deviceId,
            name = cameraName,
            secret = otp
        ).toPairingUrl()
    }

    val qrBitmap = remember(pairingUrl) {
        QrUtils.generateQrBitmap(pairingUrl, 512)
    }

    LaunchedEffect(otp) {
        linkCopied = false
    }

    LaunchedEffect(linkCopied) {
        if (linkCopied) {
            delay(2500)
            linkCopied = false
        }
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
                delay(1.seconds)
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
                val existing = securityRepo.getPairedDevice(msg.pbDeviceId)
                if (existing != null && existing.publicKeyBase64 != msg.pbPublicKeyBase64) {
                    AppLogger.w("CameraPairing", "PairRequest rejected: deviceId ${msg.pbDeviceId} already paired with different credentials")
                    return@collect
                }
                val sanitizedName = msg.pbName.trim().take(32).filter { !it.isISOControl() }.ifEmpty { "Viewer" }
                val newViewer = PairedDevice(
                    deviceId = msg.pbDeviceId,
                    name = sanitizedName,
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
                        coroutineScope.launch {
                            delay(4.seconds) // Ample retry window for viewer while servicing retries concurrently
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
                .verticalScroll(rememberScrollState())
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

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_SUBJECT,
                                context.getString(R.string.camera_pairing_share_subject, cameraName)
                            )
                            putExtra(Intent.EXTRA_TEXT, pairingUrl)
                        }
                        context.startActivity(
                            Intent.createChooser(
                                sendIntent,
                                context.getString(R.string.camera_pairing_share_chooser_title)
                            )
                        )
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.camera_pairing_share_link))
                }

                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = ClipData.newPlainText("CamMonitor Pairing Link", pairingUrl).apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                description.extras = PersistableBundle().apply {
                                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                                }
                            }
                        }
                        clipboard?.setPrimaryClip(clip)
                        linkCopied = true
                    }
                ) {
                    Icon(
                        imageVector = if (linkCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(
                            if (linkCopied) R.string.camera_pairing_link_copied
                            else R.string.camera_pairing_copy_link
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = stringResource(R.string.camera_pairing_link_security_note),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

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
