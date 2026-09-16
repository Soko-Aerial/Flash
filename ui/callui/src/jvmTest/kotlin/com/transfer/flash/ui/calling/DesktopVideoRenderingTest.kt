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
    fun verifyRgbaColorChannelMapping() {
        val bytes = byteArrayOf(
            255.toByte(), // byte 0 = 255
            0,            // byte 1 = 0
            0,            // byte 2 = 0
            255.toByte(), // byte 3 = 255
        )
        // With RGBA_8888: byte 0 is R, byte 1 is G, byte 2 is B, byte 3 is A
        val infoRgba = ImageInfo(1, 1, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        val skiaRgba = Image.makeRaster(infoRgba, bytes, 4)
        val composeRgba = skiaRgba.toComposeImageBitmap()
        val pixelRgba = composeRgba.toPixelMap()[0, 0]
        println("RGBA_8888 pixel: red=${pixelRgba.red}, green=${pixelRgba.green}, blue=${pixelRgba.blue}")
        assertEquals("Red channel must be 1.0", 1.0f, pixelRgba.red, 0.01f)
        assertEquals("Blue channel must be 0.0", 0.0f, pixelRgba.blue, 0.01f)

        // With BGRA_8888: byte 0 is B, byte 1 is G, byte 2 is R, byte 3 is A
        val infoBgra = ImageInfo(1, 1, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
        val skiaBgra = Image.makeRaster(infoBgra, bytes, 4)
        val composeBgra = skiaBgra.toComposeImageBitmap()
        val pixelBgra = composeBgra.toPixelMap()[0, 0]
        println("BGRA_8888 pixel: red=${pixelBgra.red}, green=${pixelBgra.green}, blue=${pixelBgra.blue}")
        assertEquals("Red channel in BGRA must be 0.0", 0.0f, pixelBgra.red, 0.01f)
        assertEquals("Blue channel in BGRA must be 1.0", 1.0f, pixelBgra.blue, 0.01f)
    }
}
