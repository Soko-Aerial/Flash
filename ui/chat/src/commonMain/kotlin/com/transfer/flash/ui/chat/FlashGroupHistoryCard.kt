package com.transfer.flash.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.FlashGroupHistoryUi
import com.transfer.flash.core.messaging.protocol.GroupHistoryCeiling
import com.transfer.flash.core.messaging.protocol.GroupHistoryPolicy
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

private const val DAY_MS: Long = 24L * 60L * 60L * 1000L

/**
 * UI-057: the words and rules of the group history join card and of the history rows in the group settings sheet
 * (`docs/ui/group-history-join-card.md`, ADR-100). Pure so the copy and the clamping are unit-tested
 * ([FlashGroupHistoryMathTest]); the composables only lay them out. All choices come from [GroupHistoryPolicy] so the
 * card can never offer more than the group's signed ceiling.
 */
object FlashGroupHistoryMath {
    const val CARD_TITLE = "Catch up on this group?"
    const val CARD_BODY = "You joined recently. Choose how much earlier conversation to receive from the members who are online."
    const val NO_HISTORY_TITLE = "No earlier history"

    /** The line a [GroupHistoryCeiling.NONE] group shows instead of a choice. */
    const val NO_HISTORY_SENTENCE = "This group does not share earlier history"
    const val FILES_TITLE = "Include files"
    const val FILES_ON = "Files shared in the last 7 days are offered too."
    const val FILES_OFF = "Only messages. File offers are skipped."
    const val FILES_NONE = "No history means no files."
    const val PRIMARY_ACTION = "Catch up"
    const val SKIP_ACTION = "Not now"

    /** G9: "Not now" is final for this card (it never returns); the way back is named under the choices. */
    const val SKIP_NOTE = "If you choose Not now, you can still use Load older messages in the group's settings."
    const val OK_ACTION = "OK"
    const val LOAD_OLDER_TITLE = "Load older messages"
    const val LOAD_OLDER_ACTION = "Load"
    const val CEILING_TITLE = "How much earlier history members can get"
    const val CEILING_NOTE = "Applies to people who join or return. Messages already received are kept."
    const val HISTORY_SECTION_TITLE = "Earlier history"
    const val HISTORY_SECTION_SUBTITLE = "What new and returning members can catch up on"

    /** "None", "24 hours", "7 days", "30 days" or "Everything available"; any other window is shown in days. */
    fun windowLabel(windowMs: Long): String = when {
        windowMs <= 0L -> "None"
        windowMs == GroupHistoryCeiling.H24.windowMs -> "24 hours"
        windowMs == Long.MAX_VALUE -> "Everything available"
        windowMs % DAY_MS == 0L -> (windowMs / DAY_MS).let { if (it == 1L) "1 day" else "$it days" }
        else -> "${(windowMs + DAY_MS - 1L) / DAY_MS} days"
    }

    /** The message windows the card offers under [ceiling], smallest first, always including "None". */
    fun windowOptions(ceiling: GroupHistoryCeiling): List<Long> = GroupHistoryPolicy.messageOptions(ceiling)

    /** The windows "Load older messages" offers: every option except "None". */
    fun loadOlderOptions(ceiling: GroupHistoryCeiling): List<Long> = windowOptions(ceiling).filter { it > 0L }

    /** The window selected when the card first appears: 30 days, cut down to the ceiling. */
    fun defaultWindow(ceiling: GroupHistoryCeiling): Long = GroupHistoryPolicy.defaultChoice(ceiling).messageWindowMs

    /** [windowMs] moved to the largest option the ceiling offers that is not above it (a selection never exceeds the ceiling). */
    fun snapToOptions(windowMs: Long, ceiling: GroupHistoryCeiling): Long =
        windowOptions(ceiling).lastOrNull { it <= windowMs } ?: 0L

    /** Whether the files switch can be on: no history means no files, and a no-history group offers none. */
    fun filesAvailable(windowMs: Long, ceiling: GroupHistoryCeiling): Boolean =
        windowMs > 0L && ceiling != GroupHistoryCeiling.NONE

    /** What is sent: the switch counts only where files are available at all. */
    fun effectiveFiles(wantFiles: Boolean, windowMs: Long, ceiling: GroupHistoryCeiling): Boolean =
        wantFiles && filesAvailable(windowMs, ceiling)

    /** The line under the files switch. */
    fun filesDescription(windowMs: Long, includeFiles: Boolean, ceiling: GroupHistoryCeiling): String = when {
        !filesAvailable(windowMs, ceiling) -> FILES_NONE
        includeFiles -> FILES_ON
        else -> FILES_OFF
    }

    /** A note when the group shares less than the usual 30 days, or null when it shares at least that. */
    fun ceilingFooter(ceiling: GroupHistoryCeiling): String? = when (ceiling) {
        GroupHistoryCeiling.NONE, GroupHistoryCeiling.D30, GroupHistoryCeiling.ALL -> null
        GroupHistoryCeiling.H24, GroupHistoryCeiling.D7 -> "This group shares up to ${windowLabel(ceiling.windowMs)}."
    }

    /** Spoken form of one chip: "30 days, selected". */
    fun optionDescription(windowMs: Long, selected: Boolean): String =
        windowLabel(windowMs) + if (selected) ", selected" else ""

    /** The ceilings an admin can pick, lowest first. */
    val ceilingChoices: List<GroupHistoryCeiling> = listOf(
        GroupHistoryCeiling.NONE,
        GroupHistoryCeiling.H24,
        GroupHistoryCeiling.D7,
        GroupHistoryCeiling.D30,
        GroupHistoryCeiling.ALL,
    )

    /** "Nothing", "24 hours", "7 days", "30 days", "Everything available". */
    fun ceilingLabel(ceiling: GroupHistoryCeiling): String =
        if (ceiling == GroupHistoryCeiling.NONE) "Nothing" else windowLabel(ceiling.windowMs)

    /** Spoken form of one ceiling chip: "Nothing, selected". */
    fun ceilingOptionDescription(ceiling: GroupHistoryCeiling, selected: Boolean): String =
        ceilingLabel(ceiling) + if (selected) ", selected" else ""

    /** One plain sentence on what the ceiling means for someone joining. */
    fun ceilingDescription(ceiling: GroupHistoryCeiling): String = when (ceiling) {
        GroupHistoryCeiling.NONE -> "Members get no earlier messages and no files."
        GroupHistoryCeiling.ALL -> "Members can get everything the online members still keep."
        else -> "Members can get up to ${windowLabel(ceiling.windowMs)} of earlier messages."
    }

    /** The read-only line a member who is not an admin sees. */
    fun ceilingReadOnly(ceiling: GroupHistoryCeiling): String =
        if (ceiling == GroupHistoryCeiling.NONE) NO_HISTORY_SENTENCE else ceilingDescription(ceiling)
}

/**
 * UI-057: the card a newly joined member answers once. It offers the windows inside the group's ceiling, a files switch
 * and two actions; for a group that shares no history it only states that and offers "OK". The host shows it while
 * `FlashConversationUiState.groupHistory` is non-null.
 */
@Composable
public fun FlashGroupHistoryCard(
    history: FlashGroupHistoryUi,
    onChoose: (windowMs: Long, includeFiles: Boolean) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val ceiling = history.ceiling
    val noHistory = ceiling == GroupHistoryCeiling.NONE
    val options = remember(ceiling) { FlashGroupHistoryMath.windowOptions(ceiling) }
    var selected by remember(ceiling) { mutableStateOf(FlashGroupHistoryMath.defaultWindow(ceiling)) }
    var wantFiles by remember(ceiling) { mutableStateOf(true) }
    val filesAvailable = FlashGroupHistoryMath.filesAvailable(selected, ceiling)
    val filesOn = FlashGroupHistoryMath.effectiveFiles(wantFiles, selected, ceiling)
    val title = if (noHistory) FlashGroupHistoryMath.NO_HISTORY_TITLE else FlashGroupHistoryMath.CARD_TITLE

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8)
            .clip(FlashShapes.bubbleGrouped)
            .background(colors.backgroundSurface)
            .border(FlashDimensions.borderHairline, colors.borderSubtle, FlashShapes.bubbleGrouped)
            .padding(FlashSpacing.space16)
            .semantics { contentDescription = "Earlier history choice" },
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        ) {
            FlashIcon(icon = FlashIcons.Clock, contentDescription = null, tint = colors.accentPrimary, size = 20.dp)
            FlashText(
                text = title,
                style = typography.headingSmall,
                color = colors.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
        }
        FlashText(
            text = if (noHistory) FlashGroupHistoryMath.NO_HISTORY_SENTENCE else FlashGroupHistoryMath.CARD_BODY,
            style = typography.bodyDefault,
            color = colors.textSecondary,
        )
        if (noHistory) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                HistoryButton(text = FlashGroupHistoryMath.OK_ACTION, filled = true, onClick = onSkip)
            }
        } else {
            FlashChoiceChipRail(
                labels = options.map { FlashGroupHistoryMath.windowLabel(it) },
                descriptions = options.map { FlashGroupHistoryMath.optionDescription(it, it == selected) },
                selectedIndex = options.indexOf(selected).coerceAtLeast(0),
                onSelect = { selected = options[it] },
            )
            SettingsSwitchRow(
                title = FlashGroupHistoryMath.FILES_TITLE,
                description = FlashGroupHistoryMath.filesDescription(selected, wantFiles, ceiling),
                checked = filesOn,
                enabled = filesAvailable,
                onCheckedChange = { wantFiles = it },
            )
            FlashGroupHistoryMath.ceilingFooter(ceiling)?.let {
                FlashText(text = it, style = typography.metadataDefault, color = colors.textTertiary)
            }
            FlashText(text = FlashGroupHistoryMath.SKIP_NOTE, style = typography.metadataDefault, color = colors.textTertiary)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HistoryButton(text = FlashGroupHistoryMath.SKIP_ACTION, filled = false, onClick = onSkip)
                HistoryButton(
                    text = FlashGroupHistoryMath.PRIMARY_ACTION,
                    filled = true,
                    onClick = { onChoose(selected, filesOn) },
                )
            }
        }
    }
}

/**
 * A single-select row of chips (a custom radio group: no stock Material chip). The selected chip carries a check and a
 * stronger outline so the state does not rely on colour. Wraps onto further lines when the text is large.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FlashChoiceChipRail(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    descriptions: List<String> = labels,
    enabled: Boolean = true,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        labels.forEachIndexed { index, label ->
            FlashChoiceChip(
                label = label,
                description = descriptions.getOrElse(index) { label },
                selected = index == selectedIndex,
                enabled = enabled,
                onClick = { onSelect(index) },
            )
        }
    }
}

@Composable
private fun FlashChoiceChip(
    label: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val fill by animateColorAsState(
        targetValue = if (selected) colors.accentPrimary.copy(alpha = 0.14f) else colors.backgroundSurface,
        animationSpec = motion.tweenFastSpec(),
        label = "historyChipFill",
    )
    val borderColor = when {
        selected -> colors.accentPrimary
        else -> colors.borderDefault
    }
    val borderWidth = if (selected) 2.dp else FlashDimensions.borderHairline
    val contentColor = when {
        !enabled -> colors.textTertiary
        selected -> colors.accentPrimary
        else -> colors.textPrimary
    }
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(FlashShapes.chip)
            .background(fill)
            .border(borderWidth, borderColor, FlashShapes.chip)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
    ) {
        if (selected) {
            FlashIcon(icon = FlashIcons.Check, contentDescription = null, tint = contentColor, size = 16.dp)
        }
        FlashText(
            text = label,
            style = if (selected) FlashTheme.typography.bodyEmphasis else FlashTheme.typography.bodyDefault,
            color = contentColor,
        )
    }
}

@Composable
internal fun HistoryButton(text: String, filled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(FlashShapes.button)
            .then(if (filled) Modifier.background(colors.accentPrimary) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8),
        contentAlignment = Alignment.Center,
    ) {
        FlashText(
            text = text,
            style = FlashTheme.typography.bodyEmphasis,
            color = if (filled) colors.textOnAccent else colors.textSecondary,
        )
    }
}

@Preview(name = "Group history card", showBackground = true, widthDp = 390)
@Composable
private fun FlashGroupHistoryCardPreview() {
    FlashTheme {
        Column {
            FlashGroupHistoryCard(
                history = FlashGroupHistoryUi(GroupHistoryCeiling.D30),
                onChoose = { _, _ -> },
                onSkip = {},
            )
            FlashGroupHistoryCard(
                history = FlashGroupHistoryUi(GroupHistoryCeiling.NONE),
                onChoose = { _, _ -> },
                onSkip = {},
            )
        }
    }
}
