package com.transfer.flash.core.swarm.driver

import com.transfer.flash.core.swarm.engine.SwarmCommand
import com.transfer.flash.core.swarm.model.ContentRoot

/**
 * Coalesces the per-piece `SyncAndPersistBits` commands of one content (OPT-O08-04).
 *
 * The engine emits one such command for every received piece — 4,096 of them for a 4 GB file at 1 MiB pieces, 16,384
 * for 1 GB at 64 KiB — and each one made the persistence worker call `fsync` on the partial file and write the whole
 * bitfield row again. Only the newest snapshot matters (each carries the complete bitfield and `bytesDone`), so a burst
 * of pieces costs one `fsync` and one row write per content per batch.
 *
 * INV-4 is unchanged: the batch is written before the worker returns to the queue, and a HAVE is only produced by the
 * engine's tick, which cannot run ahead of the actor that queued these commands.
 */
internal class SwarmPersistBatch {

    private val bits = LinkedHashMap<Pair<String, ContentRoot>, SwarmCommand.SyncAndPersistBits>()

    /** Commands coalesced away since the last [drain] (measurement only). */
    var coalesced: Int = 0
        private set

    val isEmpty: Boolean get() = bits.isEmpty()

    /**
     * Adds [cmd] if it is a `SyncAndPersistBits`. Returns false — and changes nothing — when the caller must flush this
     * batch first and handle [cmd] in its own order.
     */
    fun add(cmd: SwarmCommand): Boolean {
        if (cmd !is SwarmCommand.SyncAndPersistBits) return false
        val key = cmd.groupId to cmd.root
        if (bits.put(key, cmd) != null) coalesced++
        return true
    }

    /** The newest snapshot per content, in first-seen order, and empties the batch. */
    fun drain(): List<SwarmCommand.SyncAndPersistBits> {
        if (bits.isEmpty()) return emptyList()
        val out = ArrayList<SwarmCommand.SyncAndPersistBits>(bits.size)
        out.addAll(bits.values)
        bits.clear()
        return out
    }
}
