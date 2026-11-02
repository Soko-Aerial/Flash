package com.transfer.flash.core.swarm.engine

/**
 * Structured evidence sink for the scheduler (OPT-O28-04).
 *
 * The engine stays sans-IO: it never looks up a logger. The host passes an implementation that forwards to
 * `FlashProbe`, and a test passes a list. Every emission is on a *rare* event (a piece timeout, a new stall, a dropped
 * duplicate) or rate-limited, never per piece — a probe per piece would cost more than the scheduler it measures.
 */
public fun interface SwarmTelemetry {
    public fun emit(name: String, fields: List<Pair<String, Any?>>)
}
