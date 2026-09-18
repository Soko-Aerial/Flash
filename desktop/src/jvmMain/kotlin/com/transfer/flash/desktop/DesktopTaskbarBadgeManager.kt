package com.transfer.flash.desktop

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Window
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages Windows and desktop OS taskbar badging and user attention.
 *
 * Windows taskbar buttons map directly to the window's HICON (dispatched via WM_SETICON by AWT).
 * When an unread message arrives while the Flash window is in the background or minimized,
 * this manager dynamically generates high-contrast multi-resolution badged icons (16, 24, 32, 48, 64px)
 * and updates [window.setIconImages], which immediately renders the badge on the Windows taskbar icon.
 *
 * When the window returns to the foreground and gains user focus, the badge clears automatically.
 */
public object DesktopTaskbarBadgeManager {

    private val baseIconsCache: List<BufferedImage> by lazy {
        listOf(16, 24, 32, 48, 64, 96, 128, 256).map { size -> renderIcon(size, badgeCount = 0) }
    }

    private val badgedIconsCache = ConcurrentHashMap<Int, List<BufferedImage>>()

    public fun getBaseIcons(): List<BufferedImage> = baseIconsCache

    public fun getBadgedIcons(count: Int): List<BufferedImage> {
        if (count <= 0) return getBaseIcons()
        val clampedCount = if (count > 9) 10 else count
        return badgedIconsCache.computeIfAbsent(clampedCount) { c ->
            listOf(16, 24, 32, 48, 64, 96, 128, 256).map { size -> renderIcon(size, badgeCount = c) }
        }
    }

    /**
     * Updates the taskbar icon with the given unread badge count.
     * If [count] <= 0, restores the clean Flash icon.
     */
    public fun updateBadge(window: Window?, count: Int) {
        if (window == null) return
        try {
            if (count > 0) {
                window.iconImages = getBadgedIcons(count)
                requestAttention(window)
            } else {
                window.iconImages = getBaseIcons()
            }
        } catch (_: Throwable) {
            // Non-fatal fallback for headless environments or unsupported window states
        }
    }

    /** Clears the taskbar badge, restoring the pristine Flash icon. */
    public fun clearBadge(window: Window?) {
        updateBadge(window, 0)
    }

    /**
     * Requests user attention from the desktop window manager (causes the taskbar button
     * to flash on Windows 10/11 when the window is in the background).
     */
    public fun requestAttention(window: Window?) {
        try {
            if (java.awt.Taskbar.isTaskbarSupported()) {
                val tb = java.awt.Taskbar.getTaskbar()
                if (window != null && tb.isSupported(java.awt.Taskbar.Feature.USER_ATTENTION_WINDOW)) {
                    tb.requestWindowUserAttention(window)
                } else if (tb.isSupported(java.awt.Taskbar.Feature.USER_ATTENTION)) {
                    tb.requestUserAttention(true, false)
                }
            }
        } catch (_: Throwable) {
            // Non-fatal if platform does not support user attention
        }
    }

    /**
     * Renders a multi-resolution application icon with Flash Pulse Teal bolt on dark slate tile,
     * optionally badged with a vibrant coral-red notification counter on the upper-right corner.
     */
    public fun renderIcon(size: Int, badgeCount: Int): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            // Dark slate background squircle (#0F172A)
            val arc = size * 0.35
            g.color = Color(0x0F, 0x17, 0x2A)
            g.fill(RoundRectangle2D.Double(0.0, 0.0, size.toDouble(), size.toDouble(), arc, arc))

            // Subtle border outline (#334155)
            g.color = Color(0x33, 0x41, 0x55)
            val tileStroke = maxOf(1f, size * 0.04f)
            g.stroke = BasicStroke(tileStroke)
            g.draw(RoundRectangle2D.Double(0.5, 0.5, size.toDouble() - 1.0, size.toDouble() - 1.0, arc, arc))

            // Flash Lightning Bolt (scaled from 24x24 vector: M13,2 L3,14 L12,14 L11,22 L21,10 L12,10 L13,2 Z)
            val boltPad = size * 0.15
            val boltSize = size - (boltPad * 2.0)
            val scale = boltSize / 24.0

            val path = Path2D.Double().apply {
                moveTo(boltPad + 13.0 * scale, boltPad + 2.0 * scale)
                lineTo(boltPad + 3.0 * scale, boltPad + 14.0 * scale)
                lineTo(boltPad + 12.0 * scale, boltPad + 14.0 * scale)
                lineTo(boltPad + 11.0 * scale, boltPad + 22.0 * scale)
                lineTo(boltPad + 21.0 * scale, boltPad + 10.0 * scale)
                lineTo(boltPad + 12.0 * scale, boltPad + 10.0 * scale)
                closePath()
            }

            // Bolt fill: Flash Pulse Teal (#2DD4BF)
            g.color = Color(0x2D, 0xD4, 0xBF)
            g.fill(path)

            // Bolt stroke: Crisp White (#FFFFFF)
            g.color = Color.WHITE
            g.stroke = BasicStroke(maxOf(1f, size * 0.05f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(path)

            // Upper-right notification badge when badgeCount > 0
            if (badgeCount > 0) {
                renderBadge(g, size, badgeCount)
            }
        } finally {
            g.dispose()
        }
        return image
    }

    private fun renderBadge(g: Graphics2D, size: Int, badgeCount: Int) {
        val badgeDiameter = if (size <= 16) {
            size * 0.40
        } else {
            size * 0.46
        }
        val badgeX = size.toDouble() - badgeDiameter - (size * 0.02)
        val badgeY = (size * 0.02)

        // Dark separation ring around badge
        g.color = Color(0x0F, 0x17, 0x2A)
        val ringStroke = maxOf(1f, size * 0.06f)
        g.stroke = BasicStroke(ringStroke)
        g.drawOval(
            (badgeX - ringStroke / 2).toInt(),
            (badgeY - ringStroke / 2).toInt(),
            (badgeDiameter + ringStroke).toInt(),
            (badgeDiameter + ringStroke).toInt(),
        )

        // Vibrant Coral Red fill (#EF4444)
        g.color = Color(0xEF, 0x44, 0x44)
        g.fillOval(badgeX.toInt(), badgeY.toInt(), badgeDiameter.toInt(), badgeDiameter.toInt())

        // Render badge text for sizes >= 24px (for 16px, solid pip provides maximum clarity)
        if (size >= 24) {
            val text = if (badgeCount > 9) "9+" else badgeCount.toString()
            val fontSize = (badgeDiameter * 0.62).toFloat()
            val font = Font(Font.SANS_SERIF, Font.BOLD, fontSize.toInt())
            g.font = font
            g.color = Color.WHITE

            val fm = g.fontMetrics
            val textWidth = fm.stringWidth(text)
            val textHeight = fm.ascent - fm.descent
            val textX = (badgeX + (badgeDiameter - textWidth) / 2.0).toInt()
            val textY = (badgeY + (badgeDiameter + textHeight) / 2.0).toInt() - (size * 0.01).toInt()

            g.drawString(text, textX, textY)
        }
    }
}
