package com.transfer.flash.core.common.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The PC/Phone classification, pinned at the boundaries that matter.
 *
 * The feature it backs is small, but the failure mode is not: the badge is a claim about a device
 * the user cannot see, so the classifier must be **total** (hostile input never throws) and it must
 * fail to [FlashDeviceKind.UNKNOWN] rather than to a guess.
 */
class FlashDeviceKindTest {

    @Test
    fun eachDeclaredCapabilityClassifies() {
        assertEquals(
            FlashDeviceKind.DESKTOP,
            FlashDeviceKind.fromCapabilities(setOf(FlashDeviceKind.CAP_DESKTOP)),
        )
        assertEquals(
            FlashDeviceKind.PHONE,
            FlashDeviceKind.fromCapabilities(setOf(FlashDeviceKind.CAP_MOBILE)),
        )
    }

    @Test
    fun anEmptyOrUnknownSetIsUnknown_neverAGuess() {
        // A peer on an older build advertises nothing. It must render NO badge. This is the case
        // that would otherwise be filled in by pattern-matching the model string, which is exactly
        // the guesswork the enum exists to avoid.
        assertEquals(FlashDeviceKind.UNKNOWN, FlashDeviceKind.fromCapabilities(emptySet()))
        assertEquals(FlashDeviceKind.UNKNOWN, FlashDeviceKind.fromCapabilities(setOf("kiosk", "tablet")))
    }

    @Test
    fun flagsAreNormalisedBeforeMatching() {
        // The wire is peer-controlled: padding and case are not a peer's fault, and a classifier
        // fussy about them would report UNKNOWN for a device that advertised correctly.
        assertEquals(
            FlashDeviceKind.DESKTOP,
            FlashDeviceKind.fromCapabilities(setOf("  Desktop  ")),
        )
        assertEquals(
            FlashDeviceKind.PHONE,
            FlashDeviceKind.fromCapabilities(setOf("MOBILE")),
        )
    }

    @Test
    fun bothFlagsPresent_resolvesToDesktopDeterministically() {
        // Not a real device, but a total function must pick ONE answer, and picking the same one
        // every time is what keeps two hosts rendering the same peer identically. Desktop wins: a
        // host that can run a desktop session is the more useful thing to say.
        assertEquals(
            FlashDeviceKind.DESKTOP,
            FlashDeviceKind.fromCapabilities(
                setOf(FlashDeviceKind.CAP_MOBILE, FlashDeviceKind.CAP_DESKTOP),
            ),
        )
        assertEquals(
            FlashDeviceKind.DESKTOP,
            FlashDeviceKind.fromCapabilities(
                setOf(FlashDeviceKind.CAP_DESKTOP, FlashDeviceKind.CAP_MOBILE),
            ),
        )
    }

    @Test
    fun anUnrelatedCapabilitySetDoesNotImplyAPhone() {
        // The caps field carries other flags in this project (and will carry more). Only the two
        // known kind flags may classify — otherwise every future capability silently labels a peer.
        assertEquals(
            FlashDeviceKind.UNKNOWN,
            FlashDeviceKind.fromCapabilities(setOf("ptt", "group-call", "transfer")),
        )
    }
}
