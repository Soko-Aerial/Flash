package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable

/**
 * A file the user chose, in the shape `:ui:chat` hands to the transfer engine.
 *
 * [uri] is an opaque platform string — a `content://` document URI on Android, a `file:` URI on
 * desktop — and is the same value that already crossed this boundary before the shim existed.
 */
public data class FlashPickedFile(
    public val uri: String,
    public val name: String,
    public val size: Long,
)

/** Opens the platform file picker. Held across recomposition; safe to call from an event handler. */
public interface FlashFilePickerLauncher {
    /**
     * @param mimeTypes the filter to apply, in Android `ActivityResultContracts.OpenDocument`
     *   form — e.g. `listOf("image/\*", "video/\*")`, or a one-element list holding the all-files
     *   wildcard for no filter. Wildcard families are honoured on every platform; see the `jvm`
     *   actual for how they are translated for a toolkit that only understands file extensions.
     *
     *   (The backslashes are lexical, not part of the strings: Kotlin block comments nest, so a bare
     *   `/` followed by `\*` inside KDoc would open a comment that never closes. The all-files
     *   wildcard is spelled out in prose for the same reason — it contains the closing pair itself,
     *   which no escape can hide from the lexer.)
     */
    public fun launch(mimeTypes: List<String>)
}

/**
 * The file-picking seam, enacting **D7b**'s intent — one shared call site, the native picker on each
 * platform — with a hand-rolled `expect`/`actual` instead of the FileKit dependency D7b names.
 *
 * FileKit was read at source level and ruled out on three counts, all of which would have been
 * silent behaviour changes rather than compile errors:
 *
 *  1. **R10.** FileKit 0.15.0 requires kotlin-stdlib 2.4.10 and Compose Multiplatform 1.11.1. This
 *     repo is frozen at Kotlin 2.2.10 / CMP 1.9.3, so the newest usable release is 0.11.0 — and
 *     D7b's coordinates (`com.vinceglb:filekit-compose`) do not exist at all; the group is
 *     `io.github.vinceglb` and the module is `filekit-dialogs-compose`.
 *  2. **`audio/\*` is not expressible.** `FileKitType.File(extensions)` maps each extension through
 *     `MimeTypeMap.getMimeTypeFromExtension` and falls back to an all-files wildcard array when the
 *     set is empty. There is no wildcard-MIME path, so the composer's Audio filter would silently
 *     become either device-dependent or all-files.
 *  3. **Gallery would lose its persistable grant.** `FileKitType.ImageAndVideo` routes to
 *     `PickVisualMedia`, the Android photo picker — not SAF `OpenDocument`. Photo-picker URIs reject
 *     `takePersistableUriPermission`, and Flash needs that grant so the engine can keep streaming
 *     the file after this screen dies.
 *
 * One objection *was* cleared: FileKit auto-initialises from `LocalActivityResultRegistryOwner`, so
 * adopting it would not have required an `:app` change. Revisit it after a Kotlin bump.
 *
 * Because no library is added, D7b's `libs.versions.toml` alias is a no-op and R10 is untouched.
 */
@Composable
public expect fun rememberFlashFilePickerLauncher(
    onPicked: (FlashPickedFile) -> Unit,
): FlashFilePickerLauncher
