package com.transfer.flash.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OPT-O01-03: Baseline profile generator for Flash startup and core tab/list CUJs.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generateBaselineProfile() = baselineProfileRule.collect(
        packageName = "com.transfer.flash",
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 5_000)

        for (tabLabel in listOf("Transfers", "Nearby", "Settings", "Chats")) {
            device.findObject(By.text(tabLabel))?.click()
            device.waitForIdle()
            device.findObject(By.scrollable(true))?.let { scrollable ->
                scrollable.setGestureMargin(device.displayWidth / 5)
                scrollable.fling(Direction.DOWN)
                device.waitForIdle()
                scrollable.fling(Direction.UP)
                device.waitForIdle()
            }
        }
    }
}
