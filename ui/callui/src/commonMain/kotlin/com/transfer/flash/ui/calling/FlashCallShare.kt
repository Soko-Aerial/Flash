@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.calling

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.calling.FlashShareNotice
import com.transfer.flash.core.calling.ShareQuality
import com.transfer.flash.core.calling.ShareSource
import com.transfer.flash.core.calling.ShareSourceKind
import com.transfer.flash.core.calling.model.FlashCallParticipantState
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashHaptic
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale
import com.transfer.flash.ui.theme.rememberFlashHaptics

/*
 * Screen share in calls (UI-050g, ADR-102, docs/ui/calling-ui.md "UI-050g"): the picker, the "You are sharing" indicator, the
 * receiver's label and the wording. The call surface only draws; every action is a lambda in [FlashCallShareHost].
 */

/**
 * What a host gives the call screen so a person can present. Null on a host that cannot (Android today): the screen then
 * shows no way to start a share, and still shows everything a receiver needs.
 */
public class FlashCallShareHost(
    /** Screens first, then windows; empty when nothing can be listed. Called when the picker opens. */
    public val listSources: suspend () -> List<ShareSource>,
    /** True when the system shows its own picker after the share starts (Wayland), so the list may be a single entry. */
    public val usesSystemPicker: Boolean,
    /** Starts presenting [source]. `takeOver` is true when somebody else is presenting and the person chose to replace them. */
    public val onStart: (source: ShareSource, quality: ShareQuality, takeOver: Boolean) -> Unit,
    public val onStop: () -> Unit,
    public val onSetQuality: (ShareQuality) -> Unit = {},
    public val onDismissNotice: () -> Unit = {},
)

/** The wording of screen share, pure so it is unit-tested. */
internal object CallShareText {
    const val ROW_START_TITLE: String = "Share screen"
    const val ROW_START_SUBTITLE: String = "Show your screen or a window to the call"
    const val ROW_STOP_TITLE: String = "Stop sharing"
    fun rowStopSubtitle(sourceTitle: String?): String =
        if (sourceTitle.isNullOrBlank()) "You are sharing your screen" else "You are sharing $sourceTitle"

    const val PICKER_TITLE: String = "Share your screen"
    const val SECTION_SCREENS: String = "Screens"
    const val SECTION_WINDOWS: String = "Windows"
    const val LOWER_TITLE: String = "Share at lower quality"
    const val LOWER_SUBTITLE: String = "Smaller picture for a slow computer. Small text may be harder to read."
    const val SYSTEM_DIALOG_NOTE: String = "Your system will ask which screen or window to share."
    const val NOTHING_FOUND: String = "Nothing to share was found."
    const val LOADING: String = "Looking for screens..."
    fun takeOverNote(name: String): String = "$name is presenting. If you share, their share stops."

    const val INDICATOR_STARTING: String = "Starting to share..."
    fun indicatorLive(sourceTitle: String?): String =
        if (sourceTitle.isNullOrBlank()) "You are sharing your screen" else "You are sharing $sourceTitle"
    const val STOP_LABEL: String = "Stop"
    const val STOP_DESCRIPTION: String = "Stop sharing your screen"
    const val LOWERED_HINT: String = "Your computer is busy, so the picture is smaller"
    fun watchersLine(watchers: Int): String = when {
        watchers <= 0 -> "Nobody is watching yet"
        watchers == 1 -> "Seen by 1 person"
        else -> "Seen by $watchers people"
    }

    fun presenterLabel(name: String): String = "$name is presenting"
    const val PRESENTING_CHIP: String = "Presenting"

    const val CAMERA_OFF_WHILE_SHARING: String = "Camera is off while sharing"

    /** The one-line message for [notice]; [presenterName] is the other presenter, when one is known. */
    fun notice(notice: FlashShareNotice, presenterName: String?, watcherCap: Int): String = when (notice) {
        FlashShareNotice.TAKEN_OVER ->
            if (presenterName.isNullOrBlank()) "Someone else started sharing, so yours stopped" else "$presenterName started sharing, so yours stopped"
        FlashShareNotice.SOURCE_LOST -> "The shared window closed, so sharing stopped"
        FlashShareNotice.NO_FRAMES -> "Nothing could be captured from that screen, so sharing stopped"
        FlashShareNotice.FAILED -> "Sharing could not start"
        FlashShareNotice.LOWERED -> "Sharing at a lower quality to keep up"
        FlashShareNotice.WATCHER_CAP -> "Only $watcherCap people can watch your share at once"
        FlashShareNotice.SOMEONE_PRESENTING ->
            if (presenterName.isNullOrBlank()) "Someone is presenting. Stop theirs first or share anyway" else "$presenterName is presenting. Stop theirs first or share anyway"
    }
}

/** The name of the other device that presents, or null when nobody does. */
internal fun sharePresenterName(state: FlashCallUiState): String? {
    val id = state.presenterId ?: return null
    if (!state.isGroup) return state.peerName
    return state.participants.firstOrNull { it.peerId == id }?.name ?: "Someone"
}

/** Whether this device can start a share now: the core says so, and the host wired the actions. */
internal fun shareStartAvailable(state: FlashCallUiState, host: FlashCallShareHost?): Boolean =
    host != null && state.video && (state.canShareScreen || state.sharing) && !state.shareStarting

/** The id of the group participant that presents and is still in the call, else null. */
internal fun groupPresenterPeer(state: FlashCallUiState): String? {
    val id = state.presenterId ?: return null
    return id.takeIf { wanted ->
        state.participants.any { it.peerId == wanted && it.state != FlashCallParticipantState.LEFT }
    }
}

/**
 * How a tile's picture is fitted: a presentation is letterboxed so no text is cropped away, a camera keeps the usual balanced fit.
 */
internal fun videoFitFor(presenting: Boolean): CallVideoFit = if (presenting) CallVideoFit.Fit else CallVideoFit.Balanced

/**
 * Tile rectangles when somebody presents: tile 0 (the presenter) is the stage and takes [STAGE_FRACTION] of the height,
 * everyone else shares one row under it. With nobody else, the presenter takes everything.
 */
internal fun groupVideoShareRects(count: Int, width: Int, height: Int, gap: Int): List<TileRect> {
    if (count <= 0) return emptyList()
    if (count == 1) return listOf(TileRect(0, 0, width, height))
    val others = count - 1
    val stageHeight = ((height - gap) * STAGE_FRACTION).toInt()
    val rowHeight = (height - gap - stageHeight).coerceAtLeast(0)
    val out = ArrayList<TileRect>(count)
    out += TileRect(0, 0, width, stageHeight)
    val tileWidth = (width - gap * (others - 1)) / others
    repeat(others) { i -> out += TileRect(i * (tileWidth + gap), stageHeight + gap, tileWidth, rowHeight) }
    return out
}

private const val STAGE_FRACTION = 0.7f

/** The strip that cannot be missed while this device presents. Drawn above the video and above every panel. */
@Composable
internal fun FlashShareIndicator(
    state: FlashCallUiState,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.sharing) return
    val colors = FlashTheme.colors
    val haptic = rememberFlashHaptics()
    val interaction = remember { MutableInteractionSource() }
    val headline = if (state.shareStarting) CallShareText.INDICATOR_STARTING else CallShareText.indicatorLive(state.shareSourceTitle)
    val detail = when {
        state.shareStarting -> null
        state.shareLowered -> CallShareText.LOWERED_HINT
        else -> CallShareText.watchersLine(state.shareWatchers)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.accentPrimary)
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8)
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = listOfNotNull(headline, detail).joinToString(". ")
            },
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A red dot AND the words AND the glyph: the state is never colour alone.
        Box(Modifier.size(FlashSpacing.space8).clip(CircleShape).background(colors.textError).clearAndSetSemantics { })
        FlashIcon(
            icon = FlashIcons.ScreenShare,
            contentDescription = null,
            tint = colors.textOnAccent,
            size = FlashDimensions.iconMd,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = headline,
                style = FlashTheme.typography.bodyDefault,
                color = colors.textOnAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.textOnAccent.copy(alpha = 0.85f),
                    maxLines = 2,
                )
            }
        }
        Box(
            modifier = Modifier
                .heightIn(min = FlashDimensions.minTouchTarget)
                .flashPressScale(interaction)
                .clip(RoundedCornerShape(FlashShapes.radius24))
                .background(colors.textOnAccent)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                    haptic(FlashHaptic.Tick)
                    onStop()
                }
                .padding(horizontal = FlashSpacing.space20)
                .semantics { contentDescription = CallShareText.STOP_DESCRIPTION },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = CallShareText.STOP_LABEL,
                style = FlashTheme.typography.bodyDefault,
                color = colors.accentPrimary,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

/** "Ana is presenting", over the picture a receiver is looking at. */
@Composable
internal fun FlashPresenterLabel(name: String, modifier: Modifier = Modifier) {
    val text = CallShareText.presenterLabel(name)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4)
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = text
            },
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlashIcon(
            icon = FlashIcons.ScreenShare,
            contentDescription = null,
            tint = Color.White,
            size = FlashDimensions.iconSm,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Text(text = text, style = FlashTheme.typography.metadataDefault, color = Color.White, maxLines = 1, modifier = Modifier.clearAndSetSemantics { })
    }
}

/** The picker: the sources the capturer can see, the quality switch, and a note when the system chooses. */
@Composable
internal fun FlashSharePicker(
    state: FlashCallUiState,
    host: FlashCallShareHost,
    onDismiss: () -> Unit,
) {
    val colors = FlashTheme.colors
    var lower by remember { mutableStateOf(state.shareQuality == ShareQuality.LOWER) }
    val loaded by produceState<List<ShareSource>?>(initialValue = null, host) { value = host.listSources() }
    val presenter = sharePresenterName(state)
    val takeOver = state.presenterId != null
    FlashCallPanel(title = CallShareText.PICKER_TITLE, onDismiss = onDismiss) {
        if (presenter != null) {
            Text(
                text = CallShareText.takeOverNote(presenter),
                style = FlashTheme.typography.metadataDefault,
                color = colors.textSecondary,
                modifier = Modifier.padding(bottom = FlashSpacing.space8),
            )
        }
        FlashCallPanelRow(
            icon = FlashIcons.DataSaver,
            title = CallShareText.LOWER_TITLE,
            subtitle = CallShareText.LOWER_SUBTITLE,
            selected = lower,
            onClick = {
                lower = !lower
                host.onSetQuality(if (lower) ShareQuality.LOWER else ShareQuality.STANDARD)
            },
        )
        if (host.usesSystemPicker) {
            Text(
                text = CallShareText.SYSTEM_DIALOG_NOTE,
                style = FlashTheme.typography.metadataDefault,
                color = colors.textSecondary,
                modifier = Modifier.padding(vertical = FlashSpacing.space8),
            )
        }
        val sources = loaded
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            when {
                sources == null -> Text(
                    text = CallShareText.LOADING,
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(vertical = FlashSpacing.space12),
                )
                sources.isEmpty() -> Text(
                    text = CallShareText.NOTHING_FOUND,
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(vertical = FlashSpacing.space12),
                )
                else -> {
                    val screens = sources.filter { it.kind == ShareSourceKind.SCREEN }
                    val windows = sources.filter { it.kind == ShareSourceKind.WINDOW }
                    val start: (ShareSource) -> Unit = { source ->
                        host.onStart(source, if (lower) ShareQuality.LOWER else ShareQuality.STANDARD, takeOver)
                        onDismiss()
                    }
                    if (screens.isNotEmpty() && !host.usesSystemPicker) SectionLabel(CallShareText.SECTION_SCREENS)
                    screens.forEach { source ->
                        FlashCallPanelRow(
                            icon = FlashIcons.ScreenShare,
                            title = source.title,
                            subtitle = null,
                            selected = null,
                            onClick = { start(source) },
                        )
                    }
                    if (windows.isNotEmpty()) SectionLabel(CallShareText.SECTION_WINDOWS)
                    windows.forEach { source ->
                        FlashCallPanelRow(
                            icon = FlashIcons.Device,
                            title = source.title,
                            subtitle = null,
                            selected = null,
                            onClick = { start(source) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.size(FlashSpacing.space4))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = FlashTheme.typography.captionDefault,
        color = FlashTheme.colors.textSecondary,
        modifier = Modifier.padding(top = FlashSpacing.space8, bottom = FlashSpacing.space4),
    )
}

/** The cap shown in the "Only N people can watch" notice (the ladder's, for the device's tier is told by the core). */
internal const val SHARE_NOTICE_DEFAULT_CAP: Int = 4

