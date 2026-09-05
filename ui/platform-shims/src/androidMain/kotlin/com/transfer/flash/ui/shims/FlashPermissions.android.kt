package com.transfer.flash.ui.shims

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * `ContextCompat.checkSelfPermission` + `ActivityResultContracts.RequestPermission()`, which is the
 * pair `FlashConversationScreen` already used. The behaviour visible to the user is unchanged: the
 * same system dialog, raised at the same moment, with the same two outcomes.
 */
@Composable
public actual fun rememberFlashPermissionRequester(): FlashPermissionRequester {
    val context = LocalContext.current
    // A plain holder, NOT `mutableStateOf`: parking a continuation is not UI state, and writing it
    // through the snapshot system would invalidate this composition on every permission request.
    val pending = remember { PendingPermissionRequest() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val continuation = pending.continuation
        pending.continuation = null
        if (continuation != null && continuation.isActive) {
            continuation.resumeWith(Result.success(granted))
        }
    }
    return remember(context, launcher) {
        object : FlashPermissionRequester {
            override fun isGranted(permission: FlashPermission): Boolean =
                ContextCompat.checkSelfPermission(context, permission.manifestName) ==
                    PackageManager.PERMISSION_GRANTED

            override suspend fun ensureGranted(permission: FlashPermission): Boolean {
                if (isGranted(permission)) return true
                return suspendCancellableCoroutine { continuation ->
                    pending.continuation = continuation
                    // If the caller's scope dies while the dialog is up — the screen is left, or the
                    // Activity is recreated — drop the reference so the launcher callback resumes
                    // nothing. The caller is cancelled either way; this only stops a stale
                    // continuation being held past its scope.
                    continuation.invokeOnCancellation { pending.continuation = null }
                    launcher.launch(permission.manifestName)
                }
            }
        }
    }
}

private class PendingPermissionRequest {
    var continuation: CancellableContinuation<Boolean>? = null
}

/**
 * The one mapping this shim exists to hide. `commonMain` cannot name `android.Manifest.permission`,
 * and a `when` with no `else` means adding an entry to [FlashPermission] fails to compile here rather
 * than silently asking for nothing.
 */
private val FlashPermission.manifestName: String
    get() = when (this) {
        FlashPermission.Microphone -> Manifest.permission.RECORD_AUDIO
    }
