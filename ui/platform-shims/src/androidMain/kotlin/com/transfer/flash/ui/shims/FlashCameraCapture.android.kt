package com.transfer.flash.ui.shims

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

@Composable
public actual fun rememberFlashCameraCaptureLauncher(
    onCaptured: (FlashPickedFile) -> Unit,
    onFailure: (String) -> Unit,
): FlashCameraCaptureLauncher {
    val context = LocalContext.current
    val latestOnFailure by rememberUpdatedState(onFailure)
    // The camera is another process: this composition is destroyed by a rotation or a low-memory kill
    // while it is in front, and the result is delivered to a NEW composition. Plain `remember` lost
    // both values there, so the photo the user had just taken was dropped on the floor. A `Uri`/`File`
    // is not Bundle-safe, so the two are saved as text and the file is rebuilt from its path.
    var currentUri by rememberSaveable { mutableStateOf<String?>(null) }
    var currentPath by rememberSaveable { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (!success) return@rememberLauncherForActivityResult
        // A restored value can outlive its temp file (the system may clear the cache), so the file is
        // checked rather than the saved path trusted.
        restoredCapture(currentUri, currentPath)?.let(onCaptured)
    }

    return remember(launcher) {
        object : FlashCameraCaptureLauncher {
            override val capturesFromCamera: Boolean = true

            override fun launch() {
                try {
                    val cacheDir = File(context.cacheDir, "camera_captures").apply { mkdirs() }
                    val file = File(cacheDir, "flash_photo_${System.currentTimeMillis()}.jpg")
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    currentUri = uri.toString()
                    currentPath = file.absolutePath
                    launcher.launch(uri)
                } catch (e: ActivityNotFoundException) {
                    latestOnFailure("No camera app found on this device")
                } catch (e: SecurityException) {
                    // The system camera intent throws when the app declares CAMERA but it is not granted.
                    latestOnFailure("Camera permission is required to take a photo")
                } catch (e: Exception) {
                    latestOnFailure("Could not open the camera")
                }
            }
        }
    }
}

/**
 * The already-captured photo described by two restored strings, or null when either is missing or the
 * temp file is gone or empty.
 *
 * The path is a cache-directory file, so the platform may clear it between the capture and the result
 * being delivered; a zero-length file is what a camera that was killed mid-write leaves behind. Both
 * cases mean "there is no photo", never a crash or a bubble pointing at nothing.
 */
internal fun restoredCapture(uri: String?, path: String?): FlashPickedFile? {
    if (uri.isNullOrBlank() || path.isNullOrBlank()) return null
    val file = File(path)
    if (!file.isFile || file.length() <= 0L) return null
    return FlashPickedFile(
        uri = uri,
        name = file.name,
        size = file.length(),
        isPersistable = true,
    )
}
