package com.transfer.flash.ui.hub.competition.novahub

/**
 * NOVA — Hub UI/UX competition entry (OpenCode). Fixed sample world for previews and the judge bar.
 *
 * The data mirrors the shapes and names already used as samples across the repository (HubSampleData
 * "Acme Corp", colleagues like Amara Okafor and Dana Kruger, departments Engineering/Finance/People)
 * so the pitch reads as a native evolution of Flash, and the copy follows the project's plain-sentence,
 * honesty-first voice (docs/ui/flash-hub-tab.md and the exact sentences style of FlashHubCopy /
 * FlashHubStatusMath). Nothing here is production data.
 *
 * Every scenario derives from the same base world with targeted edits, so judges flipping states see
 * coherent, believable consequences across all five pages.
 */

private val ORG = NovaOrg(
    name = "Acme Corp",
    host = "hub.acme.example:443",
    rootShort = "AB12 CD34 EF56 0790",
    memberCount = 42,
    departmentCount = 5,
    groupCount = 3,
    roleLine = "Member · Engineering",
    canPost = false,
)

private val SPARK_RISING = listOf(0.2f, 0.3f, 0.26f, 0.44f, 0.5f, 0.47f, 0.62f, 0.58f, 0.71f, 0.8f, 0.76f, 0.9f)
private val SPARK_STEADY = listOf(0.5f, 0.53f, 0.47f, 0.55f, 0.5f, 0.52f, 0.49f, 0.54f, 0.51f, 0.53f, 0.5f, 0.52f)
private val SPARK_DIPPING = listOf(0.85f, 0.8f, 0.78f, 0.62f, 0.55f, 0.58f, 0.4f, 0.35f, 0.28f, 0.3f, 0.22f, 0.25f)
private val SPARK_EMPTY = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

private val PEOPLE = listOf(
    NovaMember("dev-amara", "Amara Okafor", "Engineering", "dept-eng", "Head", NovaReach.LAN, paired = false, orgVerified = true, isSelf = false, seed = "dev-amara"),
    NovaMember("dev-chen", "Chen Wei", "Engineering", "dept-eng", null, NovaReach.LAN, paired = true, orgVerified = true, isSelf = false, seed = "dev-chen"),
    NovaMember("dev-priya", "Priya Sharma", "Engineering", "dept-eng", null, NovaReach.Hub, paired = false, orgVerified = true, isSelf = false, seed = "dev-priya"),
    NovaMember("dev-yuri", "Yuri Petrov", "Engineering", "dept-eng", null, NovaReach.Offline, paired = false, orgVerified = true, isSelf = false, seed = "dev-yuri"),
    NovaMember("dev-dana", "Dana Kruger", "People", "dept-hr", "Admin", NovaReach.Hub, paired = false, orgVerified = true, isSelf = false, seed = "dev-dana"),
    NovaMember("dev-grace", "Grace Kim", "People", "dept-hr", null, NovaReach.Hub, paired = false, orgVerified = true, isSelf = false, seed = "dev-grace"),
    NovaMember("dev-ben", "Ben Hartley", "Finance", "dept-fin", "Head", NovaReach.Offline, paired = false, orgVerified = true, isSelf = false, seed = "dev-ben"),
    NovaMember("dev-felix", "Felix Wagner", "Finance", "dept-fin", null, NovaReach.Unknown, paired = false, orgVerified = false, isSelf = false, seed = "dev-felix"),
    NovaMember("dev-lars", "Lars Meissner", "Security", "dept-sec", "Head", NovaReach.LAN, paired = false, orgVerified = true, isSelf = false, seed = "dev-lars"),
    NovaMember("dev-maya", "Maya Lindström", "Security", "dept-sec", null, NovaReach.Hub, paired = false, orgVerified = true, isSelf = false, seed = "dev-maya"),
    NovaMember("dev-tomas", "Tomás Rivera", "Field Ops", "dept-fop", null, NovaReach.Hub, paired = false, orgVerified = true, isSelf = false, seed = "dev-tomas"),
    NovaMember("dev-nadia", "Nadia Sultan", "Field Ops", "dept-fop", null, NovaReach.Offline, paired = false, orgVerified = false, isSelf = false, seed = "dev-nadia"),
    NovaMember("dev-self", "You", "Engineering", "dept-eng", null, NovaReach.Unknown, paired = false, orgVerified = true, isSelf = true, seed = "dev-self"),
)

private val DEPARTMENTS = listOf(
    NovaDepartment("dept-eng", "Engineering", 12, 2, mine = true, heads = listOf("Amara Okafor"), hidden = false),
    NovaDepartment("dept-sec", "Security", 3, 1, mine = false, heads = listOf("Lars Meissner"), hidden = false),
    NovaDepartment("dept-hr", "People", 9, 1, mine = false, heads = listOf("Dana Kruger"), hidden = false),
    NovaDepartment("dept-fin", "Finance", 6, 1, mine = false, heads = listOf("Ben Hartley"), hidden = false),
    NovaDepartment("dept-sec-internal", "Security Internal", 4, 0, mine = false, heads = emptyList(), hidden = true),
)

private val DOCS = listOf(
    NovaAnnouncement(
        id = "a-1", scopeLabel = "Whole company", departmentId = null,
        title = "Office closed on Friday",
        body = "The office is closed on Friday for the annual building maintenance. Work from home as usual.\n" +
            "Details and the floor plan for Monday: https://hub.acme.example/notice",
        author = "Dana Kruger", authorRole = "Admin",
        timeLine = "3 hours ago", edited = false, pinned = true, unread = false, highPriority = false, seed = "Dana Kruger",
    ),
    NovaAnnouncement(
        id = "a-2", scopeLabel = "Whole company", departmentId = null,
        title = "Security bulletin: approve the new signing phone",
        body = "Every administrator needs a second enrolled phone to approve console actions from this week. " +
            "See the facilities desk with your badge to register yours. This is the last banner week.",
        author = "Lars Meissner", authorRole = "Department head",
        timeLine = "5 hours ago", edited = false, pinned = false, unread = true, highPriority = true, seed = "Lars Meissner",
    ),
    NovaAnnouncement(
        id = "a-3", scopeLabel = "Engineering", departmentId = "dept-eng",
        title = "Release freeze Thursday 17:00",
        body = "No merges after 17:00 on Thursday while the release candidate is cut. Emergency fixes go through me directly, not through the queue.",
        author = "Amara Okafor", authorRole = "Department head",
        timeLine = "yesterday · edited", edited = true, pinned = false, unread = true, highPriority = false, seed = "Amara Okafor",
    ),
    NovaAnnouncement(
        id = "a-4", scopeLabel = "Finance", departmentId = "dept-fin",
        title = "Payslip portal moved",
        body = "The payslip portal moved to the new address this week. The old link stops working after the 15th.",
        author = "Ben Hartley", authorRole = "Department head",
        timeLine = "2 days ago", edited = false, pinned = false, unread = false, highPriority = false, seed = "Ben Hartley",
    ),
    NovaAnnouncement(
        id = "a-5", scopeLabel = "Whole company", departmentId = null,
        title = "Town hall recording",
        body = "The recording and the slide deck from Tuesday's town hall are up in the announcer tool. Questions welcome until Friday in the open thread.",
        author = "Dana Kruger", authorRole = "Admin",
        timeLine = "4 days ago", edited = false, pinned = false, unread = false, highPriority = false, seed = "Dana Kruger",
    ),
)

private val CHAIN_OK = listOf(
    NovaChainRow(
        name = "This device", kindLabel = "Device certificate · member",
        idShort = "•••• 4A21", metaLeft = "Renews in 12 days", metaRight = "Serial 1 824", tone = NovaTone.OK, stateWord = "Valid",
        note = "Signed by the organisation root. Its key is the one that signs handshakes.",
    ),
    NovaChainRow(
        name = "Relay signer", kindLabel = "Delegation · enrol:member, mail",
        idShort = "•••• C09D", metaLeft = "Renews in 46 days", metaRight = "Delegated 3 approvals", tone = NovaTone.INFO, stateWord = "Valid",
        note = "May write access for member devices only; wildcard never grants the admin role.",
    ),
    NovaChainRow(
        name = "Organisation root", kindLabel = "Root · pinned by the link",
        idShort = ORG.rootShort.takeLast(9), metaLeft = "Pinned forever", metaRight = "42 members", tone = NovaTone.INFO, stateWord = "Pinned",
        note = "The fingerprint your device checked the first minute it met the hub (TOFU).",
    ),
)

private val CHAIN_REVOKED = listOf(
    NovaChainRow(
        name = "This device", kindLabel = "Device certificate · member",
        idShort = "•••• 4A21", metaLeft = "Revoked today 09:14", metaRight = "Serial 1 824", tone = NovaTone.FAIL, stateWord = "Revoked", revoked = true,
        note = "Removed from this organisation — announcements, people and groups stopped.",
    ),
    // The chain above this device still stands; only the leaf was removed.
    NovaChainRow(
        name = "Relay signer", kindLabel = "Delegation · enrol:member, mail",
        idShort = "•••• C09D", metaLeft = "Renews in 46 days", metaRight = "Delegated 3 approvals", tone = NovaTone.NEUTRAL, stateWord = "Valid",
        note = null,
    ),
    NovaChainRow(
        name = "Organisation root", kindLabel = "Root · pinned by the link",
        idShort = ORG.rootShort.takeLast(9), metaLeft = "Pinned forever", metaRight = "42 members", tone = NovaTone.INFO, stateWord = "Pinned",
        note = null,
    ),
)

private val VAULT_OK = NovaVault(
    deviceLine = "Nova One · Engineering · device key •••• 4A21",
    roles = listOf("member"),
    scopes = listOf("chat:send", "mailbox:receive", "file:offer"),
    stepUpCode = "418992",
    stepUpTitle = "Approval code for one dangerous action",
    stepUpBody = "This code is derived from the challenge your hub raised and its nonce (HubStepUp.approvalCode). Your phone co-signs the same bytes under the approval domain, so a console request you did not raise shows a different code.",
    revocationTitle = "Revocation list v23",
    revocationBody = "Verified 5 minutes ago. Hashed list (FO-13 v3): this device is in none of the 2 entries.",
    revocationTone = NovaTone.OK,
)

private val ROSTERS = listOf(
    NovaRoster(
        groupId = "g-eng", name = "Engineering all-hands", department = "Engineering",
        version = 7, validUntilShort = "for 26 days", members = 12,
        memberSeeds = listOf("dev-amara", "dev-chen", "dev-priya", "dev-yuri", "dev-self"),
        state = NovaRosterState.Current, origin = "Signed roster statement v7 (root + chain)",
    ),
    NovaRoster(
        groupId = "g-inc", name = "Incident command", department = "Security",
        version = 3, validUntilShort = "for 2 days", members = 6,
        memberSeeds = listOf("dev-lars", "dev-maya", "dev-tomas"),
        state = NovaRosterState.Expiring, origin = "Offered by a colleague, verified on its own",
    ),
    NovaRoster(
        groupId = "g-fin", name = "Finance close", department = "Finance",
        version = 9, validUntilShort = "until Fri 18:00", members = 6,
        memberSeeds = listOf("dev-ben", "dev-felix"),
        state = NovaRosterState.Stale, origin = "Newer statement exists; this offer loses the tie-break",
    ),
)

private val BRIDGE_OK = NovaBridge(
    modeLabel = "Direct first · hub catches what it misses",
    modeTone = NovaTone.OK,
    note = "Messages ride your Wi-Fi when a colleague is nearby; the hub carries only what it must, sealed when the colleague's key is known.",
    stats = listOf(
        NovaBridgeStat("Payloads relayed", "134", NovaTone.INFO),
        NovaBridgeStat("Sealed in flight", "31", NovaTone.OK),
        NovaBridgeStat("Re-routed direct", "68", NovaTone.INFO),
        NovaBridgeStat("Refused (not in roster)", "2", NovaTone.WARN),
    ),
    honestLine = "The hub can read group messages while they wait. Private keys never leave this phone.",
)

// --- Scenario builds ---------------------------------------------------------------------------------

private fun baseSessions(): List<NovaSession> = listOf(
    NovaSession(
        id = "s-ws", peerLabel = "hub.acme.example", kind = NovaSessionKind.WS,
        stateLabel = "Connected", stateTone = NovaTone.OK,
        detail = "Hello verified · keep-alive 28 s · envelope buffer 0", progress = null,
    ),
    NovaSession(
        id = "s-rest", peerLabel = "Directory sync", kind = NovaSessionKind.REST,
        stateLabel = "Bearer cached", stateTone = NovaTone.INFO,
        detail = "Bearer 148 s to refresh · conditional GET · 0 pages pending", progress = null,
    ),
    NovaSession(
        id = "s-peer", peerLabel = "Priya Sharma", kind = NovaSessionKind.PEER,
        stateLabel = "Direct LAN", stateTone = NovaTone.OK,
        detail = "TLS pinned · key matches the statement Seal", progress = null,
    ),
)

private fun baseTelemetry(): List<NovaTelemetry> = listOf(
    NovaTelemetry("Messages today", "38", "12 sealed · 3 through the hub", NovaTone.OK, SPARK_RISING, icon = 1),
    NovaTelemetry("Hub throughput", "612 KB/s", "p95 one-way 84 ms", NovaTone.OK, SPARK_RISING, icon = 2),
    NovaTelemetry("Directory freshness", "just now", "v4 182 · 0 pages pending", NovaTone.INFO, SPARK_STEADY, icon = 3),
    NovaTelemetry("Reach events", "17", "13 found directly · 4 out of reach", NovaTone.INFO, SPARK_STEADY, icon = 4),
)

private val SEAL_PUBLISHED = NovaSealing(
    state = NovaSealingState.Published,
    fingerprintShort = "3F81 9A6B 2C4E",
    unverified = 0, readableOff = 0,
    sentence = "Ready. Colleagues can send you messages the hub cannot read.",
)

private val LEASE_VALID = NovaLease(
    kind = NovaLeaseKind.Valid,
    sentence = "Colleague trust valid until 12 Nov",
    daysLeft = 12,
)

private val SYNC_OK = NovaSyncWatermark(
    state = NovaSyncState.Ok,
    snapshotAge = "just now",
    version = "v4 182",
    visibleCount = 13,
    backoffSentence = null,
)

private val INVITE = NovaInvite(
    host = "hub.acme.example:443",
    tokenMasked = "•••• •••• 7Q",
    rootShort = ORG.rootShort,
    usesTls = true,
    organisationName = "Acme Corp",
    expires = "tomorrow 08:00 · one use",
)

private val ENROL_CURRENT: List<NovaEnrolStep> = listOf(
    NovaEnrolStep(NovaEnrolStepKind.Link, "Link pinned", "flash://enroll → server + one-use token + root pin.", NovaStepState.Done),
    NovaEnrolStep(NovaEnrolStepKind.Verify, "Proving the hub", "The hub answered with the pinned root key and the challenge signed right.", NovaStepState.Done),
    NovaEnrolStep(NovaEnrolStepKind.Keys, "Keys in flight", "Enrolment proof, device key pair and sealing key are being written.", NovaStepState.Current),
    NovaEnrolStep(NovaEnrolStepKind.Done, "Welcome", "Directory snapshot and announcements will arrive next.", NovaStepState.Pending),
)

private val ENROL_PENDING: List<NovaEnrolStep> = listOf(
    NovaEnrolStep(NovaEnrolStepKind.Link, "Link pinned", "The link's root pin and one-use token were checked.", NovaStepState.Current),
    NovaEnrolStep(NovaEnrolStepKind.Verify, "Proving the hub", "Flash will verify the hub answers with the organisation its key belongs to.", NovaStepState.Pending),
    NovaEnrolStep(NovaEnrolStepKind.Keys, "Keys in flight", "An access certificate and your sealing key will be written once.", NovaStepState.Pending),
    NovaEnrolStep(NovaEnrolStepKind.Done, "Welcome", "Your colleagues load next, and stay cached for when the hub is away.", NovaStepState.Pending),
)

// --- The world ---------------------------------------------------------------------------------------

/** Demo world factories. Public so a judge host can rebuild a page for a scenario on the fly. */
public object NovaMockWorld {

    private val linked = NovaWorld(
        scenario = NovaScenario.ActiveSealed,
        org = ORG,
        link = NovaLinkState.Online,
        strip = NovaStrip("Connected · sealed · updated just now", NovaTone.OK),
        banners = emptyList(),
        sessions = baseSessions(),
        telemetry = baseTelemetry(),
        sealing = SEAL_PUBLISHED,
        lease = LEASE_VALID,
        people = PEOPLE,
        departments = DEPARTMENTS,
        sync = SYNC_OK,
        news = DOCS,
        invite = null,
        enrolSteps = emptyList(),
        chain = CHAIN_OK,
        vault = VAULT_OK,
        rosters = ROSTERS,
        bridge = BRIDGE_OK,
    )

    public val ActiveSealed: NovaWorld = linked

    public val Unenrolled: NovaWorld = linked.copy(
        scenario = NovaScenario.Unenrolled,
        link = NovaLinkState.NotEnrolled,
        strip = NovaStrip("Not part of an organisation yet", NovaTone.NEUTRAL),
        banners = listOf(
            NovaBanner(
                title = "Join your organisation from a link",
                body = "Paste the flash://enroll link your administrator gave you, or open it from a message. Flash checks who signed it before anything is stored.",
                tone = NovaTone.INFO,
                actionLabel = "Redeem invite",
            ),
        ),
        sessions = emptyList(),
        telemetry = listOf(
            NovaTelemetry("Messages today", "—", "Nothing is sent until you enrol", NovaTone.NEUTRAL, SPARK_EMPTY, icon = 1),
            NovaTelemetry("Hub throughput", "—", "No organisation set", NovaTone.NEUTRAL, SPARK_EMPTY, icon = 2),
            NovaTelemetry("Directory", "—", "Loads once after your first connect", NovaTone.NEUTRAL, SPARK_EMPTY, icon = 3),
            NovaTelemetry("Pairings", "4", "Your direct paired devices are unaffected", NovaTone.INFO, SPARK_STEADY, icon = 4),
        ),
        sealing = NovaSealing(
            state = NovaSealingState.Idle, fingerprintShort = "", unverified = 0, readableOff = 0,
            sentence = "Your sealing key is created when you enrol. Messages between paired phones stay end-to-end encrypted regardless.",
        ),
        lease = NovaLease(NovaLeaseKind.NeverSynced, "No snapshot on this device yet", 0),
        people = emptyList(),
        departments = emptyList(),
        sync = NovaSyncWatermark(
            state = NovaSyncState.Never, snapshotAge = "never", version = "—",
            visibleCount = 0, backoffSentence = null,
        ),
        news = emptyList(),
        invite = INVITE,
        enrolSteps = ENROL_PENDING,
        chain = emptyList(),
        vault = VAULT_OK.copy(deviceLine = "No organisation yet — the vault fills after enrolment"),
        rosters = emptyList(),
        bridge = NovaBridge(
            modeLabel = "No organisation path yet", modeTone = NovaTone.NEUTRAL,
            note = "Direct pairing still works exactly as today: chats and file transfers never touched the hub.",
            stats = emptyList(),
            honestLine = "Nothing about the consumer app changes when you enrol; the Hub appears and Transfers moves to the top bar.",
        ),
    )

    public val Enrolling: NovaWorld = linked.copy(
        scenario = NovaScenario.Enrolling,
        link = NovaLinkState.Authenticating,
        strip = NovaStrip("Signing in · proving this device", NovaTone.INFO),
        banners = emptyList(),
        sessions = listOf(
            NovaSession(
                id = "s-auth", peerLabel = "hub.acme.example", kind = NovaSessionKind.REST,
                stateLabel = "Challenge sent", stateTone = NovaTone.INFO,
                detail = "Signed /auth/verify — audience bound to the root pin", progress = 0.65f,
            ),
        ),
        telemetry = baseTelemetry().map { it.copy(spark = SPARK_EMPTY, value = "—", subline = "Collection starts next frame") },
        sealing = NovaSealing(
            state = NovaSealingState.Publishing, fingerprintShort = "3F81 9A6B 2C4E", unverified = 0, readableOff = 0,
            sentence = "Getting ready. Flash will keep trying until the hub holds the key.",
        ),
        invite = INVITE,
        enrolSteps = ENROL_CURRENT,
    )

    public val RelayDegraded: NovaWorld = linked.copy(
        scenario = NovaScenario.RelayDegraded,
        link = NovaLinkState.Online,
        strip = NovaStrip("Connected on a slow path · some messages take longer", NovaTone.WARN, actionLabel = "Why?"),
        banners = listOf(
            NovaBanner(
                title = "The company server is reachable but slow",
                body = "Direct Wi-Fi to nearby colleagues is unaffected. Messages that must wait at the hub may take a minute; sealed ones queue silently.",
                tone = NovaTone.WARN,
                actionLabel = "Retry now",
            ),
            NovaBanner(
                title = "Announcement read receipts paused",
                body = "Read marks for announcements queue until the fast path recovers. Nothing is lost.",
                tone = NovaTone.INFO,
                dismissible = true,
            ),
        ),
        sessions = listOf(
            NovaSession(
                id = "s-ws", peerLabel = "hub.acme.example", kind = NovaSessionKind.WS,
                stateLabel = "Degraded", stateTone = NovaTone.WARN,
                detail = "Keep-alive replies are late; backoff 20 s → 40 s", progress = null,
            ),
            NovaSession(
                id = "s-rest", peerLabel = "Directory sync", kind = NovaSessionKind.REST,
                stateLabel = "Retry scheduled", stateTone = NovaTone.WARN,
                detail = "Retrying in 40 s · jittered exponential backoff", progress = 0.4f,
            ),
            NovaSession(
                id = "s-peer", peerLabel = "Chen Wei", kind = NovaSessionKind.PEER,
                stateLabel = "Direct LAN", stateTone = NovaTone.OK,
                detail = "Fast path · key matches the statement", progress = null,
            ),
        ),
        telemetry = listOf(
            NovaTelemetry("Messages today", "38", "12 sealed · 3 waiting at the hub", NovaTone.WARN, SPARK_DIPPING, icon = 1),
            NovaTelemetry("Hub throughput", "132 KB/s", "p95 one-way 640 ms", NovaTone.WARN, SPARK_DIPPING, icon = 2),
            NovaTelemetry("Directory freshness", "2 min", "v4 182 · next page pending", NovaTone.INFO, SPARK_STEADY, icon = 3),
            NovaTelemetry("Reach events", "17", "13 found directly · 4 out of reach", NovaTone.INFO, SPARK_STEADY, icon = 4),
        ),
        sealing = SEAL_PUBLISHED.copy(sentence = "Still sealed. Sealed payloads queue at the hub until you return."),
        sync = SYNC_OK.copy(snapshotAge = "2 minutes ago", backoffSentence = "Next page retries in 40 s (jittered backoff)"),
    )

    public val CertRevoked: NovaWorld = linked.copy(
        scenario = NovaScenario.CertRevoked,
        link = NovaLinkState.Revoked,
        strip = NovaStrip("Removed by your organisation", NovaTone.FAIL, actionLabel = "Sign out"),
        banners = listOf(
            NovaBanner(
                title = "Your organisation removed this device",
                body = "Organisation news, people and groups are no longer available. Chats with people you paired with directly are not affected. Enrol again with a new link to come back.",
                tone = NovaTone.FAIL,
                actionLabel = "Re-enrol",
                dismissible = true,
            ),
        ),
        sessions = listOf(
            NovaSession(
                id = "s-rev", peerLabel = "hub.acme.example", kind = NovaSessionKind.WS,
                stateLabel = "Revoked", stateTone = NovaTone.FAIL,
                detail = "The hub said it cannot accept this device again. Reconnect blocked.", progress = null,
            ),
        ),
        telemetry = listOf(
            NovaTelemetry("Messages today", "11", "Sent before removal", NovaTone.FAIL, SPARK_DIPPING, icon = 1),
            NovaTelemetry("Hub throughput", "0", "No path until you re-enrol", NovaTone.FAIL, SPARK_EMPTY, icon = 2),
            NovaTelemetry("Directory", "2 h", "Snapshot from 2 hours ago, still readable", NovaTone.NEUTRAL, SPARK_STEADY, icon = 3),
            NovaTelemetry("Pairings", "4", "Direct chats and transfers keep working", NovaTone.OK, SPARK_STEADY, icon = 4),
        ),
        sealing = NovaSealing(
            state = NovaSealingState.Refused, fingerprintShort = "3F81 9A6B 2C4E", unverified = 0, readableOff = 3,
            sentence = "The hub did not accept this device's key. Ask your administrator.",
        ),
        lease = NovaLease(NovaLeaseKind.Expired, "Snapshot shown · trust lease expired", 0),
        people = PEOPLE.filter { it.orgVerified }, // cache is still honest: only rows the snapshot certified
        sync = NovaSyncWatermark(
            state = NovaSyncState.FailedOffline, snapshotAge = "2 hours ago", version = "v4 181",
            visibleCount = 11, backoffSentence = "Sync paused until you enrol again",
        ),
        news = DOCS.take(2).map { it.copy(unread = false, pinned = false) },
        chain = CHAIN_REVOKED,
        vault = VAULT_OK.copy(revocationTitle = "Revocation list v23", revocationBody = "Verified 5 minutes ago. This device sits in entry 2204/9005.", revocationTone = NovaTone.FAIL),
        rosters = emptyList(),
        bridge = NovaBridge(
            modeLabel = "Hub path closed", modeTone = NovaTone.FAIL,
            note = "Your direct sessions and file transfers are untouched. The hub refuses this device until it re-enrols.",
            stats = listOf(NovaBridgeStat("Direct sessions", "4", NovaTone.OK), NovaBridgeStat("Hub payloads", "0", NovaTone.FAIL)),
            honestLine = "The hub will tell the next client that this device is gone, so nobody writes to a dead path.",
        ),
    )

    public val OffEmpty: NovaWorld = linked.copy(
        scenario = NovaScenario.OffEmpty,
        link = NovaLinkState.Offline,
        strip = NovaStrip("Offline · showing information from 2 hours ago", NovaTone.WARN, actionLabel = "Retry"),
        banners = listOf(
            NovaBanner(
                title = "Not connected to your organisation",
                body = "No network. Cached colleagues and announcements stay readable. Messages to nearby colleagues still work.",
                tone = NovaTone.WARN,
                actionLabel = "Retry now",
            ),
        ),
        sessions = listOf(
            NovaSession(
                id = "s-off", peerLabel = "hub.acme.example", kind = NovaSessionKind.WS,
                stateLabel = "Offline", stateTone = NovaTone.WARN,
                detail = "Not connected · retrying when the network returns", progress = null,
            ),
        ),
        telemetry = listOf(
            NovaTelemetry("Messages today", "38", "12 sealed · 3 through the hub (queued)", NovaTone.WARN, SPARK_STEADY, icon = 1),
            NovaTelemetry("Hub throughput", "0", "Waiting for the network", NovaTone.WARN, SPARK_EMPTY, icon = 2),
            NovaTelemetry("Directory freshness", "2 hours", "Snapshot v4 182", NovaTone.WARN, SPARK_STEADY, icon = 3),
            NovaTelemetry("Reach events", "0", "No network observed in an hour", NovaTone.WARN, SPARK_EMPTY, icon = 4),
        ),
        sync = NovaSyncWatermark(
            state = NovaSyncState.FailedOffline,
            snapshotAge = "2 hours ago",
            version = "v4 182",
            visibleCount = 13,
            backoffSentence = "Retries when the network returns",
        ),
    )

    /** The judges' six worlds in bar order. */
    public val all: List<NovaWorld> = listOf(Unenrolled, Enrolling, ActiveSealed, RelayDegraded, CertRevoked, OffEmpty)

    public fun worldFor(scenario: NovaScenario): NovaWorld = when (scenario) {
        NovaScenario.Unenrolled -> Unenrolled
        NovaScenario.Enrolling -> Enrolling
        NovaScenario.ActiveSealed -> ActiveSealed
        NovaScenario.RelayDegraded -> RelayDegraded
        NovaScenario.CertRevoked -> CertRevoked
        NovaScenario.OffEmpty -> OffEmpty
    }
}
