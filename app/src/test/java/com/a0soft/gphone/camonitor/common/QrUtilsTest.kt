package com.a0soft.gphone.camonitor.common

import com.a0soft.gphone.camonitor.data.model.PairingPayload
import com.a0soft.gphone.camonitor.ui.common.QrUtils
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class QrUtilsTest {

    @Test
    fun testEncodeQrMatrixRoundTripsUniversalPairingUrl() {
        val payload = PairingPayload(
            ver = 1,
            deviceId = UUID.randomUUID().toString(),
            name = "客廳攝影機 Living Room",
            secret = "K7gNU3sdo+OL0wNhqoVWhr3g6s1xYv72ol/pe/Unols="
        )
        val url = payload.toPairingUrl()
        assertTrue("Expected compact URL around ~240 chars, was ${url.length}", url.length in 180..320)

        val size = 512
        val matrix = QrUtils.encodeQrMatrix(url, size)
        assertEquals(size, matrix.width)
        assertEquals(size, matrix.height)

        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val source = RGBLuminanceSource(size, size, pixels)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val decoded = QRCodeReader().decode(
            bitmap,
            mapOf(DecodeHintType.CHARACTER_SET to "UTF-8")
        )

        assertEquals(url, decoded.text)
        assertEquals(payload, PairingPayload.fromQrContent(decoded.text))
    }
}
