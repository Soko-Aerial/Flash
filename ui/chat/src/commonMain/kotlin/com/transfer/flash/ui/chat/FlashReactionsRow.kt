package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme

@Composable
fun FlashReactionsRow(
    reactions: List<String>,
    outgoing: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors

    Row(
        modifier = modifier
            .background(
                color = if (outgoing) colors.chatBgOutgoing else colors.chatBgIncoming,
                shape = FlashShapes.composerBar,
            )
            .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4),
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        reactions.forEach { reaction ->
            Text(
                text = reaction,
                style = FlashTheme.typography.metadataDefault,
                color = colors.textSecondary,
            )
        }
    }
}
