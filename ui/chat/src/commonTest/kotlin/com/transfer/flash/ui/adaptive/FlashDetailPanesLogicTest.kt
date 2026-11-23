package com.transfer.flash.ui.adaptive

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.transfer.flash.core.common.model.FlashDeviceKind
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import com.transfer.flash.ui.theme.FlashColors
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashDetailPanesLogicTest {

    @Test
    fun `transfer and peer security copy avoid technical jargon`() {
        val texts = listOf(
            FlashDetailPaneCopy.transferSecurityTitle(isVerified = true, isFailed = false),
            FlashDetailPaneCopy.transferSecurityTitle(isVerified = false, isFailed = true),
            FlashDetailPaneCopy.transferSecurityTitle(isVerified = false, isFailed = false, isVerifying = true),
            FlashDetailPaneCopy.transferSecurityTitle(isVerified = false, isFailed = false),
            FlashDetailPaneCopy.transferSecurityBody(isVerified = true, isFailed = false, errorMessage = null),
            FlashDetailPaneCopy.transferSecurityBody(isVerified = false, isFailed = true, errorMessage = null),
            FlashDetailPaneCopy.transferSecurityBody(isVerified = false, isFailed = false, errorMessage = null, isVerifying = true),
            FlashDetailPaneCopy.transferSecurityBody(isVerified = false, isFailed = false, errorMessage = null),
            FlashDetailPaneCopy.peerTrustTitle(isTrusted = true),
            FlashDetailPaneCopy.peerTrustTitle(isTrusted = false),
            FlashDetailPaneCopy.peerTrustBody(isTrusted = true, isColleague = false, orgName = null),
            FlashDetailPaneCopy.peerTrustBody(isTrusted = false, isColleague = true, orgName = "Acme"),
            FlashDetailPaneCopy.peerTrustBody(isTrusted = false, isColleague = false, orgName = null),
        )
        val forbidden = listOf("sha-256", "cryptographic", "reciprocal", "manifest", "protocol", "v2", "tls")
        texts.forEach { line ->
            assertTrue(line.isNotBlank())
            val lower = line.lowercase()
            forbidden.forEach { jargon ->
                assertFalse(jargon in lower, "Unexpected jargon '$jargon' in '$line'")
            }
        }
    }

    @Test
    fun `transport and device type labels use friendly human wording`() {
        assertEquals("Local network (Wi-Fi)", FlashDetailPaneCopy.peerTransportLabel(FlashNetworkTransport.Lan))
        assertEquals("Wi-Fi Direct", FlashDetailPaneCopy.peerTransportLabel(FlashNetworkTransport.WifiDirect))
        assertEquals("Relay", FlashDetailPaneCopy.peerTransportLabel(FlashNetworkTransport.Relay))
        assertEquals("Radio", FlashDetailPaneCopy.peerTransportLabel(FlashNetworkTransport.Radio))
        assertEquals("Nearby", FlashDetailPaneCopy.peerTransportLabel(FlashNetworkTransport.Unknown))

        assertEquals("Phone", FlashDetailPaneCopy.peerDeviceTypeLabel(FlashDeviceKind.PHONE))
        assertEquals("Computer", FlashDetailPaneCopy.peerDeviceTypeLabel(FlashDeviceKind.DESKTOP))
    }

    @Test
    fun `peer status and connection labels reflect live nearby vs offline state honestly`() {
        assertEquals(
            "Available nearby",
            FlashDetailPaneCopy.peerStatusLabel(isAvailableNearby = true, isOnline = true),
        )
        assertEquals(
            "Online",
            FlashDetailPaneCopy.peerStatusLabel(isAvailableNearby = false, isOnline = true),
        )
        assertEquals(
            "Offline · Not nearby",
            FlashDetailPaneCopy.peerStatusLabel(isAvailableNearby = false, isOnline = false),
        )

        // Offline paired devices must report Not connected rather than claiming Wi-Fi/LAN.
        assertEquals(
            "Not connected",
            FlashDetailPaneCopy.peerConnectionLabel(
                transport = FlashNetworkTransport.Lan,
                isAvailableNearby = false,
                isOnline = false,
            ),
        )
        assertEquals(
            "Connected",
            FlashDetailPaneCopy.peerConnectionLabel(
                transport = FlashNetworkTransport.Unknown,
                isAvailableNearby = false,
                isOnline = true,
            ),
        )
        assertEquals(
            "Wi-Fi Direct",
            FlashDetailPaneCopy.peerConnectionLabel(
                transport = FlashNetworkTransport.WifiDirect,
                isAvailableNearby = true,
                isOnline = true,
            ),
        )
    }

    @Test
    fun `detail buttons meet WCAG AA contrast across light, dark, and dynamic accents`() {
        val themes = listOf(
            FlashColors.light(),
            FlashColors.dark(),
            FlashColors.light().withAccent(Color(0xFF6750A4)),
            FlashColors.dark().withAccent(Color(0xFFD0BCFF)),
        )
        themes.forEach { colors ->
            val (primaryBg, primaryFg) = FlashDetailPaneCopy.buttonColors(colors, isPrimary = true, isDestructive = false)
            assertTrue(
                contrastRatio(primaryFg, primaryBg) >= 4.5f,
                "Primary detail button contrast below AA",
            )
            val (secondaryBg, secondaryFg) = FlashDetailPaneCopy.buttonColors(colors, isPrimary = false, isDestructive = false)
            assertTrue(
                contrastRatio(secondaryFg, secondaryBg) >= 4.5f,
                "Secondary detail button contrast below AA",
            )
            val (destructiveBg, destructiveFg) = FlashDetailPaneCopy.buttonColors(colors, isPrimary = false, isDestructive = true)
            val effectiveDestructiveBg = compositeOver(destructiveBg, colors.backgroundSurface)
            assertTrue(
                contrastRatio(destructiveFg, effectiveDestructiveBg) >= 4.5f,
                "Destructive detail button contrast below AA",
            )
            val (disabledBg, disabledFg) = FlashDetailPaneCopy.buttonColors(
                colors,
                isPrimary = false,
                isDestructive = false,
                enabled = false,
            )
            assertTrue(
                contrastRatio(disabledFg, disabledBg) >= 4.5f,
                "Disabled detail button contrast below AA",
            )
        }
    }

    private fun compositeOver(fg: Color, bg: Color): Color {
        val a = fg.alpha
        return Color(
            red = fg.red * a + bg.red * (1f - a),
            green = fg.green * a + bg.green * (1f - a),
            blue = fg.blue * a + bg.blue * (1f - a),
            alpha = 1f,
        )
    }

    private fun contrastRatio(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }
}
