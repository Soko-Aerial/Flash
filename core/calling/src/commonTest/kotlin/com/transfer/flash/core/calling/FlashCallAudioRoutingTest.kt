package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.model.FlashCallAudioRoute
import com.transfer.flash.core.calling.model.FlashCallAudioRoute.BLUETOOTH
import com.transfer.flash.core.calling.model.FlashCallAudioRoute.EARPIECE
import com.transfer.flash.core.calling.model.FlashCallAudioRoute.SPEAKER
import com.transfer.flash.core.calling.model.FlashCallAudioRoute.WIRED
import com.transfer.flash.core.calling.model.FlashCallAudioRouting
import com.transfer.flash.core.calling.model.FlashCallAudioRoutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-067: which output a call uses, given what is plugged in and what the user picked. */
class FlashCallAudioRoutingTest {

    private val phone = setOf(EARPIECE, SPEAKER)

    @Test
    fun `without a pick the earpiece is used and a headset takes over`() {
        assertEquals(EARPIECE, FlashCallAudioRouting.resolve(phone, null))
        assertEquals(WIRED, FlashCallAudioRouting.resolve(phone + WIRED, null))
        assertEquals(BLUETOOTH, FlashCallAudioRouting.resolve(phone + WIRED + BLUETOOTH, null))
    }

    @Test
    fun `a pick wins while the device exists`() {
        assertEquals(SPEAKER, FlashCallAudioRouting.resolve(phone + BLUETOOTH, SPEAKER))
        assertEquals(EARPIECE, FlashCallAudioRouting.resolve(phone + BLUETOOTH, EARPIECE))
    }

    @Test
    fun `a pick that disappeared falls back instead of leaving the call silent`() {
        assertFalse(FlashCallAudioRouting.stillValid(phone, BLUETOOTH))
        assertEquals(EARPIECE, FlashCallAudioRouting.resolve(phone, BLUETOOTH))
        assertTrue(FlashCallAudioRouting.stillValid(phone, null))
        assertTrue(FlashCallAudioRouting.stillValid(phone, SPEAKER))
    }

    @Test
    fun `a tablet with no earpiece uses its speaker`() {
        assertEquals(SPEAKER, FlashCallAudioRouting.resolve(setOf(SPEAKER), null))
    }

    @Test
    fun `nothing available leaves the decision to the platform`() {
        assertNull(FlashCallAudioRouting.resolve(emptySet(), null))
        assertNull(FlashCallAudioRouting.resolve(emptySet(), SPEAKER))
    }

    @Test
    fun `the picker lists what exists in a fixed order`() {
        assertEquals(
            listOf(EARPIECE, SPEAKER, BLUETOOTH, WIRED),
            FlashCallAudioRouting.pickerOrder(setOf(WIRED, BLUETOOTH, SPEAKER, EARPIECE)),
        )
        assertEquals(listOf(SPEAKER), FlashCallAudioRouting.pickerOrder(setOf(SPEAKER)))
    }

    @Test
    fun `the button opens a list only when a headset is in play`() {
        assertFalse(FlashCallAudioRoutes(listOf(EARPIECE, SPEAKER), EARPIECE).needsPicker)
        assertFalse(FlashCallAudioRoutes().needsPicker)
        assertTrue(FlashCallAudioRoutes(listOf(EARPIECE, SPEAKER, BLUETOOTH), BLUETOOTH).needsPicker)
        assertTrue(FlashCallAudioRoutes(listOf(EARPIECE, SPEAKER, WIRED), WIRED).needsPicker)
    }

    @Test
    fun `every route has a place in both orders`() {
        val all = FlashCallAudioRoute.entries.toSet()
        assertEquals(all.size, FlashCallAudioRouting.pickerOrder(all).size)
        assertEquals(BLUETOOTH, FlashCallAudioRouting.resolve(all, null))
    }

    @Test
    fun `the screen turns off against the ear only for a live voice call on the earpiece`() {
        assertTrue(FlashCallAudioRouting.screenOffAgainstEar(video = false, live = true, active = EARPIECE, speakerOn = false))
        // No route reported (a host with no routing): the speaker flag decides.
        assertTrue(FlashCallAudioRouting.screenOffAgainstEar(false, true, null, speakerOn = false))
        assertFalse(FlashCallAudioRouting.screenOffAgainstEar(false, true, null, speakerOn = true))
        assertFalse(FlashCallAudioRouting.screenOffAgainstEar(false, true, SPEAKER, false))
        assertFalse(FlashCallAudioRouting.screenOffAgainstEar(false, true, BLUETOOTH, false))
        assertFalse(FlashCallAudioRouting.screenOffAgainstEar(false, true, WIRED, false))
        assertFalse(FlashCallAudioRouting.screenOffAgainstEar(video = true, live = true, active = EARPIECE, speakerOn = false))
        assertFalse(FlashCallAudioRouting.screenOffAgainstEar(false, live = false, active = EARPIECE, speakerOn = false))
    }
}
