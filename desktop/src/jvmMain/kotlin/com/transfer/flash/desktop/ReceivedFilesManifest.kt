package com.transfer.flash.desktop

import java.io.File

/**
 * The top-level entries Flash itself wrote under the received-files folder (brutal-review F8).
 *
 * The folder is the user's "Save location" and can be any folder they picked (`Downloads`, `Desktop`). "Clear received
 * files" and the size shown in its confirmation must therefore cover only what the receive pipelines created, never
 * every child of the folder. Each pipeline records the first path segment below the root when it places a file:
 * the per-transfer directory of a direct receive or a hub file, or the file itself for a swarm download.
 *
 * Stored as one absolute path per line in [file] (under the engine's state directory, not in the received folder, so the
 * user's folder stays clean). Entries from an earlier root stay listed until they disappear.
 */
internal class ReceivedFilesManifest(private val file: File) {

    private val lock = Any()
    private var loaded: MutableSet<String>? = null

    private fun entriesLocked(): MutableSet<String> = loaded ?: run {
        val read = runCatching { file.readLines() }.getOrDefault(emptyList()).filter { it.isNotBlank() }
        read.toMutableSet().also { loaded = it }
    }

    private fun saveLocked(entries: Set<String>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(entries.joinToString("\n", postfix = if (entries.isEmpty()) "" else "\n"))
        }
    }

    /** Records the top-level entry of [placed] below [root]. A path outside the root, or the root itself, is ignored. */
    fun record(root: File, placed: File) {
        val top = topLevelEntry(root, placed) ?: return
        synchronized(lock) {
            val entries = entriesLocked()
            if (entries.add(top.path)) saveLocked(entries)
        }
    }

    /** The recorded entries that are direct children of [root] and still exist. */
    fun entriesUnder(root: File): List<File> {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return emptyList()
        val recorded = synchronized(lock) { entriesLocked().toList() }
        return recorded.map(::File).filter { entry ->
            entry.exists() && runCatching { entry.canonicalFile.parentFile == canonicalRoot }.getOrDefault(false)
        }
    }

    /** Drops [removed] (and anything that no longer exists) from the list. */
    fun forget(removed: Collection<File>) {
        synchronized(lock) {
            val entries = entriesLocked()
            val gone = removed.map { it.path }.toSet()
            if (entries.removeAll { it in gone || !File(it).exists() }) saveLocked(entries)
        }
    }

    private fun topLevelEntry(root: File, placed: File): File? {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val canonicalPlaced = runCatching { placed.canonicalFile }.getOrNull() ?: return null
        val relative = runCatching { canonicalRoot.toPath().relativize(canonicalPlaced.toPath()) }.getOrNull() ?: return null
        if (relative.nameCount == 0 || relative.getName(0).toString() == "..") return null
        return File(canonicalRoot, relative.getName(0).toString())
    }
}
