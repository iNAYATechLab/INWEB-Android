package com.inweb.app.net

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Turns a URL into a scannable QR bitmap using ZXing.
 * Colors: dark foreground on white background so it prints/scans well
 * in any lighting.
 */
object QrGenerator {

    /**
     * @param text     what to encode (usually a URL)
     * @param sizePx   width & height of the resulting square bitmap
     * @param fgColor  ARGB foreground colour (default = INWEB teal-dark)
     * @param bgColor  ARGB background colour
     */
    fun generate(
        text: String,
        sizePx: Int,
        fgColor: Int = 0xFF0F766E.toInt(),
        bgColor: Int = Color.WHITE
    ): Bitmap {
        require(sizePx > 0) { "sizePx must be > 0" }
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN        to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)

        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val offset = y * w
            for (x in 0 until w) {
                pixels[offset + x] = if (matrix.get(x, y)) fgColor else bgColor
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }
}
