package com.transfer.flash.core.radio.service

import com.transfer.flash.core.radio.connect.RadioConnectState
import com.transfer.flash.core.radio.connect.RememberedRadio
import com.transfer.flash.core.radio.driver.TncLinkState
import com.transfer.flash.core.radio.gateway.RadioGatewayState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RadioServiceTrackerTest {
    private val radio = RememberedRadio("38:D2:00:01:35:B5", "VR-N76")

    private fun connected(link: TncLinkState = TncLinkState.Connected("COM16", 0L)) = RadioGatewayState(
        isEnabled = true,
        connectState = RadioConnectState.Connected(radio),
        linkState = link,
        rememberedRadio = radio,
    )

    private fun waiting(attempt: Int) = TncLinkState.Waiting(attempt, 1000L, "link lost")

    private val off = RadioGatewayState(isEnabled = false)

    @Test
    fun radwireU13_neverStopsAServiceThatWasNeverStarted() {
        val t = RadioServiceTracker()
        repeat(5) { assertNull(t.next(off), "off state with no service must produce no call") }
        assertNull(t.next(RadioGatewayState(isEnabled = true, connectState = RadioConnectState.ChecklistShown)))
    }

    @Test
    fun radwireU14_startsOnceThenStaysQuietWhileNothingChanges() {
        val t = RadioServiceTracker()
        assertIs<RadioServiceAction.Start>(t.next(connected()))
        repeat(5) { assertNull(t.next(connected()), "identical state must not call the OS again") }
    }

    @Test
    fun radwireU15_refreshesTheNotificationOnlyWhenItsTextChanges() {
        val t = RadioServiceTracker()
        t.next(connected())
        val refreshed = t.next(connected(waiting(2)))
        assertIs<RadioServiceAction.Start>(refreshed)
        assertEquals(RadioServicePolicy.notificationContent(connected(waiting(2))), refreshed.notification)
        assertNull(t.next(connected(waiting(2))))
    }

    @Test
    fun radwireU16_stopsExactlyOnceAfterRunning_andCanRestart() {
        val t = RadioServiceTracker()
        t.next(connected())
        assertEquals(RadioServiceAction.Stop, t.next(off))
        assertNull(t.next(off), "a second off state must not stop again")
        assertIs<RadioServiceAction.Start>(t.next(connected()))
    }

    @Test
    fun radwireU17_resetForgetsTheShownNotification() {
        val t = RadioServiceTracker()
        t.next(connected())
        t.reset()
        assertNull(t.next(off), "after reset there is nothing to stop")
        assertIs<RadioServiceAction.Start>(t.next(connected()), "after reset the same state starts again")
    }
}
