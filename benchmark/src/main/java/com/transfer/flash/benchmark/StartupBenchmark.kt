package com.transfer.flash.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OPT-O01-03 / OPT-O28-01: Macrobenchmark suite measuring Android cold startup and tab/scroll CUJs.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startupCold() = benchmarkRule.measureRepeated(
        packageName = "com.transfer.flash",
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.DEFAULT,
        startupMode = StartupMode.COLD,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test
    fun tabNavigationAndScroll() = benchmarkRule.measureRepeated(
        packageName = "com.transfer.flash",
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.DEFAULT,
        startupMode = StartupMode.WARM,
        iterations = 3,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 5_000)
        },
    ) {
        for (tabLabel in listOf("Transfers", "Nearby", "Settings", "Chats")) {
            device.findObject(By.text(tabLabel))?.click()
            device.waitForIdle()
            device.findObject(By.scrollable(true))?.let { scrollable ->
                scrollable.setGestureMargin(device.displayWidth / 5)
                scrollable.fling(Direction.DOWN)
                device.waitForIdle()
            }
        }
    }
}
