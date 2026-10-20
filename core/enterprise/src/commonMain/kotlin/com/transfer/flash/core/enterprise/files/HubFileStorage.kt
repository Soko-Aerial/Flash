@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.enterprise.files

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.rest.HubFileIds

/**
 * Where the transfer engine keeps what must survive the process: one small state record per transfer (the chunk bitmap)
 * and, for a download, the plaintext being assembled.
 *
 * Every method is blocking file work; [HubFileTransfers] calls it from its I/O dispatcher. A method either succeeds or
 * throws; the engine turns a throw into [HubFileError.LocalStorage] and never pretends a write happened.
 */
@FlashInternalApi
public interface HubFileStorage {
    public fun readState(direction: HubTransferDirection, fileId: String): ByteArray?

    public fun writeState(direction: HubTransferDirection, fileId: String, bytes: ByteArray)

    public fun deleteState(direction: HubTransferDirection, fileId: String)

    /** File ids with a state record, so a host can resume every unfinished transfer at start-up. */
    public fun stateIds(direction: HubTransferDirection): List<String>

    /** Writes plaintext at [offset] of the download's part file; the bytes are durable when this returns. */
    public fun writePart(fileId: String, offset: Long, bytes: ByteArray)

    public fun readPart(fileId: String, offset: Long, length: Int): ByteArray

    public fun deletePart(fileId: String)

    /** Moves the verified part file to its finished name and returns where it is (a path the host understands). */
    public fun finishPart(fileId: String): String

    /** Free bytes for new part data, or [Long.MAX_VALUE] when the platform cannot tell. */
    public fun usableBytes(): Long
}

/** Which side of a transfer a state record belongs to. */
@FlashInternalApi
public enum class HubTransferDirection(internal val folder: String) { UPLOAD("up"), DOWNLOAD("down") }

/**
 * [HubFileStorage] on the device's file system, under [rootDirectory] (app-private storage: Android `filesDir/hub-files`,
 * desktop `~/.flash/hub-files`; the host chooses).
 *
 * Layout: `up/<fileId>.state`, `down/<fileId>.state`, `down/<fileId>.part`, `done/<fileId>.bin`. The file id is checked
 * to be 32 lowercase hex before it becomes part of a path, so nothing else can ever name a file here.
 */
@FlashInternalApi
public class DiskHubFileStorage(private val rootDirectory: String) : HubFileStorage {

    private fun dir(name: String): String = "${rootDirectory.trimEnd('/', '\\')}/$name".also(HubFileDisk::ensureDirectory)

    private fun checked(fileId: String): String {
        require(HubFileIds.isFileId(fileId)) { "not a file id" }
        return fileId
    }

    private fun statePath(direction: HubTransferDirection, fileId: String) = "${dir(direction.folder)}/${checked(fileId)}$STATE"

    private fun partPath(fileId: String) = "${dir(HubTransferDirection.DOWNLOAD.folder)}/${checked(fileId)}$PART"

    override fun readState(direction: HubTransferDirection, fileId: String): ByteArray? =
        HubFileDisk.readAll(statePath(direction, fileId), MAX_STATE_BYTES)

    override fun writeState(direction: HubTransferDirection, fileId: String, bytes: ByteArray) {
        require(bytes.size <= MAX_STATE_BYTES) { "state record too large" }
        HubFileDisk.replaceAtomically(statePath(direction, fileId), bytes)
    }

    override fun deleteState(direction: HubTransferDirection, fileId: String) {
        HubFileDisk.delete(statePath(direction, fileId))
    }

    override fun stateIds(direction: HubTransferDirection): List<String> =
        HubFileDisk.list(dir(direction.folder))
            .filter { it.endsWith(STATE) }
            .map { it.removeSuffix(STATE) }
            .filter(HubFileIds::isFileId)
            .sorted()

    override fun writePart(fileId: String, offset: Long, bytes: ByteArray) {
        HubFileDisk.writeAt(partPath(fileId), offset, bytes)
    }

    override fun readPart(fileId: String, offset: Long, length: Int): ByteArray = HubFileDisk.readAt(partPath(fileId), offset, length)

    override fun deletePart(fileId: String) {
        HubFileDisk.delete(partPath(fileId))
    }

    override fun finishPart(fileId: String): String {
        val target = "${dir(DONE)}/${checked(fileId)}$BIN"
        HubFileDisk.move(partPath(fileId), target)
        return target
    }

    override fun usableBytes(): Long = HubFileDisk.usableSpace(dir(HubTransferDirection.DOWNLOAD.folder))

    private companion object {
        const val STATE = ".state"
        const val PART = ".part"
        const val BIN = ".bin"
        const val DONE = "done"

        /** A state record is a few hundred bytes plus a 512-byte bitmap; anything far larger is not ours. */
        const val MAX_STATE_BYTES = 16 * 1024
    }
}

/**
 * [HubFileStorage] in memory, for tests on every target and for hosts' own tests. [failWrites] makes every write throw,
 * which is how a full or read-only disk looks to the engine; [usable] is what [usableBytes] answers.
 */
@FlashInternalApi
public class MemoryHubFileStorage : HubFileStorage {
    private val states = mutableMapOf<String, ByteArray>()
    private val parts = mutableMapOf<String, ByteArray>()
    public val finished: MutableMap<String, ByteArray> = mutableMapOf()
    public var failWrites: Boolean = false
    public var usable: Long = Long.MAX_VALUE

    /** How many of the next [writePart] calls throw, as a write the disk refused half-way through a run. */
    public var failNextPartWrites: Int = 0

    /** How many times [writeState] succeeded; lets a test check the engine saves progress per chunk. */
    public var stateWrites: Int = 0
        private set

    private fun key(direction: HubTransferDirection, fileId: String) = "${direction.folder}/$fileId"

    override fun readState(direction: HubTransferDirection, fileId: String): ByteArray? = states[key(direction, fileId)]?.copyOf()

    override fun writeState(direction: HubTransferDirection, fileId: String, bytes: ByteArray) {
        if (failWrites) throw IllegalStateException("disk full")
        states[key(direction, fileId)] = bytes.copyOf()
        stateWrites++
    }

    override fun deleteState(direction: HubTransferDirection, fileId: String) {
        states.remove(key(direction, fileId))
    }

    override fun stateIds(direction: HubTransferDirection): List<String> =
        states.keys.filter { it.startsWith("${direction.folder}/") }.map { it.substringAfter('/') }.sorted()

    override fun writePart(fileId: String, offset: Long, bytes: ByteArray) {
        if (failWrites) throw IllegalStateException("disk full")
        if (failNextPartWrites > 0) {
            failNextPartWrites--
            throw IllegalStateException("write refused")
        }
        val current = parts[fileId] ?: ByteArray(0)
        val end = (offset + bytes.size).toInt()
        val grown = if (current.size >= end) current else current.copyOf(end)
        bytes.copyInto(grown, offset.toInt())
        parts[fileId] = grown
    }

    override fun readPart(fileId: String, offset: Long, length: Int): ByteArray {
        val part = parts[fileId] ?: throw IllegalStateException("no part file")
        if (offset + length > part.size) throw IllegalStateException("part file shorter than expected")
        return part.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    override fun deletePart(fileId: String) {
        parts.remove(fileId)
    }

    override fun finishPart(fileId: String): String {
        finished[fileId] = parts.remove(fileId) ?: throw IllegalStateException("no part file")
        return "memory:$fileId"
    }

    override fun usableBytes(): Long = usable

    /** The part file as it is now (tests corrupt it to simulate a bad disk). */
    public fun part(fileId: String): ByteArray? = parts[fileId]

    public fun replacePart(fileId: String, bytes: ByteArray) {
        parts[fileId] = bytes.copyOf()
    }

    /** Overwrites a stored state record, as a torn or foreign file would. */
    public fun corruptState(direction: HubTransferDirection, fileId: String, bytes: ByteArray) {
        states[key(direction, fileId)] = bytes.copyOf()
    }
}

/** The plaintext being sent. [read] returns exactly [length] bytes or throws. */
@FlashInternalApi
public interface HubFileSource {
    public val size: Long

    public fun read(offset: Long, length: Int): ByteArray
}

/** A source over bytes already in memory (tests, small generated files). */
@FlashInternalApi
public class ByteArrayHubFileSource(private val bytes: ByteArray) : HubFileSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && offset + length <= bytes.size) { "read outside the source" }
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }
}

/**
 * A source over a file on disk ([path] from the host: desktop file, or an Android app-private copy). [size] is read
 * once, when the source is made; a file that shrinks afterwards makes [read] throw, and one that changes is caught by the
 * engine's re-hash on resume.
 */
@FlashInternalApi
public class DiskHubFileSource(private val path: String) : HubFileSource {
    override val size: Long = HubFileDisk.length(path)

    override fun read(offset: Long, length: Int): ByteArray = HubFileDisk.readAt(path, offset, length)
}
