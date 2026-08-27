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
 * - [Lost] when the radio reports loss AND when the presence sweeper ages an
 *   endpoint out (plan C3.5) — consumers must treat both identically.
 * - Events are hot flows; subscribers see only live traffic (no replay).
 */
public sealed interface FlashTransportEvent {
    public data class Found(val endpoint: FlashDiscoveredEndpoint) : FlashTransportEvent
    public data class Updated(val endpoint: FlashDiscoveredEndpoint) : FlashTransportEvent
    public data class Lost(val deviceId: FlashDeviceId, val serviceName: String?) : FlashTransportEvent
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
