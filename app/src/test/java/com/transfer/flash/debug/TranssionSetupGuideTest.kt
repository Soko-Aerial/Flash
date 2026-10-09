package com.transfer.flash.debug

import com.transfer.flash.debug.OemBatteryOptimizationHelper.OemBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** HIB-03: Transsion detection and the guide's step list. */
class TranssionSetupGuideTest {

    @Test
    fun `Transsion brands and manufacturers are detected in any case`() {
        assertEquals(OemBrand.TRANSSION, OemBatteryOptimizationHelper.brandOf("INFINIX", "Infinix"))
        assertEquals(OemBrand.TRANSSION, OemBatteryOptimizationHelper.brandOf("TECNO MOBILE LIMITED", "TECNO"))
        assertEquals(OemBrand.TRANSSION, OemBatteryOptimizationHelper.brandOf("itel", "itel"))
        assertEquals(OemBrand.TRANSSION, OemBatteryOptimizationHelper.brandOf("TRANSSION HOLDINGS", "tecno"))
        // brand alone is enough (a rebadged build)
        assertEquals(OemBrand.TRANSSION, OemBatteryOptimizationHelper.brandOf("unknown", "Infinix"))
    }

    @Test
    fun `other makers are not Transsion`() {
        assertEquals(OemBrand.SAMSUNG, OemBatteryOptimizationHelper.brandOf("samsung", "samsung"))
        assertEquals(OemBrand.XIAOMI, OemBatteryOptimizationHelper.brandOf("Xiaomi", "Redmi"))
        assertEquals(OemBrand.GENERIC, OemBatteryOptimizationHelper.brandOf("Google", "google"))
        assertFalse(TranssionSetupGuide.appliesTo(OemBrand.GENERIC))
        assertTrue(TranssionSetupGuide.appliesTo(OemBrand.TRANSSION))
    }

    @Test
    fun `guide lists the system exemption plus the four manual overrides`() {
        assertEquals(5, TranssionSetupGuide.steps.size)
        assertEquals(1, TranssionSetupGuide.steps.count { it.detectable })
        assertEquals(4, TranssionSetupGuide.steps.count { !it.detectable })
    }

    @Test
    fun `the detected step drops out once the exemption is held, the manual ones stay`() {
        val before = TranssionSetupGuide.pending(ignoringBatteryOptimizations = false)
        val after = TranssionSetupGuide.pending(ignoringBatteryOptimizations = true)
        assertEquals(5, before.size)
        assertEquals(4, after.size)
        assertTrue(after.none { it.id == TranssionSetupGuide.AOSP_EXEMPTION_STEP_ID })
    }

    @Test
    fun `Phone Master candidates always end with the main screen and keep their order`() {
        val auto = OemBatteryOptimizationHelper.transsionClassNames(TranssionSetupGuide.Target.AUTO_START)
        assertEquals("com.transsion.phonemaster.autostart.AutoStartActivity", auto.first())
        assertEquals("com.transsion.phonemaster.MainActivity", auto.last())
        val power = OemBatteryOptimizationHelper.transsionClassNames(TranssionSetupGuide.Target.POWER_SETTINGS)
        assertEquals("com.transsion.phonemaster.power.activity.PowerSettingsActivity", power.first())
    }
}
