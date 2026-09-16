package com.transfer.flash.ui.calling

import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
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

    @Test
    fun verifyRotationGeometryCalculations() {
        val rawW = 1280f
        val rawH = 720f
        val rotation = 90
        val isRotated = rotation == 90 || rotation == 270
        val effectiveW = if (isRotated) rawH else rawW
        val effectiveH = if (isRotated) rawW else rawH

        assertEquals(720f, effectiveW, 0.01f)
        assertEquals(1280f, effectiveH, 0.01f)

        val containerW = 1920f
        val containerH = 1080f

        val scaleFit = minOf(containerW / effectiveW, containerH / effectiveH)
        val dstWidthFit = effectiveW * scaleFit
        val dstHeightFit = effectiveH * scaleFit

        assertEquals(1080f, dstHeightFit, 0.01f)
        assertEquals(607.5f, dstWidthFit, 0.01f)

        val scaleBalanced = maxOf(containerW / effectiveW, containerH / effectiveH)
        val dstWidthBalanced = effectiveW * scaleBalanced
        val dstHeightBalanced = effectiveH * scaleBalanced

        assertEquals(1920f, dstWidthBalanced, 0.01f)
        assertEquals(3413.333f, dstHeightBalanced, 0.01f)
    }

    @Test
    fun verifyRotatedCanvasDraw() {
        // Create a 2x1 bitmap: pixel (0,0) is Red, pixel (1,0) is Blue
        val rawW = 2
        val rawH = 1
        // BGRA bytes:
        // pixel 0 (Red): B=0, G=0, R=255, A=255
        // pixel 1 (Blue): B=255, G=0, R=0, A=255
        val bytes = byteArrayOf(
            0, 0, 255.toByte(), 255.toByte(),
            255.toByte(), 0, 0, 255.toByte(),
        )
        val info = ImageInfo(rawW, rawH, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
        val skiaImg = Image.makeRaster(info, bytes, rawW * 4)
        val srcBitmap = skiaImg.toComposeImageBitmap()

        // Target canvas: 1x2 (width=1, height=2)
        val target = androidx.compose.ui.graphics.ImageBitmap(1, 2)
        val canvas = androidx.compose.ui.graphics.Canvas(target)
        val drawScope = androidx.compose.ui.graphics.drawscope.CanvasDrawScope()

        drawScope.draw(
            density = androidx.compose.ui.unit.Density(1f),
            layoutDirection = androidx.compose.ui.unit.LayoutDirection.Ltr,
            canvas = canvas,
            size = androidx.compose.ui.geometry.Size(1f, 2f),
        ) {
            drawIntoCanvas { c ->
                val rotation = 90
                val scale = 1f
                c.save()
                c.translate(size.width / 2f, size.height / 2f)
                c.rotate(rotation.toFloat())
                val dstW = rawW * scale
                val dstH = rawH * scale
                val dstLeft = -dstW / 2f
                val dstTop = -dstH / 2f
                val paint = androidx.compose.ui.graphics.Paint()
                c.drawImageRect(
                    image = srcBitmap,
                    srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                    srcSize = androidx.compose.ui.unit.IntSize(srcBitmap.width, srcBitmap.height),
                    dstOffset = androidx.compose.ui.unit.IntOffset(Math.round(dstLeft), Math.round(dstTop)),
                    dstSize = androidx.compose.ui.unit.IntSize(Math.round(dstW), Math.round(dstH)),
                    paint = paint,
                )
                c.restore()
            }
        }

        val pixelMap = target.toPixelMap()
        val topPixel = pixelMap[0, 0]
        val bottomPixel = pixelMap[0, 1]
        println("Top pixel: R=${topPixel.red}, B=${topPixel.blue}")
        println("Bottom pixel: R=${bottomPixel.red}, B=${bottomPixel.blue}")

        // In clockwise 90 degree rotation:
        // The original image was horizontal: [Red at (0,0), Blue at (1,0)]
        // Rotated 90 deg clockwise:
        // Top is Red, Bottom is Blue!
        assertEquals("Top pixel should be Red", 1.0f, topPixel.red, 0.1f)
        assertEquals("Bottom pixel should be Blue", 1.0f, bottomPixel.blue, 0.1f)
    }

    @Test
    fun verify270DegreeRotationCanvasDraw() {
        // Create a 2x1 bitmap: pixel (0,0) is Red, pixel (1,0) is Blue
        val rawW = 2
        val rawH = 1
        val bytes = byteArrayOf(
            0, 0, 255.toByte(), 255.toByte(),
            255.toByte(), 0, 0, 255.toByte(),
        )
        val info = ImageInfo(rawW, rawH, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
        val skiaImg = Image.makeRaster(info, bytes, rawW * 4)
        val srcBitmap = skiaImg.toComposeImageBitmap()

        // Target canvas: 1x2 (width=1, height=2)
        val target = androidx.compose.ui.graphics.ImageBitmap(1, 2)
        val canvas = androidx.compose.ui.graphics.Canvas(target)
        val drawScope = androidx.compose.ui.graphics.drawscope.CanvasDrawScope()

        drawScope.draw(
            density = androidx.compose.ui.unit.Density(1f),
            layoutDirection = androidx.compose.ui.unit.LayoutDirection.Ltr,
            canvas = canvas,
            size = androidx.compose.ui.geometry.Size(1f, 2f),
        ) {
            drawIntoCanvas { c ->
                val rotation = 270
                val scale = 1f
                c.save()
                c.translate(size.width / 2f, size.height / 2f)
                c.rotate(rotation.toFloat())
                val dstW = rawW * scale
                val dstH = rawH * scale
                val dstLeft = -dstW / 2f
                val dstTop = -dstH / 2f
                val paint = androidx.compose.ui.graphics.Paint()
                c.drawImageRect(
                    image = srcBitmap,
                    srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                    srcSize = androidx.compose.ui.unit.IntSize(srcBitmap.width, srcBitmap.height),
                    dstOffset = androidx.compose.ui.unit.IntOffset(Math.round(dstLeft), Math.round(dstTop)),
                    dstSize = androidx.compose.ui.unit.IntSize(Math.round(dstW), Math.round(dstH)),
                    paint = paint,
                )
                c.restore()
            }
        }

        val pixelMap = target.toPixelMap()
        val topPixel = pixelMap[0, 0]
        val bottomPixel = pixelMap[0, 1]

        // In clockwise 270 degree rotation:
        // [Red, Blue] -> 270 deg -> Top is Blue, Bottom is Red!
        assertEquals("Top pixel should be Blue", 1.0f, topPixel.blue, 0.1f)
        assertEquals("Bottom pixel should be Red", 1.0f, bottomPixel.red, 0.1f)
    }

    @Test
    fun verify180DegreeRotationCanvasDraw() {
        // Create a 2x1 bitmap: pixel (0,0) is Red, pixel (1,0) is Blue
        val rawW = 2
        val rawH = 1
        val bytes = byteArrayOf(
            0, 0, 255.toByte(), 255.toByte(),
            255.toByte(), 0, 0, 255.toByte(),
        )
        val info = ImageInfo(rawW, rawH, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
        val skiaImg = Image.makeRaster(info, bytes, rawW * 4)
        val srcBitmap = skiaImg.toComposeImageBitmap()

        // Target canvas: 2x1 (width=2, height=1)
        val target = androidx.compose.ui.graphics.ImageBitmap(2, 1)
        val canvas = androidx.compose.ui.graphics.Canvas(target)
        val drawScope = androidx.compose.ui.graphics.drawscope.CanvasDrawScope()

        drawScope.draw(
            density = androidx.compose.ui.unit.Density(1f),
            layoutDirection = androidx.compose.ui.unit.LayoutDirection.Ltr,
            canvas = canvas,
            size = androidx.compose.ui.geometry.Size(2f, 1f),
        ) {
            drawIntoCanvas { c ->
                val rotation = 180
                val scale = 1f
                c.save()
                c.translate(size.width / 2f, size.height / 2f)
                c.rotate(rotation.toFloat())
                val dstW = rawW * scale
                val dstH = rawH * scale
                val dstLeft = -dstW / 2f
                val dstTop = -dstH / 2f
                val paint = androidx.compose.ui.graphics.Paint()
                c.drawImageRect(
                    image = srcBitmap,
                    srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                    srcSize = androidx.compose.ui.unit.IntSize(srcBitmap.width, srcBitmap.height),
                    dstOffset = androidx.compose.ui.unit.IntOffset(Math.round(dstLeft), Math.round(dstTop)),
                    dstSize = androidx.compose.ui.unit.IntSize(Math.round(dstW), Math.round(dstH)),
                    paint = paint,
                )
                c.restore()
            }
        }

        val pixelMap = target.toPixelMap()
        val leftPixel = pixelMap[0, 0]
        val rightPixel = pixelMap[1, 0]

        // In 180 degree rotation:
        // [Red, Blue] -> 180 deg -> Left is Blue, Right is Red!
        assertEquals("Left pixel should be Blue", 1.0f, leftPixel.blue, 0.1f)
        assertEquals("Right pixel should be Red", 1.0f, rightPixel.red, 0.1f)
    }
}
