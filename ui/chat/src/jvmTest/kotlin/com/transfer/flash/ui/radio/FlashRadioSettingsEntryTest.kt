package com.transfer.flash.ui.radio

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RADUI-U07: the Settings entry never exposes a fake radio in a real build.
 */
class FlashRadioSettingsEntryTest {

    @Test
    fun entryIsHiddenWithoutAControllerOrOpener() {
        assertFalse(FlashRadioMath.isGatewayEntryAvailable(hasController = false, hasOpenHandler = false))
        assertTrue(FlashRadioMath.isGatewayEntryAvailable(hasController = true, hasOpenHandler = false))
        assertTrue(FlashRadioMath.isGatewayEntryAvailable(hasController = false, hasOpenHandler = true))
    }

    @Test
    fun settingsScreenSourceNeverReferencesTheFakeController() {
        val rel = "src/commonMain/kotlin/com/transfer/flash/ui/settings/FlashSettingsScreen.kt"
        val file = listOf(File(rel), File("ui/chat/$rel")).firstOrNull { it.isFile }
        assertTrue(file != null, "FlashSettingsScreen.kt must be found from the test working directory")
        assertFalse(
            file.readText().contains("FakeRadioGatewayController"),
            "FlashSettingsScreen must not fall back to FakeRadioGatewayController: fakes belong to demo entry points",
        )
        assertTrue(
            file.readText().contains("if (radioGatewayAvailable && FlashSettingsMath.rowVisible("),
            "the radio row must be gated by radioGatewayAvailable (hidden without a real controller)",
        )
    }
}
