package com.transfer.flash.core.calling

import com.shepeliev.webrtckmp.BundlePolicy
import com.shepeliev.webrtckmp.IceServer
import com.shepeliev.webrtckmp.IceTransportPolicy
import com.shepeliev.webrtckmp.RtcConfiguration
import com.shepeliev.webrtckmp.RtcpMuxPolicy

/**
 * Policy governing how an off-LAN Hub-signaled call constructs its WebRTC ICE transport.
 */
public enum class CallMediaPathPolicy {
    /** Allow host/srflx candidates without relay servers (LAN or direct reflexive connectivity only). */
    DIRECT_ONLY,

    /** Prefer direct/reflexive connectivity and fall back to TURN relay when symmetric NAT blocks direct media. */
    DIRECT_PREFERRED_RELAY_FALLBACK,

    /** Force WebRTC ICE transport exclusively through configured TURN relays (`IceTransportPolicy.Relay`). */
    RELAY_ONLY,
}

/**
 * Client-side ICE server descriptor decoded from Hub `/api/v1/calls/ice-config` or test configuration.
 */
public data class CallIceServerSpec(
    public val urls: List<String>,
    public val username: String = "",
    public val credential: String = "",
) {
    init {
        require(urls.isNotEmpty() && urls.all { it.isNotBlank() }) {
            "CallIceServerSpec requires at least one non-blank URL"
        }
    }

    public fun toWebRtcIceServer(): IceServer = IceServer(
        urls = urls,
        username = username,
        password = credential,
    )
}

/**
 * Result of checking whether an off-LAN call action can be offered/started for a peer.
 */
public sealed interface OffLanCallEligibility {
    public data class Supported(
        public val iceServers: List<CallIceServerSpec>,
        public val mediaPolicy: CallMediaPathPolicy,
        public val hasRelayServer: Boolean,
    ) : OffLanCallEligibility

    public data class Unavailable(
        public val reason: Reason,
    ) : OffLanCallEligibility {
        public enum class Reason {
            HUB_DISCONNECTED,
            HUB_CALLING_UNSUPPORTED,
            PEER_NOT_ENROLLED_FOR_HUB,
            PEER_REVOKED,
            NO_COMPATIBLE_ROUTE,
        }
    }
}

/**
 * Snapshot of Hub and peer state needed to decide whether off-LAN calling is usable and truthful to surface in UI.
 */
public data class CallRouteCapabilities(
    public val isDirectLanReachable: Boolean,
    public val isHubConnected: Boolean,
    public val hubSupportsOffLanCalling: Boolean,
    public val peerSupportsOffLanCalling: Boolean,
    public val isPeerRevoked: Boolean,
    public val mediaPolicy: CallMediaPathPolicy = CallMediaPathPolicy.DIRECT_PREFERRED_RELAY_FALLBACK,
    public val iceServers: List<CallIceServerSpec> = emptyList(),
) {
    public fun evaluateOffLanEligibility(): OffLanCallEligibility {
        if (isPeerRevoked) {
            return OffLanCallEligibility.Unavailable(OffLanCallEligibility.Unavailable.Reason.PEER_REVOKED)
        }
        if (!isHubConnected) {
            return OffLanCallEligibility.Unavailable(OffLanCallEligibility.Unavailable.Reason.HUB_DISCONNECTED)
        }
        if (!hubSupportsOffLanCalling) {
            return OffLanCallEligibility.Unavailable(OffLanCallEligibility.Unavailable.Reason.HUB_CALLING_UNSUPPORTED)
        }
        if (!peerSupportsOffLanCalling) {
            return OffLanCallEligibility.Unavailable(OffLanCallEligibility.Unavailable.Reason.PEER_NOT_ENROLLED_FOR_HUB)
        }
        val hasRelay = iceServers.any { spec ->
            spec.urls.any { it.startsWith("turn:", ignoreCase = true) || it.startsWith("turns:", ignoreCase = true) }
        }
        if (mediaPolicy == CallMediaPathPolicy.RELAY_ONLY && !hasRelay) {
            return OffLanCallEligibility.Unavailable(OffLanCallEligibility.Unavailable.Reason.NO_COMPATIBLE_ROUTE)
        }
        if (iceServers.isEmpty()) {
            return OffLanCallEligibility.Unavailable(OffLanCallEligibility.Unavailable.Reason.NO_COMPATIBLE_ROUTE)
        }
        return OffLanCallEligibility.Supported(
            iceServers = iceServers,
            mediaPolicy = mediaPolicy,
            hasRelayServer = hasRelay,
        )
    }

    /**
     * Call buttons remain visible whenever direct LAN calling works OR off-LAN calling is genuinely supported
     * and neither party is revoked.
     */
    public fun shouldShowCallActions(): Boolean {
        if (isPeerRevoked) return false
        return isDirectLanReachable || evaluateOffLanEligibility() is OffLanCallEligibility.Supported
    }

    public fun toRtcConfiguration(): RtcConfiguration {
        val iceTransportPolicy = when (mediaPolicy) {
            CallMediaPathPolicy.RELAY_ONLY -> IceTransportPolicy.Relay
            CallMediaPathPolicy.DIRECT_ONLY,
            CallMediaPathPolicy.DIRECT_PREFERRED_RELAY_FALLBACK -> IceTransportPolicy.All
        }
        return RtcConfiguration(
            bundlePolicy = BundlePolicy.MaxBundle,
            rtcpMuxPolicy = RtcpMuxPolicy.Require,
            iceTransportPolicy = iceTransportPolicy,
            iceServers = iceServers.map(CallIceServerSpec::toWebRtcIceServer),
            iceCandidatePoolSize = 1,
        )
    }
}

/**
 * Validated inbound off-LAN call signal metadata checked before mutating coordinator/session state.
 */
public data class InboundOffLanCallSignal(
    public val signalId: String,
    public val callId: String,
    public val fromPeerId: String,
    public val seq: Long,
    public val issuedAtMs: Long,
    public val ttlMs: Long,
    public val payloadText: String,
    public val senderCapabilities: Set<String> = emptySet(),
)

/**
 * Verdict from [OffLanCallSignalGuard.validateInbound].
 */
public sealed interface OffLanSignalVerdict {
    public data object Accepted : OffLanSignalVerdict
    public data class Rejected(public val reason: RejectionReason) : OffLanSignalVerdict {
        public enum class RejectionReason {
            REVOKED_PEER,
            EXPIRED_TTL,
            CLOCK_SKEW,
            REPLAYED_SIGNAL_ID,
            STALE_SEQUENCE,
            TERMINAL_CALL,
            EMPTY_PAYLOAD,
        }
    }
}

/**
 * Thread-confined / mutex-free stateful guard (callers synchronize via coordinator actor/dispatcher or lock)
 * enforcing short-lived signal TTLs, per-call replay/sequence rejection, revocation refusal, and terminal call locks.
 */
public class OffLanCallSignalGuard(
    private val maxFutureSkewMs: Long = DEFAULT_MAX_FUTURE_SKEW_MS,
    private val maxTtlMs: Long = DEFAULT_MAX_TTL_MS,
) {
    private val revokedPeers: MutableSet<String> = mutableSetOf()
    private val seenSignalIdsByCall: MutableMap<String, MutableSet<String>> = mutableMapOf()
    private val highestSeqByCallPeer: MutableMap<String, Long> = mutableMapOf()
    private val terminalCalls: MutableSet<String> = mutableSetOf()

    public fun markPeerRevoked(peerId: String) {
        if (peerId.isNotBlank()) revokedPeers.add(peerId)
    }

    public fun clearPeerRevocation(peerId: String) {
        revokedPeers.remove(peerId)
    }

    public fun isPeerRevoked(peerId: String): Boolean = peerId in revokedPeers

    public fun markCallTerminal(callId: String) {
        if (callId.isNotBlank()) {
            terminalCalls.add(callId)
        }
    }

    public fun isCallTerminal(callId: String): Boolean = callId in terminalCalls

    public fun validateInbound(
        signal: InboundOffLanCallSignal,
        nowMs: Long,
    ): OffLanSignalVerdict {
        if (signal.payloadText.isBlank()) {
            return OffLanSignalVerdict.Rejected(OffLanSignalVerdict.Rejected.RejectionReason.EMPTY_PAYLOAD)
        }
        if (signal.fromPeerId in revokedPeers) {
            return OffLanSignalVerdict.Rejected(OffLanSignalVerdict.Rejected.RejectionReason.REVOKED_PEER)
        }
        if (signal.callId in terminalCalls) {
            return OffLanSignalVerdict.Rejected(OffLanSignalVerdict.Rejected.RejectionReason.TERMINAL_CALL)
        }
        if (signal.issuedAtMs - nowMs > maxFutureSkewMs) {
            return OffLanSignalVerdict.Rejected(OffLanSignalVerdict.Rejected.RejectionReason.CLOCK_SKEW)
        }
        val effectiveTtl = signal.ttlMs.coerceIn(1L, maxTtlMs)
        if (nowMs - signal.issuedAtMs > effectiveTtl || signal.ttlMs <= 0L || signal.ttlMs > maxTtlMs) {
            return OffLanSignalVerdict.Rejected(OffLanSignalVerdict.Rejected.RejectionReason.EXPIRED_TTL)
        }
        val seenIds = seenSignalIdsByCall.getOrPut(signal.callId) { mutableSetOf() }
        if (!seenIds.add(signal.signalId)) {
            return OffLanSignalVerdict.Rejected(OffLanSignalVerdict.Rejected.RejectionReason.REPLAYED_SIGNAL_ID)
        }
        val seqKey = "${signal.callId}|${signal.fromPeerId}"
        val previousSeq = highestSeqByCallPeer[seqKey]
        if (previousSeq != null && signal.seq <= previousSeq) {
            return OffLanSignalVerdict.Rejected(OffLanSignalVerdict.Rejected.RejectionReason.STALE_SEQUENCE)
        }
        highestSeqByCallPeer[seqKey] = signal.seq
        return OffLanSignalVerdict.Accepted
    }

    public companion object {
        public const val DEFAULT_MAX_FUTURE_SKEW_MS: Long = 30_000L
        public const val DEFAULT_MAX_TTL_MS: Long = 60_000L
    }
}
