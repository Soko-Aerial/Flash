package com.transfer.flash.ui.hub.competition.novahub

import androidx.compose.runtime.Immutable

/**
 * NOVA — Hub UI/UX competition entry (OpenCode). Design-only presentation models.
 *
 * These are a UI-shape mirror of the Hub domain modelled across the repository (HubStatus.kt,
 * RelaySessionState.kt, HubTabModels.kt, HubNotice.kt, HubSealingKeyManager.kt, OrgPeerResolver.kt,
 * OrgDirectorySync.kt, HubAnnouncementModels.kt, EnrollmentResult.kt, DeviceCert.kt, HubRoles.kt,
 * HubStepUp.kt, RevocationList.kt, HubGroupRosters.kt, HubChatBridge.kt, EnrollmentInvite.kt,
 * EnterpriseEnrollmentClient.kt, DirectoryPrivacyClient.kt). The pages read presenting values only —
 * they never touch a core type, a repository, or a clock, which is what makes the package
 * preview-safe and merge-free.
 */

// --- Competitor / judge vocabulary -------------------------------------------------------------------

/** One of the six live world states the Judges' Control Bar switches between. */
public enum class NovaScenario(public val label: String) {
    Unenrolled("Unenrolled"),
    Enrolling("Enrolling"),
    ActiveSealed("Active & Sealed"),
    RelayDegraded("Relay Degraded"),
    CertRevoked("Cert Revoked"),
    OffEmpty("Empty / Offline"),
}

/** Form factor the showcase renders in. Follows the adaptive patterns of DesktopAdaptive / side bar. */
public enum class NovaForm(public val label: String) {
    MobileCompact("Phone"),
    DesktopSplit("Desktop"),
}

/** The five designed pages. Order doubles as the showcase navigation order. */
public enum class NovaPage(public val label: String, public val caption: String) {
    Command("Command", "Live telemetry & link state"),
    Directory("Directory", "Colleagues, departments & peers"),
    Broadcast("Broadcast", "Organisation announcements"),
    Vault("Vault", "Enrolment, certs & trust"),
    Bridge("Bridge", "Rosters & the hub relay path"),
}

/** Windows-size classes of the adaptive plan, reused verbatim semantics (compact < 600dp …). */
public enum class NovaWidthClass { Compact, Medium, Expanded }

// --- Organisation, link and strips -------------------------------------------------------------------

@Immutable
public data class NovaOrg(
    val name: String,
    val host: String,
    /** Groups-of-four short root fingerprint, e.g. "AB12 CD34 EF56 0790". */
    val rootShort: String,
    val memberCount: Int,
    val departmentCount: Int,
    val groupCount: Int,
    val roleLine: String,
    val canPost: Boolean,
)

/** Mirror of HubLinkState as the UI reads it. */
public enum class NovaLinkState(public val label: String) {
    NotEnrolled("Not enrolled"),
    Disconnected("Not connected"),
    Connecting("Connecting"),
    Authenticating("Signing in"),
    Online("Connected"),
    Offline("Offline"),
    CertExpiring("Access expiring"),
    CertExpired("Access expired"),
    Revoked("Removed by your organisation"),
    ServerMismatch("Not your organisation's hub"),
}

public fun NovaLinkState.tone(): NovaTone = when (this) {
    NovaLinkState.Online -> NovaTone.OK
    NovaLinkState.CertExpiring -> NovaTone.WARN
    NovaLinkState.Connecting, NovaLinkState.Authenticating, NovaLinkState.Disconnected -> NovaTone.INFO
    NovaLinkState.NotEnrolled -> NovaTone.NEUTRAL
    NovaLinkState.Offline -> NovaTone.WARN
    NovaLinkState.CertExpired, NovaLinkState.Revoked, NovaLinkState.ServerMismatch -> NovaTone.FAIL
}

/** Status strip value, modelled on FlashHubStrip (text + tone; icon comes from the tone). */
@Immutable
public data class NovaStrip(val text: String, val tone: NovaTone, val actionLabel: String? = null)

@Immutable
public data class NovaBanner(
    val title: String,
    val body: String,
    val tone: NovaTone,
    val actionLabel: String? = null,
    val dismissible: Boolean = false,
)

/**
 * Enterprise session telemetry, modelled on HubSession / RelaySessionState: one row per live or
 * pending session with its state machine position and plain-language detail.
 */
@Immutable
public data class NovaSession(
    val id: String,
    val peerLabel: String,
    val kind: NovaSessionKind,
    val stateLabel: String,
    val stateTone: NovaTone,
    /** Plain detail sentence ("Handshake 84 ms · renewal in 12 days"). */
    val detail: String,
    /** 0..1 progress shown while the session is connecting or authenticating. */
    val progress: Float?,
)

public enum class NovaSessionKind { WS, REST, PEER }

/** One live telemetry card with a spark history (0..1 values) for the command center. */
@Immutable
public data class NovaTelemetry(
    val label: String,
    val value: String,
    val subline: String,
    val tone: NovaTone,
    val spark: List<Float>,
    val icon: Int,
)

/** Mirror of HubSealingKeyState + HubNotice drop counts for the trust & sealing card. */
public enum class NovaSealingState(public val label: String) {
    Idle("Getting ready"),
    Publishing("Getting ready"),
    Published("Sealed"),
    HubTooOld("Hub too old"),
    Refused("Refused"),
    StorageFailed("Could not save"),
}

public fun NovaSealingState.tone(): NovaTone = when (this) {
    NovaSealingState.Published -> NovaTone.OK
    NovaSealingState.Idle, NovaSealingState.Publishing -> NovaTone.INFO
    NovaSealingState.HubTooOld -> NovaTone.WARN
    NovaSealingState.Refused, NovaSealingState.StorageFailed -> NovaTone.FAIL
}

@Immutable
public data class NovaSealing(
    val state: NovaSealingState,
    /** Public short fingerprint — never key material (Flash never logs or shows secrets). */
    val fingerprintShort: String,
    /** Sealed messages that did not open (HubDropCounts.unverified); shown only when > 0. */
    val unverified: Int,
    /** Readable messages refused because "Messages through the hub" is off (HubDropCounts.readableOff). */
    val readableOff: Int,
    /** One honest sentence of what the state means to the person holding the phone. */
    val sentence: String,
)

/** Plan B offline trust lease UI value (OrgLease → FlashHubStatusMath.OrgTrustState). */
public enum class NovaLeaseKind { Valid, Expiring, Expired, NeverSynced, ClockWrong, IdentityMismatch }

@Immutable
public data class NovaLease(
    val kind: NovaLeaseKind,
    /** "valid until 12 Nov" / "reconnect within 2 days" style sentence. */
    val sentence: String,
    /** Days until expiry for the meter. */
    val daysLeft: Int,
)

public fun NovaLeaseKind.tone(): NovaTone = when (this) {
    NovaLeaseKind.Valid -> NovaTone.OK
    NovaLeaseKind.Expiring -> NovaTone.WARN
    NovaLeaseKind.Expired, NovaLeaseKind.ClockWrong, NovaLeaseKind.IdentityMismatch -> NovaTone.FAIL
    NovaLeaseKind.NeverSynced -> NovaTone.NEUTRAL
}

// --- Directory ---------------------------------------------------------------------------------------

public enum class NovaReach(public val label: String) { LAN("On your Wi-Fi now"), Hub("Through the company server"), Offline("Offline"), Unknown("Availability unknown") }

public fun NovaReach.tone(): NovaTone = when (this) {
    NovaReach.LAN -> NovaTone.OK
    NovaReach.Hub -> NovaTone.INFO
    NovaReach.Offline -> NovaTone.NEUTRAL
    NovaReach.Unknown -> NovaTone.NEUTRAL
}

public fun NovaReach.dot(): Boolean? = when (this) {
    NovaReach.LAN -> true
    NovaReach.Hub -> true
    NovaReach.Offline -> false
    NovaReach.Unknown -> null // withheld presence shows NO dot (design doc), not a grey one
}

@Immutable
public data class NovaMember(
    val id: String,
    val name: String,
    val department: String,
    val departmentId: String,
    val roleBadge: String?,
    val reach: NovaReach,
    val paired: Boolean,
    /** Snapshot statement matches the live session key (OrgPeerResolver.bindingOk). */
    val orgVerified: Boolean,
    val isSelf: Boolean,
    val seed: String,
) {
    val initialsText: String
        get() {
            val words = name.trim().split(' ').filter { it.isNotEmpty() }
            return when {
                words.isEmpty() -> "?"
                words.size == 1 -> words[0].take(1)
                else -> words.first().take(1) + words.last().take(1)
            }
        }
}

@Immutable
public data class NovaDepartment(
    val id: String,
    val name: String,
    val memberCount: Int,
    val groupCount: Int,
    val mine: Boolean,
    val heads: List<String>,
    /** True for a directory-privacy hidden department (visible only to its own members and admins). */
    val hidden: Boolean,
) {
    val initialsText: String
        get() {
            val words = name.trim().split(' ').filter { it.isNotEmpty() }
            return if (words.isEmpty()) "?" else (words.first().take(1) + (words.getOrNull(1)?.take(1) ?: "")).uppercase()
        }
}

/** Mirror of HubSyncStatus + the sync watermark (cursor version, backoff) of OrgDirectorySync. */
public enum class NovaSyncState { Never, Ok, Syncing, FailedOffline, FailedOther, Unsupported }

public fun NovaSyncState.tone(): NovaTone = when (this) {
    NovaSyncState.Never -> NovaTone.NEUTRAL
    NovaSyncState.Ok, NovaSyncState.Syncing -> NovaTone.OK
    NovaSyncState.FailedOffline, NovaSyncState.FailedOther -> NovaTone.WARN
    NovaSyncState.Unsupported -> NovaTone.NEUTRAL
}

@Immutable
public data class NovaSyncWatermark(
    val state: NovaSyncState,
    /** "2 minutes ago" style of the last snapshot applied. */
    val snapshotAge: String,
    /** Monotonic directory version (OrgDirectorySync cursor), e.g. "v4 182". */
    val version: String,
    /** Member count in the snapshot the device may see. */
    val visibleCount: Int,
    val backoffSentence: String?,
)

// --- Broadcast feed ----------------------------------------------------------------------------------

@Immutable
public data class NovaAnnouncement(
    val id: String,
    val scopeLabel: String,
    val departmentId: String?,
    val title: String,
    val body: String,
    val author: String,
    val authorRole: String,
    val timeLine: String,
    val edited: Boolean,
    val pinned: Boolean,
    val unread: Boolean,
    /** Priority badge modelled on the console's "priority" flag (high = urgent bulletin). */
    val highPriority: Boolean,
    val seed: String,
) {
    val initialsText: String
        get() {
            val words = author.trim().split(' ').filter { it.isNotEmpty() }
            return if (words.isEmpty()) "?" else words.first().take(1) + (words.getOrNull(1)?.take(1) ?: "")
        }
}

// --- Enrolment & trust vault -------------------------------------------------------------------------

/** Result of an invite the demo screen shows; mirrors EnrollmentInvite + the honesty the UI owes. */
@Immutable
public data class NovaInvite(
    val host: String,
    /** Masked single-use token, e.g. "•••• •••• 7Q" — the demo never shows a real token. */
    val tokenMasked: String,
    val rootShort: String,
    val usesTls: Boolean,
    val organisationName: String,
    val expires: String,
)

public enum class NovaEnrolStepKind { Link, Verify, Keys, Done }

@Immutable
public data class NovaEnrolStep(
    val kind: NovaEnrolStepKind,
    val headline: String,
    val body: String,
    val state: NovaStepState,
)

public enum class NovaStepState { Done, Current, Pending }

/** One trust-chain row of the certificate inspector (DeviceCert / DelegationCert / root). */
@Immutable
public data class NovaChainRow(
    val name: String,
    val kindLabel: String,
    val idShort: String,
    val metaLeft: String,
    val metaRight: String,
    val tone: NovaTone,
    val stateWord: String,
    val note: String?,
    /** Dims the row and shows the revoked notice where the state word says so. */
    val revoked: Boolean = false,
)

/** Structure of the vault page beyond the chain: roles, step-up, revocation list. */
@Immutable
public data class NovaVault(
    val deviceLine: String,
    val roles: List<String>,
    val scopes: List<String>,
    val stepUpCode: String,
    val stepUpTitle: String,
    val stepUpBody: String,
    val revocationTitle: String,
    val revocationBody: String,
    val revocationTone: NovaTone,
)

// --- Rosters & relay bridge --------------------------------------------------------------------------

public enum class NovaRosterState(public val label: String) { Current("Adopted"), Expiring("Renews soon"), Stale("Stale offer"), Rejected("Rejected") }

public fun NovaRosterState.tone(): NovaTone = when (this) {
    NovaRosterState.Current -> NovaTone.OK
    NovaRosterState.Expiring -> NovaTone.WARN
    NovaRosterState.Stale -> NovaTone.INFO
    NovaRosterState.Rejected -> NovaTone.FAIL
}

@Immutable
public data class NovaRoster(
    val groupId: String,
    val name: String,
    val department: String,
    val version: Long,
    val validUntilShort: String,
    val members: Int,
    val memberSeeds: List<String>,
    val state: NovaRosterState,
    val origin: String,
)

@Immutable
public data class NovaBridgeStat(
    val label: String,
    val value: String,
    val tone: NovaTone,
)

/** Mirror of HubChatBridge / HubRelayPort path summary lines. */
@Immutable
public data class NovaBridge(
    val modeLabel: String,
    val modeTone: NovaTone,
    val note: String,
    val stats: List<NovaBridgeStat>,
    /** True when the demo bridge shows the honest "the hub can read group messages" line. */
    val honestLine: String,
)

// --- World snapshot ----------------------------------------------------------------------------------

/**
 * Everything the five pages draw, for one competition scenario. Immutable; the judges' bar builds a
 * new one per scenario switch, so a page's entrance animation has a chance to replay.
 */
@Immutable
public data class NovaWorld(
    val scenario: NovaScenario,
    val org: NovaOrg,
    val link: NovaLinkState,
    val strip: NovaStrip,
    val banners: List<NovaBanner>,
    val sessions: List<NovaSession>,
    val telemetry: List<NovaTelemetry>,
    val sealing: NovaSealing,
    val lease: NovaLease,
    val people: List<NovaMember>,
    val departments: List<NovaDepartment>,
    val sync: NovaSyncWatermark,
    val news: List<NovaAnnouncement>,
    val invite: NovaInvite?,
    val enrolSteps: List<NovaEnrolStep>,
    val chain: List<NovaChainRow>,
    val vault: NovaVault,
    val rosters: List<NovaRoster>,
    val bridge: NovaBridge,
    /** Seconds live counter basis the pages format (they own their ticker for the elapsed part). */
    val liveTickBaseSeconds: Int = 41,
)
