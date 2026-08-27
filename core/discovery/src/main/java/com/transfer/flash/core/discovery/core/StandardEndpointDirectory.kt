package com.transfer.flash.core.discovery.core

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint

/**
 * Pure, in-memory [EndpointDirectory] (plan C3.3/C3.5).
 *
 * Design notes:
 * - Fully deterministic: every method that depends on time takes an explicit
 *   `nowMs` parameter. No clocks, no Android types, no coroutine dependencies,
 *   so tests drive virtual time directly.
 * - Not thread-safe by design (see interface KDoc); the owner guards access.
 *
 * Change detection compares exactly these endpoint fields:
 * `hostAddress`, `port`, `serviceName`, `device.friendlyName`,
 * `device.protocolVersion`. `FlashDiscoveredEndpoint` carries no device-model
 * field, so model changes cannot be observed here (the TXT `model` key feeds
 * UI rows via decode, not this directory).
 */
public class StandardEndpointDirectory : EndpointDirectory {

    private val entries = LinkedHashMap<FlashDeviceId, EndpointDirectory.Entry>()

    override fun applySeen(endpoint: FlashDiscoveredEndpoint, nowMs: Long): EndpointDirectory.Diff {
        val existing = entries[endpoint.deviceId]
        return if (existing == null) {
            val entry = EndpointDirectory.Entry(endpoint, nowMs, nowMs)
            entries[endpoint.deviceId] = entry
            EndpointDirectory.Diff.Found(entry)
        } else if (!fieldsEqual(existing.endpoint, endpoint)) {
            val entry = EndpointDirectory.Entry(endpoint, existing.firstSeenAtMs, nowMs)
            entries[endpoint.deviceId] = entry
            EndpointDirectory.Diff.Updated(entry, existing)
        } else {
            // Same fields: presence refresh only. firstSeenAtMs preserved.
            entries[endpoint.deviceId] = existing.copy(lastSeenAtMs = nowMs)
            EndpointDirectory.Diff.Unchanged
        }
    }

    override fun applyLost(deviceId: FlashDeviceId): EndpointDirectory.Diff {
        val removed = entries.remove(deviceId) ?: return EndpointDirectory.Diff.Unchanged
        return EndpointDirectory.Diff.Lost(removed.endpoint.deviceId)
    }

    /**
     * Removes and reports entries with `nowMs - lastSeenAtMs >= graceWindowMs`.
     *
     * Boundary semantics: the comparison is `>=`, so an entry seen EXACTLY
     * `graceWindowMs` ago IS expired at that instant. A negative age (clock
     * moved backwards, `nowMs < lastSeenAtMs`) never expires.
     */
    override fun sweepExpired(graceWindowMs: Long, nowMs: Long): List<EndpointDirectory.Diff.Lost> {
        val agedOut = entries.values
            .filter { nowMs - it.lastSeenAtMs >= graceWindowMs }
            .map { it.endpoint.deviceId }
        return agedOut.map { deviceId ->
            entries.remove(deviceId)
            EndpointDirectory.Diff.Lost(deviceId)
        }
    }

    /**
     * Most-recently-seen first (`lastSeenAtMs` DESC), tie-broken by deviceId
     * ascending for stable ordering across rebuilds.
     */
    override fun snapshot(): List<EndpointDirectory.Entry> = entries.values.sortedWith(
        compareByDescending<EndpointDirectory.Entry> { it.lastSeenAtMs }
            .thenComparator { a, b -> a.endpoint.deviceId.value.compareTo(b.endpoint.deviceId.value) }
    )

    override fun get(deviceId: FlashDeviceId): EndpointDirectory.Entry? = entries[deviceId]

    private fun fieldsEqual(a: FlashDiscoveredEndpoint, b: FlashDiscoveredEndpoint): Boolean =
        a.hostAddress == b.hostAddress &&
            a.port == b.port &&
            a.serviceName == b.serviceName &&
            a.device.friendlyName == b.device.friendlyName &&
            a.device.protocolVersion == b.device.protocolVersion
}
