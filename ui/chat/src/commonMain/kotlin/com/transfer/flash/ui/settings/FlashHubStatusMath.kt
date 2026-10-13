@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.ui.settings

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.network.enterprise.EnrollFailureReason
import com.transfer.flash.core.network.enterprise.EnterpriseDirectoryGroup
import com.transfer.flash.core.network.enterprise.EnterpriseJoinResult
import com.transfer.flash.core.network.enterprise.EnterpriseJoinStatus
import com.transfer.flash.core.network.enterprise.HubLinkState
import com.transfer.flash.core.network.enterprise.HubOfflineReason
import com.transfer.flash.core.network.enterprise.HubSealingKeyState
import com.transfer.flash.core.network.enterprise.HubStatus
import com.transfer.flash.core.network.enterprise.UnenrolResult

/** Colour family of the status dot in Settings. The UI maps each to a Flash colour token. */
public enum class FlashHubDot { GOOD, PROGRESS, WARNING, BAD, NEUTRAL }

/** What the primary button of a [FlashHubBanner] does. */
public enum class FlashHubBannerAction { RETRY_NOW, RE_ENROL, DISMISS, NONE }

/** A banner above the organisation card: a plain sentence and at most one primary action. */
public data class FlashHubBanner(
    public val kind: HubLinkState,
    public val title: String,
    public val body: String,
    public val primary: FlashHubBannerAction,
    public val primaryLabel: String,
    /** True when the banner has a second, dismiss-style action (a revoked notice can be closed). */
    public val dismissible: Boolean = false,
)

/**
 * What this device knows about private (sealed) messages through the hub (ADR-126), for the connection details.
 * [keyState] null means the host has no sealing key store. The counts are for this run of the app.
 */
public data class FlashHubSealingUi(
    public val keyState: HubSealingKeyState? = null,
    /** Sealed messages that did not open (changed on the way, or not from who they claimed). Never shown as chat content. */
    public val unverified: Int = 0,
    /** Readable messages refused because "Messages through the hub" is off. */
    public val readableOff: Int = 0,
)

/** A label/value pair in the connection detail sheet. */
public data class FlashHubDetailLine(public val label: String, public val value: String)

/**
 * Pure presentation rules for the organisation hub card, banners and sheets (CLI-07, CLI-08, CLI-13).
 *
 * Every sentence is written for people (AGENTS.md section 22): no status codes, no protocol names. Anything technical
 * (the short root fingerprint) is shown in the connection detail sheet only, reached by long-press.
 */
public object FlashHubStatusMath {

    /** What the user must be told before turning on readable chat content through the hub (ADR-121). */
    public const val CONTENT_VIA_HUB_DISCLOSURE: String =
        "Your organisation's hub will be able to read these messages while they wait for a colleague who is offline. " +
            "Messages sent directly between phones stay private to the two phones. Messages to a colleague whose app supports " +
            "private messages are sealed first, and the hub cannot read those. Group messages are not sealed yet."

    public const val CONTENT_VIA_HUB_SUBTITLE: String =
        "Let the hub carry message text to colleagues who are not reachable directly. The hub can read it."

    public const val GROUP_LIMITATION_TEXT: String =
        "Group messages reach colleagues you are paired with. Colleagues you only know through the organisation " +
            "cannot read this group's messages yet."

    public fun dot(status: HubStatus): FlashHubDot = when (status.state) {
        HubLinkState.ONLINE -> FlashHubDot.GOOD
        HubLinkState.CONNECTING, HubLinkState.AUTHENTICATING -> FlashHubDot.PROGRESS
        HubLinkState.CERT_EXPIRING, HubLinkState.OFFLINE, HubLinkState.DISCONNECTED -> FlashHubDot.WARNING
        HubLinkState.CERT_EXPIRED, HubLinkState.REVOKED, HubLinkState.SERVER_MISMATCH -> FlashHubDot.BAD
        HubLinkState.NOT_ENROLLED -> FlashHubDot.NEUTRAL
    }

    /** The one line next to the dot. */
    public fun statusText(status: HubStatus, nowMs: Long): String = when (status.state) {
        HubLinkState.NOT_ENROLLED -> "Not set up"
        HubLinkState.DISCONNECTED -> "Not connected"
        HubLinkState.CONNECTING -> "Connecting"
        HubLinkState.AUTHENTICATING -> "Signing in"
        HubLinkState.ONLINE -> "Connected"
        HubLinkState.OFFLINE -> offlineText(status, nowMs)
        HubLinkState.CERT_EXPIRING -> "Connected, access needs renewing"
        HubLinkState.CERT_EXPIRED -> "Access expired"
        HubLinkState.REVOKED -> "Removed by your organisation"
        HubLinkState.SERVER_MISMATCH -> "Not your organisation's hub"
    }

    private fun offlineText(status: HubStatus, nowMs: Long): String {
        val base = when (status.offlineReason) {
            HubOfflineReason.NO_NETWORK -> "Offline, no connection"
            HubOfflineReason.HUB_NOT_ANSWERING -> "Offline, the hub is not answering"
            HubOfflineReason.REFUSED -> "Offline, the hub refused this device"
            HubOfflineReason.KICKED -> "Offline, signed in on another device"
            HubOfflineReason.UPDATE_NEEDED -> "Offline, update Flash to reconnect"
            HubOfflineReason.OTHER, null -> "Offline"
        }
        val retry = retryText(status.nextRetryAtMs, nowMs)
        return if (retry == null) base else "$base. $retry"
    }

    /** "Trying again in 20 seconds", or null when nothing is scheduled. */
    public fun retryText(nextRetryAtMs: Long?, nowMs: Long): String? {
        val at = nextRetryAtMs ?: return null
        val left = ((at - nowMs).coerceAtLeast(0L) + 999L) / 1000L
        return when {
            left <= 1L -> "Trying again now"
            left < 90L -> "Trying again in $left seconds"
            left < 5400L -> "Trying again in ${(left + 30L) / 60L} minutes"
            else -> "Trying again in ${(left + 1800L) / 3600L} hours"
        }
    }

    public fun lastConnectedText(lastConnectedAtMs: Long?, nowMs: Long): String {
        val at = lastConnectedAtMs ?: return "Not yet"
        val s = ((nowMs - at).coerceAtLeast(0L)) / 1000L
        return when {
            s < 45L -> "Just now"
            s < 90L -> "1 minute ago"
            s < 3600L -> "${(s + 30L) / 60L} minutes ago"
            s < 86_400L -> "${(s + 1800L) / 3600L} hours ago"
            else -> "${s / 86_400L} days ago"
        }
    }

    public fun certText(daysLeft: Int?): String = when {
        daysLeft == null -> "Unknown"
        daysLeft <= 0 -> "Ends today"
        daysLeft == 1 -> "1 day left"
        else -> "$daysLeft days left"
    }

    /** The banner for a state that needs the user, or null. Only one banner is shown, the most serious. */
    public fun banner(status: HubStatus, nowMs: Long): FlashHubBanner? = when (status.state) {
        HubLinkState.REVOKED -> FlashHubBanner(
            kind = HubLinkState.REVOKED,
            title = "Your organisation removed this device",
            body = "Organisation messages stopped. Chats with people you paired with directly are not affected. " +
                "Enrol again with a new link from your administrator to come back.",
            primary = FlashHubBannerAction.RE_ENROL,
            primaryLabel = "Re-enrol",
            dismissible = true,
        )
        HubLinkState.SERVER_MISMATCH -> FlashHubBanner(
            kind = HubLinkState.SERVER_MISMATCH,
            title = "This is not your organisation's hub",
            body = "The server at this address did not prove it belongs to your organisation, so Flash did not send " +
                "anything to it. Check with your administrator that the address is right.",
            primary = FlashHubBannerAction.RETRY_NOW,
            primaryLabel = "Retry now",
        )
        HubLinkState.CERT_EXPIRED -> FlashHubBanner(
            kind = HubLinkState.CERT_EXPIRED,
            title = "Your organisation access has expired",
            body = "Flash could not renew it in time. Connect to the internet and try again, or enrol again with a new link.",
            primary = FlashHubBannerAction.RE_ENROL,
            primaryLabel = "Re-enrol",
        )
        HubLinkState.CERT_EXPIRING -> FlashHubBanner(
            kind = HubLinkState.CERT_EXPIRING,
            title = "Your organisation access ends soon",
            body = "${certText(status.certDaysLeft)}. Flash could not renew it yet; it keeps trying while the hub is reachable.",
            primary = FlashHubBannerAction.RETRY_NOW,
            primaryLabel = "Retry now",
        )
        HubLinkState.OFFLINE -> FlashHubBanner(
            kind = HubLinkState.OFFLINE,
            title = "Not connected to your organisation",
            body = listOfNotNull(offlineText(status, nowMs), "Messages to nearby colleagues still work.").joinToString(" "),
            primary = FlashHubBannerAction.RETRY_NOW,
            primaryLabel = "Retry now",
        )
        else -> null
    }

    /** The rows of the connection detail sheet (long-press on the card). */
    public fun detailLines(status: HubStatus, nowMs: Long, sealing: FlashHubSealingUi? = null): List<FlashHubDetailLine> = buildList {
        add(FlashHubDetailLine("Status", statusText(status, nowMs)))
        if (status.hubName.isNotBlank()) add(FlashHubDetailLine("Organisation", status.hubName))
        if (status.hubHost.isNotBlank()) add(FlashHubDetailLine("Hub address", status.hubHost))
        if (status.department.isNotBlank()) add(FlashHubDetailLine("Department", status.department))
        if (status.role.isNotBlank()) add(FlashHubDetailLine("Role", status.role))
        add(FlashHubDetailLine("Last connected", lastConnectedText(status.lastConnectedAtMs, nowMs)))
        add(FlashHubDetailLine("Access", certText(status.certDaysLeft)))
        if (status.rootFingerprintShort.isNotBlank()) add(FlashHubDetailLine("Organisation key", status.rootFingerprintShort))
        if (status.pendingUnenrol) {
            add(FlashHubDetailLine("Leaving", "The hub has not been told yet. Flash will tell it when it is reachable."))
        }
        status.storageError?.let { add(FlashHubDetailLine("Problem", it)) }
        if (sealing != null) addAll(sealingLines(sealing))
    }

    /** The private-messages lines of the connection details (ADR-126). Counts appear only when they are not zero. */
    public fun sealingLines(sealing: FlashHubSealingUi): List<FlashHubDetailLine> = buildList {
        sealing.keyState?.let { add(FlashHubDetailLine("Private messages", sealingStateText(it))) }
        if (sealing.unverified > 0) {
            add(FlashHubDetailLine("Messages not opened", "${sealing.unverified}. They could not be checked, so Flash did not keep them."))
        }
        if (sealing.readableOff > 0) {
            add(FlashHubDetailLine("Messages not accepted", "${sealing.readableOff}. They were readable by the hub and \"Messages through the hub\" is off."))
        }
    }

    public fun sealingStateText(state: HubSealingKeyState): String = when (state) {
        HubSealingKeyState.PUBLISHED -> "Ready. Colleagues can send you messages the hub cannot read."
        HubSealingKeyState.IDLE, HubSealingKeyState.PUBLISHING -> "Getting ready. Flash will keep trying."
        HubSealingKeyState.HUB_TOO_OLD -> "Your organisation's hub needs an update before it can carry private messages."
        HubSealingKeyState.REFUSED -> "The hub did not accept this device's key. Ask your administrator."
        HubSealingKeyState.STORAGE_FAILED -> "Flash could not save the key on this device. Free some space."
    }

    /** One short sentence per enrolment/renewal failure, written for people. */
    public fun enrollFailureText(reason: EnrollFailureReason): String = when (reason) {
        EnrollFailureReason.INVITE_NOT_PINNED ->
            "This link does not say which organisation it belongs to, so Flash will not use it. Ask your administrator for a new link."
        EnrollFailureReason.CLEARTEXT_REFUSED ->
            "This link points to a hub that is not secure, so Flash will not use it. Ask your administrator for a secure link."
        EnrollFailureReason.INVALID_ADDRESS ->
            "The hub address in this link cannot be used. Ask your administrator for a new link."
        EnrollFailureReason.INVITE_EXPIRED -> "This link has expired. Ask your administrator for a new one."
        EnrollFailureReason.INVITE_USED -> "This link was already used. Ask your administrator for a new one."
        EnrollFailureReason.INVITE_REJECTED -> "The hub did not accept this link. Check it is copied completely, or ask for a new one."
        EnrollFailureReason.QUOTA_FULL -> "Your organisation has no free places left. Ask your administrator to make room."
        EnrollFailureReason.RATE_LIMITED -> "Too many attempts. Wait a few minutes and try again."
        EnrollFailureReason.WRONG_ORGANISATION ->
            "The server that answered is not your organisation's. Nothing was saved. Check the link with your administrator."
        EnrollFailureReason.WRONG_DEVICE -> "The hub returned access for a different device. Nothing was saved. Try again."
        EnrollFailureReason.CERTIFICATE_INVALID -> "The access the hub returned could not be verified. Nothing was saved. Try again later."
        EnrollFailureReason.UNREACHABLE -> "Could not reach the hub. Check your internet connection and try again."
        EnrollFailureReason.UNEXPECTED_RESPONSE -> "The hub answered in a way Flash does not understand. Update Flash or ask your administrator."
        EnrollFailureReason.HUB_ERROR -> "The hub had a problem. Try again in a little while."
        EnrollFailureReason.STORAGE_FAILED -> "Flash could not save the organisation settings on this device. Free some space and try again."
    }

    /** What to tell the user after they leave the organisation. */
    public fun unenrolText(result: UnenrolResult): String = when (result) {
        UnenrolResult.DONE -> "You left the organisation."
        UnenrolResult.DONE_HUB_NOT_TOLD -> "You left the organisation on this device. The hub will be told when it is reachable."
        UnenrolResult.DONE_HUB_REFUSED -> "You left the organisation on this device."
        UnenrolResult.STORAGE_FAILED -> "Flash could not remove the organisation settings from this device. Try again."
    }

    public fun relinkOfferText(count: Int): String =
        if (count == 1) "Restore 1 group you had linked to this organisation?" else "Restore $count groups you had linked to this organisation?"

    // --- Department groups ------------------------------------------------------------------------------------

    /** Badges on a directory group row: member count, approval, department. */
    public data class GroupBadges(
        public val memberText: String,
        public val approvalText: String?,
        public val departmentText: String?,
        public val adminPendingText: String?,
    )

    public fun groupBadges(group: EnterpriseDirectoryGroup, myDepartment: String?): GroupBadges {
        val members = when (group.memberCount) {
            1 -> "1 member"
            else -> "${group.memberCount} members"
        }
        val withOnline = group.onlineCount?.takeIf { it > 0 }?.let { "$members, $it online" } ?: members
        return GroupBadges(
            memberText = withOnline,
            approvalText = if (group.requiresApproval) "Needs approval" else null,
            departmentText = group.department.takeIf { it.isNotBlank() && !it.equals(myDepartment, ignoreCase = true) },
            adminPendingText = group.pendingCount?.takeIf { it > 0 }?.let { if (it == 1) "1 request waiting" else "$it requests waiting" },
        )
    }

    /** The button label on a group row, given whether the user is a member or already asked. */
    public fun joinButtonLabel(requiresApproval: Boolean, joined: Boolean, pending: Boolean): String = when {
        joined -> "Joined"
        pending -> "Waiting for approval"
        requiresApproval -> "Ask to join"
        else -> "Join"
    }

    // --- Joining a group (CLI-04) -----------------------------------------------------------------------------

    public enum class JoinFeedbackKind { JOINED, PENDING, REFUSED, FAILED }

    public data class JoinFeedback(public val kind: JoinFeedbackKind, public val text: String)

    /** What to tell the user after tapping Join / Ask to join. */
    public fun joinFeedback(groupName: String, result: FlashResult<EnterpriseJoinResult>): JoinFeedback = when (result) {
        is FlashResult.Success -> when (result.value.status) {
            EnterpriseJoinStatus.JOINED -> JoinFeedback(JoinFeedbackKind.JOINED, "You joined $groupName.")
            EnterpriseJoinStatus.PENDING ->
                JoinFeedback(JoinFeedbackKind.PENDING, "Request sent. You will be added when an admin of $groupName approves it.")
            EnterpriseJoinStatus.REJECTED ->
                JoinFeedback(JoinFeedbackKind.REFUSED, result.value.reason?.takeIf { isPlainSentence(it) } ?: "$groupName did not accept your request.")
        }
        is FlashResult.Failure -> JoinFeedback(JoinFeedbackKind.FAILED, hubErrorText(result.error))
    }

    /** The sentence for a directory or join call that failed. Technical messages are replaced by a generic one. */
    public fun hubErrorText(error: FlashError): String = when (error) {
        is FlashError.NetworkUnavailable, is FlashError.ConnectionTimeout, is FlashError.PeerUnavailable ->
            "Could not reach your organisation. Check your connection and try again."
        is FlashError.Unknown -> error.message.takeIf { isPlainSentence(it) } ?: "Something went wrong talking to your organisation. Try again."
        else -> "Something went wrong talking to your organisation. Try again."
    }

    private fun isPlainSentence(text: String): Boolean =
        text.isNotBlank() && text.length <= 140 &&
            listOf("exception", "http", "{", "}", "stack", "null", "java.", "kotlin.").none { text.contains(it, ignoreCase = true) }

    /** Row text of a request an admin can decide. */
    public fun joinRequestTitle(displayName: String): String = "$displayName wants to join"
}
