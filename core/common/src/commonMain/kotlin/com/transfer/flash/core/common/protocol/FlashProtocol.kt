package com.transfer.flash.core.common.protocol

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * Flash wire protocol version constant and compatibility policy (C0.1).
 *
 * ## Version negotiation decision: assert-on-handshake (exact match), not capability flags
 *
 * **Decision:** peers exchange [VERSION] in the first handshake frame in both directions;
 * a mismatch is a hard, typed protocol error (`FlashError.ProtocolMismatch`) that tears the
 * session down before any stateful traffic. No down-negotiation, no capability bitset — for now.
 *
 * **Why assert-first:**
 * - Established framed/binary protocols negotiate the version at connection setup and pin it
 *   for the lifetime of the connection, rejecting mismatches loudly rather than degrading
 *   silently. CQL binary protocol v5 fixes the version from the STARTUP message ("only one
 *   version of messages is accepted on a given connection"):
 *   https://cassandra.apache.org/doc/latest/cassandra/_attachments/native_protocol_v5.html (§2.3, §2.4.1.1)
 * - The Agent Client Protocol requires both sides to "agree on a protocol version"; if the
 *   peer's version is unsupported the client MUST close the connection:
 *   https://agentclientprotocol.com/protocol/initialization
 * - A deliberately strict design (phux ADR-0061) argues "a silently half-compatible peer is a
 *   worse failure than a loud refusal" and reserves version equality for changes no additive
 *   shape can express, shipping additive surface as capabilities instead:
 *   https://github.com/phall1/phux/blob/main/ADR/0061-capabilities-add-versions-break.md
 * - Capability flags (Bitcoin `services` bitfield, Git pack capabilities, BIP 434 feature
 *   messages) solve a different problem — optional features on a stable base format:
 *   https://spec.nexa.org/network/messages/version/
 *   https://git-scm.dev/docs/gitprotocol-capabilities
 *   https://bips.dev/434/
 *
 * **Flash-specific rationale:** Flash currently has exactly two client implementations (Android
 * app + planned desktop/Rust bridge) with no fleet of heterogeneous versions to serve. An exact
 * match keeps v1 dead simple, produces deterministic typed errors instead of subtle
 * misbehavior, and defers capability-flag complexity until there are real additive features
 * that need it.
 *
 * **Revisit when:** more than one active wire revision must coexist in the field; then adopt
 * min..max range advertisement or capability bits per the phux/BIP-434 patterns above.
 */
@FlashInternalApi
public object FlashProtocol {

    /**
     * Current Flash wire protocol version. Bumped only for breaking wire changes;
     * additive changes must not bump this while the exact-match policy holds.
     */
    public const val VERSION: Int = 2

    /**
     * Exact-match compatibility policy (v1): a peer is compatible only if its advertised
     * version equals [VERSION]. Both older and newer peers are rejected so that neither side
     * guesses about the other's framing semantics.
     */
    public fun isCompatible(peerVersion: Int): Boolean = peerVersion == VERSION
}
