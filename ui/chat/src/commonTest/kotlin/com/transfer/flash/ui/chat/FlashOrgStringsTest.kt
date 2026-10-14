package com.transfer.flash.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Plan B Section 9: Canonical user-facing strings verification.
 * Verifies that all constants match Section 9 verbatim and that formatting helpers behave correctly.
 */
class FlashOrgStringsTest {

    @Test
    fun `ORGT_U35_plan_b_section_9_exact_strings_verbatim_match`() {
        // Table 9 exact strings from HUB-OFFLINE-ORG-TRUST-PLAN.md
        assertEquals(
            "Colleague trust valid until %s",
            FlashOrgStrings.ORG_TRUST_VALID,
            "org_trust_valid must match Plan B §9 verbatim",
        )
        assertEquals(
            "Connect to the company network within %d days to keep chatting with colleagues offline.",
            FlashOrgStrings.ORG_TRUST_EXPIRING,
            "org_trust_expiring must match Plan B §9 verbatim",
        )
        assertEquals(
            "Reconnect to your company network to chat with colleagues who are not paired with you.",
            FlashOrgStrings.ORG_TRUST_EXPIRED,
            "org_trust_expired must match Plan B §9 verbatim",
        )
        assertEquals(
            "Connect to the company network once to load your colleagues.",
            FlashOrgStrings.ORG_TRUST_NEVER,
            "org_trust_never must match Plan B §9 verbatim",
        )
        assertEquals(
            "This company server does not support offline colleague chat yet.",
            FlashOrgStrings.ORG_TRUST_UNSUPPORTED,
            "org_trust_unsupported must match Plan B §9 verbatim",
        )
        assertEquals(
            "Your device clock looks wrong. Colleague trust may fail until it is fixed.",
            FlashOrgStrings.ORG_TRUST_CLOCK,
            "org_trust_clock must match Plan B §9 verbatim",
        )
        assertEquals(
            "Your device identity changed. Rejoin your organisation to chat with colleagues again.",
            FlashOrgStrings.ORG_TRUST_IDENTITY,
            "org_trust_identity must match Plan B §9 verbatim",
        )
        assertEquals(
            "Colleague · verified by %s",
            FlashOrgStrings.ORG_CHIP_COLLEAGUE,
            "org_chip_colleague must match Plan B §9 verbatim",
        )
        assertEquals(
            "Colleague verified by the company server",
            FlashOrgStrings.ORG_CHIP_A11Y,
            "org_chip_a11y must match Plan B §9 verbatim",
        )
        assertEquals(
            "Colleagues are verified by your company's server. Pair with someone when you need to be sure it is really them.",
            FlashOrgStrings.ORG_HONESTY_LINE,
            "org_honesty_line must match Plan B §9 verbatim",
        )
        assertEquals(
            "This person is not in your company directory.",
            FlashOrgStrings.ORG_START_NOT_MEMBER,
            "org_start_not_member must match Plan B §9 verbatim",
        )
        assertEquals(
            "That is you.",
            FlashOrgStrings.ORG_START_SELF,
            "org_start_self must match Plan B §9 verbatim",
        )
        assertEquals(
            "This person is no longer part of your organisation.",
            FlashOrgStrings.ORG_START_REMOVED,
            "org_start_removed must match Plan B §9 verbatim",
        )
        assertEquals(
            "Reconnect to your company network to start a chat with this colleague.",
            FlashOrgStrings.ORG_START_EXPIRED,
            "org_start_expired must match Plan B §9 verbatim",
        )
        assertEquals(
            "You are no longer part of this organisation. Your old messages stay here.",
            FlashOrgStrings.ORG_READONLY,
            "org_readonly must match Plan B §9 verbatim",
        )
        assertEquals(
            "Waiting for your next company sync.",
            FlashOrgStrings.ORG_WAITING_SYNC,
            "org_waiting_sync must match Plan B §9 verbatim",
        )
        assertEquals(
            "Group messages sent through your company's server can be read by it. Messages sent directly between phones are encrypted.",
            FlashOrgStrings.ORG_GROUP_UNSEALED,
            "org_group_unsealed must match Plan B §9 verbatim",
        )
    }

    @Test
    fun `ORGT_U35_chipText_formatting_with_orgName_and_fallback`() {
        // Explicit organization name
        assertEquals(
            "Colleague · verified by Acme Corp",
            FlashOrgStrings.chipText("Acme Corp"),
        )
        // Whitespace trimming
        assertEquals(
            "Colleague · verified by Initech",
            FlashOrgStrings.chipText("  Initech  "),
        )
        // Null fallback to default "company server"
        assertEquals(
            "Colleague · verified by company server",
            FlashOrgStrings.chipText(null),
        )
        // Empty string fallback
        assertEquals(
            "Colleague · verified by company server",
            FlashOrgStrings.chipText(""),
        )
        // Blank string fallback
        assertEquals(
            "Colleague · verified by company server",
            FlashOrgStrings.chipText("   \t\n  "),
        )
    }
}
