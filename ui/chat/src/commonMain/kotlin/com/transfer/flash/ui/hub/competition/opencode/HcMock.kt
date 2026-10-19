package com.transfer.flash.ui.hub.competition.opencode

import com.transfer.flash.core.messaging.model.HubAnnouncementUi
import com.transfer.flash.core.messaging.model.HubDepartmentUi
import com.transfer.flash.core.messaging.model.HubGroupMembership
import com.transfer.flash.core.messaging.model.HubGroupUi
import com.transfer.flash.core.messaging.model.HubPersonRole
import com.transfer.flash.core.messaging.model.HubPersonUi
import com.transfer.flash.core.messaging.model.HubPostingRights
import com.transfer.flash.core.messaging.model.HubReachability
import com.transfer.flash.core.messaging.model.HubSampleData
import com.transfer.flash.core.messaging.model.HubSyncStatus

/**
 * Mock state for the competition deck.
 *
 * The five pages take one immutable [HcDeck] and never reach for a repository, a hub client or a Room type — exactly the
 * shape the shipped Hub tab uses (`FlashHubScreenData`), so the same discipline is visible in the entry. The Judge's
 * control bar swaps [HcScenario] and the whole deck is rebuilt:
 *
 * ```
 * val deck = remember(scenario) { hcDeckFor(scenario) }
 * ```
 *
 * Directory content is seeded from `HubSampleData` (the project's own preview fixture) and extended, so the pages show
 * the same kind of data the real tab does. Certificate, delegation, statement and revocation figures are modelled on
 * `:core:hub-protocol`'s types (`DeviceCert`, `DelegationCert`, `OrgMemberStatement`, `RevocationList`, `HubRoles`,
 * `HubStepUp`) without depending on them.
 */

/** The six hub states the Judge's control bar switches between. */
internal enum class HcScenario(val label: String, val blurb: String) {
    UNENROLLED("Unenrolled", "This device belongs to no organisation yet."),
    ENROLLING("Enrolling", "Redeeming an invitation and proving the device key."),
    ACTIVE_SEALED("Active & sealed", "Signed in, directory fresh, content sealed end to end."),
    RELAY_DEGRADED("Relay degraded", "Hub reachable in bursts; mailbox holding, backoff retrying."),
    CERT_REVOKED("Cert revoked / expired", "The organisation no longer accepts this device."),
    EMPTY_OFFLINE("Empty / offline", "Nothing has ever synced; every page falls back to its empty state."),
    ;

    val icon: HcGlyph
        get() = when (this) {
            UNENROLLED -> HcGlyph.HUB
            ENROLLING -> HcGlyph.CLOCK
            ACTIVE_SEALED -> HcGlyph.SHIELD
            RELAY_DEGRADED -> HcGlyph.RELAY
            CERT_REVOKED -> HcGlyph.WARNING
            EMPTY_OFFLINE -> HcGlyph.LINK_OFF
        }

    val tone: HcTone
        get() = when (this) {
            UNENROLLED -> HcTone.MUTED
            ENROLLING -> HcTone.ACCENT
            ACTIVE_SEALED -> HcTone.SUCCESS
            RELAY_DEGRADED -> HcTone.WARNING
            CERT_REVOKED -> HcTone.DANGER
            EMPTY_OFFLINE -> HcTone.MUTED
        }
}

internal enum class HcFormFactor(val label: String) { COMPACT("Mobile"), DESKTOP("Desktop split-pane") }

internal enum class HcTheme(val label: String) { DARK("Dark"), LIGHT("Light") }

internal enum class HcMotionMode(val label: String) { HIGH("High motion"), REDUCED("Reduced motion") }

/** The one-glance link verdict, mirroring `HubLinkState`. */
internal enum class HcLink(val label: String) {
    NOT_ENROLLED("Not enrolled"),
    DISCONNECTED("Signed out"),
    CONNECTING("Connecting"),
    AUTHENTICATING("Authenticating"),
    ONLINE("Online"),
    OFFLINE("Offline"),
    CERT_EXPIRING("Certificate expiring"),
    CERT_EXPIRED("Certificate expired"),
    REVOKED("Removed by the organisation"),
    SERVER_MISMATCH("Different hub answered"),
    ;

    val tone: HcTone
        get() = when (this) {
            ONLINE -> HcTone.SUCCESS
            CONNECTING, AUTHENTICATING, CERT_EXPIRING -> HcTone.WARNING
            OFFLINE, DISCONNECTED, NOT_ENROLLED -> HcTone.MUTED
            CERT_EXPIRED, REVOKED, SERVER_MISMATCH -> HcTone.DANGER
        }

    val isLive: Boolean get() = this == ONLINE || this == CERT_EXPIRING
}

/** The relay state machine, mirroring `RelaySessionState` plus its two terminal branches. */
internal enum class HcPhase(val label: String) {
    DISCONNECTED("Idle"),
    CONNECTING("Opening socket"),
    AUTHENTICATING("Proving identity"),
    CONNECTED("Signed in"),
    SUSPENDED("Backing off"),
    REVOKED("Blocked"),
}

/** What the sealing layer can do right now, mirroring `HubSealingStatus`. */
internal enum class HcSealing(val label: String) {
    SEALED("Sealed end to end"),
    PLAINTEXT_ONLY("Readable on the hub"),
    KEY_CHANGED("Sealing key changed"),
    NOT_PUBLISHED("Colleague has no key"),
    OFF("Switched off"),
    ;

    val tone: HcTone
        get() = when (this) {
            SEALED -> HcTone.SUCCESS
            PLAINTEXT_ONLY -> HcTone.WARNING
            KEY_CHANGED -> HcTone.DANGER
            NOT_PUBLISHED, OFF -> HcTone.MUTED
        }
}

internal enum class HcStepState { DONE, ACTIVE, PENDING, FAILED }

internal data class HcEnrollmentStep(val title: String, val detail: String, val state: HcStepState)

internal data class HcBanner(
    val id: String,
    val tone: HcTone,
    val glyph: HcGlyph,
    val title: String,
    val body: String,
    val action: String? = null,
    val secondaryAction: String? = null,
)

internal data class HcTelemetry(
    val latencyMs: List<Float>,
    val throughputKbps: List<Float>,
    val sealedCount: Int,
    val readableCount: Int,
    val droppedUnverified: Int,
    val liveSessions: Int,
    val mailboxHeld: Int,
    val retryAttempt: Int,
    val retryInSeconds: Int,
    val uptimeMs: Long,
    val envelopesOut: Int,
    val envelopesIn: Int,
)

internal data class HcSealingKey(
    val fingerprint: String,
    val pendingFingerprint: String?,
    val state: HcSealing,
    val rotatedAtMs: Long,
    val recipientsSealed: Int,
    val recipientsWaiting: Int,
)

internal data class HcLease(
    val daysLeft: Int,
    val untilMs: Long,
    val clockTrusted: Boolean,
    val source: String,
)

internal data class HcCertificate(
    val deviceId: String,
    val role: String,
    val department: String,
    val serialHex: String,
    val validFromMs: Long,
    val validToMs: Long,
    val algorithm: String,
    val issuerFingerprint: String,
    val spkiFingerprint: String,
    val signatureVerified: Boolean,
)

internal data class HcDelegation(
    val serial: Long,
    val scopes: List<String>,
    val validFromMs: Long,
    val validToMs: Long,
    val maxDevices: Int,
    val issuedDevices: Int,
    val delegateFingerprint: String,
    val signatureVerified: Boolean,
)

internal data class HcStatement(
    val serial: Long,
    val issuedAtMs: Long,
    val role: String,
    val departmentId: String,
    val signatureVerified: Boolean,
    val bindingOk: Boolean,
)

internal data class HcRevocations(
    val version: Int,
    val entries: Int,
    val fetchedAtMs: Long,
    val hashed: Boolean,
    val rootPinOk: Boolean,
    val listsThisDevice: Boolean,
)

internal data class HcRosterMember(
    val deviceId: String,
    val name: String,
    val role: String,
    val department: String,
    val online: Boolean?,
    val verified: Boolean,
    val paired: Boolean,
)

internal data class HcRoster(
    val groupId: String,
    val name: String,
    val department: String,
    val version: Long,
    val memberCount: Int,
    val adminCount: Int,
    val onlineCount: Int,
    val validUntilMs: Long,
    val statementSerial: Long,
    val adoption: String,
    val pendingRequests: Int,
    val members: List<HcRosterMember>,
)

internal data class HcBridge(
    val conversationId: String,
    val title: String,
    val kind: String,
    val viaHub: Boolean,
    val sealed: HcSealing,
    val queueHeld: Int,
    val delivered: Int,
    val lastAck: String,
    val note: String,
    val plaintextHonesty: Boolean,
)

internal data class HcPrivacy(
    val presenceVisible: Boolean,
    val directoryVisible: Boolean,
    val hiddenDepartments: Boolean,
    val hubContentAllowed: Boolean,
    val sealableOnly: Boolean,
)

/** Everything the five pages draw. Immutable, pre-derived, no repository in sight. */
internal data class HcDeck(
    val scenario: HcScenario,
    val nowMs: Long = HC_NOW_MS,
    val orgName: String,
    val orgRootFingerprint: String,
    val hubName: String,
    val hubHost: String,
    val hubVersion: String,
    val myDeviceId: String,
    val myName: String,
    val myRole: HubPersonRole,
    val myRoleLabel: String,
    val myDepartment: String,
    val myDepartmentId: String?,
    val link: HcLink,
    val phase: HcPhase,
    val syncStatus: HubSyncStatus,
    val lastSyncedAtMs: Long?,
    val directoryEpoch: String,
    val directoryVersion: Long,
    val directoryTotal: Int,
    val offlineGraceDays: Int,
    val telemetry: HcTelemetry,
    val sealingKey: HcSealingKey,
    val lease: HcLease,
    val certificate: HcCertificate?,
    val delegation: HcDelegation?,
    val statement: HcStatement?,
    val revocations: HcRevocations?,
    val enrollment: List<HcEnrollmentStep>,
    val inviteGroups: Int,
    val banners: List<HcBanner>,
    val announcements: List<HubAnnouncementUi>,
    val posting: HubPostingRights,
    val people: List<HubPersonUi>,
    val departments: List<HubDepartmentUi>,
    val groups: List<HubGroupUi>,
    val rosters: List<HcRoster>,
    val bridges: List<HcBridge>,
    val privacy: HcPrivacy,
) {
    /** True when the device holds organisation credentials at all. */
    val enrolled: Boolean get() = certificate != null

    /** Colleagues the directory can currently show. */
    val visiblePeople: List<HubPersonUi> get() = people.filterNot { it.isSelf }

    val unreadAnnouncements: Int get() = announcements.count { !it.isRead }

    val orgInitials: String get() = hcInitials(orgName)

    /** Where the directory snapshot stands in one sentence, for the sync watermark. */
    val syncSentence: String
        get() = when (syncStatus) {
            HubSyncStatus.NEVER -> "Never synced on this device"
            HubSyncStatus.OK -> lastSyncedAtMs?.let { "Synced ${hcAgo(nowMs, it)}" } ?: "Synced"
            HubSyncStatus.FAILED_OFFLINE -> lastSyncedAtMs?.let { "Offline since ${hcAgo(nowMs, it)}" } ?: "Offline"
            HubSyncStatus.FAILED_OTHER -> "Last sync failed"
            HubSyncStatus.UNSUPPORTED -> "This hub has no directory sync"
        }
}

// ---------------------------------------------------------------------------------------------------------------
// Fixture content
// ---------------------------------------------------------------------------------------------------------------

private val HC_ROOT_FINGERPRINT = "9F2C41A7D8B65E3307B4C1DA5E88F207"

private fun hcPeople(): List<HubPersonUi> = listOf(
    HubPersonUi(
        deviceId = "dev-amara", displayName = "Amara Okafor", departmentId = "dept-eng", departmentName = "Engineering",
        role = HubPersonRole.HEAD, online = true, reachability = HubReachability.LAN, paired = true, orgVerified = true, isSelf = false,
    ),
    HubPersonUi(
        deviceId = "dev-chen", displayName = "Chen Wei", departmentId = "dept-eng", departmentName = "Engineering",
        role = HubPersonRole.PERSON, online = true, reachability = HubReachability.HUB, paired = false, orgVerified = true, isSelf = false,
    ),
    HubPersonUi(
        deviceId = "dev-sofia", displayName = "Sofia Marchesi", departmentId = "dept-eng", departmentName = "Engineering",
        role = HubPersonRole.PERSON, online = false, reachability = HubReachability.OFFLINE, paired = true, orgVerified = true, isSelf = false,
    ),
    HubPersonUi(
        deviceId = "dev-ben", displayName = "Ben Hartley", departmentId = "dept-fin", departmentName = "Finance",
        role = HubPersonRole.PERSON, online = null, reachability = HubReachability.UNKNOWN, paired = false, orgVerified = true, isSelf = false,
    ),
    HubPersonUi(
        deviceId = "dev-dana", displayName = "Dana Kruger", departmentId = "dept-hr", departmentName = "People",
        role = HubPersonRole.ADMIN, online = true, reachability = HubReachability.HUB, paired = false, orgVerified = true, isSelf = false,
    ),
    HubPersonUi(
        deviceId = "dev-yusuf", displayName = "Yusuf Adeyemi", departmentId = "dept-ops", departmentName = "Operations",
        role = HubPersonRole.HEAD, online = true, reachability = HubReachability.LAN, paired = false, orgVerified = true, isSelf = false,
    ),
    HubPersonUi(
        deviceId = "dev-nadia", displayName = "Nadia Rahman", departmentId = "dept-ops", departmentName = "Operations",
        role = HubPersonRole.PERSON, online = false, reachability = HubReachability.HUB, paired = false, orgVerified = true, isSelf = false,
    ),
    HubPersonUi(
        deviceId = "dev-marco", displayName = "Marco Ruiz", departmentId = "dept-fin", departmentName = "Finance",
        role = HubPersonRole.PERSON, online = null, reachability = HubReachability.UNKNOWN, paired = false, orgVerified = false, isSelf = false,
    ),
    HubPersonUi(
        deviceId = "dev-self", displayName = "You", departmentId = "dept-eng", departmentName = "Engineering",
        role = HubPersonRole.PERSON, online = true, reachability = HubReachability.LAN, paired = false, orgVerified = true, isSelf = true,
    ),
)

private fun hcDepartments(): List<HubDepartmentUi> = listOf(
    HubDepartmentUi("dept-eng", "Engineering", 24, 3, true, listOf("dev-amara"), listOf("Amara Okafor"), false),
    HubDepartmentUi("dept-ops", "Operations", 18, 1, false, listOf("dev-yusuf"), listOf("Yusuf Adeyemi"), false),
    HubDepartmentUi("dept-fin", "Finance", 7, 1, false, emptyList(), emptyList(), false),
    HubDepartmentUi("dept-hr", "People", 6, 0, false, listOf("dev-dana"), listOf("Dana Kruger"), true),
)

private fun hcGroups(): List<HubGroupUi> = listOf(
    HubGroupUi("g-eng", "Engineering all-hands", "Engineering", 24, 11, false, HubGroupMembership.JOINED, null),
    HubGroupUi("g-ops", "Ops on-call", "Operations", 12, 4, true, HubGroupMembership.PENDING, 1),
    HubGroupUi("g-fin", "Finance close", "Finance", 7, null, true, HubGroupMembership.NOT_JOINED, null),
)

private fun hcAnnouncements(nowMs: Long): List<HubAnnouncementUi> = listOf(
    HubAnnouncementUi(
        id = "ann-1", scope = "org", departmentId = null,
        title = "Office closed on Friday",
        body = "The building is closed on Friday for the annual maintenance window. Work from home; the hub and the " +
            "directory stay available. Facilities will re-open Monday 07:00.\n\nQuestions: https://hub.acme.example/notice",
        authorName = "Dana Kruger", authorRole = "admin",
        createdAtMs = nowMs - 42L * 60_000L, updatedAtMs = nowMs - 40L * 60_000L,
        edited = false, pinned = true, expiresAtMs = null, seq = 2914L, isRead = false,
    ),
    HubAnnouncementUi(
        id = "ann-2", scope = "department", departmentId = "dept-eng",
        title = "Release freeze from Thursday 17:00",
        body = "No merges into release/2.6 after Thursday 17:00. Hotfixes go through Amara. The freeze lifts once the " +
            "customer migration completes.",
        authorName = "Amara Okafor", authorRole = "dept_admin",
        createdAtMs = nowMs - 6L * 3_600_000L, updatedAtMs = nowMs - 6L * 3_600_000L,
        edited = false, pinned = false, expiresAtMs = null, seq = 2911L, isRead = false,
    ),
    HubAnnouncementUi(
        id = "ann-3", scope = "org", departmentId = null,
        title = "New sealing keys rolled out",
        body = "Every device has published a fresh sealing key. If a colleague shows \u201Ckey changed\u201D, confirm the new " +
            "code once and messages resume sealed. Nothing on the hub is ever stored readable.",
        authorName = "Dana Kruger", authorRole = "admin",
        createdAtMs = nowMs - 28L * 3_600_000L, updatedAtMs = nowMs - 27L * 3_600_000L,
        edited = true, pinned = false, expiresAtMs = null, seq = 2902L, isRead = true,
    ),
    HubAnnouncementUi(
        id = "ann-4", scope = "department", departmentId = "dept-ops",
        title = "On-call rota for week 43",
        body = "Yusuf primary, Nadia secondary. Escalation goes to the duty admin through the hub.",
        authorName = "Yusuf Adeyemi", authorRole = "dept_admin",
        createdAtMs = nowMs - 2L * 86_400_000L, updatedAtMs = nowMs - 2L * 86_400_000L,
        edited = false, pinned = false, expiresAtMs = nowMs + 4L * 86_400_000L, seq = 2888L, isRead = true,
    ),
    HubAnnouncementUi(
        id = "ann-5", scope = "org", departmentId = null,
        title = "Directory privacy: hidden departments",
        body = "The People department is now excluded from the shared directory. Only its own members see it.",
        authorName = "Dana Kruger", authorRole = "admin",
        createdAtMs = nowMs - 9L * 86_400_000L, updatedAtMs = nowMs - 9L * 86_400_000L,
        edited = false, pinned = false, expiresAtMs = null, seq = 2841L, isRead = true,
    ),
)

private fun hcRosters(): List<HcRoster> = listOf(
    HcRoster(
        groupId = "g-eng", name = "Engineering all-hands", department = "Engineering", version = 41L,
        memberCount = 24, adminCount = 3, onlineCount = 11, validUntilMs = HC_NOW_MS + 26L * 86_400_000L,
        statementSerial = 41L, adoption = "ADOPTED", pendingRequests = 0,
        members = listOf(
            HcRosterMember("dev-amara", "Amara Okafor", "owner", "Engineering", true, true, true),
            HcRosterMember("dev-chen", "Chen Wei", "admin", "Engineering", true, true, false),
            HcRosterMember("dev-sofia", "Sofia Marchesi", "member", "Engineering", false, true, true),
            HcRosterMember("dev-self", "You", "member", "Engineering", true, true, false),
            HcRosterMember("dev-jonas", "Jonas Fisker", "member", "Engineering", null, true, false),
        ),
    ),
    HcRoster(
        groupId = "g-ops", name = "Ops on-call", department = "Operations", version = 17L,
        memberCount = 12, adminCount = 1, onlineCount = 4, validUntilMs = HC_NOW_MS + 26L * 86_400_000L,
        statementSerial = 17L, adoption = "STALE (offered 18 rejected)", pendingRequests = 1,
        members = listOf(
            HcRosterMember("dev-yusuf", "Yusuf Adeyemi", "owner", "Operations", true, true, false),
            HcRosterMember("dev-nadia", "Nadia Rahman", "member", "Operations", false, true, false),
            HcRosterMember("dev-omar", "Omar Haddad", "member", "Operations", null, false, false),
        ),
    ),
)

private fun hcBridges(): List<HcBridge> = listOf(
    HcBridge(
        conversationId = "dev-ben", title = "Ben Hartley", kind = "Direct 1:1", viaHub = true,
        sealed = HcSealing.SEALED, queueHeld = 0, delivered = 34,
        lastAck = "Delivered through the organisation's server", note = "Sealed envelope; the hub never sees the text.",
        plaintextHonesty = false,
    ),
    HcBridge(
        conversationId = "g-fin", title = "Finance close", kind = "Group · linked to the hub", viaHub = true,
        sealed = HcSealing.PLAINTEXT_ONLY, queueHeld = 2, delivered = 118,
        lastAck = "Held until Marco is online", note = "Group content travels readable: there is no per-sender group key yet.",
        plaintextHonesty = true,
    ),
    HcBridge(
        conversationId = "dev-marco", title = "Marco Ruiz", kind = "Direct 1:1", viaHub = true,
        sealed = HcSealing.KEY_CHANGED, queueHeld = 5, delivered = 12,
        lastAck = "Waiting for you to confirm the new sealing code", note = "Nothing is sent until the new code is confirmed.",
        plaintextHonesty = false,
    ),
    HcBridge(
        conversationId = "dev-sofia", title = "Sofia Marchesi", kind = "Direct 1:1", viaHub = false,
        sealed = HcSealing.SEALED, queueHeld = 0, delivered = 402,
        lastAck = "Direct over Wi-Fi", note = "Reachable on the LAN; the hub path is unused right now.",
        plaintextHonesty = false,
    ),
)

// ---------------------------------------------------------------------------------------------------------------
// Scenario factory
// ---------------------------------------------------------------------------------------------------------------

/** Builds the deck for one Judge state. Called with `remember(scenario)`; cheap enough not to memoise further. */
internal fun hcDeckFor(scenario: HcScenario): HcDeck {
    val now = HC_NOW_MS
    val base = HcDeck(
        scenario = scenario,
        nowMs = now,
        orgName = "Acme Corp",
        orgRootFingerprint = HC_ROOT_FINGERPRINT,
        hubName = "Acme Hub",
        hubHost = "hub.acme.example:8443",
        hubVersion = "2.6.1",
        myDeviceId = "dev-self",
        myName = "You",
        myRole = HubPersonRole.PERSON,
        myRoleLabel = "Member",
        myDepartment = "Engineering",
        myDepartmentId = "dept-eng",
        link = HcLink.ONLINE,
        phase = HcPhase.CONNECTED,
        syncStatus = HubSyncStatus.OK,
        lastSyncedAtMs = now - 3L * 60_000L,
        directoryEpoch = "e7",
        directoryVersion = 1284L,
        directoryTotal = 55,
        offlineGraceDays = 30,
        telemetry = HcTelemetry(
            latencyMs = listOf(38f, 41f, 36f, 52f, 44f, 39f, 37f, 43f, 40f, 38f, 61f, 42f, 39f, 37f, 40f, 38f),
            throughputKbps = listOf(120f, 180f, 240f, 210f, 320f, 280f, 360f, 300f, 260f, 340f, 380f, 310f, 290f, 350f, 330f, 360f),
            sealedCount = 1_284,
            readableCount = 96,
            droppedUnverified = 0,
            liveSessions = 3,
            mailboxHeld = 0,
            retryAttempt = 0,
            retryInSeconds = 0,
            uptimeMs = 4L * 3_600_000L + 11L * 60_000L,
            envelopesOut = 742,
            envelopesIn = 638,
        ),
        sealingKey = HcSealingKey(
            fingerprint = "3A9F1C6E5B27D480",
            pendingFingerprint = null,
            state = HcSealing.SEALED,
            rotatedAtMs = now - 3L * 86_400_000L,
            recipientsSealed = 48,
            recipientsWaiting = 0,
        ),
        lease = HcLease(daysLeft = 29, untilMs = now + 29L * 86_400_000L, clockTrusted = true, source = "Directory sync e7 v1284"),
        certificate = HcCertificate(
            deviceId = "dev-self",
            role = "member",
            department = "dept-eng",
            serialHex = "4E1B 9C07 3A52 88D0",
            validFromMs = now - 61L * 86_400_000L,
            validToMs = now + 304L * 86_400_000L,
            algorithm = "ECDSA P-256 · SHA-256",
            issuerFingerprint = "C17B 8E44 0A9D 51F2",
            spkiFingerprint = "6D02 5A31 BC88 40E7",
            signatureVerified = true,
        ),
        delegation = HcDelegation(
            serial = 4L,
            scopes = listOf("enrol:member", "enrol:dept_admin", "revoke:member"),
            validFromMs = now - 120L * 86_400_000L,
            validToMs = now + 245L * 86_400_000L,
            maxDevices = 400,
            issuedDevices = 55,
            delegateFingerprint = "C17B 8E44 0A9D 51F2",
            signatureVerified = true,
        ),
        statement = HcStatement(
            serial = 212L,
            issuedAtMs = now - 3L * 86_400_000L,
            role = "member",
            departmentId = "dept-eng",
            signatureVerified = true,
            bindingOk = true,
        ),
        revocations = HcRevocations(
            version = 12,
            entries = 3,
            fetchedAtMs = now - 2L * 3_600_000L,
            hashed = true,
            rootPinOk = true,
            listsThisDevice = false,
        ),
        enrollment = emptyList(),
        inviteGroups = 3,
        banners = listOf(
            HcBanner(
                id = "b-ann", tone = HcTone.ACCENT, glyph = HcGlyph.ANNOUNCEMENT,
                title = "2 announcements are unread",
                body = "Facilities posted a closure notice and Engineering a release freeze.",
                action = "Read",
            ),
        ),
        announcements = hcAnnouncements(now),
        posting = HubPostingRights.None,
        people = hcPeople(),
        departments = hcDepartments(),
        groups = hcGroups(),
        rosters = hcRosters(),
        bridges = hcBridges(),
        privacy = HcPrivacy(
            presenceVisible = true,
            directoryVisible = true,
            hiddenDepartments = true,
            hubContentAllowed = false,
            sealableOnly = true,
        ),
    )

    return when (scenario) {
        HcScenario.ACTIVE_SEALED -> base.copy(
            myRole = HubPersonRole.HEAD,
            myRoleLabel = "Head of Engineering",
            posting = HubPostingRights.Department(listOf("dept-eng")),
        )

        HcScenario.UNENROLLED -> base.copy(
            orgName = "No organisation",
            link = HcLink.NOT_ENROLLED,
            phase = HcPhase.DISCONNECTED,
            syncStatus = HubSyncStatus.NEVER,
            lastSyncedAtMs = null,
            directoryEpoch = "",
            directoryVersion = 0L,
            directoryTotal = 0,
            certificate = null,
            delegation = null,
            statement = null,
            revocations = null,
            sealingKey = base.sealingKey.copy(state = HcSealing.OFF, pendingFingerprint = null, recipientsSealed = 0, recipientsWaiting = 0),
            lease = HcLease(daysLeft = 0, untilMs = 0L, clockTrusted = true, source = "No snapshot"),
            telemetry = base.telemetry.copy(
                latencyMs = emptyList(), throughputKbps = emptyList(), sealedCount = 0, readableCount = 0,
                liveSessions = 0, mailboxHeld = 0, uptimeMs = 0L, envelopesOut = 0, envelopesIn = 0,
            ),
            enrollment = listOf(
                HcEnrollmentStep("Enter the invitation code", "8 characters from your administrator", HcStepState.ACTIVE),
                HcEnrollmentStep("Prove this device", "One-time key exchange with the hub", HcStepState.PENDING),
                HcEnrollmentStep("Receive the certificate", "Signed by the organisation", HcStepState.PENDING),
                HcEnrollmentStep("Sync the directory", "Departments and colleagues", HcStepState.PENDING),
            ),
            banners = listOf(
                HcBanner(
                    id = "b-un", tone = HcTone.MUTED, glyph = HcGlyph.HUB,
                    title = "This device is not part of an organisation",
                    body = "Flash works on its own. Enrol to see the company directory, get announcements and reach colleagues through the hub.",
                    action = "Enter code", secondaryAction = "What is this?",
                ),
            ),
            announcements = emptyList(),
            posting = HubPostingRights.None,
            people = emptyList(),
            departments = emptyList(),
            groups = emptyList(),
            rosters = emptyList(),
            bridges = emptyList(),
        )

        HcScenario.ENROLLING -> base.copy(
            link = HcLink.AUTHENTICATING,
            phase = HcPhase.AUTHENTICATING,
            syncStatus = HubSyncStatus.NEVER,
            lastSyncedAtMs = null,
            certificate = null,
            statement = null,
            revocations = null,
            directoryVersion = 0L,
            directoryTotal = 0,
            directoryEpoch = "",
            sealingKey = base.sealingKey.copy(state = HcSealing.OFF, recipientsSealed = 0, recipientsWaiting = 0),
            lease = HcLease(daysLeft = 0, untilMs = 0L, clockTrusted = true, source = "Enrolment in progress"),
            telemetry = base.telemetry.copy(
                sealedCount = 0, readableCount = 0, liveSessions = 1, mailboxHeld = 0,
                uptimeMs = 42_000L, envelopesOut = 0, envelopesIn = 0,
            ),
            enrollment = listOf(
                HcEnrollmentStep("Enter the invitation code", "ACME-7Q4K accepted by hub.acme.example", HcStepState.DONE),
                HcEnrollmentStep("Prove this device", "Signing the hub's nonce with the install key", HcStepState.ACTIVE),
                HcEnrollmentStep("Receive the certificate", "Waiting for the signed device certificate", HcStepState.PENDING),
                HcEnrollmentStep("Sync the directory", "Departments and colleagues", HcStepState.PENDING),
            ),
            banners = listOf(
                HcBanner(
                    id = "b-enr", tone = HcTone.ACCENT, glyph = HcGlyph.CLOCK,
                    title = "Enrolling with Acme Hub",
                    body = "The invitation was accepted. Flash is proving this device's key; nothing else is sent.",
                ),
            ),
            announcements = emptyList(),
            posting = HubPostingRights.None,
            people = emptyList(),
            departments = emptyList(),
            groups = emptyList(),
            rosters = emptyList(),
            bridges = emptyList(),
        )

        HcScenario.RELAY_DEGRADED -> base.copy(
            link = HcLink.OFFLINE,
            phase = HcPhase.SUSPENDED,
            posting = HubPostingRights.OrgWide(listOf("dept-eng", "dept-ops", "dept-fin", "dept-hr")),
            syncStatus = HubSyncStatus.FAILED_OFFLINE,
            lastSyncedAtMs = now - 47L * 60_000L,
            telemetry = base.telemetry.copy(
                latencyMs = listOf(210f, 340f, 0f, 480f, 190f, 0f, 0f, 520f, 260f, 0f, 300f, 410f),
                throughputKbps = listOf(40f, 22f, 0f, 8f, 60f, 0f, 0f, 12f, 34f, 0f, 18f, 6f),
                sealedCount = 1_190,
                readableCount = 96,
                droppedUnverified = 2,
                liveSessions = 1,
                mailboxHeld = 17,
                retryAttempt = 6,
                retryInSeconds = 24,
                envelopesOut = 801,
                envelopesIn = 638,
            ),
            sealingKey = base.sealingKey.copy(
                state = HcSealing.KEY_CHANGED,
                pendingFingerprint = "B710 44DE 9C03 AF28",
                recipientsWaiting = 1,
            ),
            lease = HcLease(daysLeft = 27, untilMs = now + 27L * 86_400_000L, clockTrusted = true, source = "Directory sync e7 v1279 (46 min old)"),
            banners = listOf(
                HcBanner(
                    id = "b-held", tone = HcTone.WARNING, glyph = HcGlyph.RELAY,
                    title = "17 messages are held by the hub",
                    body = "They will be delivered when the recipients next sign in. Nothing was lost.",
                ),
                HcBanner(
                    id = "b-key", tone = HcTone.DANGER, glyph = HcGlyph.SHIELD,
                    title = "Marco Ruiz published a new sealing code",
                    body = "Messages to Marco are paused until you confirm B710 44DE 9C03 AF28.",
                    action = "Review code", secondaryAction = "Why?",
                ),
                HcBanner(
                    id = "b-drop", tone = HcTone.DANGER, glyph = HcGlyph.WARNING,
                    title = "2 sealed envelopes did not verify",
                    body = "They were dropped, not stored. This can be a changed key or a tampered delivery.",
                    action = "Open audit",
                ),
            ),
        )

        HcScenario.CERT_REVOKED -> base.copy(
            link = HcLink.REVOKED,
            phase = HcPhase.REVOKED,
            syncStatus = HubSyncStatus.FAILED_OTHER,
            lastSyncedAtMs = now - 3L * 86_400_000L,
            certificate = base.certificate?.copy(
                validFromMs = now - 425L * 86_400_000L,
                validToMs = now - 2L * 86_400_000L,
                signatureVerified = true,
            ),
            statement = base.statement?.copy(bindingOk = false),
            revocations = base.revocations?.copy(version = 13, entries = 4, fetchedAtMs = now - 5L * 60_000L, listsThisDevice = true),
            sealingKey = base.sealingKey.copy(state = HcSealing.OFF, pendingFingerprint = null, recipientsSealed = 0, recipientsWaiting = 0),
            lease = HcLease(daysLeft = 0, untilMs = now - 2L * 86_400_000L, clockTrusted = true, source = "Certificate expired before the lease"),
            telemetry = base.telemetry.copy(
                sealedCount = 1_284, readableCount = 96, droppedUnverified = 4, liveSessions = 0,
                mailboxHeld = 0, retryAttempt = 0, retryInSeconds = 0, envelopesOut = 802, envelopesIn = 640,
            ),
            banners = listOf(
                HcBanner(
                    id = "b-rev", tone = HcTone.DANGER, glyph = HcGlyph.WARNING,
                    title = "This device was removed by the organisation",
                    body = "Revision 13 of the revocation list lists this certificate. Chat over the LAN still works; the hub path is closed.",
                    action = "Request a new invite", secondaryAction = "See details",
                ),
                HcBanner(
                    id = "b-exp", tone = HcTone.WARNING, glyph = HcGlyph.CLOCK,
                    title = "The device certificate expired 2 days ago",
                    body = "Automatic renewal was attempted 11 times and refused. Only a fresh invitation restores the hub.",
                ),
            ),
        )

        HcScenario.EMPTY_OFFLINE -> base.copy(
            link = HcLink.OFFLINE,
            phase = HcPhase.SUSPENDED,
            syncStatus = HubSyncStatus.NEVER,
            lastSyncedAtMs = null,
            directoryEpoch = "",
            directoryVersion = 0L,
            directoryTotal = 0,
            telemetry = base.telemetry.copy(
                latencyMs = emptyList(), throughputKbps = emptyList(), sealedCount = 0, readableCount = 0,
                liveSessions = 0, mailboxHeld = 0, retryAttempt = 2, retryInSeconds = 48,
                uptimeMs = now - (now - 900_000L), envelopesOut = 0, envelopesIn = 0,
            ),
            sealingKey = base.sealingKey.copy(state = HcSealing.OFF, recipientsSealed = 0, recipientsWaiting = 0),
            lease = HcLease(daysLeft = 0, untilMs = 0L, clockTrusted = true, source = "No snapshot"),
            statement = null,
            revocations = null,
            enrollment = emptyList(),
            banners = listOf(
                HcBanner(
                    id = "b-never", tone = HcTone.MUTED, glyph = HcGlyph.LINK_OFF,
                    title = "The directory has never synced on this device",
                    body = "The certificate is valid, so a single successful sync is all that is needed. Retrying in 48 s.",
                    action = "Try now",
                ),
            ),
            announcements = emptyList(),
            posting = HubPostingRights.None,
            people = emptyList(),
            departments = emptyList(),
            groups = emptyList(),
            rosters = emptyList(),
            bridges = emptyList(),
        )
    }
}

/** Sanity fixture used by the directory page when the Judge wants an "as the shipped tab would seed it" comparison. */
internal val HcSampleCounts: Triple<Int, Int, Int> =
    Triple(HubSampleData.people.size, HubSampleData.departments.size, HubSampleData.groups.size)
