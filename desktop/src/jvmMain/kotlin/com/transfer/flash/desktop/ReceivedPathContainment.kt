package com.transfer.flash.desktop

import java.io.File

/**
 * True when a received file's destination stays inside the save folder (SEC-D07-01). [canonicalRoot], [destDir] and
 * [dest] must already be canonical (links resolved): the transfer folder has to sit under the root and the file under
 * the transfer folder. Checking only the file against its own folder let a pre-existing symlink or junction named like
 * the transfer id move both outside the root.
 */
internal fun isContainedInReceivedRoot(canonicalRoot: File, destDir: File, dest: File): Boolean =
    destDir.path.startsWith(canonicalRoot.path + File.separator) &&
        dest.path.startsWith(destDir.path + File.separator)
