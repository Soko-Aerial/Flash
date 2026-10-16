package com.transfer.flash.ui.adaptive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for UI-034 pure adaptive math: Material window-size breakpoints,
 * pane weights, and two-pane allowance (UI-033 navigation stays layout-agnostic).
 */
class FlashAdaptiveLogicTest {

    // --- Breakpoint boundaries ---

    @Test
    fun `width just below medium lower bound is compact`() {
        assertEquals(FlashWindowSizeClass.Compact, FlashAdaptiveMath.windowSizeForWidth(599.9f))
    }

    @Test
    fun `medium lower bound inclusive`() {
        assertEquals(FlashWindowSizeClass.Medium, FlashAdaptiveMath.windowSizeForWidth(600f))
    }

    @Test
    fun `width just below expanded lower bound is medium`() {
        assertEquals(FlashWindowSizeClass.Medium, FlashAdaptiveMath.windowSizeForWidth(839.9f))
    }

    @Test
    fun `expanded lower bound inclusive`() {
        assertEquals(FlashWindowSizeClass.Expanded, FlashAdaptiveMath.windowSizeForWidth(840f))
    }

    @Test
    fun `typical phone widths are compact`() {
        assertEquals(FlashWindowSizeClass.Compact, FlashAdaptiveMath.windowSizeForWidth(360f))
        assertEquals(FlashWindowSizeClass.Compact, FlashAdaptiveMath.windowSizeForWidth(411f))
    }

    @Test
    fun `typical tablet and unfolded foldable widths are expanded`() {
        assertEquals(FlashWindowSizeClass.Medium, FlashAdaptiveMath.windowSizeForWidth(700f))
        assertEquals(FlashWindowSizeClass.Expanded, FlashAdaptiveMath.windowSizeForWidth(1280f))
    }

    @Test
    fun `degenerate zero or negative width falls back to compact`() {
        assertEquals(FlashWindowSizeClass.Compact, FlashAdaptiveMath.windowSizeForWidth(0f))
        assertEquals(FlashWindowSizeClass.Compact, FlashAdaptiveMath.windowSizeForWidth(-1f))
    }

    // --- Two-pane allowance ---

    @Test
    fun `two pane allowed only on expanded width`() {
        assertTrue(FlashAdaptiveMath.isTwoPaneAllowed(FlashWindowSizeClass.Expanded))
        assertFalse(FlashAdaptiveMath.isTwoPaneAllowed(FlashWindowSizeClass.Medium))
        assertFalse(FlashAdaptiveMath.isTwoPaneAllowed(FlashWindowSizeClass.Compact))
    }

    // --- Pane weights ---

    @Test
    fun `list pane takes full width outside expanded`() {
        assertEquals(1f, FlashAdaptiveMath.listPaneWeight(FlashWindowSizeClass.Compact), 0f)
        assertEquals(1f, FlashAdaptiveMath.listPaneWeight(FlashWindowSizeClass.Medium), 0f)
    }

    @Test
    fun `list pane uses expanded token weight in two pane mode`() {
        assertEquals(
            FlashAdaptiveMath.ListPaneExpandedWeight,
            FlashAdaptiveMath.listPaneWeight(FlashWindowSizeClass.Expanded),
            0f
        )
        assertEquals(0.38f, FlashAdaptiveMath.listPaneWeight(FlashWindowSizeClass.Expanded), 0f)
    }

    @Test
    fun `detail pane takes full width when shown as single pane`() {
        assertEquals(1f, FlashAdaptiveMath.detailPaneWeight(FlashWindowSizeClass.Compact), 0f)
        assertEquals(1f, FlashAdaptiveMath.detailPaneWeight(FlashWindowSizeClass.Medium), 0f)
    }

    @Test
    fun `detail pane uses expanded token weight in two pane mode`() {
        assertEquals(
            FlashAdaptiveMath.DetailPaneExpandedWeight,
            FlashAdaptiveMath.detailPaneWeight(FlashWindowSizeClass.Expanded),
            0f
        )
        assertEquals(0.62f, FlashAdaptiveMath.detailPaneWeight(FlashWindowSizeClass.Expanded), 0f)
    }

    @Test
    fun `expanded pane weights sum to one`() {
        val total = FlashAdaptiveMath.listPaneWeight(FlashWindowSizeClass.Expanded) +
            FlashAdaptiveMath.detailPaneWeight(FlashWindowSizeClass.Expanded)
        assertEquals(1f, total, 1e-6f)
    }

    // --- Pane width math (AD-2) ---

    @Test
    fun `list pane takes total width below expanded`() {
        assertEquals(500f, FlashAdaptiveMath.listPaneWidthDp(500f))
        assertEquals(700f, FlashAdaptiveMath.listPaneWidthDp(700f))
        assertEquals(839.9f, FlashAdaptiveMath.listPaneWidthDp(839.9f))
    }

    @Test
    fun `list pane width clamped to minimum at 840dp expanded lower bound`() {
        // 840 * 0.38 = 319.2 -> clamped to ListPaneMinWidthDp = 320f
        assertEquals(320f, FlashAdaptiveMath.listPaneWidthDp(840f))
    }

    @Test
    fun `list pane width proportional at 1100dp`() {
        // 1100 * 0.38 = 418f (within 320..480)
        assertEquals(418f, FlashAdaptiveMath.listPaneWidthDp(1100f), 0.01f)
    }

    @Test
    fun `list pane width clamped to maximum on wide screens`() {
        // 1440 * 0.38 = 547.2 -> clamped to ListPaneMaxWidthDp = 480f
        assertEquals(480f, FlashAdaptiveMath.listPaneWidthDp(1440f))
        // 1920 * 0.38 = 729.6 -> clamped to 480f
        assertEquals(480f, FlashAdaptiveMath.listPaneWidthDp(1920f))
        // 2560 * 0.38 = 972.8 -> clamped to 480f
        assertEquals(480f, FlashAdaptiveMath.listPaneWidthDp(2560f))
    }

    // --- AD-2: the draggable splitter (owner request 2026-10-16) ---

    @Test
    fun `a dragged list width stays inside the user limits and leaves the conversation room`() {
        val available = 1400f - 68f
        assertEquals(280f, FlashAdaptiveMath.clampListPaneWidthDp(100f, available), "never narrower than the minimum")
        assertEquals(500f, FlashAdaptiveMath.clampListPaneWidthDp(500f, available))
        assertEquals(720f, FlashAdaptiveMath.clampListPaneWidthDp(5000f, available), "never wider than the maximum")
        listOf(840f, 1000f, 1100f, 1440f, 1920f, 2560f).forEach { total ->
            val avail = total - 68f
            listOf(0f, 280f, 400f, 720f, 10_000f).forEach { requested ->
                val list = FlashAdaptiveMath.clampListPaneWidthDp(requested, avail)
                assertTrue(list >= 280f && list <= 720f, "list $list out of limits at $total")
                assertTrue(avail - list >= FlashAdaptiveMath.DetailPaneUserMinWidthDp || list == 280f, "conversation starved at $total with list $list")
            }
        }
    }

    @Test
    fun `a corrupt saved width cannot break the layout`() {
        assertEquals(280f, FlashAdaptiveMath.clampListPaneWidthDp(Float.NaN, 1200f))
        assertEquals(280f, FlashAdaptiveMath.clampListPaneWidthDp(Float.NEGATIVE_INFINITY, 1200f))
        assertEquals(280f, FlashAdaptiveMath.clampListPaneWidthDp(-50f, 1200f))
    }

    @Test
    fun `no saved width keeps the automatic width, a saved one wins in two pane mode only`() {
        assertEquals(FlashAdaptiveMath.listPaneWidthDp(1440f), FlashAdaptiveMath.effectiveListPaneWidthDp(1440f, 1372f, null))
        assertEquals(560f, FlashAdaptiveMath.effectiveListPaneWidthDp(1440f, 1372f, 560f))
        assertEquals(500f, FlashAdaptiveMath.effectiveListPaneWidthDp(500f, 432f, 560f), "below expanded the list takes the whole width")
    }

    @Test
    fun `detail pane always has at least its minimum width when two pane is allowed`() {
        listOf(840f, 1000f, 1100f, 1440f, 1920f, 2560f).forEach { totalWidth ->
            val listWidth = FlashAdaptiveMath.listPaneWidthDp(totalWidth)
            val remainingForDetail = totalWidth - listWidth
            assertTrue(
                remainingForDetail >= FlashAdaptiveMath.DetailPaneMinWidthDp,
                "Detail pane starved below minimum for totalWidth=$totalWidth: remaining=$remainingForDetail",
            )
        }
    }
}
