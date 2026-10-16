package com.transfer.flash.ui.radio

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
        controller.confirmAuthorisation()
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
}
