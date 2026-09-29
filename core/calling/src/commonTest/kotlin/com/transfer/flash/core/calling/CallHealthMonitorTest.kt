package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.model.FlashCallHealthWarning
import com.transfer.flash.core.common.perf.FlashNetworkBand
import com.transfer.flash.core.common.perf.FlashPerformanceMode
import com.transfer.flash.core.common.perf.FlashThermalStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** G6 health rules (plan §4.5). */
class CallHealthMonitorTest {

    @Test
    fun `a cool, idle device has nothing to say`() {
        val m = CallHealthMonitor()
        val v = m.update(0, FlashThermalStatus.LIGHT, cpuPercent = 10.0, softwareDecode = false)
        assertEquals(CallHealthMonitor.Verdict(), v)
    }

    @Test
    fun `moderate heat warns and marks the device struggling, without capping`() {
        val v = CallHealthMonitor().update(0, FlashThermalStatus.MODERATE, null, false)
        assertEquals(FlashCallHealthWarning.WARM, v.warning)
        assertTrue(v.struggling)
        assertNull(v.receiveCap)
        assertTrue(v.acceptNew)
    }

    @Test
    fun `severe heat caps to one video, turns new watchers down, and says so`() {
        for (status in listOf(FlashThermalStatus.SEVERE, FlashThermalStatus.CRITICAL, FlashThermalStatus.EMERGENCY)) {
            val v = CallHealthMonitor().update(0, status, null, false)
            assertEquals(FlashCallHealthWarning.HOT, v.warning)
            assertEquals(1, v.receiveCap)
            assertFalse(v.acceptNew)
            assertTrue(v.showingFewer)
        }
        // It lifts when the device cools.
        val m = CallHealthMonitor()
        m.update(0, FlashThermalStatus.SEVERE, null, false)
        assertEquals(CallHealthMonitor.Verdict(), m.update(1_000, FlashThermalStatus.NONE, null, false))
    }

    @Test
    fun `cpu must stay high for thirty seconds, and a dip resets it`() {
        val m = CallHealthMonitor(cpuThresholdPercent = 40.0, cpuSustainMs = 30_000)
        assertNull(m.update(0, FlashThermalStatus.NONE, 55.0, false).warning)
        assertNull(m.update(29_000, FlashThermalStatus.NONE, 55.0, false).warning)
        assertNull(m.update(29_500, FlashThermalStatus.NONE, 20.0, false).warning)
        assertNull(m.update(40_000, FlashThermalStatus.NONE, 55.0, false).warning)
        val v = m.update(70_000, FlashThermalStatus.NONE, 55.0, false)
        assertEquals(FlashCallHealthWarning.CPU, v.warning)
        assertTrue(v.struggling)
        // An unknown sample changes nothing.
        assertEquals(FlashCallHealthWarning.CPU, m.update(71_000, FlashThermalStatus.NONE, null, false).warning)
    }

    @Test
    fun `software decoding warns once and stays for the call`() {
        val m = CallHealthMonitor()
        assertEquals(FlashCallHealthWarning.SOFTWARE_DECODE, m.update(0, FlashThermalStatus.NONE, null, true).warning)
        assertEquals(FlashCallHealthWarning.SOFTWARE_DECODE, m.update(1_000, FlashThermalStatus.NONE, null, false).warning)
        assertFalse(m.verdict().struggling)
    }

    @Test
    fun `show fewer caps receiving and quiets the soft warnings, but not the hot one`() {
        val m = CallHealthMonitor()
        m.update(0, FlashThermalStatus.MODERATE, null, false)
        m.showFewer = true
        val v = m.verdict()
        assertNull(v.warning)
        assertEquals(1, v.receiveCap)
        assertTrue(v.showingFewer)
        assertTrue(v.struggling)
        assertEquals(FlashCallHealthWarning.HOT, m.update(1_000, FlashThermalStatus.SEVERE, null, false).warning)
        m.showFewer = false
        assertNull(m.update(2_000, FlashThermalStatus.NONE, null, false).receiveCap)
    }

    @Test
    fun `software decoders are told apart from hardware ones`() {
        listOf("libvpx", "FFmpeg", "libaom", "dav1d", "c2.android.vp8.decoder", "OMX.google.vp8.decoder", "ExternalDecoder (libvpx)")
            .forEach { assertTrue(CallHealthMonitor.isSoftwareDecoder(it), it) }
        listOf("c2.qti.vp8.decoder", "OMX.MTK.VIDEO.DECODER.VPX", "c2.exynos.vp8.decoder", "", null)
            .forEach { assertFalse(CallHealthMonitor.isSoftwareDecoder(it), "$it") }
    }

    @Test
    fun `the verdict feeds the video limits`() {
        val hot = CallHealthMonitor().update(0, FlashThermalStatus.SEVERE, null, false)
        val limits = GroupVideoLimits.of(
            FlashPerformanceMode.HIGH, FlashNetworkBand.WIFI_5GHZ,
            struggling = hot.struggling, receiveCap = hot.receiveCap, acceptNew = hot.acceptNew,
        )
        assertEquals(1, limits.receive)
        assertFalse(limits.acceptNew)
        val warmLow = CallHealthMonitor().update(0, FlashThermalStatus.MODERATE, null, false)
        assertEquals(360, GroupVideoLimits.of(FlashPerformanceMode.LOW, null, struggling = warmLow.struggling).quality)
    }
}
