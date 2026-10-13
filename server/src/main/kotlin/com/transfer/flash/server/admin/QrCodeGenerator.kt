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
        /** Adds an inline `style` attribute (blocked by the console's CSP anyway); the console and landing page style the SVG with CSS classes instead. */
        styled: Boolean = false,
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
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $width $height" width="$sizePx" height="$sizePx" shape-rendering="crispEdges"${if (styled) " style=\"display:block;border-radius:8px;background:$lightColor;\"" else ""}>""")
        sb.append("""<rect width="$width" height="$height" fill="$lightColor"/>""")
        // ONE path with a run per row of dark modules: a 60x60 code is about 8 KB, not 130 KB of one-pixel rectangles
        // (a response with many of them must stay inside the 64 KiB REST body cap).
        sb.append("""<path fill="$darkColor" d="""")
        for (y in 0 until height) {
            var x = 0
            while (x < width) {
                if (matrix.get(x, y)) {
                    var run = 1
                    while (x + run < width && matrix.get(x + run, y)) run++
                    sb.append("M$x ${y}h${run}v1h-${run}z")
                    x += run
                } else {
                    x++
                }
            }
        }
        sb.append("\"/>")
        sb.append("</svg>")
        return sb.toString()
    }
}
