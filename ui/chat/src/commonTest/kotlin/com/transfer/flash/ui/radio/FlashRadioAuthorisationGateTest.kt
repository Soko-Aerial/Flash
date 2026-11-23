package com.transfer.flash.ui.radio

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.theme.FlashColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RADUI-U03: Security & Authorisation Gate tests for Profile M encrypted RF transmissions (Plan R §8, ADR-178).
 */
class FlashRadioAuthorisationGateTest {

    @Test
    fun testProfileMNotSelectableWithoutAuthorisation() {
        assertFalse(
            FlashRadioMath.isProfileMSelectable(isAuthorised = false),
            "Profile M must not be selectable without confirmed regulatory authorisation"
        )
        assertTrue(
            FlashRadioMath.isProfileMSelectable(isAuthorised = true),
            "Profile M must be selectable once user has confirmed regulatory authorisation"
        )
    }

    @Test
    fun testControllerEnforcesAuthorisationGateOnProfileSelection() {
        val controller = FakeRadioGatewayController(
            initialState = RadioGatewayUiState(
                enabled = true,
                selectedProfile = RadioSecurityProfile.PROFILE_A,
                isAuthorisedForEncryption = false,
                authorisationConfirmedAtEpochMs = 0L,
            )
        )

        // Attempting to select Profile M directly without authorization should be rejected
        controller.selectProfile(RadioSecurityProfile.PROFILE_M)
        assertEquals(
            RadioSecurityProfile.PROFILE_A,
            controller.state.value.selectedProfile,
            "Controller must refuse Profile M selection when unauthorized"
        )

        // Confirm authorization
        controller.confirmAuthorisation("W1AW")
        assertTrue(controller.state.value.isAuthorisedForEncryption)
        assertTrue(controller.state.value.authorisationConfirmedAtEpochMs > 0L)
        assertEquals(RadioSecurityProfile.PROFILE_M, controller.state.value.selectedProfile)

        // Can switch back to Profile A freely
        controller.selectProfile(RadioSecurityProfile.PROFILE_A)
        assertEquals(RadioSecurityProfile.PROFILE_A, controller.state.value.selectedProfile)

        // And can switch back to Profile M because it is authorized
        controller.selectProfile(RadioSecurityProfile.PROFILE_M)
        assertEquals(RadioSecurityProfile.PROFILE_M, controller.state.value.selectedProfile)
    }

    @Test
    fun testProfileMNotMarkedSelectedWhenAuthorisationIsMissing() {
        assertFalse(
            isProfileMSelected(
                selectedProfile = RadioSecurityProfile.PROFILE_M,
                isAuthorisedForEncryption = false,
            ),
        )
        assertTrue(
            isProfileMSelected(
                selectedProfile = RadioSecurityProfile.PROFILE_M,
                isAuthorisedForEncryption = true,
            ),
        )
        assertFalse(
            isProfileMSelected(
                selectedProfile = RadioSecurityProfile.PROFILE_A,
                isAuthorisedForEncryption = true,
            ),
        )
    }

    @Test
    fun testRadioPttColorsMeetWcagAaAcrossLightAndDark() {
        for (colors in listOf(FlashColors.light(), FlashColors.dark())) {
            val ready = radioPttColors(colors, enabled = true, talking = false)
            assertEquals(colors.accentFill, ready.background)
            assertEquals(colors.onAccentFill, ready.foreground)
            assertTrue(contrastRatio(ready.foreground, ready.background) >= 4.5f)

            val talking = radioPttColors(colors, enabled = true, talking = true)
            assertEquals(colors.accentSecondary, talking.background)
            assertTrue(
                contrastRatio(talking.foreground, talking.background) >= 4.5f,
                "Active talking PTT contrast must be >= 4.5:1",
            )
        }
    }

    @Test
    fun testRadioBottomPaddingRespectsBottomInsetWithoutDoublePaddingDock() {
        assertEquals(96.dp, radioGatewayBottomContentPadding(bottomInset = 72.dp, dockVisible = false))
        assertEquals(24.dp, radioGatewayBottomContentPadding(bottomInset = 72.dp, dockVisible = true))
    }

    private fun contrastRatio(a: Color, b: Color): Float {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    private fun relativeLuminance(color: Color): Float =
        0.2126f * linearize(color.red) +
            0.7152f * linearize(color.green) +
            0.0722f * linearize(color.blue)

    private fun linearize(channel: Float): Float =
        if (channel <= 0.03928f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)
}
