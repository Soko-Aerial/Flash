package com.transfer.flash.ui.calling

import androidx.compose.ui.graphics.toComposeImageBitmap
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
    fun bgraMakeRasterConversionWorks() {
        val width = 1280
        val height = 720
        val bytes = ByteArray(width * height * 4)
        // Fill some test pixel data
        bytes[0] = 255.toByte() // B
        bytes[1] = 0 // G
        bytes[2] = 0 // R
        bytes[3] = 255.toByte() // A
        val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
        val skiaImg = Image.makeRaster(info, bytes, width * 4)
        assertNotNull(skiaImg)
        val composeBitmap = skiaImg.toComposeImageBitmap()
        assertEquals(width, composeBitmap.width)
        assertEquals(height, composeBitmap.height)
    }
}
