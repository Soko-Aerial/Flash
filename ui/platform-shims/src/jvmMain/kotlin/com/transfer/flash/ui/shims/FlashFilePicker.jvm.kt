package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * `javax.swing.JFileChooser` — deliberately not AWT's `java.awt.FileDialog`.
 *
 * `FileDialog` renders the OS-native chooser, which sounds preferable, but its only filtering hook is
 * `setFilenameFilter`, and **Windows ignores it outright**. On the platform this repo is developed on
 * that would turn the composer's Gallery and Audio filters into "all files" with no warning — the
 * same class of silent regression that ruled FileKit out. `JFileChooser` filters correctly on every
 * platform, at the cost of a Swing-styled dialog.
 */
@Composable
public actual fun rememberFlashFilePickerLauncher(
    onPicked: (FlashPickedFile) -> Unit,
): FlashFilePickerLauncher = remember(onPicked) {
    object : FlashFilePickerLauncher {
        override fun launch(mimeTypes: List<String>) {
            // Runs on the calling thread, which under Compose Desktop is the AWT event thread — the
            // only thread Swing permits this on. `showOpenDialog` pumps its own event loop while
            // modal, so blocking here is how a Swing dialog is supposed to behave.
            val chooser = JFileChooser().apply {
                isMultiSelectionEnabled = false
                fileSelectionMode = JFileChooser.FILES_ONLY
                extensionFilterFor(mimeTypes)?.let {
                    addChoosableFileFilter(it)
                    fileFilter = it
                    // Leave the "All Files" entry in the dropdown: the Android picker's MIME filter
                    // is advisory too (a provider may offer anything), and a user whose file has an
                    // unusual extension must not be stuck.
                    isAcceptAllFileFilterUsed = true
                }
            }
            if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return
            val file: File = chooser.selectedFile ?: return
            if (!file.isFile) return
            onPicked(
                FlashPickedFile(
                    // `File.toURI()` yields `file:/C:/…`, the same shape `FlashVoiceRecorder` produces
                    // from `Uri.fromFile` on Android, so both sides of the seam speak URIs.
                    uri = file.toURI().toString(),
                    name = file.name,
                    size = file.length(),
                ),
            )
        }
    }
}

/**
 * Translates the Android MIME filter into the extension list Swing understands.
 *
 * Returns `null` when everything is acceptable — the all-files MIME type, an empty list, or a MIME
 * type this mapping does not recognise. Returning `null` (no filter) rather than an empty filter is
 * the safe direction: an unrecognised type must never hide files.
 *
 * `internal` so `jvmTest` can assert the mapping without opening a modal dialog.
 */
internal fun extensionFilterFor(mimeTypes: List<String>): FileNameExtensionFilter? {
    if (mimeTypes.isEmpty() || mimeTypes.any { it == "*/*" }) return null
    val extensions = LinkedHashSet<String>()
    for (mimeType in mimeTypes) {
        val mapped = EXTENSIONS_BY_MIME[mimeType.lowercase()]
            // A concrete type such as `image/png` maps to its own subtype, which is the extension for
            // every format Flash actually sends.
            ?: mimeType.substringAfterLast('/').takeIf { it.isNotBlank() && it != "*" }?.let { setOf(it) }
            ?: return null
        extensions += mapped
    }
    if (extensions.isEmpty()) return null
    return FileNameExtensionFilter(
        mimeTypes.joinToString(", "),
        *extensions.toTypedArray(),
    )
}

/**
 * The three wildcard families the composer asks for. Kept explicit rather than derived from
 * `MimetypesFileTypeMap`, whose default table is a stub on most JREs and answers
 * `application/octet-stream` for `.m4a` — the extension a Flash voice note actually uses.
 */
private val EXTENSIONS_BY_MIME: Map<String, Set<String>> = mapOf(
    "image/*" to setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif"),
    "video/*" to setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp"),
    "audio/*" to setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "amr"),
)
