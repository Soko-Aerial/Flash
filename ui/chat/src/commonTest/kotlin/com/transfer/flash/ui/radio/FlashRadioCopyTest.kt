package com.transfer.flash.ui.radio

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RADUI-U02: Unit tests for [FlashRadioCopy] ensuring all required strings and formatters are non-blank.
 */
class FlashRadioCopyTest {

    @Test
    fun testCoreStringsNonBlank() {
        assertFalse(FlashRadioCopy.SCREEN_TITLE.isBlank())
        assertFalse(FlashRadioCopy.SETTINGS_ROW_TITLE.isBlank())
        assertFalse(FlashRadioCopy.SETTINGS_ROW_SUBTITLE.isBlank())
        assertFalse(FlashRadioCopy.MASTER_SWITCH_TITLE.isBlank())
        assertFalse(FlashRadioCopy.MASTER_SWITCH_SUBTITLE.isBlank())
        assertFalse(FlashRadioCopy.CHECKLIST_TITLE.isBlank())
        assertFalse(FlashRadioCopy.CHECKLIST_STEP_1.isBlank())
        assertFalse(FlashRadioCopy.CHECKLIST_STEP_2.isBlank())
        assertFalse(FlashRadioCopy.CHECKLIST_STEP_3.isBlank())
        assertFalse(FlashRadioCopy.FIND_SECTION_TITLE.isBlank())
        assertFalse(FlashRadioCopy.ACTION_SCAN_RADIOS.isBlank())
        assertFalse(FlashRadioCopy.ACTION_OPEN_BT_SETTINGS.isBlank())
        assertFalse(FlashRadioCopy.SECURITY_SECTION_TITLE.isBlank())
        assertFalse(FlashRadioCopy.PROFILE_M_TITLE.isBlank())
        assertFalse(FlashRadioCopy.PROFILE_A_TITLE.isBlank())
        assertFalse(FlashRadioCopy.AUTHORISATION_DIALOG_TITLE.isBlank())
        assertFalse(FlashRadioCopy.AUTHORISATION_DIALOG_BODY.isBlank())
        assertFalse(FlashRadioCopy.AUTHORISATION_STATEMENT.isBlank())
        assertFalse(FlashRadioCopy.MODES_SECTION_TITLE.isBlank())
        assertFalse(FlashRadioCopy.TRANSMIT_WARNING_TITLE.isBlank())
        assertFalse(FlashRadioCopy.TRANSMIT_WARNING_BODY.isBlank())
        assertFalse(FlashRadioCopy.ACTION_TRANSMIT_NOW.isBlank())
    }

    @Test
    fun testFormatters() {
        assertTrue(FlashRadioCopy.candidateQuestion("VR-N76").contains("VR-N76"))
        assertTrue(FlashRadioCopy.actionStopScan(5).contains("5"))
        assertTrue(FlashRadioCopy.addressHint("38:D2:00:01:35:B5").contains("38:D2:00:01:35:B5"))
        assertTrue(FlashRadioCopy.sentenceConfirmCandidate("UV-Pro").contains("UV-Pro"))
        assertTrue(FlashRadioCopy.sentenceConnected("VR-N76").contains("VR-N76"))
        assertTrue(FlashRadioCopy.a11yPairDevice("VR-N76").contains("VR-N76"))
    }
}
