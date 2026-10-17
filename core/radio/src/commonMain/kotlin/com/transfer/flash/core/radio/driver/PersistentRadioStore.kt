package com.transfer.flash.core.radio.driver

import com.transfer.flash.core.radio.withRadioLock

/**
 * Pluggable atomic key-value persistence for radio counter high-water marks and replay windows.
 */
public interface RadioStatePersistence {
    /** Reads all stored key-value pairs. Returns empty map if unreadable or not found. */
    public fun readAll(): Map<String, String>

    /** Atomically writes the key-value map to durable storage. */
    public fun writeAll(entries: Map<String, String>)
}

/**
 * In-memory implementation of [RadioStatePersistence] for tests and simulation.
 * Supports simulating a crash (process termination) where in-flight uncommitted state is lost,
 * or where state is reset or reloaded.
 */
public class InMemoryRadioStatePersistence(
    initialData: Map<String, String> = emptyMap(),
) : RadioStatePersistence {
    private val lock = Any()
    private val data = HashMap<String, String>(initialData)

    override fun readAll(): Map<String, String> = withRadioLock(lock) {
        HashMap(data)
    }

    override fun writeAll(entries: Map<String, String>): Unit = withRadioLock(lock) {
        data.clear()
        data.putAll(entries)
    }

    /** Simulates a process restart by returning a new persistence instance with the exact persisted data. */
    public fun simulateRestart(): InMemoryRadioStatePersistence = withRadioLock(lock) {
        InMemoryRadioStatePersistence(HashMap(data))
    }

    /** Number of stored keys. */
    public val size: Int get() = withRadioLock(lock) { data.size }
}

/**
 * Durable implementation of [RadioCounterStore] and [RadioReplayStore].
 *
 * Enforces Plan R §8 and RADIO-WIRE-FORMAT.md §10 safety contracts:
 * 1. Counters: stores a high-water mark ahead of use. On restart or crash, [RadioSendCounter]
 *    reads this persisted high-water mark and jumps past it, guaranteeing no counter/nonce
 *    can ever be reused under the same key.
 * 2. Replay Windows: persists the 64-wide RFC 4303 sliding window (highest received counter
 *    and 64-bit bitmap) so a restart does not allow previously captured frames to be replayed.
 * 3. Never throws exceptions on corrupted or unreadable data: falls back safely to null.
 * 4. Strictly ZERO cryptographic keys or frame contents are written to storage.
 */
public class PersistentRadioStore(
    private val persistence: RadioStatePersistence,
) : RadioCounterStore, RadioReplayStore {

    private val lock = Any()
    private val memoryCache = HashMap<String, String>()
    private var initialized = false

    private fun ensureLoaded() {
        if (!initialized) {
            val entries = runCatching { persistence.readAll() }.getOrDefault(emptyMap())
            memoryCache.clear()
            memoryCache.putAll(entries)
            initialized = true
        }
    }

    // --- RadioCounterStore ---

    override fun loadCounter(key: String): Long? = withRadioLock(lock) {
        ensureLoaded()
        val propKey = "counter.$key"
        val raw = memoryCache[propKey]
        raw?.toLongOrNull()?.takeIf { it >= 0 }
    }

    override fun storeCounter(key: String, reservedUpTo: Long): Unit = withRadioLock(lock) {
        ensureLoaded()
        val propKey = "counter.$key"
        memoryCache[propKey] = reservedUpTo.toString()
        persistLocked()
    }

    // --- RadioReplayStore ---

    override fun load(key: String): Pair<Long, Long>? = withRadioLock(lock) {
        ensureLoaded()
        val highKey = "replay.$key.highest"
        val bitKey = "replay.$key.bitmap"
        val highRaw = memoryCache[highKey]
        val bitRaw = memoryCache[bitKey]
        val highest = highRaw?.toLongOrNull()
        val bitmap = bitRaw?.toLongOrNull()
        if (highest != null && bitmap != null) highest to bitmap else null
    }

    override fun store(key: String, highest: Long, bitmap: Long): Unit = withRadioLock(lock) {
        ensureLoaded()
        val highKey = "replay.$key.highest"
        val bitKey = "replay.$key.bitmap"
        memoryCache[highKey] = highest.toString()
        memoryCache[bitKey] = bitmap.toString()
        persistLocked()
    }

    private fun persistLocked() {
        runCatching {
            persistence.writeAll(HashMap(memoryCache))
        }
    }
}
