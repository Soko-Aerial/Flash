package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.FlashSelfMembership
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * UI-029 addendum 3: the words on a group screen whose owner is no longer a member (it left, or the group owner removed
 * it). Pure so the copy is unit-tested; [FlashGroupSelfNotice] only lays it out.
 */
public object FlashGroupSelfNoticeMath {
    /** The headline, or null while the device is an active member (nothing to say). */
    public fun title(membership: FlashSelfMembership): String? = when (membership) {
        FlashSelfMembership.Active -> null
        FlashSelfMembership.Left -> "You left this group"
        FlashSelfMembership.Removed -> "You were removed from this group"
    }

    /** One line under the headline: what the member still has. Null exactly when [title] is null. */
    public fun detail(membership: FlashSelfMembership): String? =
        if (membership == FlashSelfMembership.Active) null else "You can still read what you already received."
}

/**
 * Replaces the composer on a group the device is out of. It is deliberately not interactive: there is nothing to type
 * into and nothing to press. The owner adding the device back returns the state to Active and the composer with it.
 */
@Composable
fun FlashGroupSelfNotice(
    membership: FlashSelfMembership,
    modifier: Modifier = Modifier,
) {
    val title = FlashGroupSelfNoticeMath.title(membership) ?: return
    val detail = FlashGroupSelfNoticeMath.detail(membership).orEmpty()
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        modifier = modifier
            .fillMaxWidth()
            .background(colors.backgroundSurfaceSubtle)
            .navigationBarsPadding()
            .heightIn(min = 56.dp)
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space12)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title. $detail"
            },
    ) {
        FlashIcon(
            icon = FlashIcons.Group,
            contentDescription = null,
            tint = colors.textSecondary,
            size = FlashDimensions.iconSm,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlashText(text = title, style = typography.metadataEmphasis, color = colors.textPrimary)
            FlashText(text = detail, style = typography.metadataDefault, color = colors.textSecondary)
        }
    }
}

@Preview(name = "Group notice - removed", showBackground = true)
@Composable
private fun FlashGroupSelfNoticeRemovedPreview() {
    FlashTheme { FlashGroupSelfNotice(membership = FlashSelfMembership.Removed) }
}

@Preview(name = "Group notice - left", showBackground = true)
@Composable
private fun FlashGroupSelfNoticeLeftPreview() {
    FlashTheme { FlashGroupSelfNotice(membership = FlashSelfMembership.Left) }
}
