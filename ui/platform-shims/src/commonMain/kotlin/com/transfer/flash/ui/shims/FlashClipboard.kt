package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

/** Puts plain text on the system clipboard. */
public interface FlashClipboard {
    public fun copy(text: String)
}

/**
 * The clipboard seam — and the one shim in this module with **no** `expect`/`actual` pair, because
 * Compose already ships a multiplatform clipboard and `:ui:chat` only ever needed plain text.
 *
 * Replaces `FlashConversationScreen.copyToClipboard`, which reached for
 * `Context.getSystemService(Context.CLIPBOARD_SERVICE)` and `ClipData.newPlainText("Flash Message", …)`
 * directly.
 *
 * One behaviour difference worth recording: the clip *label* changes from `"Flash Message"` to
 * whatever Compose's Android implementation uses. The label is metadata — it is not the pasted
 * content — and modern Android surfaces it nowhere the user can see, so this is a naming change
 * rather than a functional one. Preserving it would mean keeping an `android.content` import in
 * `:ui:chat` for the sake of a string nothing reads.
 *
 * The transient "Copied to clipboard" confirmation is deliberately **not** raised here: it is the
 * caller's snackbar (D7a), and a shim that also spoke to a `SnackbarHostState` would need one passed
 * in, coupling the clipboard to the host's lifecycle for no gain.
 */
@Composable
@Suppress("DEPRECATION")
public fun rememberFlashClipboard(): FlashClipboard {
    // `LocalClipboardManager`/`ClipboardManager` are deprecated in favour of `LocalClipboard`, whose
    // `setClipEntry` is a suspend function taking a platform-specific `ClipEntry` (an `android.content.ClipData`
    // on Android, an AWT `Transferable` on desktop). Constructing one from common code at CMP 1.9.3
    // means an expect/actual of its own — three more files to convert a string. The deprecated API is
    // common, synchronous, and does exactly what the two call sites need.
    val clipboardManager = LocalClipboardManager.current
    return remember(clipboardManager) {
        object : FlashClipboard {
            override fun copy(text: String) {
                clipboardManager.setText(AnnotatedString(text))
            }
        }
    }
}
