package com.transfer.flash.core.calling

/**
 * Smallest mutual-exclusion primitive for THIS module's commonMain — Phase 25 S2e.
 *
 * The session classes were androidMain when they used `java.util.concurrent` collections
 * (CopyOnWriteArrayList / ConcurrentHashMap). Moving them to commonMain (D1 = Option B: no
 * `java.*` in common code) needs a lock primitive to give plain maps/lists the same
 * concurrent safety. `core:common`'s `PlatformLock` is `internal` to that module, so it is
 * duplicated here rather than widened — same pattern, same reason (R2: `androidMain` and
 * `jvmMain` are sibling source sets with no `dependsOn` edge, so the actual is written
 * twice, byte-identically, on both JVM-family targets).
 *
 * Reentrant, like the JVM monitor the COW types effectively relied on.
 */
internal expect class PlatformMonitor() {
    /** Runs [block] while holding the lock, then releases it. Reentrant per thread. */
    fun <T> withLock(block: () -> T): T
}

/**
 * A lock-guarded [MutableList] — the commonMain stand-in for `CopyOnWriteArrayList`.
 *
 * COW's "iterate a snapshot while someone mutates" is what the session code actually used
 * (job registration racing teardown, frame buffer drains); a snapshot under a lock gives
 * the same guarantee with one primitive that exists on every target.
 */
internal class SyncList<T> {
    private val monitor = PlatformMonitor()
    private val backing = ArrayList<T>()

    val size: Int
        get() = monitor.withLock { backing.size }

    fun isEmpty(): Boolean = monitor.withLock { backing.isEmpty() }

    fun add(item: T): Boolean = monitor.withLock { backing.add(item) }

    /** Snapshot — safe to iterate (and cancel, and clear) while another thread mutates. */
    fun toList(): List<T> = monitor.withLock { backing.toList() }

    fun clear() = monitor.withLock { backing.clear() }
}
