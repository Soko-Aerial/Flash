package com.transfer.flash.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashTheme

/**
 * The desktop push-to-talk control (UI-051 addendum A): one round mic button that toggles the floor,
 * the same toggle semantics as the phone's hardware press.
 *
 * It only *asks* the engine; whether a session exists is read from the engine's floor state by the
 * caller and passed as [active]. While active the button is filled with the accent so the state is
 * readable without the overlay (which is also on screen), and its description flips to "Stop talking".
 */
@Composable
internal fun DesktopPttButton(
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val colors = FlashTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) colors.accentPrimary else colors.backgroundSurfaceStrong)
            .border(1.dp, if (active) colors.accentPrimary else colors.borderSubtle, CircleShape)
            .clickable(onClickLabel = if (active) "Stop talking" else "Push to talk", onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = if (active) "Stop talking" else "Push to talk"
            },
        contentAlignment = Alignment.Center,
    ) {
        FlashIcon(
            icon = FlashIcons.Microphone,
            // Empty, not null: null falls back to the icon's own "Voice message" and would be merged into
            // the button's description above.
            contentDescription = "",
            tint = if (active) colors.textOnAccent else colors.textSecondary,
            size = 20.dp,
        )
    }
}
