package com.transfer.flash.ptt

import android.annotation.SuppressLint
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.ptt.PttFloorState
import com.transfer.flash.core.ptt.FlashPtt
import com.transfer.flash.core.ptt.PttPressOutcome
import com.transfer.flash.notifications.FlashNotificationManager
import com.transfer.flash.ui.shims.FlashPermission
import com.transfer.flash.ui.shims.rememberFlashPermissionRequester
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.FlashTypography
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample

/**
 * Phase 3: in-app PTT session surface.
 *
 * A state-driven overlay (not a nav destination): it appears whenever the floor machine
 * leaves Idle and vanishes on return, so back-navigation underneath never kills a
 * session — the notification owns background visibility. Completes three jobs the
 * engine cannot do itself: consume deferred hardware presses (backgrounded / unpermitted
 * path, permission prompt included), toast session notices, and render status.
 *
 * Recomposition discipline (EXP-012/013): the root reads engine *state* only (rare
 * transitions). Seconds tick inside [PttElapsedText], 1 Hz stats text inside
 * [PttStatsText], and 10 Hz levels inside [PttLevelMeter] — each leaf owns its
 * subscription, so a level tick never recomposes the card.
 *
 * @param animateLevels tier gate from the shell (false on LOW); reduce-motion is read
 * directly from [FlashTheme.motion] here.
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
        when (engine.onPttButton()) {
            PttPressOutcome.NO_PEERS ->
                showToast("No paired devices online")
            PttPressOutcome.CALL_ACTIVE ->
                showToast("Call in progress — PTT unavailable")
            PttPressOutcome.VOICE_NOTE_ACTIVE ->
                showToast("Voice recording in progress — PTT unavailable")
            else -> Unit
        }
    }

    val talking = sessionState as? PttFloorState.Talking
    val listening = sessionState as? PttFloorState.Listening
    if (talking == null && listening == null) return
    val startedAtMs = talking?.startedAtMs ?: listening!!.startedAtMs
    val isTalking = talking != null
    val colors = FlashTheme.colors
    val shapes = FlashShapes
    val reduceMotion = FlashTheme.motion.reduceMotion

    Box(
        modifier = Modifier.fillMaxSize().background(colors.scrim).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.backgroundSurfaceStrong, shapes.button)
                .border(1.dp, colors.borderSubtle, shapes.button)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlashText(
                text = if (isTalking) "You're talking" else "Listening — ${listening?.holderName ?: "Peer"}",
                style = FlashTypography.default().bodyEmphasis,
                color = colors.textPrimary,
            )
            PttElapsedText(startedAtMs)
            PttStatsText(engine, isTalking)
            if (animateLevels && !reduceMotion) {
                PttLevelMeter(engine)
            } else {
                FlashText(
                    text = if (isTalking) "Transmitting…" else "Receiving audio…",
                    style = FlashTypography.default().captionDefault,
                    color = colors.textSecondary,
                )
            }
            Box(
                modifier = Modifier
                    .background(colors.accentPrimary, shapes.chip)
                    .clickable(onClickLabel = if (isTalking) "Stop" else "Leave") {
                        engine.stopLocal()
                    }
                    .padding(horizontal = 28.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                FlashText(
                    text = if (isTalking) "Stop" else "Leave",
                    style = FlashTypography.default().bodyEmphasis,
                    color = colors.textOnAccent,
                )
            }
        }
    }
}

/**
 * mm:ss ticker scoped to this leaf (call-screen clock pattern): sleeps to the next
 * monotonic second boundary so wall-clock changes cannot jump a session timer.
 */
@Composable
private fun PttElapsedText(startedAtMs: Long) {
    var nowMs by remember(startedAtMs) { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAtMs) {
        while (true) {
            val current = SystemClock.elapsedRealtime()
            val next = (current / 1000 + 1) * 1000
            delay((next - current).coerceAtLeast(0L))
            nowMs = SystemClock.elapsedRealtime()
        }
    }
    FlashText(
        text = formatPttElapsed(nowMs - startedAtMs),
        style = FlashTypography.default().bodyDefault,
        color = FlashTheme.colors.textSecondary,
    )
}

internal fun formatPttElapsed(elapsedMs: Long): String {
    val totalSec = (elapsedMs.coerceAtLeast(0L) / 1000).toInt()
    return "${totalSec / 60}:${(totalSec % 60).toString().padStart(2, '0')}"
}

/** 1 Hz stats line; owns its sampled subscription so the card never tick-recomposes. */
@Composable
@SuppressLint("StateFlowValueCalledInComposition") // `.value` only seeds collectAsState's
// initial; the flow itself is collected, so the UI does recompose on change.
private fun PttStatsText(engine: FlashPtt, talking: Boolean) {
    val stats by remember(engine) {
        engine.stats.sample(1000L)
    }.collectAsState(initial = engine.stats.value)
    val snapshot = stats
    val body = when {
        snapshot == null -> "Live"
        talking -> listOfNotNull(
            "Live",
            "${snapshot.members} listening".takeIf { snapshot.members > 0 },
            snapshot.rttMs?.let { "$it ms" },
        ).joinToString(" • ")
        else -> "Live • ${(snapshot.lossPercent * 100).roundToInt()}% loss" +
            (snapshot.rttMs?.let { " • $it ms" } ?: "")
    }
    FlashText(
        text = body,
        style = FlashTypography.default().captionDefault,
        color = FlashTheme.colors.textSecondary,
    )
}

/**
 * Live level bars at 10 Hz, leaf-scoped (see file KDoc): a 24-bar pseudo-spectrum whose
 * heights follow the packet RMS. Deterministic per level (no timers) — bars move only
 * when the voice does. Static status text replaces it on LOW / reduce-motion.
 */
@Composable
@SuppressLint("StateFlowValueCalledInComposition") // `.value` only seeds collectAsState's
// initial; the flow itself is collected, so the UI does recompose on change.
private fun PttLevelMeter(engine: FlashPtt) {
    val stats by remember(engine) {
        engine.stats.sample(100L)
    }.collectAsState(initial = engine.stats.value)
    val level = (stats?.amplitude01 ?: 0f).coerceIn(0f, 1f)
    val colors = FlashTheme.colors
    Canvas(modifier = Modifier.fillMaxWidth().height(48.dp)) {
        val bars = 24
        val gapPx = 4.dp.toPx()
        val barWidth = (size.width - gapPx * (bars - 1)) / bars
        if (barWidth <= 0f) return@Canvas
        val trackColor = colors.borderSubtle
        val liveColor = colors.accentPrimary
        for (i in 0 until bars) {
            val weight = 0.35f + 0.65f * abs(sin(i * 0.7f))
            val full = size.height
            val live = (size.height * (0.08f + 0.92f * level * weight))
                .coerceIn(2.dp.toPx(), full)
            val x = i * (barWidth + gapPx)
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(x, 0f),
                size = Size(barWidth, full),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
            drawRoundRect(
                color = liveColor,
                topLeft = Offset(x, (full - live) / 2f),
                size = Size(barWidth, live),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }
    }
}
