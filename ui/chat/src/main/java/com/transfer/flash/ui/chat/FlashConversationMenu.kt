package com.transfer.flash.ui.chat

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * Pure visibility rules for the conversation three-dot menu (group Phase D). The app composes
 * them with its own capability flags so the menu stays data-driven and testable.
 */
public object FlashConversationMenuMath {
    /** Items for a 1:1 conversation. */
    public fun directItems(
        canRevokeTrust: Boolean,
    ): List<FlashConversationMenuItem> = buildList {
        add(FlashConversationMenuItem.VIEW_PROFILE)
        add(FlashConversationMenuItem.SEARCH)
        if (canRevokeTrust) add(FlashConversationMenuItem.REVOKE_TRUST)
        add(FlashConversationMenuItem.CLEAR_CONVERSATION)
    }

    /** Items for a group conversation. Leave is hidden when the device is the sole member. */
    public fun groupItems(
        canLeave: Boolean,
    ): List<FlashConversationMenuItem> = buildList {
        add(FlashConversationMenuItem.GROUP_INFO)
        add(FlashConversationMenuItem.ADD_MEMBERS)
        add(FlashConversationMenuItem.SEARCH)
        if (canLeave) add(FlashConversationMenuItem.LEAVE_GROUP)
    }
}

public enum class FlashConversationMenuItem(val label: String) {
    VIEW_PROFILE("View profile"),
    SEARCH("Search in conversation"),
    REVOKE_TRUST("Revoke trust"),
    CLEAR_CONVERSATION("Clear conversation"),
    GROUP_INFO("Group info"),
    ADD_MEMBERS("Add members"),
    LEAVE_GROUP("Leave group"),
}

/**
 * The conversation three-dot menu (group Phase D): a Material `DropdownMenu` used purely as
 * infrastructure, rendered with Flash text tokens and anchored wherever the header's More
 * button sits. Item selection is reported as an enum to the host.
 */
@Composable
public fun FlashConversationMenu(
    expanded: Boolean,
    items: List<FlashConversationMenuItem>,
    onItemSelected: (FlashConversationMenuItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
    ) {
        items.forEach { item ->
            DropdownMenuItem(
                text = {
                    FlashText(
                        text = item.label,
                        style = FlashTheme.typography.bodyDefault,
                    )
                },
                onClick = {
                    onDismiss()
                    onItemSelected(item)
                },
            )
        }
    }
}
