package com.transfer.flash.ui.calling

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.shepeliev.webrtckmp.VideoStreamTrack
import com.transfer.flash.core.calling.FlashCallMedia
import com.transfer.flash.core.calling.model.FlashCallParticipantState
import com.transfer.flash.core.calling.model.FlashCallParticipantUi
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.core.calling.model.FlashParticipantVideo
import com.transfer.flash.ui.avatar.FlashAvatar
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Group video call surface (UI-050b, G1; `docs/ui/calling-ui.md`): one tile per participant plus the
 * local camera as the same corner PiP as a 1:1 call. In the compact shape (UI-050c, G5: this device
 * receives one video) it is a single main tile; the participant strip sits above the controls
 * ([FlashGroupVideoStrip]). A tap pins a participant's video, and a tap on the pinned one unpins it.
 *
 * Every tile is a direct child of one [Layout], keyed by device id, so a participant's renderer stays
 * the same composable instance when others join or leave and its tile moves to another row. Each
 * tile that has a picture (granted, or an older client that always sends) composes its
 * [FlashCallVideoSurface], whose track may be null for a moment, so a renegotiated track only
 * re-binds the sink. A tile with no picture shows only the avatar and composes no surface at all:
 * on Android every surface is a native view with its own renderer thread and compositor layer, and
 * a call of eight with one watched peer would otherwise carry seven of them for nothing.
 */
@Composable
internal fun FlashGroupVideoSurfaces(
    state: FlashCallUiState,
    session: FlashCallMedia?,
    onVideoFocus: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val remoteTracks = rememberRemoteVideoTracks(session?.remoteVideoTracks)
    val localTrack = rememberVideoStreamTrack(session?.localVideoStreamTrack)
    val present = state.participants.filter { it.state != FlashCallParticipantState.LEFT }
    // Compact (G5): one main tile. It is one composable instance, so a new main person only re-binds its renderer.
    val main = if (state.compactVideo) groupVideoMainPeer(state) else null
    val tiles = if (main != null) present.filter { it.peerId == main } else present
    val rounded = tiles.size > 1

    Box(modifier = modifier) {
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                if (main != null) {
                    val participant = tiles.single()
                    val pinned = participant.peerId == state.videoFocusPeerId
                    FlashGroupVideoTile(
                        participant = participant,
                        track = remoteTracks[participant.peerId],
                        rounded = false,
                        pinned = pinned,
                        // Only unpins: a stray tap must not pin the current speaker by surprise.
                        onClick = if (pinned) ({ onVideoFocus(null) }) else null,
                        dataSaver = state.dataSaver,
                        showingFewer = state.showingFewerVideos,
                        isMain = true,
                    )
                } else {
                    tiles.forEach { participant ->
                        key(participant.peerId) {
                            FlashGroupVideoTile(
                                participant = participant,
                                track = remoteTracks[participant.peerId],
                                rounded = rounded,
                                pinned = participant.peerId == state.videoFocusPeerId,
                                onClick = { onVideoFocus(nextVideoFocus(state, participant.peerId)) },
                                dataSaver = state.dataSaver,
                                showingFewer = state.showingFewerVideos,
                                isMain = false,
                            )
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val gap = if (measurables.size > 1) TILE_GAP.roundToPx() else 0
            val rects = groupVideoTileRects(measurables.size, width, height, gap)
            val placeables = measurables.mapIndexed { i, m ->
                val r = rects[i]
                m.measure(Constraints.fixed(r.width.coerceAtLeast(0), r.height.coerceAtLeast(0)))
            }
            layout(width, height) {
                placeables.forEachIndexed { i, p -> p.place(rects[i].x, rects[i].y) }
            }
        }

        FlashCallVideoSurface(
            track = localTrack,
            fit = CallVideoFit.Fit,
            zOrderMediaOverlay = true,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(FlashSpacing.space16)
                .width(PIP_WIDTH)
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(FlashShapes.radius12))
                .border(1.dp, FlashTheme.colors.backgroundSurfaceSubtle, RoundedCornerShape(FlashShapes.radius12)),
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(FlashSpacing.space16),
        ) {
            Text(
                text = state.peerName,
                style = FlashTheme.typography.headingMedium,
                color = Color.White,
            )
            FlashCallStatusLine(state = state, color = Color.White.copy(alpha = 0.8f))
            FlashCallStatsBadge(session = session, state = state, onDark = true)
        }
    }
}

@Composable
private fun FlashGroupVideoTile(
    participant: FlashCallParticipantUi,
    track: VideoStreamTrack?,
    rounded: Boolean,
    pinned: Boolean,
    onClick: (() -> Unit)?,
    dataSaver: Boolean,
    showingFewer: Boolean,
    isMain: Boolean,
) {
    val colors = FlashTheme.colors
    val shape = RoundedCornerShape(if (rounded) FlashShapes.radius12 else 0.dp)
    // G3: a negotiated track carries nothing until the participant grants this device's request.
    val showsVideo = track != null && participant.video.hasPicture()
    // Granted, but the track has not arrived yet: say so instead of showing an unexplained avatar.
    val status = participantStatusLabel(participant, dataSaver, showingFewer, isMain)
        ?: "Starting video…".takeIf { participant.state == FlashCallParticipantState.CONNECTED && participant.video.hasPicture() && track == null }
    val description = buildString {
        append(participant.name)
        append(if (showsVideo) ", video" else ", no video")
        if (status != null) append(", ").append(status)
        if (pinned) append(", pinned")
    }
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .clip(shape)
            .then(
                if (participant.isSpeaking) Modifier.border(2.dp, colors.statusOnline, shape) else Modifier,
            )
            .then(
                if (onClick != null) {
                    Modifier
                        .flashPressScale(interaction)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClickLabel = videoFocusClickLabel(participant.name, pinned),
                            role = Role.Button,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = description },
    ) {
        // Keyed on the grant, not on the track: the renderer is released only when the view is discarded (a track
        // change must never release it), and a grant that is lost really does discard it.
        if (participant.video.hasPicture()) {
            FlashCallVideoSurface(track = track, fit = CallVideoFit.Balanced, modifier = Modifier.fillMaxSize())
        }
        if (!showsVideo) {
            Box(
                modifier = Modifier.fillMaxSize().background(EMPTY_TILE),
                contentAlignment = Alignment.Center,
            ) {
                FlashAvatar(
                    initials = participant.name.take(2),
                    seed = participant.peerId,
                    size = FlashDimensions.avatarLg,
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) {
                    FlashIcon(
                        icon = FlashIcons.Pin,
                        contentDescription = null,
                        tint = Color.White,
                        size = FlashDimensions.iconSm,
                    )
                    Box(Modifier.size(FlashSpacing.space4))
                }
                Text(
                    text = participant.name,
                    style = FlashTheme.typography.metadataDefault,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                FlashPeerBadges(
                    micMuted = participant.isMuted,
                    cameraOff = false,
                    handRaised = participant.handRaised,
                    onDark = true,
                    modifier = Modifier.padding(start = FlashSpacing.space4),
                )
            }
            if (status != null) {
                Text(
                    text = status,
                    style = FlashTheme.typography.metadataDefault,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The status word under a participant's name, or null when there is nothing to say.
 *
 * [dataSaver] and [showingFewer] are this device's own settings (ERROR-105): under either, a tile that was never asked
 * for video says why it shows none, instead of a bare avatar that looks like a tap that did nothing. [isMain] is the
 * compact layout's one main tile (it has the single video "Show fewer" allows).
 */
internal fun participantStatusLabel(
    participant: FlashCallParticipantUi,
    dataSaver: Boolean = false,
    showingFewer: Boolean = false,
    isMain: Boolean = false,
): String? = participant.note ?: when (participant.state) {
    FlashCallParticipantState.INVITED -> if (participant.reachable) "Invited" else "Not reachable yet"
    FlashCallParticipantState.CONNECTING -> "Connecting…"
    FlashCallParticipantState.CONNECTED -> {
        // A request for this person's video that was refused or went unanswered is said first, and "Muted" no longer
        // hides it: whoever tapped the tile to see that video needs to know why nothing came. Anything that is merely
        // this person's own state (their camera is off, a request still pending) stays below "Muted", which is what
        // blocks the conversation.
        val video = videoStatusLabel(participant)
        val failed = participant.video in VIDEO_REQUEST_FAILED
        when {
            video != null && failed && participant.isMuted -> "$video · Muted"
            participant.isMuted -> "Muted"
            video != null -> video
            participant.handRaised -> "Hand raised"
            else -> videoOffLabel(participant, dataSaver, showingFewer, isMain)
        }
    }
    FlashCallParticipantState.DISCONNECTED -> "Reconnecting…"
    FlashCallParticipantState.LEFT -> "Left"
}

/**
 * ERROR-105: why a video that was never asked for is not showing, when this device's own setting is the reason.
 * Data saver asks nobody for video; "Show fewer" asks for one. Null when the video is on its way, was refused (the
 * labels of [videoStatusLabel] say that) or nothing on this device holds it back.
 */
internal fun videoOffLabel(
    participant: FlashCallParticipantUi,
    dataSaver: Boolean,
    showingFewer: Boolean,
    isMain: Boolean,
): String? = when {
    participant.video != FlashParticipantVideo.OFF -> null
    dataSaver -> "Video is off to save data"
    showingFewer && !isMain -> "Showing fewer videos"
    else -> null
}

/** The answers to a video request that mean "you will not get it now". */
private val VIDEO_REQUEST_FAILED = setOf(
    FlashParticipantVideo.CAMERA_OFF,
    FlashParticipantVideo.BUSY,
    FlashParticipantVideo.SENDER_HOT,
    FlashParticipantVideo.NO_RESPONSE,
)

/** Why this participant's video is not showing (or is on its way), or null when it is showing or was never asked for. */
internal fun videoStatusLabel(participant: FlashCallParticipantUi): String? = when {
    participant.video == FlashParticipantVideo.CAMERA_OFF || participant.cameraOff -> "Camera off"
    participant.video == FlashParticipantVideo.BUSY -> "Video busy"
    participant.video == FlashParticipantVideo.SENDER_HOT -> "Too hot to send video"
    participant.video == FlashParticipantVideo.NO_RESPONSE -> "Video not responding"
    participant.video == FlashParticipantVideo.REQUESTED -> "Requesting video…"
    else -> null
}

/**
 * Whom the compact main tile shows (UI-050c): the core's choice (pinned, else the followed speaker),
 * else the first participant whose video arrives, else the first participant. Null with nobody left.
 */
internal fun groupVideoMainPeer(state: FlashCallUiState): String? {
    val present = state.participants.filter { it.state != FlashCallParticipantState.LEFT }
    state.videoMainPeerId?.let { id -> if (present.any { it.peerId == id }) return id }
    return (present.firstOrNull { it.video.hasPicture() } ?: present.firstOrNull())?.peerId
}

/** What a tap on [tapped] pins (UI-050c): the tapped participant, or null (follow the speaker) if it was pinned. */
internal fun nextVideoFocus(state: FlashCallUiState, tapped: String): String? =
    if (state.videoFocusPeerId == tapped) null else tapped

/** The TalkBack action label for a tap on a participant's tile or chip. */
internal fun videoFocusClickLabel(name: String, pinned: Boolean): String =
    if (pinned) "Unpin $name's video" else "Pin $name's video"

/** Whether this participant's video is arriving: granted (G3), or an older client that always sends. */
internal fun FlashParticipantVideo.hasPicture(): Boolean =
    this == FlashParticipantVideo.RECEIVING || this == FlashParticipantVideo.UNMANAGED

@Composable
private fun rememberRemoteVideoTracks(
    flow: StateFlow<Map<String, VideoStreamTrack>>?,
): Map<String, VideoStreamTrack> {
    val source = remember(flow) { flow ?: MutableStateFlow(emptyMap()) }
    return source.collectAsState().value
}

/** One tile's position and size in pixels. */
internal data class TileRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Tiles per row for [count] tiles (UI-050b): 1 → one; 2 → stacked, or side by side when [wide];
 * 3–4 → two per row; 5+ → two per row, or three when [wide]. The last row may be shorter.
 */
internal fun groupVideoRows(count: Int, wide: Boolean): List<Int> {
    if (count <= 0) return emptyList()
    val columns = when {
        count == 1 -> 1
        count == 2 -> if (wide) 2 else 1
        count <= 4 -> 2
        else -> if (wide) 3 else 2
    }
    return List((count + columns - 1) / columns) { row -> minOf(columns, count - row * columns) }
}

/**
 * Lays out [count] tiles in a [width] × [height] box with [gap] pixels between and around them
 * (no outer gap for a single tile). Rows share the height; a row's tiles share its width, so a
 * short last row has wider tiles.
 */
internal fun groupVideoTileRects(count: Int, width: Int, height: Int, gap: Int): List<TileRect> {
    val rows = groupVideoRows(count, wide = width > height)
    if (rows.isEmpty()) return emptyList()
    val outer = if (count > 1) gap else 0
    val rowHeight = (height - 2 * outer - gap * (rows.size - 1)) / rows.size
    val out = ArrayList<TileRect>(count)
    rows.forEachIndexed { rowIndex, perRow ->
        val y = outer + rowIndex * (rowHeight + gap)
        val tileWidth = (width - 2 * outer - gap * (perRow - 1)) / perRow
        repeat(perRow) { col ->
            out += TileRect(outer + col * (tileWidth + gap), y, tileWidth, rowHeight)
        }
    }
    return out
}

private val TILE_GAP = 4.dp
private val EMPTY_TILE = Color(0xFF1C1C1E)
