package com.transfer.flash.quicksettings

import android.service.quicksettings.Tile
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the simplified Quick Settings tile toggle and state mapping. */
class FlashTileServiceTest {

    @Test
    fun `tapping tile toggles cleanly between Discoverable and Off`() {
        assertEquals(FlashTileTapAction.START_DISCOVERABLE, FlashTileMath.tapAction(isRunning = false))
        assertEquals(FlashTileTapAction.STOP, FlashTileMath.tapAction(isRunning = true))
    }

    @Test
    fun `stopped tile renders inactive state with clear tap-to-start subtitle`() {
        val spec = FlashTileMath.tileSpec(running = false, mode = FlashDiscoveryMode.STANDARD)
        assertFalse(spec.isActive)
        assertEquals(Tile.STATE_INACTIVE, spec.tileState())
        assertEquals("Flash Share", spec.label)
        assertEquals("Off (Tap to start)", spec.subtitle)
    }

    @Test
    fun `all running modes including GHOST show active tile state without technical jargon`() {
        val specs = FlashDiscoveryMode.entries.associateWith { mode ->
            FlashTileMath.tileSpec(running = true, mode = mode)
        }
        specs.forEach { (mode, spec) ->
            assertTrue("$mode must render Tile.STATE_ACTIVE when running", spec.isActive)
            assertEquals(Tile.STATE_ACTIVE, spec.tileState())
            assertFalse("$mode subtitle must not contain Ghost jargon", spec.subtitle.contains("Ghost", ignoreCase = true))
            assertFalse("$mode subtitle must not contain Eco jargon", spec.subtitle.contains("Eco", ignoreCase = true))
            assertFalse("$mode subtitle must not contain Boost jargon", spec.subtitle.contains("Boost", ignoreCase = true))
            assertFalse("$mode subtitle must not contain Kiosk jargon", spec.subtitle.contains("Kiosk", ignoreCase = true))
        }
        assertEquals("Discoverable", specs.getValue(FlashDiscoveryMode.STANDARD).subtitle)
        assertEquals("Hidden", specs.getValue(FlashDiscoveryMode.GHOST).subtitle)
    }
}
