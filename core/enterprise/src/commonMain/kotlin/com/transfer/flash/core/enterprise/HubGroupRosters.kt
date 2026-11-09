package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashProbe
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.EcdsaVerifier
import com.transfer.flash.core.hubprotocol.certs.GroupRosterStatement
import com.transfer.flash.core.hubprotocol.certs.RevocationList
import com.transfer.flash.core.hubprotocol.certs.RosterStatus
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.persistence.db.dao.OrgDirectoryDao
import com.transfer.flash.core.persistence.db.entity.HubGroupRosterEntity
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A hub group's verified roster as the LAN gate reads it (Plan B section 10). */
public data class HubGroupRoster(
    val groupId: String,
    val groupName: String,
    val version: Long,
    val memberDeviceIds: Set<String>,
    val adminDeviceIds: Set<String>,
    val validUntilMs: Long,
)

/** What a peer is offered about one group: the exact signed statement and the delegation chain it verifies with. */
public data class HubRosterOfferPayload(val groupId: String, val statementJson: String, val delegationChainJson: String)

public enum class RosterAdoption { ADOPTED, STALE, INVALID, NOT_ENROLLED, NOT_A_MEMBER, SENDER_NOT_MEMBER, TOO_MANY }

/** What the chat reads and feeds of the hub groups' rosters (implemented by [HubGroupRosters]; faked in chat tests). */
public interface HubGroupRosterView {
    /** The group's roster, or null when it is unknown or its validity window is closed (never "everybody"). */
    public fun current(groupId: String, nowMs: Long): HubGroupRoster?

    /** True when a roster statement is stored for [groupId], current or not. */
    public fun isHubGroup(groupId: String): Boolean

    /** True when a known revocation bars use of this roster, independent of the peer's other trust paths. */
    public fun revocationBlocks(groupId: String): Boolean = false

    /** False while a linked group's revocation state is unavailable, so Hub delivery can retry. */
    public fun revocationStateReady(): Boolean = true

    /** The stored statement of [groupId] for a peer, only while it is current. */
    public fun offerFor(groupId: String, nowMs: Long): HubRosterOfferPayload?

    public fun groupIds(): Set<String>

    /** Reloads the in-memory set from storage. */
    public suspend fun refresh()

    /** Forgets every roster (leaving the organisation). */
    public suspend fun clear()

    /** Verifies a colleague's offer on its own and adopts it only if it is newer; the sender must be a member. */
    public suspend fun adopt(offer: HubRosterOfferPayload, offeredBy: String? = null): RosterAdoption
}

/**
 * The hub groups' roster statements, in memory for the synchronous LAN gate (Plan B Phase B4, plan section 10).
 *
 * Statements arrive from the directory sync (already verified there) or from a colleague's offer ([adopt]); this class
 * verifies an offer on its own and never lets a lower version replace a higher one. [current] answers only while the
 * statement's validity window is open: an expired roster means nobody is accepted for that group.
 */
@FlashInternalApi
public class HubGroupRosters(
    private val dao: OrgDirectoryDao,
    private val credentialsProvider: () -> EnrollmentCredentials?,
    private val verifier: EcdsaVerifier,
    private val localDeviceId: () -> String?,
    private val revocationsProvider: () -> RevocationList? = { null },
    private val clock: () -> Long = { SystemTimeSource.nowMs() },
    private val permitsOrg: () -> Boolean = { true },
    private val revocationLoaded: () -> Boolean = { true },
) : HubGroupRosterView {
    private val lock = Mutex()

    @kotlin.concurrent.Volatile
    private var index: Map<String, Held> = emptyMap()

    private class Held(
        val roster: HubGroupRoster,
        val entity: HubGroupRosterEntity,
        val issuer: DelegationCert?,
    ) {
        @kotlin.concurrent.Volatile
        var filtered: Pair<RevocationList, HubGroupRoster>? = null
    }

    /** Reloads the in-memory set from storage (after a directory sync, at start). Unreadable rows are skipped. */
    override suspend fun refresh() {
        lock.withLock {
            val next = HashMap<String, Held>()
            val creds = credentialsProvider()
            val now = clock()
            for (row in dao.allGroupRosters()) {
                val st = decode(row.statementJson) ?: continue
                if (st.groupId != row.groupId) continue
                val chain = decodeChain(row.delegationChainJson)
                var issuer: DelegationCert? = null
                if (creds != null && chain != null) {
                    st.verify(chain, creds.rootPublicKey, verifier, now, onVerifiedDelegation = { issuer = it })
                }
                // Keep the group marker even when the stored roster cannot grant access.
                next[row.groupId] = Held(toRoster(st), row, issuer)
            }
            index = next
        }
    }

    override suspend fun clear() {
        lock.withLock { index = emptyMap() }
    }

    /** The group's roster, or null when it is unknown or its window is closed (never "everybody"). */
    override fun current(groupId: String, nowMs: Long): HubGroupRoster? {
        val held = index[groupId] ?: return null
        val issuer = held.issuer ?: return null
        val roster = held.roster
        if (nowMs > roster.validUntilMs || !permitsOrg() || credentialsProvider()?.rootPublicKey != issuer.rootPublicKey) return null
        val list = revocationsProvider() ?: return roster
        if (list.isDelegationRevoked(issuer.serial)) return null
        if (list.revokedDeviceIds.isEmpty() && list.revokedDeviceHashes.isEmpty()) return roster
        held.filtered?.let { (applied, filtered) -> if (applied === list) return filtered }
        val members = roster.memberDeviceIds.filterNotTo(mutableSetOf(), list::revokesDevice)
        return roster.copy(memberDeviceIds = members, adminDeviceIds = roster.adminDeviceIds.intersect(members))
            .also { held.filtered = list to it }
    }

    override fun isHubGroup(groupId: String): Boolean = index.containsKey(groupId)

    override fun revocationBlocks(groupId: String): Boolean {
        if (!permitsOrg()) return true
        val issuer = index[groupId]?.issuer ?: return false
        return revocationsProvider()?.isDelegationRevoked(issuer.serial) == true
    }

    override fun revocationStateReady(): Boolean = revocationLoaded()

    /** The stored statement of [groupId] for a peer, only while it is current. */
    override fun offerFor(groupId: String, nowMs: Long): HubRosterOfferPayload? {
        val h = index[groupId] ?: return null
        val roster = current(groupId, nowMs) ?: return null
        if (localDeviceId() !in roster.memberDeviceIds) return null
        return HubRosterOfferPayload(groupId, h.entity.statementJson, h.entity.delegationChainJson)
    }

    override fun groupIds(): Set<String> = index.keys

    /** Verifies a colleague's offer on its own (root, chain, signature, window) and adopts it only if it is newer. */
    override suspend fun adopt(offer: HubRosterOfferPayload, offeredBy: String?): RosterAdoption = lock.withLock {
        val creds = credentialsProvider() ?: return RosterAdoption.NOT_ENROLLED
        if (!permitsOrg()) return RosterAdoption.NOT_ENROLLED
        val statement = decode(offer.statementJson) ?: return RosterAdoption.INVALID
        if (statement.groupId != offer.groupId) return RosterAdoption.INVALID
        val chain = decodeChain(offer.delegationChainJson) ?: return RosterAdoption.INVALID
        val revocations = revocationsProvider()
        var issuer: DelegationCert? = null
        val status = statement.verify(
            delegationChain = chain,
            rootPublicKey = creds.rootPublicKey,
            verifier = verifier,
            nowMs = clock(),
            revocations = revocations,
            onVerifiedDelegation = { issuer = it },
        )
        if (status != RosterStatus.OK) {
            FlashProbe.emit("org.roster.rejected", "group" to offer.groupId, "reason" to status.name, "via" to "gossip")
            return RosterAdoption.INVALID
        }
        if (offeredBy != null && (!statement.isMember(offeredBy) || revocations?.revokesDevice(offeredBy) == true)) {
            return RosterAdoption.SENDER_NOT_MEMBER
        }
        val me = localDeviceId()
        if (me == null || !statement.isMember(me) || revocations?.revokesDevice(me) == true) return RosterAdoption.NOT_A_MEMBER
        val held = index[offer.groupId]
        if (held != null && held.roster.version >= statement.version) return RosterAdoption.STALE
        if (held == null && index.size >= MAX_GROUPS) return RosterAdoption.TOO_MANY
        val entity = HubGroupRosterEntity(statement.groupId, statement.version, offer.statementJson, offer.delegationChainJson, statement.validUntilMs)
        dao.upsertGroupRosters(listOf(entity))
        index = index + (statement.groupId to Held(toRoster(statement), entity, issuer))
        FlashProbe.emit("org.roster.adopted", "group" to statement.groupId, "version" to statement.version, "via" to "gossip")
        RosterAdoption.ADOPTED
    }

    private fun decode(json: String): GroupRosterStatement? =
        if (json.length > MAX_JSON || jsonTooDeep(json)) null else HubRestCodec.decodeOrNull<GroupRosterStatement>(json, MAX_JSON)

    private fun decodeChain(json: String): List<DelegationCert>? =
        if (json.length > MAX_JSON || jsonTooDeep(json)) null
        else HubRestCodec.decodeOrNull<List<DelegationCert>>(json, MAX_JSON)?.takeIf { it.size <= MAX_CHAIN }

    private fun toRoster(st: GroupRosterStatement): HubGroupRoster = HubGroupRoster(
        groupId = st.groupId,
        groupName = st.groupName,
        version = st.version,
        memberDeviceIds = st.memberDeviceIds.toSet(),
        adminDeviceIds = st.adminDeviceIds.toSet(),
        validUntilMs = st.validUntilMs,
    )

    public companion object {
        public const val MAX_JSON: Int = 32 * 1024
        public const val MAX_CHAIN: Int = 8
        public const val MAX_GROUPS: Int = 256
    }
}

/**
 * True when [json] nests objects or arrays deeper than [max] (brackets inside strings do not count). A roster statement and
 * a delegation chain are flat, so a deeper document is hostile; it is refused before a recursive decoder sees it.
 */
internal fun jsonTooDeep(json: String, max: Int = 16): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    for (c in json) {
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            continue
        }
        when (c) {
            '"' -> inString = true
            '{', '[' -> if (++depth > max) return true
            '}', ']' -> depth--
        }
    }
    return false
}
