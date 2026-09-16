package com.transfer.flash.ui.calling

import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.awt.image.BufferedImage

class DesktopVideoRenderingTest {

    @Test
    fun bufferedImageToComposeImageBitmapWorks() {
        val width = 640
        val height = 480
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val composeBitmap = img.toComposeImageBitmap()
        assertEquals(width, composeBitmap.width)
        assertEquals(height, composeBitmap.height)
    }

    @Test
    fun skiaImageMakeRasterWorks() {
        val width = 640
        val height = 480
        val bytes = ByteArray(width * height * 4)
        val info = ImageInfo(
            width = width,
            height = height,
            colorType = ColorType.BGRA_8888,
            alphaType = ColorAlphaType.PREMUL,
        )
        val skiaImg = Image.makeRaster(info, bytes, width * 4)
        assertNotNull(skiaImg)
        val composeBitmap = skiaImg.toComposeImageBitmap()
        assertEquals(width, composeBitmap.width)
        assertEquals(height, composeBitmap.height)
    }

    @Test
    fun fourCCFormatsAreAvailable() {
        val formats = dev.onvoid.webrtc.media.FourCC.values().map { it.name }
        println("Available FourCC: $formats")
        org.junit.Assert.assertTrue(formats.contains("ARGB"))
        org.junit.Assert.assertTrue(formats.contains("BGRA"))
    }

    @Test
    fun verifyLibyuvArgbWithSkiaBgraProducesCorrectRgb() {
        // Libyuv FourCC.ARGB outputs memory byte order: [B, G, R, A]
        // For a pure red pixel (R=255, G=0, B=0, A=255):
        val redPixelBytes = byteArrayOf(
            0,            // byte 0 = B (Blue)
            0,            // byte 1 = G (Green)
            255.toByte(), // byte 2 = R (Red)
            255.toByte(), // byte 3 = A (Alpha)
        )
        // Paired with Skia ColorType.BGRA_8888 (where byte 0 is B, byte 1 is G, byte 2 is R, byte 3 is A):
        val info = ImageInfo(1, 1, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
        val skiaImg = Image.makeRaster(info, redPixelBytes, 4)
        val composeBitmap = skiaImg.toComposeImageBitmap()
        val pixel = composeBitmap.toPixelMap()[0, 0]
        assertEquals("Red channel must be 1.0", 1.0f, pixel.red, 0.01f)
        assertEquals("Green channel must be 0.0", 0.0f, pixel.green, 0.01f)
        assertEquals("Blue channel must be 0.0", 0.0f, pixel.blue, 0.01f)
        assertEquals("Alpha channel must be 1.0", 1.0f, pixel.alpha, 0.01f)
    }
}
