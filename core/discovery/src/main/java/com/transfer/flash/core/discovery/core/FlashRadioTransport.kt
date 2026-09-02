package com.transfer.flash.core.discovery.core

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import kotlinx.coroutines.flow.Flow

/**
 * Identity payload a device advertises about itself (plan C3.2, extended P3.5-A3).
 * Carried in radio-specific form: NSD TXT records today; BLE adv payload /
 * Wi-Fi Aware publish extras later. [deviceModel] feeds the Nearby tab rows;
 * [protocolVersion] lets peers hide incompatible versions pre-connection.
 *
 * P3.5 additions (both defaulted so existing callers compile unchanged):
 * - [capabilities]: capability flags advertised via TXT `caps` (e.g. `kiosk`
 *   willingness from RECEIVE_KIOSK mode). INFORMATIONAL ONLY on the wire —
 *   mDNS/DNS-SD is unauthenticated (RFC 6762), so advertised caps are a hint,
 *   never an access decision; enforcement happens at connect time (C3.10 seam).
 * - [fingerprintPrefix]: first 8 hex chars of this device's identity
 *   fingerprint (C2.3). May be absent until pairing lands; peers use it as a
 *   pre-connection consistency cross-check only (see zeroconf spoofing threat
 *   model in docs/security notes / plan P3.5 research).
 */
public data class FlashAdvertisedIdentity(
    val deviceId: FlashDeviceId,
    val friendlyName: String,
    val deviceModel: String,
    val protocolVersion: Int,
    val capabilities: Set<String> = emptySet(),
    val fingerprintPrefix: String? = null,
)

/**
 * Radio-agnostic discovery events (plan C3.1 target abstraction).
 *
 * Semantics every implementation MUST honor:
 * - [Found] exactly once per peer per browsing session, then [Updated] on any
 *   address/name/version change.
 * - [Presence] whenever the radio re-confirms an ALREADY-known peer with no
 *   field change. Emitting this is MANDATORY for any transport whose consumer
 *   ages peers out on a TTL: without it a live-but-quiet peer looks departed
 *   (see [Presence] KDoc).
 * - [Lost] when the radio reports loss AND when the presence sweeper ages an
 *   endpoint out (plan C3.5) — consumers must treat both identically.
 * - Events are hot flows; subscribers see only live traffic (no replay).
 */
public sealed interface FlashTransportEvent {
    public data class Found(val endpoint: FlashDiscoveredEndpoint) : FlashTransportEvent
    public data class Updated(val endpoint: FlashDiscoveredEndpoint) : FlashTransportEvent

    /**
     * Liveness heartbeat: this peer is STILL here and nothing about it changed.
     *
     * Exists because presence and change are different signals. A directory
     * dedups repeated sightings down to "no change" and therefore emits neither
     * [Found] nor [Updated] — but a downstream TTL sweeper reads that silence as
     * "gone" and evicts a perfectly healthy peer (the historical bug: a device
     * appeared once, then vanished ~grace-window later and never returned,
     * because every later sighting also deduped to no-change).
     *
     * Consumers MUST treat this as a presence refresh only: bump `lastSeenAt`,
     * do NOT re-emit it as a user-visible transition. A consumer that has
     * already dropped the peer MAY treat it as a re-[Found] to self-heal.
     */
    public data class Presence(val endpoint: FlashDiscoveredEndpoint) : FlashTransportEvent
    public data class Lost(val deviceId: FlashDeviceId, val serviceName: String?) : FlashTransportEvent

    /**
     * @param browsing whether THIS transport is currently browsing. Reported
     *   truthfully (an advertising-only transport reports `false`) so consumers
     *   can watchdog a radio that gave up; the human-readable [message] carries
     *   the advertising detail.
     */
    public data class StateChanged(val browsing: Boolean, val message: String) : FlashTransportEvent
}

/**
 * Seam behind which every radio transport lives (plan C3.1). One implementation
 * per medium: NSD/LAN now (see `nsd` package), Wi-Fi Direct / Wi-Fi Aware /
 * BLE-presence later (plan C3.6-C3.8). Implementations own their radio lifecycle
 * but MUST NOT hold UI types.
 */
public interface FlashRadioTransport {
    /** Transport identifier used by [CompositeDiscovery] for priority/dedup reporting. */
    public val transportName: String

    public val events: Flow<FlashTransportEvent>

    /**
     * Advertise this device using its own identity details until [stop].
     * Idempotent: re-invoking while advertising updates the advertised record.
     */
    public suspend fun startAdvertising(port: Int, identity: FlashAdvertisedIdentity): FlashResult<Unit>

    /**
     * Browse CONTINUOUSLY until [stop]: peers already present appear within
     * seconds; peers joining later appear without any caller action; internal
     * start failures restart with capped retries (plan C3.3).
     */
    public suspend fun startBrowsing(): FlashResult<Unit>

    /**
     * Forces a browse restart even when this transport believes it is already
     * browsing. [startBrowsing] is idempotent by design (a no-op while active),
     * which makes it useless for recovery: a radio whose browse died silently —
     * Wi-Fi ↔ hotspot transition, doze, an OEM mDNS stack that stopped
     * delivering — still reports itself as browsing and ignores the restart.
     * Callers use this on a connectivity change / screen-on / watchdog tick.
     *
     * Default implementation delegates to [startBrowsing] for transports with
     * no forced-restart concept.
     */
    public suspend fun restartBrowsing(): FlashResult<Unit> = startBrowsing()

    /** Stops advertising and browsing and releases radio resources. Idempotent. */
    public suspend fun stop(): FlashResult<Unit>

    /**
     * Applies a discovery-mode policy (plan P3.5-B2). Default no-op so radios
     * that do not support mode tuning (future transports) compile unchanged;
     * [CompositeDiscovery] fans out blindly to every transport.
     *
     * Implementations SHOULD honor: advertise toggle immediately, duty-cycle
     * and backoff knobs at their next loop iteration (live-when-safe rule).
     */
    public suspend fun setMode(policy: DiscoveryModePolicy) {}
}
