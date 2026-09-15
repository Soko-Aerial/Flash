package com.transfer.flash.desktop

import com.transfer.flash.ui.transfers.FlashTransferItemUi
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Desktop stubs for the 6 Android-only helpers in `:app`'s `MainActivity.kt` (Phase 21,
 * sub-step 21-4 — the phase file's Step 5, adapted to the symbols that actually exist).
 *
 * Minimal implementations that make the desktop window compile and function for the core
 * transfer flow. Sharing/gallery polish is deliberately deferred (the phase's own Do-NOT).
 */
internal object DesktopHelpers {

    /**
     * Stub for MainActivity's `shareTransferredFile`: opens the file's directory in the system
     * file manager. A real save-dialog is future polish.
     */
    fun shareTransferredFile(item: FlashTransferItemUi) {
        val path = item.localPath ?: return
        val file = File(path)
        if (!file.exists() || !Desktop.isDesktopSupported()) return
        runCatching { Desktop.getDesktop().open(file.parentFile) }
    }

    /**
     * Desktop MIME guesser — replaces `android.webkit.MimeTypeMap`.
     */
    fun guessMimeType(fileName: String): String =
        URLConnection.guessContentTypeFromName(fileName) ?: "*/*"

    /**
     * Desktop URI resolver — replaces FileProvider-based URI resolution. Accepts an absolute
     * path, or a `file://` URI, and returns a `file://` URI.
     */
    fun resolveShareableUri(ref: String?): URI? {
        if (ref.isNullOrBlank()) return null
        return runCatching {
            when {
                ref.startsWith("file://") -> URI(ref)
                ref.startsWith("content://") -> null // Android-only scheme; no desktop equivalent
                else -> File(ref).toURI()
            }
        }.getOrNull()
    }

    /**
     * Desktop share — opens the file with the system default application (replaces
     * `Intent.ACTION_SEND`).
     */
    fun shareImageUri(ref: String?, mimeType: @Suppress("UNUSED_PARAMETER") String) {
        val uri = resolveShareableUri(ref) ?: return
        val file = runCatching { File(uri) }.getOrNull() ?: return
        if (!file.exists() || !Desktop.isDesktopSupported()) return
        runCatching { Desktop.getDesktop().open(file) }
    }

    /**
     * Desktop "save to gallery" — copies the file to `~/Downloads/Flash/` (the closest
     * equivalent to Android's MediaStore; the phase's Do-NOT explicitly blesses this).
     */
    fun saveImageToGallery(ref: String?, mimeType: String) {
        val uri = resolveShareableUri(ref) ?: return
        val source = runCatching { File(uri) }.getOrNull() ?: return
        if (!source.exists()) return

        val downloadsDir = File(System.getProperty("user.home"), "Downloads/Flash")
        downloadsDir.mkdirs()
        val ext = when (mimeType.substringAfter("/", "jpg")) {
            "jpeg" -> "jpg"
            "png" -> "png"
            "gif" -> "gif"
            "webp" -> "webp"
            else -> "jpg"
        }
        val target = File(downloadsDir, "flash_${System.currentTimeMillis()}.$ext")
        runCatching {
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Desktop attachment opener — replaces `Intent.ACTION_VIEW` + FileProvider: opens the
     * file with the system default application.
     */
    fun openAttachment(path: String?, mimeType: @Suppress("UNUSED_PARAMETER") String) {
        if (path.isNullOrBlank()) return
        val file = File(path)
        if (!file.exists() || !Desktop.isDesktopSupported()) return
        runCatching { Desktop.getDesktop().open(file) }
    }

    // ---- received-storage helpers (Settings tab; `:app` scans DiscoveryEngineHolder's root,
    // the desktop equivalent scans DesktopEngine's received root) ----

    fun clearReceivedFiles(engine: DesktopEngine) {
        // Asks the ENGINE for its root. This used to re-derive `~/FlashReceived` from the user home,
        // which silently ignored `DesktopEngine(receivedRoot = …)` — the constructor every desktop
        // test uses — so it reported and cleared a directory the engine was not writing to.
        val root = engine.receivedDirectory
        if (!root.exists()) return
        // Only delete under the engine's canonical root — same containment discipline the
        // receive pipeline applies on write.
        root.listFiles()?.forEach { child -> runCatching { child.deleteRecursively() } }
    }

    /**
     * Total bytes under the engine's received-files root, or 0 when there are none.
     *
     * Blocking directory walk — call it off the UI thread. Mirrors what `:app` gets from its
     * MediaStore-backed scan; there is no equivalent index on desktop, so the filesystem IS the
     * index.
     *
     * Answers 0 rather than null on an empty directory **on purpose**: the Settings card's clear
     * control is enabled only for a `totalBytes != null && > 0` scan (see
     * `FlashStorageMath.canClearReceivedFiles`), so `0` is what renders "No received files" with the
     * control correctly disabled. Returning null would instead print "Storage usage unavailable",
     * which is a claim about a failure that did not happen.
     */
    fun receivedFilesBytes(engine: DesktopEngine): Long {
        val root = engine.receivedDirectory
        if (!root.exists()) return 0L
        return runCatching {
            root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }
}
