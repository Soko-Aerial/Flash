package com.transfer.flash

import java.io.File

/**
 * Whether [path], taken from an intent extra, names a plain file inside one of the app's own received-file roots.
 *
 * `MainActivity` is exported (launcher and share target), so the "open this file" action can be sent by any app. The
 * notification that legitimately sends it always carries a real filesystem path of a received file, so a URI string
 * (`content://` or `file://`, which would hand a read grant for any URI Flash holds to whichever app answers the view
 * intent) and any path outside the roots are refused (SEC-D11-01).
 */
internal fun isOpenableReceivedPath(path: String, roots: List<File>): Boolean {
    if (path.isBlank() || path.contains("://") || path.contains('\u0000')) return false
    val file = try {
        File(path).canonicalFile
    } catch (e: java.io.IOException) {
        return false
    }
    return roots.any { root ->
        val canonicalRoot = try { root.canonicalFile } catch (e: java.io.IOException) { return@any false }
        file.path.startsWith(canonicalRoot.path + File.separator)
    }
}
