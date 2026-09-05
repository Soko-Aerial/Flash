package com.transfer.flash.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.FlashImageAttachmentUi
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.shims.FlashBackHandler
import com.transfer.flash.ui.shims.rememberFlashImageDecoder
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashMotion
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * UI-018 media viewer item model — one photo album entry with attribution metadata.
 */
data class FlashMediaViewerItem(
    val image: FlashImageAttachmentUi,
    val senderName: String,
    val timeLabel: String,
)

private data class FlashMediaDecodeState(
    val bitmap: ImageBitmap? = null,
    val failed: Boolean = false,
)

/**
 * Pure math + decision logic for the media viewer (UI-018).
 *
 * Free of Compose runtime types so gesture policy and decode guards are unit-testable
 * without instrumentation (see [FlashMediaViewerLogicTest]).
 */
object FlashMediaViewerMath {
    const val ZOOM_MIN = 1.0f
    const val ZOOM_MAX = 4.0f
    const val ZOOM_OVERSHOOT = 0.35f
    const val DOUBLE_TAP_SCALE = 2.3f
    const val VERTICAL_CLAIM_FACTOR = 2.0f
    const val DISMISS_DISTANCE_DP = 180f
    const val DISMISS_VELOCITY_PX_PER_SEC = 900f
    const val DISMISS_SCALE_DELTA = 0.06f
    const val MAX_DECODE_LONG_EDGE = 4096

    /** Hard clamp applied when a gesture settles (rubber-band target). */
    fun clampedScale(raw: Float): Float = raw.coerceIn(ZOOM_MIN, ZOOM_MAX)

    /** Pinch may temporarily overshoot [ZOOM_MAX] by [ZOOM_OVERSHOOT] before spring-in. */
    fun pinchCeiling(): Float = ZOOM_MAX * (1f + ZOOM_OVERSHOOT)

    /**
     * Offset correction keeping the image point under [centroidToCenter] fixed while scale
     * changes by [ratio] (new / old). Per axis; origin is the viewport center.
     */
    fun anchoredOffset(oldOffset: Float, centroidToCenter: Float, ratio: Float): Float =
        centroidToCenter - (centroidToCenter - oldOffset) * ratio

    /** Pan bounds per axis for the given viewport extent at [scale] (origin center). */
    fun panLimit(viewportExtentPx: Float, scale: Float): Float =
        max(0f, ((scale - 1f) * viewportExtentPx) / 2f)

    /** Dismiss claim: vertical displacement must exceed factor × touch slop AND dominate X. */
    fun claimsDismiss(totalDx: Float, totalDy: Float, touchSlopPx: Float): Boolean =
        abs(totalDy) > abs(totalDx) * VERTICAL_CLAIM_FACTOR && abs(totalDy) > touchSlopPx

    /** Drag release past distance threshold → dismiss. */
    fun shouldDismissByDistance(dragY: Float, thresholdPx: Float): Boolean =
        dragY >= thresholdPx

    /** Fling release past velocity threshold → dismiss. */
    fun shouldDismissByVelocity(velocityYPxPerSec: Float): Boolean =
        velocityYPxPerSec >= DISMISS_VELOCITY_PX_PER_SEC

    /** Chrome counter label, e.g. `3 / 7` (1-based). */
    fun counterLabel(index: Int, total: Int): String =
        "${(index + 1).coerceAtLeast(1)} / ${total.coerceAtLeast(1)}"

    /** TalkBack page description. */
    fun pageDescription(index: Int, total: Int): String =
        "Photo ${(index + 1).coerceAtLeast(1)} of ${total.coerceAtLeast(1)}"

    /** Clamp requested start index into valid pager range. */
    fun initialPage(requested: Int, total: Int): Int =
        if (total <= 0) 0 else requested.coerceIn(0, total - 1)

    /**
     * Power-of-two `inSampleSize` so the decoded long edge stays ≤ [MAX_DECODE_LONG_EDGE].
     */
    fun computeInSampleSize(width: Int, height: Int, maxLongEdge: Int = MAX_DECODE_LONG_EDGE): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var longEdge = maxOf(width, height)
        while (longEdge > maxLongEdge) {
            sample *= 2
            longEdge /= 2
        }
        return sample
    }

    /** Backdrop alpha mapped from dismiss progress 0→1. */
    fun backdropAlpha(dismissProgress: Float): Float = (1f - dismissProgress).coerceIn(0f, 1f)

    /** Page scale during dismiss drag: 1 → (1 − [DISMISS_SCALE_DELTA]) across full drag. */
    fun dismissPageScale(dismissProgress: Float): Float =
        1f - DISMISS_SCALE_DELTA * dismissProgress.coerceIn(0f, 1f)
}

/**
 * Per-page zoom + pan transform state read exclusively inside [graphicsLayer] scopes
 * (GPU transforms, zero recomposition during gestures).
 */
@Stable
class FlashZoomState internal constructor() {
    val scale = Animatable(FlashMediaViewerMath.ZOOM_MIN)
    val offsetX = Animatable(0f)
    val offsetY = Animatable(0f)

    val isZoomed: Boolean
        get() = scale.value > FlashMediaViewerMath.ZOOM_MIN + 0.01f

    /** Spring-reset all transforms (page change, double-tap collapse, close while zoomed). */
    suspend fun reset(motion: FlashMotion) {
        val spec = motion.springDefaultSpec<Float>()
        coroutineScope {
            launch { scale.animateTo(FlashMediaViewerMath.ZOOM_MIN, spec) }
            launch { offsetX.animateTo(0f, spec) }
            launch { offsetY.animateTo(0f, spec) }
        }
    }

    /** Rubber-band spring back into bounds after pinch/pan release. */
    suspend fun settle(viewport: Size, motion: FlashMotion) {
        val spec = motion.springDefaultSpec<Float>()
        val targetScale = FlashMediaViewerMath.clampedScale(scale.value)
        val limitX = FlashMediaViewerMath.panLimit(viewport.width, targetScale)
        val limitY = FlashMediaViewerMath.panLimit(viewport.height, targetScale)
        coroutineScope {
            launch { scale.animateTo(targetScale, spec) }
            launch { offsetX.animateTo(offsetX.value.coerceIn(-limitX, limitX), spec) }
            launch { offsetY.animateTo(offsetY.value.coerceIn(-limitY, limitY), spec) }
        }
    }
}

@Composable
fun rememberFlashZoomState(): FlashZoomState = remember { FlashZoomState() }

/**
 * UI-018 — Full-screen immersive media viewer.
 *
 * Render inside `AnimatedVisibility(motion.mediaOpenEnter(), motion.mediaOpenExit())`.
 * Always-dark backdrop; chrome auto-hides on single tap; per-page pinch/double-tap zoom;
 * un-zoomed vertical drag-to-dismiss; HorizontalPager album carousel.
 */
@Composable
fun FlashMediaViewer(
    items: List<FlashMediaViewerItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onShare: (Int) -> Unit = {},
    onSave: (Int) -> Unit = {},
    onForward: (Int) -> Unit = {},
    /**
     * Play the video on page [Int] in the platform player. Video pages show a frame like any other
     * page, so without this the badge would be decoration and a clip reachable only by swiping
     * (images and videos share one album) would be unplayable.
     */
    onPlayVideo: (Int) -> Unit = {},
) {
    if (items.isEmpty()) return

    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val pageCount = items.size
    val pagerState = rememberPagerState(
        initialPage = FlashMediaViewerMath.initialPage(initialIndex, pageCount),
        pageCount = { pageCount },
    )

    var chromeVisible by remember { mutableStateOf(true) }

    // Dismiss drag distance in px; read only inside draw/graphicsLayer scopes so the
    // gesture never recomposes — it only redraws backdrop alpha + page transform.
    val dismissDragPx = remember { mutableStateOf(0f) }
    val dismissThresholdPx = with(density) { FlashMediaViewerMath.DISMISS_DISTANCE_DP.dp.toPx() }

    fun requestDismiss() {
        scope.launch { onDismiss() }
    }

    FlashBackHandler { requestDismiss() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    color = colors.mediaViewerBackdrop.copy(
                        alpha = FlashMediaViewerMath.backdropAlpha(dismissDragPx.value / dismissThresholdPx),
                    ),
                )
            },
    ) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items[page]
            val zoomState = rememberFlashZoomState()

            // Clean transform contract: settled page change springs any zoom back to 1×.
            LaunchedEffect(pagerState.settledPage) {
                if (pagerState.settledPage != page && zoomState.isZoomed) {
                    zoomState.reset(motion)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        contentDescription = FlashMediaViewerMath.pageDescription(page, pageCount)
                    }
                    .graphicsLayer {
                        val progress = dismissDragPx.value / dismissThresholdPx
                        scaleX = FlashMediaViewerMath.dismissPageScale(progress)
                        scaleY = FlashMediaViewerMath.dismissPageScale(progress)
                        translationY = dismissDragPx.value * 0.5f
                    },
            ) {
                FlashMediaPage(
                    item = item,
                    zoomState = zoomState,
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    onPlay = { onPlayVideo(page) },
                    onDismissDrag = { dragY -> dismissDragPx.value = dragY },
                    onDismissSettle = { dismissDragPx.value = 0f },
                    onDismissConfirm = {
                        dismissDragPx.value = 0f
                        requestDismiss()
                    },
                )
            }
        }

        // Top chrome bar — counter + close
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(motion.tweenNormalSpec()),
            exit = fadeOut(motion.tweenFastSpec()),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = FlashSpacing.space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlashIconButtonChrome(
                    icon = FlashIcons.Close,
                    tint = colors.mediaViewerChromeText,
                    contentDescription = FlashIcons.Close.contentDescription,
                    onClick = ::requestDismiss,
                )
                Spacer(modifier = Modifier.weight(1f))
                FlashText(
                    text = FlashMediaViewerMath.counterLabel(pagerState.currentPage, pageCount),
                    style = FlashTheme.typography.metadataEmphasis,
                    color = colors.mediaViewerChromeText.copy(alpha = ChromeTextAlpha),
                    // UI-038: page counter changes on swipe — announce politely.
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(modifier = Modifier.weight(1f))
                // The overflow (⋮) slot held a button with an empty onClick — a control that looked
                // live and did nothing. Every action it could host already sits in the bottom bar,
                // so it is now a spacer that keeps the counter optically centred.
                Spacer(modifier = Modifier.size(FlashDimensions.minTouchTarget))
            }
        }

        // Bottom chrome bar — attribution + actions
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(motion.tweenNormalSpec()),
            exit = fadeOut(motion.tweenFastSpec()),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val currentItem = items[pagerState.currentPage]
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space12),
            ) {
                FlashText(
                    text = "${currentItem.senderName} • ${currentItem.timeLabel}",
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.mediaViewerChromeText.copy(alpha = ChromeTextAlpha),
                )
                Spacer(modifier = Modifier.height(FlashSpacing.space8))
                Row(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                    FlashIconButtonChrome(
                        icon = FlashIcons.Download,
                        tint = colors.mediaViewerChromeText,
                        contentDescription = if (currentItem.image.isVideo) "Save video" else "Save image",
                        onClick = { onSave(pagerState.currentPage) },
                    )
                    FlashIconButtonChrome(
                        icon = FlashIcons.Share,
                        tint = colors.mediaViewerChromeText,
                        contentDescription = FlashIcons.Share.contentDescription,
                        onClick = { onShare(pagerState.currentPage) },
                    )
                    FlashIconButtonChrome(
                        icon = FlashIcons.Forward,
                        tint = colors.mediaViewerChromeText,
                        contentDescription = FlashIcons.Forward.contentDescription,
                        onClick = { onForward(pagerState.currentPage) },
                    )
                }
            }
        }
    }
}

/** Constant white-92 chrome text alpha over the always-dark backdrop (media-viewer.md). */
private const val ChromeTextAlpha = 0.92f

/**
 * One zoomable viewer page: bitmap decode with sample-size guard, seed-gradient loading
 * placeholder, failure state, pinch/double-tap zoom, pan, and un-zoomed dismiss drag. Video pages
 * render a decoded frame plus a play badge that hands off to the platform player.
 */
@Composable
private fun FlashMediaPage(
    item: FlashMediaViewerItem,
    zoomState: FlashZoomState,
    onToggleChrome: () -> Unit,
    onPlay: () -> Unit,
    onDismissDrag: (Float) -> Unit,
    onDismissSettle: () -> Unit,
    onDismissConfirm: () -> Unit,
) {
    val imageDecoder = rememberFlashImageDecoder()
    val motion = FlashTheme.motion
    val viewConfiguration = LocalViewConfiguration.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val decodeState = produceState(
        FlashMediaDecodeState(),
        key1 = item.image.uri,
        key2 = item.image.thumbUri,
        key3 = item.image.isVideo,
    ) {
        val source = item.image.uri ?: item.image.thumbUri
        value = withContext(Dispatchers.IO) {
            // Shared with the in-bubble tiles, which buys this page three things it lacked: an EXIF
            // rotation pass (a portrait photo used to open sideways), a frame for video pages (a
            // BitmapFactory decode of an mp4 returns null, so swiping onto a clip hit `failed`), and
            // one code path for the sample-size guard. memoize = false because a 4096-edge bitmap
            // would evict the entire thumbnail cache to store something nobody asks for twice.
            imageDecoder.decode(
                source = source,
                isVideo = item.image.isVideo,
                maxLongEdge = FlashMediaViewerMath.MAX_DECODE_LONG_EDGE,
                memoize = false,
                computeInSampleSize = FlashMediaViewerMath::computeInSampleSize,
            )?.let { FlashMediaDecodeState(bitmap = it) } ?: FlashMediaDecodeState(failed = true)
        }
    }

    val seed = item.image.seedColor
    val gradientColors = remember(seed) {
        val base = Color(seed)
        listOf(base, base.copy(alpha = 0.75f), base.copy(alpha = 0.9f))
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // Tap/double-tap live in their own lightweight scope; taps never consume drags.
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onToggleChrome() },
                    onDoubleTap = { tapPoint ->
                        scope.launch {
                            if (zoomState.isZoomed) {
                                zoomState.reset(motion)
                            } else {
                                val targetScale = FlashMediaViewerMath.DOUBLE_TAP_SCALE
                                val ratio = targetScale / zoomState.scale.value
                                val centerX = size.width / 2f
                                val centerY = size.height / 2f
                                val targetX = FlashMediaViewerMath.anchoredOffset(zoomState.offsetX.value, tapPoint.x - centerX, ratio)
                                val targetY = FlashMediaViewerMath.anchoredOffset(zoomState.offsetY.value, tapPoint.y - centerY, ratio)
                                val spec = motion.springDefaultSpec<Float>()
                                coroutineScope {
                                    launch { zoomState.scale.animateTo(targetScale, spec) }
                                    launch { zoomState.offsetX.animateTo(targetX, spec) }
                                    launch { zoomState.offsetY.animateTo(targetY, spec) }
                                }
                            }
                        }
                    },
                )
            }
            // Claim-policy gesture scope (see docs/ui/media-viewer.md §Gesture specification).
            .pointerInput(Unit) {
                val viewport = Size(size.width.toFloat(), size.height.toFloat())
                val dismissThreshold = FlashMediaViewerMath.DISMISS_DISTANCE_DP.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var totalDx = 0f
                    var totalDy = 0f
                    var claimedPinch = false
                    var claimedPan = false
                    var claimedDismiss = false
                    val velocityTracker = VelocityTracker()

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount == 0) break

                        if (pressedCount >= 2) {
                            // Claim 1: pinch owns until all pointers up.
                            claimedPinch = true
                            claimedPan = false
                            claimedDismiss = false
                            val zoomChange = event.calculateZoom()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            if (zoomChange != 1f && centroid.isSpecified) {
                                val oldScale = zoomState.scale.value
                                val newScale = (oldScale * zoomChange).coerceAtMost(FlashMediaViewerMath.pinchCeiling())
                                val ratio = newScale / oldScale
                                val centerX = size.width / 2f
                                val centerY = size.height / 2f
                                val targetX = FlashMediaViewerMath.anchoredOffset(zoomState.offsetX.value, centroid.x - centerX, ratio)
                                val targetY = FlashMediaViewerMath.anchoredOffset(zoomState.offsetY.value, centroid.y - centerY, ratio)
                                // Restricted scope: route suspending snaps through the external scope.
                                scope.launch {
                                    zoomState.scale.snapTo(newScale)
                                    zoomState.offsetX.snapTo(targetX)
                                    zoomState.offsetY.snapTo(targetY)
                                }
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }

                        val change = event.changes.firstOrNull { it.pressed } ?: continue
                        val delta = change.positionChange()
                        totalDx += delta.x
                        totalDy += delta.y
                        velocityTracker.addPosition(change.uptimeMillis, change.position)

                        if (!claimedPinch && !claimedPan && !claimedDismiss &&
                            (abs(totalDx) > viewConfiguration.touchSlop || abs(totalDy) > viewConfiguration.touchSlop)
                        ) {
                            if (zoomState.isZoomed) {
                                // Claim 2: zoomed single finger pans.
                                claimedPan = true
                            } else if (FlashMediaViewerMath.claimsDismiss(totalDx, totalDy, viewConfiguration.touchSlop)) {
                                // Claim 3: un-zoomed dominant-vertical drag dismisses.
                                claimedDismiss = true
                            }
                            // Claim 4: horizontal dominance stays unconsumed → pager pages.
                        }

                        when {
                            claimedPan -> {
                                val targetX = zoomState.offsetX.value + delta.x
                                val targetY = zoomState.offsetY.value + delta.y
                                scope.launch {
                                    zoomState.offsetX.snapTo(targetX)
                                    zoomState.offsetY.snapTo(targetY)
                                }
                                change.consume()
                            }
                            claimedDismiss -> {
                                onDismissDrag(max(0f, totalDy))
                                change.consume()
                            }
                        }
                    }

                    // Release: rubber-band zoom/pan back into bounds, or resolve dismissal.
                    when {
                        claimedPan || claimedPinch -> scope.launch { zoomState.settle(viewport, motion) }
                        claimedDismiss -> {
                            val velocity = velocityTracker.calculateVelocity().y
                            if (FlashMediaViewerMath.shouldDismissByDistance(totalDy, dismissThreshold) ||
                                FlashMediaViewerMath.shouldDismissByVelocity(velocity)
                            ) {
                                onDismissConfirm()
                            } else {
                                onDismissSettle()
                            }
                        }
                    }
                }
            },
    ) {
        val result = decodeState.value

        when {
            result.bitmap != null -> {
                Image(
                    bitmap = result.bitmap,
                    contentDescription = item.image.caption
                        ?: if (item.image.isVideo) "Video" else "Photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoomState.scale.value
                            scaleY = zoomState.scale.value
                            translationX = zoomState.offsetX.value
                            translationY = zoomState.offsetY.value
                        },
                )
            }
            result.failed -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(FlashSpacing.space16),
                ) {
                    FlashIcon(
                        icon = FlashIcons.Failed,
                        tint = FlashTheme.colors.mediaViewerChromeText.copy(alpha = ChromeTextAlpha),
                        size = FlashDimensions.iconLg,
                    )
                    FlashText(
                        text = if (item.image.isVideo) "Couldn't load video" else "Couldn't load image",
                        style = FlashTheme.typography.metadataDefault,
                        color = FlashTheme.colors.mediaViewerChromeText.copy(alpha = ChromeTextAlpha),
                    )
                }
            }
            else -> {
                // Seed-gradient loading placeholder matching the grid tile fallback (UI-017).
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(gradientColors)),
                )
            }
        }

        // A frame is a still: a video page needs an explicit "play" target, offered as soon as the
        // decode settles either way — an undecodable frame says nothing about whether the clip
        // plays. It sits above the page's own gesture scopes, so tapping the badge plays while
        // tapping anywhere else still toggles chrome.
        if (item.image.isVideo && (result.bitmap != null || result.failed)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(FlashDimensions.minTouchTarget)
                    .background(Color(0x99000000), CircleShape)
                    .clickable(onClick = onPlay)
                    .semantics { role = Role.Button },
            ) {
                FlashIcon(
                    icon = FlashIcons.Play,
                    tint = Color.White,
                    contentDescription = "Play video",
                    size = FlashDimensions.iconMd,
                )
            }
        }
    }
}

/** Viewer chrome icon button — white-92 tint, 48dp touch target. */
@Composable
private fun FlashIconButtonChrome(
    icon: FlashIconSpec,
    tint: Color,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(FlashDimensions.minTouchTarget)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        FlashIcon(
            icon = icon,
            tint = tint,
            contentDescription = contentDescription,
        )
    }
}

@Preview(name = "Media Viewer", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun FlashMediaViewerPreview() {
    FlashTheme {
        FlashMediaViewer(
            items = listOf(
                FlashMediaViewerItem(
                    image = FlashImageAttachmentUi(id = "i1", width = 1920, height = 1080, caption = "Sunset"),
                    senderName = "Alex Rivera",
                    timeLabel = "10:30 AM",
                ),
                FlashMediaViewerItem(
                    image = FlashImageAttachmentUi(id = "i2", width = 800, height = 1200),
                    senderName = "Alex Rivera",
                    timeLabel = "10:31 AM",
                ),
            ),
            initialIndex = 0,
            onDismiss = {},
        )
    }
}
