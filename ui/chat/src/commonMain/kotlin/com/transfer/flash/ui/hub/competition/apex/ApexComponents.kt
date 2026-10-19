package com.transfer.flash.ui.hub.competition.apex

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashElevation
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * APEX ZENITH — Reusable High-Craft Visual Components.
 */

// -------------------------------------------------------------------------------------------------
// Card & Surfaces
// -------------------------------------------------------------------------------------------------

@Composable
internal fun ApexCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(ApexDims.cardRadius),
    tone: ApexTone? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = FlashSpacing.space16,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = FlashTheme.colors
    val toneColors = tone?.let { apexTone(it) }
    val borderColor = toneColors?.border ?: colors.borderSubtle
    val surfaceColor = toneColors?.wash?.copy(alpha = 0.08f) ?: colors.backgroundSurface

    val interactionSource = remember { MutableInteractionSource() }
    val clickModifier = if (onClick != null) {
        Modifier
            .flashPressScale(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    } else Modifier

    Column(
        modifier = modifier
            .clip(shape)
            .background(surfaceColor, shape)
            .border(FlashDimensions.borderHairline, borderColor, shape)
            .then(clickModifier)
            .padding(contentPadding),
    ) {
        content()
    }
}

@Composable
internal fun ApexTile(
    label: String,
    value: String,
    caption: String? = null,
    tone: ApexTone = ApexTone.STANDBY,
    icon: FlashIconSpec? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val toneColors = apexTone(tone)
    val colors = FlashTheme.colors
    val interactionSource = remember { MutableInteractionSource() }

    val clickModifier = if (onClick != null) {
        Modifier
            .flashPressScale(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    } else Modifier

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(ApexDims.tileRadius))
            .background(toneColors.wash)
            .border(FlashDimensions.borderHairline, toneColors.border, RoundedCornerShape(ApexDims.tileRadius))
            .then(clickModifier)
            .padding(FlashSpacing.space12),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlashText(
                    text = label.uppercase(),
                    style = FlashTheme.typography.metadataEmphasis,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (icon != null) {
                    FlashIcon(
                        icon = icon,
                        tint = toneColors.indicator,
                        size = FlashDimensions.iconSm,
                        contentDescription = null,
                    )
                }
            }
            Spacer(Modifier.height(FlashSpacing.space2))
            FlashText(
                text = value,
                style = FlashTheme.typography.headingMedium,
                color = toneColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (caption != null) {
                FlashText(
                    text = caption,
                    style = FlashTheme.typography.captionDefault,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Badges, Status Dots & Indicators
// -------------------------------------------------------------------------------------------------

@Composable
internal fun ApexStatusDot(
    tone: ApexTone,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
    animated: Boolean = true,
) {
    val toneColors = apexTone(tone)
    val motion = FlashTheme.motion
    val minimal = FlashTheme.minimalChrome

    val shouldAnimate = animated && !motion.reduceMotion && !minimal
    val scale = if (shouldAnimate) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        infiniteTransition.animateFloat(
            initialValue = 0.85f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseScale",
        ).value
    } else 1f

    Box(
        modifier = modifier.size(size * 1.6f),
        contentAlignment = Alignment.Center,
    ) {
        if (shouldAnimate) {
            Box(
                modifier = Modifier
                    .size(size * scale * 1.5f)
                    .clip(CircleShape)
                    .background(toneColors.glow),
            )
        }
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(toneColors.indicator),
        )
    }
}

@Composable
internal fun ApexBadge(
    text: String,
    tone: ApexTone,
    icon: FlashIconSpec? = null,
    modifier: Modifier = Modifier,
) {
    val toneColors = apexTone(tone)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(ApexDims.badgeRadius))
            .background(toneColors.wash)
            .border(FlashDimensions.borderHairline, toneColors.border, RoundedCornerShape(ApexDims.badgeRadius))
            .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
    ) {
        if (icon != null) {
            FlashIcon(
                icon = icon,
                tint = toneColors.indicator,
                size = 12.dp,
                contentDescription = null,
            )
        } else {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(toneColors.indicator),
            )
        }
        FlashText(
            text = text,
            style = FlashTheme.typography.captionEmphasis,
            color = toneColors.text,
            maxLines = 1,
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Buttons & Interactive Controls
// -------------------------------------------------------------------------------------------------

@Composable
internal fun ApexButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: FlashIconSpec? = null,
    tone: ApexTone = ApexTone.BUSY,
    enabled: Boolean = true,
) {
    val toneColors = apexTone(tone)
    val colors = FlashTheme.colors
    val interactionSource = remember { MutableInteractionSource() }

    val bg = if (enabled) toneColors.indicator else colors.backgroundSurfaceStrong
    val fg = if (enabled) colors.textOnAccent else colors.textTertiary

    Row(
        modifier = modifier
            .heightIn(min = ApexDims.touchTargetMin)
            .clip(RoundedCornerShape(ApexDims.pillRadius))
            .background(bg)
            .flashPressScale(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = FlashSpacing.space20, vertical = FlashSpacing.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            FlashIcon(
                icon = icon,
                tint = fg,
                size = FlashDimensions.iconSm,
                contentDescription = null,
            )
            Spacer(Modifier.width(FlashSpacing.space8))
        }
        FlashText(
            text = text,
            style = FlashTheme.typography.bodyEmphasis,
            color = fg,
        )
    }
}

@Composable
internal fun ApexSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: FlashIconSpec? = null,
    tone: ApexTone = ApexTone.STANDBY,
    enabled: Boolean = true,
) {
    val toneColors = apexTone(tone)
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .heightIn(min = ApexDims.touchTargetMin)
            .clip(RoundedCornerShape(ApexDims.pillRadius))
            .background(toneColors.wash)
            .border(FlashDimensions.borderHairline, toneColors.border, RoundedCornerShape(ApexDims.pillRadius))
            .flashPressScale(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            FlashIcon(
                icon = icon,
                tint = toneColors.text,
                size = FlashDimensions.iconSm,
                contentDescription = null,
            )
            Spacer(Modifier.width(FlashSpacing.space4))
        }
        FlashText(
            text = text,
            style = FlashTheme.typography.captionEmphasis,
            color = toneColors.text,
        )
    }
}

@Composable
internal fun ApexIconButton(
    icon: FlashIconSpec,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = icon.contentDescription,
    tint: Color? = null,
    size: Dp = 40.dp,
    enabled: Boolean = true,
) {
    val colors = FlashTheme.colors
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.backgroundSurfaceStrong.copy(alpha = 0.5f))
            .flashPressScale(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        FlashIcon(
            icon = icon,
            tint = tint ?: colors.textPrimary,
            size = FlashDimensions.iconSm,
            contentDescription = contentDescription,
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Search Bar & Filter Chips
// -------------------------------------------------------------------------------------------------

@Composable
internal fun ApexSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onClear: () -> Unit = { onQueryChange("") },
) {
    val colors = FlashTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(ApexDims.pillRadius))
            .background(colors.composerInputBackground)
            .border(FlashDimensions.borderHairline, colors.borderDefault, RoundedCornerShape(ApexDims.pillRadius))
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlashIcon(
            icon = FlashIcons.Search,
            tint = colors.textTertiary,
            size = FlashDimensions.iconSm,
            contentDescription = null,
        )
        Spacer(Modifier.width(FlashSpacing.space8))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                FlashText(
                    text = placeholder,
                    style = FlashTheme.typography.bodyDefault,
                    color = colors.textTertiary,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                textStyle = FlashTheme.typography.bodyDefault.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accentPrimary),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
        }
        if (query.isNotEmpty()) {
            ApexIconButton(
                icon = FlashIcons.Close,
                onClick = onClear,
                size = 28.dp,
                contentDescription = "Clear search",
            )
        }
    }
}

@Composable
internal fun ApexFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
) {
    val colors = FlashTheme.colors
    val interactionSource = remember { MutableInteractionSource() }

    val bg = if (selected) colors.accentPrimary.copy(alpha = 0.16f) else colors.backgroundSurfaceStrong.copy(alpha = 0.5f)
    val border = if (selected) colors.accentPrimary else colors.borderSubtle
    val textCol = if (selected) colors.accentPrimary else colors.textSecondary

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(ApexDims.pillRadius))
            .background(bg)
            .border(FlashDimensions.borderHairline, border, RoundedCornerShape(ApexDims.pillRadius))
            .flashPressScale(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
    ) {
        FlashText(
            text = label,
            style = if (selected) FlashTheme.typography.captionEmphasis else FlashTheme.typography.captionDefault,
            color = textCol,
        )
        if (count != null) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (selected) colors.accentPrimary else colors.textTertiary.copy(alpha = 0.3f))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                FlashText(
                    text = count.toString(),
                    style = FlashTheme.typography.metadataEmphasis,
                    color = if (selected) colors.textOnAccent else colors.textSecondary,
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Monospace Code / Fingerprint Box
// -------------------------------------------------------------------------------------------------

@Composable
internal fun ApexCodeBox(
    code: String,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val colors = FlashTheme.colors
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ApexDims.tileRadius))
            .background(colors.backgroundSurfaceStrong.copy(alpha = 0.7f))
            .border(FlashDimensions.borderHairline, colors.borderDefault, RoundedCornerShape(ApexDims.tileRadius))
            .padding(FlashSpacing.space12),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (label != null) {
                FlashText(
                    text = label.uppercase(),
                    style = FlashTheme.typography.metadataEmphasis,
                    color = colors.textTertiary,
                )
            }
            ApexSecondaryButton(
                text = if (copied) "Copied!" else "Copy",
                icon = if (copied) FlashIcons.Check else FlashIcons.Share,
                tone = if (copied) ApexTone.ONLINE else ApexTone.STANDBY,
                onClick = {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                    scope.launch {
                        delay(2000)
                        copied = false
                    }
                },
            )
        }
        Spacer(Modifier.height(FlashSpacing.space4))
        FlashText(
            text = code,
            style = apexMono(FlashTheme.typography.captionDefault),
            color = colors.textPrimary,
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Empty States & Headings
// -------------------------------------------------------------------------------------------------

@Composable
internal fun ApexEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: FlashIconSpec = FlashIcons.Hub,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = FlashSpacing.space32, horizontal = FlashSpacing.space16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(colors.backgroundSurfaceStrong)
                .border(FlashDimensions.borderHairline, colors.borderSubtle, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            FlashIcon(
                icon = icon,
                tint = colors.textTertiary,
                size = 32.dp,
                contentDescription = null,
            )
        }
        Spacer(Modifier.height(FlashSpacing.space16))
        FlashText(
            text = title,
            style = FlashTheme.typography.headingMedium,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(FlashSpacing.space8))
        FlashText(
            text = description,
            style = FlashTheme.typography.bodyDefault,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(FlashSpacing.space20))
            ApexButton(text = actionText, onClick = onAction)
        }
    }
}

@Composable
internal fun ApexSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = FlashSpacing.space8),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlashText(
            text = title,
            style = FlashTheme.typography.headingSmall,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        if (actionText != null && onAction != null) {
            val interactionSource = remember { MutableInteractionSource() }
            FlashText(
                text = actionText,
                style = FlashTheme.typography.captionEmphasis,
                color = colors.accentPrimary,
                modifier = Modifier
                    .flashPressScale(interactionSource)
                    .clickable(interactionSource = interactionSource, indication = null, onClick = onAction),
            )
        }
    }
}

@Composable
internal fun ApexDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(FlashDimensions.borderHairline)
            .background(FlashTheme.colors.borderSubtle),
    )
}
