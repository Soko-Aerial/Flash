package com.transfer.flash.core.common.concurrent

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * Lock-guarded [MutableMap] for `commonMain` — the shared stand-in for
 * `java.util.concurrent.ConcurrentHashMap` (Phase 2, slice 4).
 *
 * Promoted from `:core:calling`'s `PlatformMonitor.kt`, where an identical internal copy
 * grew for the group-call mesh (Phase 25 S2e). Two copies of concurrency plumbing is how
 * the app/desktop pairing codecs drifted apart before, so the canonical one lives here
 * and calling's copy goes away. Behavioral notes from the original KDoc are preserved:
 *
 * - [getOrPut] computes [missing] while HOLDING the lock (CHM computes outside it).
 *   Correctness is unchanged — [PlatformLock] is reentrant, so a [missing] that
 *   re-enters this map cannot deadlock.
 * - [keysSnapshot]/[valuesSnapshot]/[toMap] return snapshots where CHM is weakly
 *   consistent: every existing call site either already copied or is stronger under one.
 *
 * `@FlashInternalApi` (not plain `public`): cross-module visible without widening the
 * published library ABI — the `UuidIdGenerator`/`FlashIdGenerator` precedent (R7).
 */
@FlashInternalApi
public class SyncMap<K : Any, V : Any> {
    private val monitor = PlatformLock()
    private val backing = HashMap<K, V>()

    public operator fun get(key: K): V? = monitor.withLock { backing[key] }

    public operator fun set(key: K, value: V) {
        monitor.withLock { backing[key] = value }
    }

    public fun getOrPut(key: K, missing: () -> V): V = monitor.withLock { backing.getOrPut(key, missing) }

    public fun remove(key: K): V? = monitor.withLock { backing.remove(key) }

    /**
     * Removes [key] only when it currently maps to [value] — the `ConcurrentHashMap.remove
     * (key, value)` this replaces in the group-sync election (`syncRounds`).
     */
    public fun remove(key: K, value: V): Boolean = monitor.withLock {
        if (backing[key] == value) {
            backing.remove(key)
            true
        } else {
            false
        }
    }

    public fun keysSnapshot(): List<K> = monitor.withLock { backing.keys.toList() }

    public fun valuesSnapshot(): List<V> = monitor.withLock { backing.values.toList() }

    /** Snapshot of the entries — see [keysSnapshot]. */
    public fun toMap(): Map<K, V> = monitor.withLock { backing.toMap() }

    public fun clear(): Unit = monitor.withLock { backing.clear() }
}

/**
 * Lock-guarded set for `commonMain` — the shared stand-in for
 * `ConcurrentHashMap.newKeySet()`.
 *
 * Same promotion story as [SyncMap]: the group-sync election (`messageIds`,
 * `acknowledgedMessageIds`, `claimedGroupMedia`) and the transfer fan-out sets need set
 * semantics with atomic `add`-as-claim (`add` returns false when already present — the
 * claim check). Backed by a [HashMap] of units rather than a list so `add`/`contains`
 * stay O(1) as the sets grow.
 */
@FlashInternalApi
public class SyncSet<E : Any> {
    private val monitor = PlatformLock()
    private val backing = HashMap<E, Unit>()

    /**
     * Adds [element]. Returns false when it was already present — this return value IS the
     * claim mechanism (`claimedGroupMedia`), not a convenience.
     */
    public fun add(element: E): Boolean = monitor.withLock {
        if (backing.containsKey(element)) {
            false
        } else {
            backing[element] = Unit
            true
        }
    }

    public fun remove(element: E): Boolean = monitor.withLock { backing.remove(element) != null }

    /** Adds every element; returns true when the set changed. Mirrors `MutableSet.addAll`. */
    public fun addAll(elements: Iterable<E>): Boolean = monitor.withLock {
        var changed = false
        elements.forEach { if (!backing.containsKey(it)) {
            backing[it] = Unit
            changed = true
        } }
        changed
    }

    public operator fun contains(element: E): Boolean = monitor.withLock { backing.containsKey(element) }

    public fun toSet(): Set<E> = monitor.withLock { backing.keys.toSet() }

    public fun toList(): List<E> = monitor.withLock { backing.keys.toList() }

    public fun isEmpty(): Boolean = monitor.withLock { backing.isEmpty() }
}
