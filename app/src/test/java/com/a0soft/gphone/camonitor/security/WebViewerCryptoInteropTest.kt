package com.a0soft.gphone.camonitor.security

import com.a0soft.gphone.camonitor.data.model.SignalingEnvelope
import com.a0soft.gphone.camonitor.data.model.SignalingMessage
import com.a0soft.gphone.camonitor.data.security.CryptoUtils
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

class WebViewerCryptoInteropTest {

    private fun findRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs/viewer/test/interop-bridge.mjs").exists()) {
                return dir
            }
            dir = dir.parentFile
        }
        error("Could not locate repository root containing docs/viewer/test/interop-bridge.mjs")
    }

    private fun isNodeAvailable(): Boolean {
        return try {
            val proc = ProcessBuilder("node", "--version").start()
            proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    @Test
    fun testBidirectionalCryptoAndSignalingInteropWithWebViewer() {
        assumeTrue("Node.js runtime required for cross-platform WebCrypto interop test", isNodeAvailable())
        val repoRoot = findRepoRoot()
        val bridgeScript = File(repoRoot, "docs/viewer/test/interop-bridge.mjs")

        val paId = "11111111-1111-4111-8111-111111111111"
        val pbId = "22222222-2222-4222-8222-222222222222"
        val paKeys = CryptoUtils.generateSoftwareEcKeyPair()
        val paPubB64 = CryptoUtils.publicKeyToBase64(paKeys.public)
        val otp = CryptoUtils.generateOtp()
        val otpKey = CryptoUtils.hkdfSha256(otp.toByteArray(Charsets.UTF_8), ByteArray(0), "CamMonitor-OTP".toByteArray(), 32)

        val now = 1_764_000_000_000L
        val confirmMsg = SignalingMessage.PairConfirm(
            paDeviceId = paId,
            paName = "Nursery Cam 寶寶",
            paPublicKeyBase64 = paPubB64
        )
        val confirmNonce = CryptoUtils.generateNonce()
        val (confirmCipher, confirmIv, confirmTag) = CryptoUtils.encryptAesGcm(
            confirmMsg.toJson().toByteArray(Charsets.UTF_8),
            otpKey
        )
        val confirmSigInput = "$paId$pbId$now$confirmNonce$confirmCipher".toByteArray(Charsets.UTF_8)
        val confirmSig = CryptoUtils.sign(confirmSigInput, paKeys.private)
        val confirmEnvelope = SignalingEnvelope(
            senderId = paId,
            recipientId = pbId,
            timestamp = now,
            nonce = confirmNonce,
            ciphertext = confirmCipher,
            iv = confirmIv,
            tag = confirmTag,
            signature = confirmSig
        )

        // Generate a pre-keyed sample vector for raw primitive checks
        val rawPlain = "Hello WebCrypto from Android CryptoUtils! 你好"
        val rawAesKey = ByteArray(32) { (it * 7 + 3).toByte() }
        val (rawCipher, rawIv, rawTag) = CryptoUtils.encryptAesGcm(rawPlain.toByteArray(Charsets.UTF_8), rawAesKey)
        val rawSig = CryptoUtils.sign(rawPlain.toByteArray(Charsets.UTF_8), paKeys.private)

        val inputJson = JSONObject().apply {
            put("now", now)
            put("paId", paId)
            put("pbId", pbId)
            put("paPubB64", paPubB64)
            put("otp", otp)
            put("otpKeyB64", Base64.getEncoder().encodeToString(otpKey))
            put("rawPlain", rawPlain)
            put("rawAesKeyB64", Base64.getEncoder().encodeToString(rawAesKey))
            put("rawCipher", rawCipher)
            put("rawIv", rawIv)
            put("rawTag", rawTag)
            put("rawSig", rawSig)
            put("confirmEnvelope", JSONObject(confirmEnvelope.toJson()))
        }.toString()

        val proc = ProcessBuilder("node", bridgeScript.absolutePath)
            .directory(repoRoot)
            .redirectErrorStream(false)
            .start()
        proc.outputStream.use { it.write(inputJson.toByteArray(Charsets.UTF_8)) }
        val stdout = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val stderr = proc.errorStream.bufferedReader(Charsets.UTF_8).readText()
        assertTrue("Node interop bridge timed out", proc.waitFor(15, TimeUnit.SECONDS))
        assertEquals("Node interop bridge failed: $stderr\nstdout=$stdout", 0, proc.exitValue())

        val out = JSONObject(stdout)
        assertTrue("Node bridge must verify PA primitives", out.getBoolean("verifiedAndroidVectors"))

        // Now verify Web -> Android direction using Android CryptoUtils
        val pbPubB64 = out.getString("pbPubB64")
        val pbPubKey = CryptoUtils.publicKeyFromBase64(pbPubB64)

        // 1. Verify ECDH derived key matches bit-for-bit
        val androidEcdhKey = CryptoUtils.deriveEcdhKey(paKeys.private, pbPubKey)
        assertEquals(
            "ECDH+HKDF derived key must match bit-for-bit between WebCrypto and Android",
            out.getString("webEcdhKeyB64"),
            Base64.getEncoder().encodeToString(androidEcdhKey)
        )

        // 2. Verify & decrypt Web -> Android PAIR_REQUEST (OTP-keyed + PoP signature)
        val pairReqEnv = SignalingEnvelope.fromJson(out.getJSONObject("pairRequestEnvelope").toString())!!
        val pairReqSigPayload = "${pairReqEnv.senderId}${pairReqEnv.recipientId}${pairReqEnv.timestamp}${pairReqEnv.nonce}${pairReqEnv.ciphertext}".toByteArray(Charsets.UTF_8)
        assertTrue("Web PAIR_REQUEST PoP signature must verify in Android", CryptoUtils.verify(pairReqSigPayload, pairReqEnv.signature, pbPubKey))
        val pairReqPlain = String(CryptoUtils.decryptAesGcm(pairReqEnv.ciphertext, pairReqEnv.iv, pairReqEnv.tag, otpKey), Charsets.UTF_8)
        val pairReqMsg = SignalingMessage.fromJson(pairReqPlain) as? SignalingMessage.PairRequest
        assertNotNull(pairReqMsg)
        assertEquals(pbId, pairReqMsg?.pbDeviceId)
        assertEquals(pbPubB64, pairReqMsg?.pbPublicKeyBase64)

        // 3. Verify & decrypt Web -> Android OFFER (ECDH-keyed + ECDSA DER signature)
        val offerEnv = SignalingEnvelope.fromJson(out.getJSONObject("offerEnvelope").toString())!!
        val offerSigPayload = "${offerEnv.senderId}${offerEnv.recipientId}${offerEnv.timestamp}${offerEnv.nonce}${offerEnv.ciphertext}".toByteArray(Charsets.UTF_8)
        assertTrue("Web OFFER ECDSA signature must verify in Android", CryptoUtils.verify(offerSigPayload, offerEnv.signature, pbPubKey))
        val offerPlain = String(CryptoUtils.decryptAesGcm(offerEnv.ciphertext, offerEnv.iv, offerEnv.tag, androidEcdhKey), Charsets.UTF_8)
        val offerMsg = SignalingMessage.fromJson(offerPlain) as? SignalingMessage.Offer
        assertNotNull(offerMsg)
        assertEquals("v=0\r\no=- 42 2 IN IP4 127.0.0.1", offerMsg?.sdp)

        // 4. Verify & decrypt Android -> Web -> Android ANSWER echo check
        val answerMsg = SignalingMessage.Answer(sdp = "v=0\r\nanswer-sdp", cameraName = "Nursery Cam 寶寶", fps = 20)
        val answerNonce = CryptoUtils.generateNonce()
        val (ansCipher, ansIv, ansTag) = CryptoUtils.encryptAesGcm(answerMsg.toJson().toByteArray(Charsets.UTF_8), androidEcdhKey)
        val ansSig = CryptoUtils.sign("$paId$pbId${now + 1000}$answerNonce$ansCipher".toByteArray(Charsets.UTF_8), paKeys.private)
        val ansEnv = SignalingEnvelope(paId, pbId, now + 1000, answerNonce, ansCipher, ansIv, ansTag, ansSig)

        // Invoke bridge in decrypt-ecdh mode
        val checkProc = ProcessBuilder("node", bridgeScript.absolutePath, "--verify-answer")
            .directory(repoRoot)
            .start()
        val checkPayload = JSONObject().apply {
            put("now", now + 1000)
            put("pbId", pbId)
            put("paId", paId)
            put("paPubB64", paPubB64)
            put("pbPkcs8B64", out.getString("pbPkcs8B64"))
            put("answerEnvelope", JSONObject(ansEnv.toJson()))
        }.toString()
        checkProc.outputStream.use { it.write(checkPayload.toByteArray(Charsets.UTF_8)) }
        val checkOut = checkProc.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val checkErr = checkProc.errorStream.bufferedReader(Charsets.UTF_8).readText()
        assertTrue("Second bridge run timed out", checkProc.waitFor(15, TimeUnit.SECONDS))
        assertEquals("Answer verification in WebCrypto failed: $checkErr", 0, checkProc.exitValue())
        val checkJson = JSONObject(checkOut)
        assertEquals("v=0\r\nanswer-sdp", checkJson.getString("sdp"))
        assertEquals("Nursery Cam 寶寶", checkJson.getString("cameraName"))
        assertEquals(20, checkJson.getInt("fps"))
    }
}
