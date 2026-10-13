package com.transfer.flash.core.security.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What this device remembers about one colleague's sealing key (ADR-126, minimal "key changed" detection).
 *
 * [pinned] is the fingerprint of the key messages are sealed to. [pending] is a DIFFERENT fingerprint the directory
 * served later: while it is set nothing is sent to that colleague through the hub until the user confirms it.
 */
@FlashInternalApi
public data class HubSealingPin(public val pinned: String, public val pending: String? = null) {
    /** True while a changed key waits for the user's confirmation. */
    public val blocked: Boolean get() = pending != null
}

/** What [HubSealingPinMath.evaluate] concluded about a key the directory just served. */
@FlashInternalApi
public enum class HubKeyVerdict {
    /** First time this device sees a key for the colleague: it is remembered. */
    FIRST_USE,

    /** The same key as before. */
    SAME,

    /** A different key than the remembered one: blocked until confirmed. */
    CHANGED,
}

/** Pure rules of the pin; no storage, no clock. */
@FlashInternalApi
public object HubSealingPinMath {

    public class Result(public val verdict: HubKeyVerdict, public val next: HubSealingPin)

    /** [current] is what is remembered (null on first use); [served] is the fingerprint the directory serves now. */
    public fun evaluate(current: HubSealingPin?, served: String): Result {
        if (current == null) return Result(HubKeyVerdict.FIRST_USE, HubSealingPin(served))
        if (served == current.pinned) return Result(HubKeyVerdict.SAME, HubSealingPin(current.pinned))
        return Result(HubKeyVerdict.CHANGED, HubSealingPin(current.pinned, pending = served))
    }

    /** The pin after the user confirmed the change; unchanged when nothing was waiting. */
    public fun confirm(current: HubSealingPin): HubSealingPin =
        current.pending?.let { HubSealingPin(it) } ?: current
}

/** Persistence of the pins (the whole map is rewritten on each change; there are few and they are small). */
@FlashInternalApi
public interface HubSealingPinStore {
    /** The stored pins, empty when there are none or they cannot be read. Never throws. */
    public suspend fun load(): Map<String, HubSealingPin>

    /** Replaces the stored pins. Throws when the write fails. */
    public suspend fun save(pins: Map<String, HubSealingPin>)
}

/** In-memory [HubSealingPinStore]. */
@FlashInternalApi
public class MemoryHubSealingPinStore(initial: Map<String, HubSealingPin> = emptyMap()) : HubSealingPinStore {
    private var pins: Map<String, HubSealingPin> = initial
    public var failSaves: Boolean = false

    override suspend fun load(): Map<String, HubSealingPin> = pins

    override suspend fun save(pins: Map<String, HubSealingPin>) {
        check(!failSaves) { "storage unavailable" }
        this.pins = pins
    }
}

/** Text form used by the platform pin stores: one line per colleague, `deviceId TAB pinned [TAB pending]`. */
@FlashInternalApi
public object HubSealingPinCodec {
    public fun encode(pins: Map<String, HubSealingPin>): String = pins.entries
        .filter { (id, _) -> id.isNotBlank() && '\t' !in id && '\n' !in id }
        .joinToString("\n") { (id, pin) -> listOfNotNull(id, pin.pinned, pin.pending).joinToString("\t") }

    public fun decode(text: String?): Map<String, HubSealingPin> {
        if (text.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, HubSealingPin>()
        for (line in text.split('\n')) {
            val bits = line.split('\t')
            if (bits.size !in 2..3 || bits[0].isBlank() || bits[1].isBlank()) continue
            out[bits[0]] = HubSealingPin(bits[1], bits.getOrNull(2)?.takeIf { it.isNotBlank() })
        }
        return out
    }
}

/**
 * The live pins of this device (ADR-126): the first key served for a colleague is remembered, a different one later
 * blocks sending to that colleague through the hub until [confirm]. Persisted through [store].
 */
@FlashInternalApi
public class HubSealingPins(private val store: HubSealingPinStore) {
    private val lock = Mutex()
    private var loaded = false
    private var pins: Map<String, HubSealingPin> = emptyMap()
    private val _blocked = MutableStateFlow<Map<String, HubSealingPin>>(emptyMap())

    /** The colleagues whose key changed and waits for confirmation (device id to pin). Empty until [load] ran. */
    public val blocked: StateFlow<Map<String, HubSealingPin>> = _blocked.asStateFlow()

    /** Reads the stored pins so [blocked] is correct from the start. Safe to call twice. */
    public suspend fun load(): Unit = lock.withLock { ensureLoaded() }

    /**
     * Checks the key the directory serves for [deviceId] (its fingerprint) against the pin and remembers the result.
     * A write failure is not fatal: the in-memory pin still protects this run.
     */
    public suspend fun check(deviceId: String, servedFingerprint: String): HubKeyVerdict = lock.withLock {
        ensureLoaded()
        val result = HubSealingPinMath.evaluate(pins[deviceId], servedFingerprint)
        if (pins[deviceId] != result.next) put(deviceId, result.next)
        result.verdict
    }

    /** True when sending to [deviceId] must wait for the user's confirmation. */
    public suspend fun isBlocked(deviceId: String): Boolean = lock.withLock {
        ensureLoaded()
        pins[deviceId]?.blocked == true
    }

    /** The user chose to continue with the new key. Returns false when nothing was waiting. */
    public suspend fun confirm(deviceId: String): Boolean = lock.withLock {
        ensureLoaded()
        val current = pins[deviceId] ?: return@withLock false
        if (!current.blocked) return@withLock false
        put(deviceId, HubSealingPinMath.confirm(current))
        true
    }

    /** What is remembered for [deviceId], or null. */
    public suspend fun pinOf(deviceId: String): HubSealingPin? = lock.withLock {
        ensureLoaded()
        pins[deviceId]
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        pins = runCatching { store.load() }.getOrDefault(emptyMap())
        loaded = true
        publish()
    }

    private suspend fun put(deviceId: String, pin: HubSealingPin) {
        pins = pins + (deviceId to pin)
        publish()
        runCatching { store.save(pins) }
    }

    private fun publish() {
        _blocked.value = pins.filterValues { it.blocked }
    }
}
