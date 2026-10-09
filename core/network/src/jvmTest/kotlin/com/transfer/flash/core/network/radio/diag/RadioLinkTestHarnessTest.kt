package com.transfer.flash.core.network.radio.diag

import com.transfer.flash.core.network.radio.ByteLink
import com.transfer.flash.core.network.radio.LinkException
import com.transfer.flash.core.network.radio.PortInfo
import com.transfer.flash.core.network.radio.SerialPortCatalog
import com.transfer.flash.core.network.radio.SerialSettings
import com.transfer.flash.core.network.radio.TncLinkState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RadioLinkTestHarnessTest {

    private class NoPorts : SerialPortCatalog {
        override fun listPorts(): List<PortInfo> = emptyList()
        override fun open(systemName: String, settings: SerialSettings): ByteLink =
            throw LinkException("cannot open $systemName (no such port)")
    }

    @Test
    fun noPortsIsAnAnswerNotACrash() = runBlocking {
        val h = RadioLinkTestHarness(NoPorts())
        assertTrue(h.listPorts().isEmpty())
    }

    /** Records the thread each blocking catalog call runs on. */
    private class ThreadSpy : SerialPortCatalog {
        val threads = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun listPorts(): List<PortInfo> {
            threads += Thread.currentThread().name
            return emptyList()
        }

        override fun open(systemName: String, settings: SerialSettings): ByteLink {
            threads += Thread.currentThread().name
            throw LinkException("cannot open $systemName")
        }
    }

    @Test
    fun blockingPortCallsNeverRunOnTheCallersThread() {
        // R5: the desktop window calls the harness from its UI thread; enumerating and opening a port are blocking native calls.
        val spy = ThreadSpy()
        val h = RadioLinkTestHarness(spy)
        val ui = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "fake-ui-thread") }
        try {
            runBlocking(ui.asCoroutineDispatcher()) {
                h.listPorts()
                h.connect("COM7", 115200, TestRole.STATION_A)
            }
        } finally {
            ui.shutdown()
        }
        assertEquals(2, spy.threads.size)
        assertTrue(spy.threads.none { it == "fake-ui-thread" }, "blocking calls ran on the UI thread: ${spy.threads}")
    }

    @Test
    fun openingAMissingPortReportsFailureAndKeepsTheLog() = runBlocking {
        val h = RadioLinkTestHarness(NoPorts())
        h.connect("COM99", 115200, TestRole.STATION_A)
        val st = h.state.value
        assertIs<HarnessState.Failed>(st)
        assertTrue(st.message.contains("COM99"))
        assertTrue(h.renderLog().contains("harness.open_failed"))
        assertEquals(null, h.tester)
    }

    @Test
    fun theSimulatedRadioRunsTheWholeSequenceWithoutHardware() = runBlocking {
        val h = RadioLinkTestHarness(NoPorts())
        h.connectSimulated(TestRole.STATION_A)
        withTimeout(10_000) { h.driver!!.state.first { it is TncLinkState.Connected } }
        val t = h.tester!!
        t.sendKissTransparencyTest()
        t.sendAx25TextTest()
        t.sendFlashFramePayload()
        val burst = t.runBurst(count = 4, bodyBytes = 100, ackWaitMs = 5_000)
        assertEquals(4, burst.acked, burst.summary())
        val file = File.createTempFile("flash-radio-test", ".txt")
        try {
            h.exportLog(file)
            val text = file.readText()
            assertTrue(text.startsWith("# Flash radio link test"), "header first")
            assertTrue(text.contains(" TX "), "TX hex dumps are present")
            assertTrue(text.contains("RX"), "RX dumps are present")
        } finally {
            file.delete()
            h.disconnect()
        }
    }
}
