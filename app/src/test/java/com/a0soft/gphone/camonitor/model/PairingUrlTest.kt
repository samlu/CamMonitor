package com.a0soft.gphone.camonitor.model

import com.a0soft.gphone.camonitor.data.model.PairingPayload
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class PairingUrlTest {

    @Test
    fun testToPairingUrlFormatAndNoPadding() {
        val payload = PairingPayload(
            ver = 1,
            deviceId = "cam-uuid-1234",
            name = "Living Room Cam",
            secret = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        )
        val url = payload.toPairingUrl()
        assertTrue(url.startsWith("${PairingPayload.DEFAULT_WEB_VIEWER_BASE_URL}#pair="))
        val fragmentToken = url.substringAfter("#pair=")
        assertFalse("Base64url token must omit '=' padding", fragmentToken.contains("="))
        assertFalse("Base64url token must not contain '+'", fragmentToken.contains("+"))
        assertFalse("Base64url token must not contain '/'", fragmentToken.contains("/"))
    }

    @Test
    fun testToPairingUrlAndFromQrContentRoundTrip_CjkAndSpecialChars() {
        val payload = PairingPayload(
            ver = 1,
            deviceId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
            name = "客廳鏡頭「1F」& 寶寶房 #2",
            secret = "7v8w9x0y1z2A3B4C5D6E7F8G9H0I1J2K3L4M5N6O7P8=",
            broker = "broker.hivemq.com"
        )
        val url = payload.toPairingUrl()
        val parsed = PairingPayload.fromQrContent(url)
        assertNotNull(parsed)
        assertEquals(payload, parsed)
    }

    @Test
    fun testFromQrContent_CustomBaseUrlAndTrailingParams() {
        val payload = PairingPayload(
            deviceId = "dev-99",
            name = "Porch",
            secret = "secret-xyz"
        )
        val url = payload.toPairingUrl("https://example.org/custom/#ignored")
        assertTrue(url.startsWith("https://example.org/custom/#pair="))
        assertEquals(payload, PairingPayload.fromQrContent("  $url\n"))
        assertEquals(payload, PairingPayload.fromQrContent("$url&extra=1"))
    }

    @Test
    fun testFromQrContent_BareFragmentAndPaddedOrPercentEncodedBase64Url() {
        val payload = PairingPayload(
            deviceId = "dev-pad",
            name = "Cam",
            secret = "sec"
        )
        val padded = Base64.getUrlEncoder().encodeToString(payload.toJson().toByteArray(Charsets.UTF_8))
        val percentEncoded = padded.replace("=", "%3D")
        assertEquals(payload, PairingPayload.fromQrContent("#pair=$padded"))
        assertEquals(payload, PairingPayload.fromQrContent("pair=$padded"))
        assertEquals(payload, PairingPayload.fromQrContent("https://samlu.github.io/CamMonitor/viewer/#pair=$percentEncoded"))
    }

    @Test
    fun testFromQrContent_LegacyRawJsonFallback() {
        val payload = PairingPayload(
            ver = 1,
            deviceId = "legacy-cam",
            name = "Nursery",
            secret = "legacy-secret"
        )
        val parsed = PairingPayload.fromQrContent(payload.toJson())
        assertEquals(payload, parsed)
    }

    @Test
    fun testFromQrContent_MalformedInputsReturnNull() {
        val invalidInputs = listOf(
            "",
            "   ",
            "https://samlu.github.io/CamMonitor/viewer/",
            "https://samlu.github.io/CamMonitor/viewer/#pair=",
            "https://samlu.github.io/CamMonitor/viewer/#pair=!!!not-base64!!!",
            "https://samlu.github.io/CamMonitor/viewer/#pair=" +
                Base64.getUrlEncoder().withoutPadding().encodeToString("{\"deviceId\":\"\"}".toByteArray()),
            "{not json}",
            "{\"deviceId\":\"id\",\"name\":\" \",\"secret\":\"s\"}"
        )
        invalidInputs.forEach { raw ->
            assertNull("Expected null for malformed input: $raw", PairingPayload.fromQrContent(raw))
        }
    }
}
