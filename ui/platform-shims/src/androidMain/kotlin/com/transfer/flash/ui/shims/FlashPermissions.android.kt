package com.transfer.flash.ui.shims

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * `ContextCompat.checkSelfPermission` + `ActivityResultContracts.RequestPermission()`, which is the
 * pair `FlashConversationScreen` already used. The behaviour visible to the user is unchanged: the
 * same system dialog, raised at the same moment, with the same two outcomes.
 *
 * What did change is what happens around it: the launcher has one callback and no request id, so it
 * serves one request at a time. [PermissionRequestArbiter] queues concurrent requests instead of
 * letting the second one overwrite the first caller's continuation.
 */
@Composable
public actual fun rememberFlashPermissionRequester(): FlashPermissionRequester {
    val context = LocalContext.current
    // A plain holder, NOT `mutableStateOf`: parking a continuation is not UI state, and writing it
    // through the snapshot system would invalidate this composition on every permission request.
    val requests = remember { PermissionRequestArbiter() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requests.onResult(granted)
    }
    return remember(context, launcher, requests) {
        object : FlashPermissionRequester {
            override fun isGranted(permission: FlashPermission): Boolean =
                ContextCompat.checkSelfPermission(context, permission.manifestName) ==
                    PackageManager.PERMISSION_GRANTED

            override suspend fun ensureGranted(permission: FlashPermission): Boolean =
                requests.ensure(
                    permission = permission.manifestName,
                    // Re-read per attempt: an earlier dialog, or a grant made in Settings while this
                    // request was queued, must not raise a second dialog.
                    isGranted = { name ->
                        ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
                    },
                    launch = { name -> launcher.launch(name) },
                )
        }
    }
}

/**
 * The one mapping this shim exists to hide. `commonMain` cannot name `android.Manifest.permission`,
 * and a `when` with no `else` means adding an entry to [FlashPermission] fails to compile here rather
 * than silently asking for nothing.
 */
private val FlashPermission.manifestName: String
    get() = when (this) {
        FlashPermission.Microphone -> Manifest.permission.RECORD_AUDIO
        FlashPermission.Camera -> Manifest.permission.CAMERA
    }
