package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * JVM / Desktop camera capture fallback: allows choosing an existing photo from disk.
 */
@Composable
public actual fun rememberFlashCameraCaptureLauncher(
    onCaptured: (FlashPickedFile) -> Unit,
): FlashCameraCaptureLauncher = remember(onCaptured) {
    object : FlashCameraCaptureLauncher {
        override fun launch() {
            val chooser = JFileChooser().apply {
                isMultiSelectionEnabled = false
                fileSelectionMode = JFileChooser.FILES_ONLY
                dialogTitle = "Select Photo"
                fileFilter = FileNameExtensionFilter("Images", "jpg", "jpeg", "png", "webp", "gif")
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                val file = chooser.selectedFile
                if (file != null && file.isFile && file.exists()) {
                    onCaptured(
                        FlashPickedFile(
                            uri = file.toURI().toString(),
                            name = file.name,
                            size = file.length(),
                        ),
                    )
                }
            }
        }
    }
}
