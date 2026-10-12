package com.transfer.flash.server.admin

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 100% offline, zero-network, air-gapped vector SVG QR Code Generator.
 * Uses Google ZXing core to produce lightweight scalable vector graphics (SVG)
 * directly inlined in HTML responses, completely eliminating reliance on external
 * third-party CDN or image generator APIs.
 */
object QrCodeGenerator {

    /**
     * Generates a self-contained inline SVG string representing the QR code for [text].
     */
    fun generateSvg(
        text: String,
        sizePx: Int = 200,
        margin: Int = 2,
        darkColor: String = "#0F172A",
        lightColor: String = "#FFFFFF",
    ): String {
        val hints = mapOf(
            EncodeHintType.MARGIN to margin,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val width = matrix.width
        val height = matrix.height

        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $width $height" width="$sizePx" height="$sizePx" shape-rendering="crispEdges" style="display:block;border-radius:8px;background:$lightColor;">""")
        sb.append("""<rect width="$width" height="$height" fill="$lightColor"/>""")
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (matrix.get(x, y)) {
                    sb.append("""<rect x="$x" y="$y" width="1" height="1" fill="$darkColor"/>""")
                }
            }
        }
        sb.append("</svg>")
        return sb.toString()
    }
}
