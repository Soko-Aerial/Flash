package com.transfer.flash.ui.hub.competition.apex

import androidx.compose.runtime.Immutable
import com.transfer.flash.core.messaging.model.HubAnnouncementUi
import com.transfer.flash.core.messaging.model.HubDepartmentUi
import com.transfer.flash.core.messaging.model.HubGroupUi
import com.transfer.flash.core.messaging.model.HubPersonUi

/**
 * APEX ZENITH — Presentation Models for the Hub Competition Suite.
 *
 * All models are pure UI data carriers, 100% immutable and preview-safe.
 */

// -------------------------------------------------------------------------------------------------
// Competition / Judge Vocabulary
// -------------------------------------------------------------------------------------------------

public enum class ApexScenario(val label: String, val description: String) {
    ACTIVE_SEALED("Active & Sealed", "Signed in, directory fresh, E2E cryptographic sealing active."),
    RELAY_DEGRADED("Relay Degraded", "Hub connection intermittent; relay fallback active with queued packets."),
    ENROLLING("Enrolling", "Redeeming enterprise invitation token and verifying device key pair."),
    UNENROLLED("Unenrolled", "Device is fresh and unenrolled with no organization binding."),
    CERT_REVOKED("Cert Revoked", "Device certificate revoked by organization CRL seq #49102."),
    EMPTY_OFFLINE("Empty / Offline", "No local cache, offline state with zero synchronized records."),
}

public enum class ApexFormFactor(val label: String) {
    MOBILE_COMPACT("Phone (412dp)"),
    DESKTOP_SPLIT("Desktop Split (1180dp)"),
}

public enum class ApexTheme(val label: String) {
    DARK("Pulse Dark"),
    LIGHT("Pulse Light"),
}

public enum class ApexMotionMode(val label: String) {
    HIGH_MOTION("High Motion (60fps Spring)"),
    REDUCED_MOTION("Reduced (Power-Save / Flat)"),
}

public enum class ApexPage(val label: String, val shortName: String, val subtitle: String) {
    COMMAND("Command Center", "Command", "Telemetry & Sealing"),
    DIRECTORY("Directory & Peers", "People", "Organization Colleague Explorer"),
    ANNOUNCEMENTS("Announcements", "Broadcast", "Organization Bulletins"),
    VAULT("Trust Vault", "Security", "Certificates & Cryptographic Identity"),
    ROSTERS("Rosters & Bridge", "Rosters", "Enterprise Groups & Relay Port"),
}

// -------------------------------------------------------------------------------------------------
// Domain Presentation Models
// -------------------------------------------------------------------------------------------------

@Immutable
public data class ApexOrgInfo(
    val name: String,
    val host: String,
    val rootKeyFingerprint: String,
    val memberCount: Int,
    val departmentCount: Int,
    val groupCount: Int,
    val myRole: String,
    val isSealed: Boolean,
)

public enum class ApexLinkState(val label: String) {
    CONNECTED("Connected (TLS 1.3)"),
    AUTHENTICATING("Authenticating Key"),
    RELAY_FALLBACK("Relay Fallback"),
    RECONNECTING("Reconnecting..."),
    OFFLINE("Offline"),
    REVOKED("Access Revoked"),
}

@Immutable
public data class ApexSealingStatus(
    val isSealed: Boolean,
    val cipherSuite: String,
    val keyRotationExpiresMs: Long,
    val activePinsCount: Int,
    val verifiedPinCount: Int,
    val lastKeySyncMs: Long,
    val hubFingerprint: String,
)

@Immutable
public data class ApexRelayMetrics(
    val activeConnections: Int,
    val outboxQueued: Int,
    val latencyMs: Int,
    val droppedPackets: Int,
    val relayPort: Int,
    val throughputKbps: Long,
    val socketHealthPct: Int,
)

@Immutable
public data class ApexSystemNotice(
    val id: String,
    val title: String,
    val message: String,
    val tone: ApexTone,
    val timestampMs: Long,
    val actionLabel: String? = null,
    val dismissible: Boolean = false,
)

@Immutable
public data class ApexSyncWatermark(
    val sequenceNumber: Long,
    val lastSyncedAtMs: Long,
    val status: String,
    val snapshotHash: String,
)

@Immutable
public data class ApexPeerDetail(
    val deviceId: String,
    val displayName: String,
    val departmentName: String,
    val roleTitle: String,
    val isHead: Boolean,
    val isAdmin: Boolean,
    val reachability: String, // "LAN", "HUB Relay", "Offline"
    val isOnline: Boolean,
    val isVerified: Boolean,
    val isPaired: Boolean,
    val isSelf: Boolean,
    val ipAddress: String,
    val publicKeyFingerprint: String,
    val encryptionAlgorithm: String,
    val lastSeenMs: Long,
)

@Immutable
public data class ApexCertNode(
    val id: String,
    val subjectName: String,
    val issuerName: String,
    val level: String, // "ROOT CA", "HUB DELEGATION", "MEMBER STATEMENT", "DEVICE KEY"
    val serialNumber: String,
    val fingerprint: String,
    val validFromMs: Long,
    val validUntilMs: Long,
    val isValid: Boolean,
    val isRevoked: Boolean,
    val algorithm: String,
)

@Immutable
public data class ApexGroupRosterItem(
    val groupId: String,
    val title: String,
    val departmentName: String,
    val memberCount: Int,
    val onlineCount: Int,
    val requiresApproval: Boolean,
    val isJoined: Boolean,
    val isPending: Boolean,
    val rosterSignatureVerified: Boolean,
    val rosterVersion: Long,
    val rosterAdminLock: Boolean,
)

@Immutable
public data class ApexRelayPacketTrace(
    val traceId: String,
    val recipientDeviceId: String,
    val payloadType: String,
    val sizeBytes: Long,
    val latencyMs: Int,
    val status: String, // "DELIVERED", "IN_FLIGHT", "BUFFERED"
    val timestampMs: Long,
)

/**
 * Complete immutable snapshot of the Hub Deck state.
 */
@Immutable
public data class ApexHubDeck(
    val scenario: ApexScenario,
    val org: ApexOrgInfo,
    val linkState: ApexLinkState,
    val sealing: ApexSealingStatus,
    val relay: ApexRelayMetrics,
    val notices: List<ApexSystemNotice>,
    val syncWatermark: ApexSyncWatermark,
    val departments: List<HubDepartmentUi>,
    val colleagues: List<ApexPeerDetail>,
    val announcements: List<HubAnnouncementUi>,
    val canPostAnnouncements: Boolean,
    val certChain: List<ApexCertNode>,
    val groupRosters: List<ApexGroupRosterItem>,
    val packetTraces: List<ApexRelayPacketTrace>,
    val nowMs: Long,
)
