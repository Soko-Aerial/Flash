package com.transfer.flash.ptt

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.transfer.flash.core.messaging.ptt.PttFloorState
import com.transfer.flash.core.ptt.FlashPtt
import com.transfer.flash.notifications.FlashNotificationManager
import com.transfer.flash.ui.calling.PttSessionOverlayContent
import com.transfer.flash.ui.calling.pttPressOutcomeMessage
import com.transfer.flash.ui.shims.FlashPermission
import com.transfer.flash.ui.shims.rememberFlashPermissionRequester

/**
 * Phase 3: in-app PTT session surface, Android host.
 *
 * A state-driven overlay (not a nav destination): it appears whenever the floor machine
 * leaves Idle and vanishes on return, so back-navigation underneath never kills a
 * session — the notification owns background visibility. Completes three jobs the
 * engine cannot do itself: consume deferred hardware presses (backgrounded / unpermitted
 * path, permission prompt included), toast session notices, and render status.
 *
 * The card itself (role, elapsed, stats, level meter, Stop/Leave and its recomposition
 * discipline, EXP-012/013) lives in `:ui:callui` as [PttSessionOverlayContent] since ADR-058, so
 * the desktop shell draws the very same surface. Everything Android-specific stays here:
 * the `RECORD_AUDIO` prompt and [FlashNotificationManager].
 *
 * @param animateLevels tier gate from the shell (false on LOW); reduce-motion is read
 * from the theme inside the shared card.
 */
@Composable
public fun PttSessionOverlay(
    engine: FlashPtt?,
    pressPending: Boolean,
    ready: Boolean,
    animateLevels: Boolean,
    onConsumePress: () -> Unit,
    showToast: (String) -> Unit,
) {
    if (engine == null) {
        LaunchedEffect(pressPending) {
            if (pressPending) {
                showToast("Flash is starting — try again")
                onConsumePress()
            }
        }
        return
    }
    val sessionState by engine.state.collectAsState()
    LaunchedEffect(engine) {
        engine.notices.collect { showToast(it) }
    }
    val permissions = rememberFlashPermissionRequester()
    val context = LocalContext.current
    LaunchedEffect(pressPending, ready) {
        if (!pressPending || !ready) return@LaunchedEffect
        onConsumePress()
        FlashNotificationManager.clearPttTapToTalk(context)
        if (engine.state.value !is PttFloorState.Idle) return@LaunchedEffect
        val granted = permissions.isGranted(FlashPermission.Microphone) ||
            permissions.ensureGranted(FlashPermission.Microphone)
        if (!granted) {
            showToast("Microphone permission is needed to talk")
            return@LaunchedEffect
        }
        pttPressOutcomeMessage(engine.onPttButton())?.let(showToast)
    }

    PttSessionOverlayContent(
        engine = engine,
        state = sessionState,
        animateLevels = animateLevels,
    )
}
