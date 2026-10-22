package com.a0soft.gphone.camonitor.data.model

import org.json.JSONObject

/**
 * QR Code Pairing schema exchanged between Camera PA and Viewer PB.
 */
data class PairingPayload(
    val ver: Int = 1,
    val deviceId: String,
    val name: String,
    val secret: String, // 256-bit Base64 OTP secret
    val broker: String? = null
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("ver", ver)
            put("deviceId", deviceId)
            put("name", name)
            put("secret", secret)
            broker?.let { put("broker", it) }
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): PairingPayload? {
            return try {
                val json = JSONObject(jsonStr)
                PairingPayload(
                    ver = json.optInt("ver", 1),
                    deviceId = json.getString("deviceId"),
                    name = json.getString("name"),
                    secret = json.getString("secret"),
                    broker = json.optString("broker").takeIf { it.isNotEmpty() }
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Persisted record of an authorized viewer or camera.
 */
data class PairedDevice(
    val deviceId: String,
    val name: String,
    val publicKeyBase64: String,
    val pairedTimestamp: Long,
    val role: String? = null
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("deviceId", deviceId)
            put("name", name)
            put("publicKeyBase64", publicKeyBase64)
            put("pairedTimestamp", pairedTimestamp)
            role?.let { put("role", it) }
        }
    }

    companion object {
        fun fromJson(json: JSONObject): PairedDevice {
            return PairedDevice(
                deviceId = json.getString("deviceId"),
                name = json.getString("name"),
                publicKeyBase64 = json.getString("publicKeyBase64"),
                pairedTimestamp = json.getLong("pairedTimestamp"),
                role = json.optString("role").takeIf { it.isNotEmpty() }
            )
        }
    }
}

/**
 * Encrypted and authenticated signaling message envelope sent over public MQTT.
 */
data class SignalingEnvelope(
    val senderId: String,
    val recipientId: String,
    val timestamp: Long,
    val nonce: String,
    val ciphertext: String,
    val iv: String,
    val tag: String,
    val signature: String
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("senderId", senderId)
            put("recipientId", recipientId)
            put("timestamp", timestamp)
            put("nonce", nonce)
            put("ciphertext", ciphertext)
            put("iv", iv)
            put("tag", tag)
            put("signature", signature)
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): SignalingEnvelope? {
            return try {
                val json = JSONObject(jsonStr)
                SignalingEnvelope(
                    senderId = json.getString("senderId"),
                    recipientId = json.getString("recipientId"),
                    timestamp = json.getLong("timestamp"),
                    nonce = json.getString("nonce"),
                    ciphertext = json.getString("ciphertext"),
                    iv = json.getString("iv"),
                    tag = json.getString("tag"),
                    signature = json.getString("signature")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Inner decrypted payload exchanged via signaling.
 */
sealed class SignalingMessage {
    data class PairRequest(
        val pbDeviceId: String,
        val pbName: String,
        val pbPublicKeyBase64: String
    ) : SignalingMessage()

    data class PairConfirm(
        val paDeviceId: String,
        val paName: String,
        val paPublicKeyBase64: String
    ) : SignalingMessage()

    data class Offer(
        val sdp: String
    ) : SignalingMessage()

    data class Answer(
        val sdp: String,
        val cameraName: String? = null,
        val fps: Int? = null
    ) : SignalingMessage()

    data class IceCandidate(
        val sdpMid: String,
        val sdpMLineIndex: Int,
        val sdp: String
    ) : SignalingMessage()

    data class Reject(
        val reason: String
    ) : SignalingMessage()

    fun toJson(): String {
        val json = JSONObject()
        when (this) {
            is PairRequest -> {
                json.put("type", "PAIR_REQUEST")
                json.put("pbDeviceId", pbDeviceId)
                json.put("pbName", pbName)
                json.put("pbPublicKeyBase64", pbPublicKeyBase64)
            }
            is PairConfirm -> {
                json.put("type", "PAIR_CONFIRM")
                json.put("paDeviceId", paDeviceId)
                json.put("paName", paName)
                json.put("paPublicKeyBase64", paPublicKeyBase64)
            }
            is Offer -> {
                json.put("type", "OFFER")
                json.put("sdp", sdp)
            }
            is Answer -> {
                json.put("type", "ANSWER")
                json.put("sdp", sdp)
                cameraName?.let { json.put("cameraName", it) }
                fps?.let { json.put("fps", it) }
            }
            is IceCandidate -> {
                json.put("type", "ICE_CANDIDATE")
                json.put("sdpMid", sdpMid)
                json.put("sdpMLineIndex", sdpMLineIndex)
                json.put("sdp", sdp)
            }
            is Reject -> {
                json.put("type", "REJECT")
                json.put("reason", reason)
            }
        }
        return json.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): SignalingMessage? {
            return try {
                val json = JSONObject(jsonStr)
                when (json.getString("type")) {
                    "PAIR_REQUEST" -> PairRequest(
                        pbDeviceId = json.getString("pbDeviceId"),
                        pbName = json.getString("pbName"),
                        pbPublicKeyBase64 = json.getString("pbPublicKeyBase64")
                    )
                    "PAIR_CONFIRM" -> PairConfirm(
                        paDeviceId = json.getString("paDeviceId"),
                        paName = json.getString("paName"),
                        paPublicKeyBase64 = json.getString("paPublicKeyBase64")
                    )
                    "OFFER" -> Offer(sdp = json.getString("sdp"))
                    "ANSWER" -> Answer(
                        sdp = json.getString("sdp"),
                        cameraName = json.optString("cameraName").takeIf { it.isNotEmpty() },
                        fps = if (json.has("fps")) json.getInt("fps") else null
                    )
                    "ICE_CANDIDATE" -> IceCandidate(
                        sdpMid = json.getString("sdpMid"),
                        sdpMLineIndex = json.getInt("sdpMLineIndex"),
                        sdp = json.getString("sdp")
                    )
                    "REJECT" -> Reject(reason = json.getString("reason"))
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Control commands sent over WebRTC DataChannel.
 */
enum class NightModeSetting {
    AUTO,
    NIGHT,
    NORMAL
}

sealed class ControlCommand {
    data class Torch(val enabled: Boolean) : ControlCommand()
    object PttStart : ControlCommand()
    object PttStop : ControlCommand()
    data class Rename(val name: String) : ControlCommand()
    data class Battery(val percentage: Int, val isCharging: Boolean) : ControlCommand()
    object QueryBattery : ControlCommand()
    data class SetNightMode(val mode: String) : ControlCommand()
    data class NightModeReport(val mode: String, val isNightActive: Boolean) : ControlCommand()
    data class FpsReport(val fps: Int) : ControlCommand()

    fun toJson(): String {
        val json = JSONObject()
        when (this) {
            is Torch -> {
                json.put("cmd", "TORCH")
                json.put("enabled", enabled)
            }
            is PttStart -> json.put("cmd", "PTT_START")
            is PttStop -> json.put("cmd", "PTT_STOP")
            is Rename -> {
                json.put("cmd", "RENAME")
                json.put("name", name)
            }
            is Battery -> {
                json.put("cmd", "BATTERY")
                json.put("pct", percentage)
                json.put("charging", isCharging)
            }
            is QueryBattery -> json.put("cmd", "QUERY_BATTERY")
            is SetNightMode -> {
                json.put("cmd", "SET_NIGHT_MODE")
                json.put("mode", mode)
            }
            is NightModeReport -> {
                json.put("cmd", "NIGHT_MODE_REPORT")
                json.put("mode", mode)
                json.put("active", isNightActive)
            }
            is FpsReport -> {
                json.put("cmd", "FPS_REPORT")
                json.put("fps", fps)
            }
        }
        return json.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): ControlCommand? {
            return try {
                val json = JSONObject(jsonStr)
                when (json.getString("cmd")) {
                    "TORCH" -> Torch(enabled = json.getBoolean("enabled"))
                    "PTT_START" -> PttStart
                    "PTT_STOP" -> PttStop
                    "RENAME" -> Rename(name = json.getString("name"))
                    "BATTERY" -> Battery(
                        percentage = json.getInt("pct"),
                        isCharging = json.optBoolean("charging", false)
                    )
                    "QUERY_BATTERY" -> QueryBattery
                    "SET_NIGHT_MODE" -> SetNightMode(mode = json.getString("mode"))
                    "NIGHT_MODE_REPORT" -> NightModeReport(
                        mode = json.getString("mode"),
                        isNightActive = json.optBoolean("active", false)
                    )
                    "FPS_REPORT" -> FpsReport(fps = json.getInt("fps"))
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Log entry for diagnostics.
 */
enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    fun format(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
        val time = sdf.format(java.util.Date(timestamp))
        return "[$time] [${level.name}] [$tag] $message"
    }
}
