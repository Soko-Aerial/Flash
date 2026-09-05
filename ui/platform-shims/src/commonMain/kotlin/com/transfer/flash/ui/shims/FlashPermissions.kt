package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable

/**
 * The runtime permissions Flash's chat UI asks for.
 *
 * Exactly one entry today — `RECORD_AUDIO`, for voice messages. Modelled as an enum rather than a raw
 * permission string because the string is an Android constant that `commonMain` cannot name, and
 * because a desktop actual has to be able to answer for the whole set without knowing any of them.
 */
public enum class FlashPermission {
    Microphone,
}

/** Reads and requests runtime permissions. */
public interface FlashPermissionRequester {
    /** Whether [permission] is already held. Cheap; safe to call on every event. */
    public fun isGranted(permission: FlashPermission): Boolean

    /**
     * Returns immediately if [permission] is already held, otherwise shows the platform prompt and
     * suspends until the user answers. Returns whether it is held afterwards.
     */
    public suspend fun ensureGranted(permission: FlashPermission): Boolean
}

/**
 * The permission seam, enacting **D7c** — "Android `actual` uses existing `RequestPermission` +
 * `ContextCompat` internally; desktop returns granted unconditionally. No permissions library."
 *
 * D7c words the seam as a bare `expect suspend fun ensurePermission(...)`. It cannot be one: the
 * Android implementation needs an `ActivityResultLauncher`, which only
 * `rememberLauncherForActivityResult` can produce and only from a composition. So the seam is a
 * `@Composable` factory returning a handle whose method is the `suspend fun` D7c describes — the same
 * shape Phase 18 had to use for `FlashThemeSwatches`, and for the same reason: the platform state
 * lives in the composition, not in a free function.
 */
@Composable
public expect fun rememberFlashPermissionRequester(): FlashPermissionRequester
