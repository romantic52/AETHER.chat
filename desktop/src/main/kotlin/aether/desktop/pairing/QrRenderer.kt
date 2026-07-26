package aether.desktop.pairing

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage

/** QR-код содержимого привязки. Кодирование оффлайн, без сети. */
object QrRenderer {
    fun render(payload: String, sizePx: Int = 320, dark: Boolean = false): ImageBitmap =
        renderAwt(payload, sizePx, dark).toComposeImageBitmap()

    /** AWT-вариант для записи в файл (headless-проверки, экспорт). */
    fun renderAwt(payload: String, sizePx: Int = 320, dark: Boolean = false): BufferedImage {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val fg = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        val bg = if (dark) 0xFF17212B.toInt() else 0xFFFFFFFF.toInt()
        val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                image.setRGB(x, y, if (matrix.get(x, y)) fg else bg)
            }
        }
        return image
    }
}
