package com.a0soft.gphone.camonitor.data.signaling

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.a0soft.gphone.camonitor.data.model.SignalingEnvelope
import com.a0soft.gphone.camonitor.data.model.SignalingMessage
import com.a0soft.gphone.camonitor.data.security.SecurityRepository
import com.a0soft.gphone.camonitor.log.AppLogger
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttClientSslConfig
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume

interface SignalingClient {
    val incomingMessages: SharedFlow<Pair<String, SignalingMessage>>
    val isConnectedFlow: StateFlow<Boolean>
    suspend fun startListening(): Boolean
    suspend fun stopListening()
    suspend fun sendEnvelope(envelope: SignalingEnvelope): Boolean
    suspend fun sendSignalingMessage(
        recipientId: String,
        message: SignalingMessage,
        secretOrDerivedKey: ByteArray
    ): Boolean
}

class MqttSignalingClient(
    private val securityRepository: SecurityRepository,
    private val context: Context? = null
) : SignalingClient {

    private val tag = "MqttSignalingClient"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _incomingMessages = MutableSharedFlow<Pair<String, SignalingMessage>>(extraBufferCapacity = 64)
    override val incomingMessages: SharedFlow<Pair<String, SignalingMessage>> = _incomingMessages.asSharedFlow()

    private val _isConnectedFlow = MutableStateFlow(false)
    override val isConnectedFlow: StateFlow<Boolean> = _isConnectedFlow.asStateFlow()

    private var activeClient: Mqtt3AsyncClient? = null
    private var clientGeneration: Long = 0L
    private var isListeningWanted: Boolean = false
    private var reconnectJob: Job? = null
    private val connectMutex = Mutex()

    companion object {
        const val PRIMARY_BROKER_HOST = "broker.hivemq.com"
        const val PRIMARY_BROKER_PORT = 8883
        const val SECONDARY_BROKER_HOST = "broker.emqx.io"
        const val SECONDARY_BROKER_PORT = 8883
    }

    init {
        context?.let { ctx ->
            try {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm?.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        AppLogger.i(tag, "Network available callback received")
                        if (isListeningWanted && !_isConnectedFlow.value) {
                            val deviceId = securityRepository.getDeviceId()
                            val topic = "cammonitor/$deviceId/rx"
                            reconnectJob?.cancel()
                            triggerReconnectLoop(topic)
                        }
                    }

                    override fun onLost(network: Network) {
                        AppLogger.w(tag, "Network lost callback received")
                        _isConnectedFlow.value = false
                    }
                })
                AppLogger.d(tag, "Connectivity network callback registered successfully")
            } catch (e: Exception) {
                AppLogger.w(tag, "Unable to register network callback: ${e.message}")
            }
        }
    }

    override suspend fun startListening(): Boolean {
        isListeningWanted = true
        if (_isConnectedFlow.value && activeClient?.state?.isConnected == true) {
            AppLogger.d(tag, "Already connected and listening")
            return true
        }

        val deviceId = securityRepository.getDeviceId()
        val topic = "cammonitor/$deviceId/rx"

        AppLogger.i(tag, "Connecting to MQTT signaling for device: $deviceId, topic: $topic")
        val success = connectWithRetry(topic)
        if (!success) {
            triggerReconnectLoop(topic)
        }
        return success
    }

    private suspend fun resolveHostCandidates(host: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val addresses = java.net.InetAddress.getAllByName(host)
            val ipv4List = addresses.filterIsInstance<java.net.Inet4Address>().mapNotNull { it.hostAddress }
            val ipv6List = addresses.filterIsInstance<java.net.Inet6Address>().mapNotNull { it.hostAddress }
            // Dual-stack cellular networks prefer IPv6 by default, but some public MQTT brokers 
            // (e.g. broker.hivemq.com) have broken/refused AAAA endpoints on port 1883/8883.
            // Prefer IPv4 addresses first to ensure robust cellular & Wi-Fi connectivity.
            val candidates = ipv4List + ipv6List
            if (candidates.isNotEmpty()) {
                AppLogger.d(tag, "Resolved $host candidates: $candidates")
                candidates
            } else {
                listOf(host)
            }
        } catch (e: Exception) {
            AppLogger.w(tag, "Failed DNS resolution for $host: ${e.message}, using raw hostname")
            listOf(host)
        }
    }

    private suspend fun connectWithRetry(topic: String): Boolean = connectMutex.withLock {
        if (!isListeningWanted) return@withLock false
        val currentClient = activeClient
        if (_isConnectedFlow.value && currentClient != null && currentClient.state.isConnected) return@withLock true

        try {
            activeClient?.disconnect()
        } catch (_: Exception) {}
        activeClient = null

        val currentGen = ++clientGeneration
        val brokers = listOf(
            PRIMARY_BROKER_HOST to PRIMARY_BROKER_PORT,
            SECONDARY_BROKER_HOST to SECONDARY_BROKER_PORT
        )

        for ((canonicalHost, port) in brokers) {
            val candidates = resolveHostCandidates(canonicalHost)
            AppLogger.i(tag, "Attempting TLS connection to $canonicalHost:$port (candidates=$candidates, gen=$currentGen)...")

            for (targetHost in candidates) {
                val success = try {
                    withTimeoutOrNull(8_000L) {
                        attemptConnect(
                            host = targetHost,
                            expectedHost = canonicalHost,
                            port = port,
                            useSsl = true,
                            topic = topic,
                            generation = currentGen
                        )
                    } ?: false
                } catch (e: Exception) {
                    AppLogger.w(tag, "TLS connection error for $targetHost ($canonicalHost): ${e.message}")
                    false
                }
                if (success) {
                    AppLogger.i(tag, "Successfully connected via TLS to $targetHost:$port (host=$canonicalHost, gen=$currentGen)")
                    _isConnectedFlow.value = true
                    return@withLock true
                }
            }
        }

        AppLogger.w(tag, "All TLS candidate connection attempts failed across brokers (gen=$currentGen)")
        _isConnectedFlow.value = false
        return@withLock false
    }

    private suspend fun attemptConnect(
        host: String,
        expectedHost: String,
        port: Int,
        useSsl: Boolean,
        topic: String,
        generation: Long
    ): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val clientId = "cammonitor-" + UUID.randomUUID().toString().take(8)
            var hasEverConnected = false

            val builder = MqttClient.builder()
                .useMqttVersion3()
                .identifier(clientId)
                .serverHost(host)
                .serverPort(port)
                .addConnectedListener {
                    if (generation == clientGeneration) {
                        hasEverConnected = true
                        AppLogger.i(tag, "MQTT connected callback (gen=$generation, host=$host)")
                        _isConnectedFlow.value = true
                    }
                }
                .addDisconnectedListener { context ->
                    if (generation == clientGeneration && hasEverConnected) {
                        AppLogger.w(tag, "MQTT disconnected (gen=$generation, host=$host): ${context.cause.message}")
                        _isConnectedFlow.value = false
                        if (isListeningWanted) {
                            val deviceId = securityRepository.getDeviceId()
                            triggerReconnectLoop("cammonitor/$deviceId/rx")
                        }
                    }
                }

            val newClient = if (useSsl) {
                val sslConfig = MqttClientSslConfig.builder()
                    .hostnameVerifier { _, session ->
                        // Validate certificate matches the canonical hostname (e.g. broker.hivemq.com)
                        HttpsURLConnection.getDefaultHostnameVerifier().verify(expectedHost, session)
                    }
                    .build()
                builder.sslConfig(sslConfig).buildAsync()
            } else {
                builder.buildAsync()
            }

            continuation.invokeOnCancellation {
                try {
                    newClient.disconnect()
                } catch (_: Exception) {}
            }

            try {
                newClient.connectWith()
                    .cleanSession(true)
                    .keepAlive(30)
                    .send()
                    .whenComplete { _, connectError ->
                        if (!continuation.isActive) {
                            try { newClient.disconnect() } catch (_: Exception) {}
                            return@whenComplete
                        }
                        if (connectError != null) {
                            AppLogger.w(tag, "Failed connecting to $host:$port (gen=$generation): ${connectError.message}")
                            try { newClient.disconnect() } catch (_: Exception) {}
                            continuation.resume(false)
                        } else {
                            AppLogger.i(tag, "Connected to $host:$port. Subscribing to $topic (gen=$generation)...")
                            newClient.subscribeWith()
                                .topicFilter(topic)
                                .qos(MqttQos.AT_LEAST_ONCE)
                                .callback { publish ->
                                    val payloadStr = String(publish.payloadAsBytes, Charsets.UTF_8)
                                    handleIncomingRawPayload(payloadStr)
                                }
                                .send()
                                .whenComplete { _, subError ->
                                    if (!continuation.isActive) {
                                        try { newClient.disconnect() } catch (_: Exception) {}
                                        return@whenComplete
                                    }
                                    if (subError != null) {
                                        AppLogger.e(tag, "Subscription failed on $topic (gen=$generation)", subError)
                                        try { newClient.disconnect() } catch (_: Exception) {}
                                        continuation.resume(false)
                                    } else {
                                        AppLogger.i(tag, "Subscribed successfully to $topic on $host:$port (gen=$generation)")
                                        activeClient = newClient
                                        _isConnectedFlow.value = true
                                        continuation.resume(true)
                                    }
                                }
                        }
                    }
            } catch (e: Exception) {
                AppLogger.e(tag, "Exception during connection attempt to $host", e)
                try { newClient.disconnect() } catch (_: Exception) {}
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }

    private fun triggerReconnectLoop(topic: String) {
        if (!isListeningWanted) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var delayMs = 2000L
            while (isActive && isListeningWanted && !_isConnectedFlow.value) {
                AppLogger.i(tag, "Scheduled reconnect in ${delayMs}ms (wanted=$isListeningWanted, connected=${_isConnectedFlow.value})...")
                delay(delayMs)
                if (!isActive || !isListeningWanted || _isConnectedFlow.value) break

                val success = connectWithRetry(topic)
                if (success) {
                    AppLogger.i(tag, "Reconnection successfully restored signaling")
                    break
                }
                delayMs = (delayMs * 2).coerceAtMost(15_000L)
            }
        }
    }

    private fun handleIncomingRawPayload(rawJson: String) {
        scope.launch {
            try {
                val envelope = SignalingEnvelope.fromJson(rawJson)
                if (envelope == null) {
                    AppLogger.w(tag, "Dropped malformed envelope")
                    return@launch
                }

                // Determine decryption key:
                // 1. If sender is in whitelist, derive ECDH key
                // 2. If active OTP is set, derive OTP key
                val senderDevice = securityRepository.getPairedDevice(envelope.senderId)
                val otp = securityRepository.getActivePairingOtp()

                val otpKey = if (otp != null) {
                    com.a0soft.gphone.camonitor.data.security.CryptoUtils.hkdfSha256(
                        otp.toByteArray(Charsets.UTF_8),
                        ByteArray(0),
                        "CamMonitor-OTP".toByteArray(),
                        32
                    )
                } else null

                val ecdhKey = if (senderDevice != null) {
                    val remotePubKey = com.a0soft.gphone.camonitor.data.security.CryptoUtils.publicKeyFromBase64(senderDevice.publicKeyBase64)
                    com.a0soft.gphone.camonitor.data.security.CryptoUtils.deriveEcdhKey(
                        securityRepository.getKeyPair().private,
                        remotePubKey
                    )
                } else null

                val decryptedJson = when {
                    ecdhKey != null -> {
                        val result = securityRepository.verifyAndDecryptEnvelope(envelope, ecdhKey, isOtpKey = false)
                        if (result == null && otpKey != null) {
                            securityRepository.verifyAndDecryptEnvelope(envelope, otpKey, isOtpKey = true)
                        } else {
                            result
                        }
                    }
                    otpKey != null -> {
                        securityRepository.verifyAndDecryptEnvelope(envelope, otpKey, isOtpKey = true)
                    }
                    else -> {
                        AppLogger.w(tag, "Dropped envelope from unauthorized sender: ${envelope.senderId}")
                        return@launch
                    }
                }

                if (decryptedJson != null) {
                    val message = SignalingMessage.fromJson(decryptedJson)
                    if (message != null) {
                        AppLogger.i(tag, "Received verified message [${message.javaClass.simpleName}] from ${envelope.senderId}")
                        _incomingMessages.emit(Pair(envelope.senderId, message))
                    } else {
                        AppLogger.w(tag, "Failed parsing decrypted signaling message from ${envelope.senderId}: ${decryptedJson.take(100)}")
                    }
                } else {
                    AppLogger.w(tag, "Verification or decryption failed for envelope from ${envelope.senderId} (hasEcdhKey=${ecdhKey != null}, hasOtpKey=${otpKey != null})")
                }
            } catch (e: Exception) {
                AppLogger.e(tag, "Error handling incoming payload", e)
            }
        }
    }

    private suspend fun awaitConnection(timeoutMs: Long = 10_000L): Boolean {
        val currentClient = activeClient
        if (_isConnectedFlow.value && currentClient != null && currentClient.state.isConnected) return true
        _isConnectedFlow.value = false
        AppLogger.i(tag, "awaitConnection: client is not connected, initiating connection...")
        startListening()
        return withTimeoutOrNull(timeoutMs) {
            _isConnectedFlow.first { it }
        } ?: false
    }

    override suspend fun sendEnvelope(envelope: SignalingEnvelope): Boolean {
        val startPublish = System.currentTimeMillis()
        var connected = awaitConnection(7000L)
        if (!connected) {
            AppLogger.w(tag, "First awaitConnection attempt failed for ${envelope.recipientId}, retrying once...")
            connected = awaitConnection(5000L)
        }
        if (!connected) {
            AppLogger.e(tag, "Cannot send envelope to ${envelope.recipientId}: Signaling client not connected to broker")
            return false
        }

        val currentClient = activeClient
        if (currentClient == null || !currentClient.state.isConnected) {
            AppLogger.e(tag, "Cannot send envelope to ${envelope.recipientId}: MQTT client is disconnected")
            _isConnectedFlow.value = false
            return false
        }

        val targetTopic = "cammonitor/${envelope.recipientId}/rx"
        val payload = envelope.toJson().toByteArray(Charsets.UTF_8)

        return suspendCancellableCoroutine { continuation ->
            try {
                currentClient.publishWith()
                    .topic(targetTopic)
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .payload(payload)
                    .send()
                    .whenComplete { _, throwable ->
                        val duration = System.currentTimeMillis() - startPublish
                        if (throwable != null) {
                            AppLogger.e(tag, "Failed publishing to $targetTopic after ${duration}ms: ${throwable.message}", throwable)
                            _isConnectedFlow.value = false
                            if (continuation.isActive) continuation.resume(false)
                        } else {
                            AppLogger.i(tag, "Published envelope to $targetTopic (${payload.size} bytes in ${duration}ms)")
                            if (continuation.isActive) continuation.resume(true)
                        }
                    }
            } catch (e: Exception) {
                AppLogger.e(tag, "Exception during publish to $targetTopic", e)
                _isConnectedFlow.value = false
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }

    override suspend fun sendSignalingMessage(
        recipientId: String,
        message: SignalingMessage,
        secretOrDerivedKey: ByteArray
    ): Boolean {
        val plaintextJson = message.toJson()
        val envelope = securityRepository.createEnvelope(recipientId, plaintextJson, secretOrDerivedKey)
        AppLogger.d(tag, "Sending [${message.javaClass.simpleName}] to $recipientId (envelope nonce=${envelope.nonce}, size=${envelope.ciphertext.length} chars)")
        return sendEnvelope(envelope)
    }

    override suspend fun stopListening() {
        isListeningWanted = false
        reconnectJob?.cancel()
        reconnectJob = null
        connectMutex.withLock {
            try {
                activeClient?.disconnect()
                activeClient = null
                _isConnectedFlow.value = false
                AppLogger.i(tag, "Disconnected from MQTT signaling")
            } catch (e: Exception) {
                AppLogger.w(tag, "Error during MQTT disconnect: ${e.message}")
            }
        }
    }
}
