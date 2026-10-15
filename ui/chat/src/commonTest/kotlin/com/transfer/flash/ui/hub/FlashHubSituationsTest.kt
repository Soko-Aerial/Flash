@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.hub

import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.messaging.model.HubPersonRole
import com.transfer.flash.core.messaging.model.HubPersonUi
import com.transfer.flash.core.messaging.model.HubReachability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HUBT-U70..: the situations the Hub tab must get right. Each test names the situation in people's words
 * (hub online and the colleague unpaired, hub offline and a verified colleague on the LAN, ...) and pins what the
 * person sheet offers and what Message / Call do.
 */
class FlashHubSituationsTest {

    private fun person(
        paired: Boolean = false,
        org: Boolean = true,
        reach: HubReachability = HubReachability.UNKNOWN,
        self: Boolean = false,
        name: String = "Amara Okafor",
    ) = HubPersonUi("dev-0001", name, "d1", "Finance", HubPersonRole.PERSON, null, reach, paired, org, self)

    private fun fail(code: String, text: String) = FlashResult.Failure(FlashError.Domain(code, text))

    // --- Hub online, colleague unpaired ----------------------------------------------------------------

    @Test fun hubOnlineUnpairedColleagueOffersMessageButNotCallOrPairByDefault() {
        val p = person(paired = false, reach = HubReachability.HUB)
        val sheet = FlashHubMath.personSheet(p, callAllowed = FlashHubMath.callAllowed(p, true), pairAvailable = false)
        assertTrue(sheet.messageEnabled)
        assertFalse(sheet.showCall, "a hub-only colleague is never called from here")
        assertFalse(sheet.showPair, "pairing is not wired by the host")
        assertEquals(FlashHubCopy.PERSON_UNPAIRED_FOOTER, sheet.footer)
    }

    // --- Hub offline, verified colleague on the LAN -----------------------------------------------------

    @Test fun hubOfflineVerifiedColleagueOnTheLanCanBeCalledAndMessaged() {
        val p = person(paired = false, org = true, reach = HubReachability.LAN)
        assertTrue(FlashHubMath.callAllowed(p, true))
        val sheet = FlashHubMath.personSheet(p, callAllowed = true, pairAvailable = false)
        assertTrue(sheet.showCall)
        assertTrue(sheet.messageEnabled)
        assertEquals(FlashHubCopy.PERSON_HONESTY, sheet.honesty)
    }

    @Test fun anUnverifiedUnpairedPersonOnTheLanIsNeverRung() {
        val p = person(paired = false, org = false, reach = HubReachability.LAN)
        assertFalse(FlashHubMath.callAllowed(p, true))
    }

    @Test fun theHostGateAlwaysWins() {
        val p = person(paired = true, reach = HubReachability.LAN)
        assertFalse(FlashHubMath.callAllowed(p, hostAllows = false))
    }

    // --- Revoked / identity changed ---------------------------------------------------------------------

    @Test fun everyColleagueStartSentenceOfTheRepositoryReachesThePersonVerbatim() {
        listOf(
            "This person is no longer part of your organisation.",
            "This person is not in your company directory.",
            "Reconnect to your company network to start a chat with this colleague.",
            "Connect to the company network once to load your colleagues.",
            "That is you.",
        ).forEach { sentence ->
            val outcome = FlashHubMath.messageOutcome(person(), FlashResult.Failure(FlashError.Unknown(sentence)))
            assertEquals(FlashHubMessageOutcome.Show(sentence), outcome, sentence)
        }
    }

    @Test fun aStructuredDomainFailureBecomesTheGenericSentenceNotItsCode() {
        val outcome = FlashHubMath.messageOutcome(person(), fail("ERR_X", "ERR_X internal"))
        assertEquals(FlashHubMessageOutcome.Show(FlashHubCopy.MESSAGE_FAILED), outcome)
    }

    @Test fun technicalFailureTextNeverReachesThePerson() {
        val outcome = FlashHubMath.messageOutcome(person(), fail("x", "java.lang.IllegalStateException: null at Foo"))
        assertEquals(FlashHubMessageOutcome.Show(FlashHubCopy.MESSAGE_FAILED), outcome)
    }

    @Test fun anUnknownErrorWithPlainTextIsShownVerbatim() {
        val outcome = FlashHubMath.messageOutcome(person(), FlashResult.Failure(FlashError.Unknown("You are no longer part of this organisation.")))
        assertEquals(FlashHubMessageOutcome.Show("You are no longer part of this organisation."), outcome)
    }

    @Test fun aPairedColleagueOpensTheExistingConversationWithoutAskingTheRepository() {
        val p = person(paired = true)
        assertEquals(FlashHubMessageOutcome.Open("dev-0001"), FlashHubMath.messageOutcome(p, null))
    }

    @Test fun yourOwnRowNeverMessagesOrCallsYou() {
        val p = person(self = true, reach = HubReachability.LAN, paired = true)
        val sheet = FlashHubMath.personSheet(p, callAllowed = FlashHubMath.callAllowed(p, true), pairAvailable = true)
        assertFalse(sheet.messageEnabled)
        assertFalse(sheet.showCall)
        assertFalse(sheet.showPair)
        assertNull(sheet.footer)
        assertEquals(FlashHubMessageOutcome.Show("That is you."), FlashHubMath.messageOutcome(p, null))
    }

    // --- A name that is hostile or empty -----------------------------------------------------------------

    @Test fun anEmptyDisplayNameStillYieldsInitialsAndASpokenRow() {
        val p = person(name = "")
        assertTrue(FlashHubMath.initials(p.displayName).isNotEmpty())
        assertTrue(FlashHubMath.personAccessibility(p, includeDepartment = true).isNotBlank())
    }

    @Test fun aBidiOverrideInANameCannotReorderTheSpokenOrInitials() {
        val p = person(name = "‮evil Name")
        assertFalse(FlashHubMath.initials(p.displayName).contains('‮'))
    }
}
