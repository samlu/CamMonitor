package com.a0soft.gphone.camonitor.ui.common

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrUtils {
    internal fun encodeQrMatrix(content: String, size: Int = 512): BitMatrix {
        val writer = QRCodeWriter()
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        return writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    }

    fun generateQrBitmap(content: String, size: Int = 512): ImageBitmap? {
        return try {
            val bitMatrix = encodeQrMatrix(content, size)
            val bitmap = createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap[x, y] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            bitmap.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
}
