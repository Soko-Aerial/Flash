package com.transfer.flash.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.transfer.flash.core.messaging.model.FlashImageAttachmentUi
import com.transfer.flash.core.messaging.model.FlashMessageStatus
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.shims.FlashImageDecoder
import com.transfer.flash.ui.shims.rememberFlashImageDecoder
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashMotion
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Adaptive collage and grid presentation for single and multi-photo messages in chat (UI-017).
 */
@Composable
fun FlashImageGrid(
    images: List<FlashImageAttachmentUi>,
    modifier: Modifier = Modifier,
    isMine: Boolean = false,
    onImageClick: (index: Int, image: FlashImageAttachmentUi) -> Unit = { _, _ -> },
    onLongPress: () -> Unit = {},
) {
    if (images.isEmpty()) return

    val gutter = 2.5.dp
    val tileRadius = RoundedCornerShape(4.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
    ) {
        when (images.size) {
            1 -> {
                FlashSingleImageTile(
                    image = images[0],
                    tileRadius = tileRadius,
                    onClick = { onImageClick(0, images[0]) },
                    onLongPress = onLongPress,
                )
            }
            2 -> {
                FlashTwoImageGrid(
                    images = images,
                    gutter = gutter,
                    tileRadius = tileRadius,
                    onImageClick = onImageClick,
                    onLongPress = onLongPress,
                )
            }
            3 -> {
                FlashThreeImageGrid(
                    images = images,
                    gutter = gutter,
                    tileRadius = tileRadius,
                    onImageClick = onImageClick,
                    onLongPress = onLongPress,
                )
            }
            4 -> {
                FlashFourImageGrid(
                    images = images,
                    gutter = gutter,
                    tileRadius = tileRadius,
                    onImageClick = onImageClick,
                    onLongPress = onLongPress,
                )
            }
            else -> {
                FlashMultiImageGrid(
                    images = images,
                    gutter = gutter,
                    tileRadius = tileRadius,
                    onImageClick = onImageClick,
                    onLongPress = onLongPress,
                )
            }
        }
    }
}

@Composable
private fun FlashSingleImageTile(
    image: FlashImageAttachmentUi,
    tileRadius: RoundedCornerShape,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    // Received attachments carry no pixel dimensions (the repository never opens the file), so every
    // photo used to land in a 4:3 box and ContentScale.Crop shaved the top and bottom off portrait
    // shots — the common case for phone photos. The decoded bitmap knows its own shape, so adopt it
    // as soon as the tile has one and keep 4:3 only as the pre-decode placeholder ratio.
    var decodedRatio by remember(image.uri, image.thumbUri) { mutableStateOf<Float?>(null) }
    val ratio = remember(image.width, image.height, decodedRatio) {
        val declared = if (image.width > 0 && image.height > 0) {
            image.width.toFloat() / image.height.toFloat()
        } else {
            decodedRatio
        }
        (declared ?: (4f / 3f)).coerceIn(0.5f, 2.0f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .heightIn(min = 140.dp, max = 300.dp),
    ) {
        FlashImageTile(
            image = image,
            shape = tileRadius,
            onClick = onClick,
            onLongPress = onLongPress,
            onIntrinsicRatio = { decodedRatio = it },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun FlashTwoImageGrid(
    images: List<FlashImageAttachmentUi>,
    gutter: Dp,
    tileRadius: RoundedCornerShape,
    onImageClick: (Int, FlashImageAttachmentUi) -> Unit,
    onLongPress: () -> Unit = {},
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(gutter),
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
    ) {
        FlashImageTile(
            image = images[0],
            shape = tileRadius,
            onClick = { onImageClick(0, images[0]) },
            onLongPress = onLongPress,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
        FlashImageTile(
            image = images[1],
            shape = tileRadius,
            onClick = { onImageClick(1, images[1]) },
            onLongPress = onLongPress,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
    }
}

@Composable
private fun FlashThreeImageGrid(
    images: List<FlashImageAttachmentUi>,
    gutter: Dp,
    tileRadius: RoundedCornerShape,
    onImageClick: (Int, FlashImageAttachmentUi) -> Unit,
    onLongPress: () -> Unit = {},
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(gutter),
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp),
    ) {
        FlashImageTile(
            image = images[0],
            shape = tileRadius,
            onClick = { onImageClick(0, images[0]) },
            onLongPress = onLongPress,
            modifier = Modifier
                .weight(1.2f)
                .fillMaxHeight(),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(gutter),
            modifier = Modifier
                .weight(0.8f)
                .fillMaxHeight(),
        ) {
            FlashImageTile(
                image = images[1],
                shape = tileRadius,
                onClick = { onImageClick(1, images[1]) },
                onLongPress = onLongPress,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            FlashImageTile(
                image = images[2],
                shape = tileRadius,
                onClick = { onImageClick(2, images[2]) },
                onLongPress = onLongPress,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}

@Composable
private fun FlashFourImageGrid(
    images: List<FlashImageAttachmentUi>,
    gutter: Dp,
    tileRadius: RoundedCornerShape,
    onImageClick: (Int, FlashImageAttachmentUi) -> Unit,
    onLongPress: () -> Unit = {},
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(gutter),
        modifier = Modifier
            .fillMaxWidth()
            .height(250.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(gutter),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            FlashImageTile(
                image = images[0],
                shape = tileRadius,
                onClick = { onImageClick(0, images[0]) },
                onLongPress = onLongPress,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            FlashImageTile(
                image = images[1],
                shape = tileRadius,
                onClick = { onImageClick(1, images[1]) },
                onLongPress = onLongPress,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(gutter),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            FlashImageTile(
                image = images[2],
                shape = tileRadius,
                onClick = { onImageClick(2, images[2]) },
                onLongPress = onLongPress,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            FlashImageTile(
                image = images[3],
                shape = tileRadius,
                onClick = { onImageClick(3, images[3]) },
                onLongPress = onLongPress,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FlashMultiImageGrid(
    images: List<FlashImageAttachmentUi>,
    gutter: Dp,
    tileRadius: RoundedCornerShape,
    onImageClick: (Int, FlashImageAttachmentUi) -> Unit,
    onLongPress: () -> Unit = {},
) {
    val overflowCount = images.size - 3

    Column(
        verticalArrangement = Arrangement.spacedBy(gutter),
        modifier = Modifier
            .fillMaxWidth()
            .height(250.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(gutter),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            FlashImageTile(
                image = images[0],
                shape = tileRadius,
                onClick = { onImageClick(0, images[0]) },
                onLongPress = onLongPress,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            FlashImageTile(
                image = images[1],
                shape = tileRadius,
                onClick = { onImageClick(1, images[1]) },
                onLongPress = onLongPress,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(gutter),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            FlashImageTile(
                image = images[2],
                shape = tileRadius,
                onClick = { onImageClick(2, images[2]) },
                onLongPress = onLongPress,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                FlashImageTile(
                    image = images[3],
                    shape = tileRadius,
                    onClick = { onImageClick(3, images[3]) },
                    onLongPress = onLongPress,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(tileRadius)
                        .background(Color(0x99000000))
                        .combinedClickable(
                            onClick = { onImageClick(3, images[3]) },
                            onLongClick = onLongPress,
                        ),
                ) {
                    Text(
                        text = "+$overflowCount",
                        style = FlashTheme.typography.headingMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

/**
 * Individual image tile rendering bitmap image or stylized gradient placeholder with tactile press.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FlashImageTile(
    image: FlashImageAttachmentUi,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(4.dp),
    onClick: () -> Unit = {},
    onLongPress: () -> Unit = {},
    /** Reports the decoded bitmap's width/height ratio so a caller can size itself to the media. */
    onIntrinsicRatio: ((Float) -> Unit)? = null,
) {
    val imageDecoder = rememberFlashImageDecoder()
    var isPressed by remember { mutableStateOf(false) }
    val motion = FlashTheme.motion
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = motion.springSnappySpec(),
        label = "tile_press_scale",
    )

    // Decoding lives behind FlashImageDecoder: this used to be a full-resolution BitmapFactory decode
    // wrapped in runCatching, which turned an OutOfMemoryError on a large photo into a silent
    // gradient placeholder, ignored EXIF rotation, and returned null for every video. isVideo is a
    // key because it selects the decoder (still bytes vs. a retrieved frame), not just the source.
    //
    // ERROR-033: below HIGH the tile is decoded smaller and at half the colour depth. It is a key
    // too, not a value captured behind the decoder's back, so a tier that resolves after first
    // composition re-decodes instead of leaving a stale bitmap on screen.
    val minimalChrome = FlashTheme.minimalChrome
    val bitmapState = produceState<ImageBitmap?>(
        null,
        image.uri,
        image.thumbUri,
        image.isVideo,
        minimalChrome,
    ) {
        val source = image.uri ?: image.thumbUri
        value = withContext(Dispatchers.IO) {
            imageDecoder.decode(
                source = source,
                isVideo = image.isVideo,
                maxLongEdge = if (minimalChrome) {
                    FlashImageDecoder.TILE_LONG_EDGE_MINIMAL_PX
                } else {
                    FlashImageDecoder.TILE_LONG_EDGE_PX
                },
                lowColorDepth = minimalChrome,
                computeInSampleSize = FlashMediaViewerMath::computeInSampleSize,
            )
        }
    }

    val seed = image.seedColor
    val gradientColors = remember(seed) {
        val base = Color(seed)
        listOf(
            base,
            base.copy(alpha = 0.75f),
            base.copy(alpha = 0.90f),
        )
    }

    val bitmap = bitmapState.value
    LaunchedEffect(bitmap) {
        val decoded = bitmap ?: return@LaunchedEffect
        if (decoded.height > 0) {
            onIntrinsicRatio?.invoke(decoded.width.toFloat() / decoded.height.toFloat())
        }
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = { onClick() },
                    onLongPress = { onLongPress() },
                )
            }
            // detectTapGestures is invisible to accessibility services: the tile had a described
            // Image inside but no activatable node, so TalkBack could read a photo and never open
            // it. Merging pulls that description up as this button's label.
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick(label = if (image.isVideo) "Play video" else "Open image") {
                    onClick()
                    true
                }
                onLongClick(label = "Message actions") {
                    onLongPress()
                    true
                }
            },
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = image.caption ?: "Image attachment",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Stylized generative gradient photo placeholder
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(gradientColors)),
            ) {
                FlashIcon(
                    icon = if (image.isVideo) FlashIcons.Play else FlashIcons.Gallery,
                    tint = Color.White.copy(alpha = 0.5f),
                    size = FlashDimensions.iconLg,
                )
            }
        }

        // B4: video attachments overlay a centered play affordance on top of the thumbnail so a
        // tap clearly means "play". Images render bare.
        if (image.isVideo && bitmap != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0x99000000)),
                ) {
                    FlashIcon(
                        icon = FlashIcons.Play,
                        tint = Color.White,
                        size = FlashDimensions.iconMd,
                    )
                }
            }
        }
    }
}

/**
 * Floating frosted timestamp pill for media-only borderless bubbles.
 */
@Composable
fun FlashFloatingTimestampPill(
    timeLabel: String,
    modifier: Modifier = Modifier,
    deliveryStatus: FlashMessageStatus? = null,
    isMine: Boolean = false,
) {
    SurfacePill(
        modifier = modifier.padding(FlashSpacing.space4),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                text = timeLabel,
                style = FlashTheme.typography.metadataDefault.copy(fontSize = 11.sp),
                color = Color.White.copy(alpha = 0.9f),
            )
            if (isMine && deliveryStatus != null) {
                FlashDeliveryStatusIcon(
                    status = deliveryStatus,
                    size = 14.dp,
                )
            }
        }
    }
}

@Composable
private fun SurfacePill(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x73000000)),
    ) {
        content()
    }
}
