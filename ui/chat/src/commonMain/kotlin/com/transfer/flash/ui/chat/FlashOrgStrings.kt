package com.transfer.flash.ui.chat

/**
 * Canonical user-facing strings for organisation trust (Plan B Section 9).
 * Exact English sentences; shared by Android and Desktop hosts.
 */
public object FlashOrgStrings {
    public const val ORG_TRUST_VALID: String = "Colleague trust valid until %s"
    public const val ORG_TRUST_EXPIRING: String = "Connect to the company network within %d days to keep chatting with colleagues offline."
    public const val ORG_TRUST_EXPIRED: String = "Reconnect to your company network to chat with colleagues who are not paired with you."
    public const val ORG_TRUST_NEVER: String = "Connect to the company network once to load your colleagues."
    public const val ORG_TRUST_UNSUPPORTED: String = "This company server does not support offline colleague chat yet."
    public const val ORG_TRUST_CLOCK: String = "Your device clock looks wrong. Colleague trust may fail until it is fixed."
    public const val ORG_TRUST_IDENTITY: String = "Your device identity changed. Rejoin your organisation to chat with colleagues again."
    public const val ORG_CHIP_COLLEAGUE: String = "Colleague · verified by %s"
    public const val ORG_CHIP_A11Y: String = "Colleague verified by the company server"
    public const val ORG_HONESTY_LINE: String = "Colleagues are verified by your company's server. Pair with someone when you need to be sure it is really them."
    public const val ORG_START_NOT_MEMBER: String = "This person is not in your company directory."
    public const val ORG_START_SELF: String = "That is you."
    public const val ORG_START_REMOVED: String = "This person is no longer part of your organisation."
    public const val ORG_START_EXPIRED: String = "Reconnect to your company network to start a chat with this colleague."
    public const val ORG_READONLY: String = "You are no longer part of this organisation. Your old messages stay here."
    public const val ORG_WAITING_SYNC: String = "Waiting for your next company sync."
    public const val ORG_GROUP_UNSEALED: String = "Group messages sent through your company's server can be read by it. Messages sent directly between phones are encrypted."

    /** Returns the formatted chip label for an organisation peer. */
    public fun chipText(orgName: String?): String {
        val name = orgName?.trim()?.ifBlank { null } ?: "company server"
        return "Colleague · verified by $name"
    }
}
