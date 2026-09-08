package com.transfer.flash.ui.shims

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * `ActivityResultContracts.OpenDocument()`, byte-for-byte the picker `FlashConversationScreen` ran
 * before this shim existed — same contract, same persistable-permission call, same metadata query.
 * The only thing that moved is which file it lives in.
 */
@Composable
public actual fun rememberFlashFilePickerLauncher(
    onPicked: (FlashPickedFile) -> Unit,
): FlashFilePickerLauncher {
    val context = LocalContext.current
    // `rememberLauncherForActivityResult` wraps `onPicked` in a `rememberUpdatedState` internally, so
    // a recomposition that supplies a new lambda is picked up without re-registering the contract.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            // Persist read access so the transfer can stream the file even after this screen dies.
            // `runCatching` because a provider is free to refuse, and a refusal must not lose the pick.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val (name, size) = resolveFileMetadata(context, uri)
            onPicked(FlashPickedFile(uri = uri.toString(), name = name, size = size))
        }
    }
    return remember(launcher) {
        object : FlashFilePickerLauncher {
            override fun launch(mimeTypes: List<String>) {
                // `OpenDocument` takes the MIME array verbatim: `arrayOf("image/*", "video/*")` for
                // Gallery, `arrayOf("audio/*")` for Audio, `arrayOf("*/*")` otherwise. Flattening
                // these to a single filter is exactly what adopting FileKit would have done.
                launcher.launch(mimeTypes.toTypedArray())
            }
        }
    }
}

/**
 * Moved verbatim from `FlashConversationScreen.kt`. A document provider may answer either column with
 * null — or expose neither — so both fall back: the last path segment for the name, 0 for the size.
 */
private fun resolveFileMetadata(context: Context, uri: Uri): Pair<String, Long> {
    var name = uri.lastPathSegment ?: "file"
    var size = 0L
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    name = cursor.getString(nameIndex)
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
    }
    return name to size
}
