package com.transfer.flash.ui.shims

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
    var currentUri by remember { mutableStateOf<Uri?>(null) }
    var currentFile by remember { mutableStateOf<File?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success: Boolean ->
        val uri = currentUri
        val file = currentFile
        if (success && uri != null && file != null && file.exists() && file.length() > 0) {
            onCaptured(
                FlashPickedFile(
                    uri = uri.toString(),
                    name = file.name,
                    size = file.length(),
                    isPersistable = true,
                ),
            )
        }
    }

    return remember(launcher) {
        object : FlashCameraCaptureLauncher {
            override val capturesFromCamera: Boolean = true

            override fun launch() {
                try {
                    val cacheDir = File(context.cacheDir, "camera_captures").apply { mkdirs() }
                    val file = File(cacheDir, "flash_photo_${System.currentTimeMillis()}.jpg")
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    currentUri = uri
                    currentFile = file
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
