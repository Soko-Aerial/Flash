package com.transfer.flash.ui.hub.competition.claude

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.transfer.flash.ui.hub.competition.apex.HubCompetitionShowcaseScreen as ApexShowcaseScreen
import com.transfer.flash.ui.hub.competition.novahub.HubCompetitionShowcaseScreen as NovaShowcaseScreen
import com.transfer.flash.ui.hub.competition.opencode.HubCompetitionShowcaseScreen as DeckShowcaseScreen
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale

/**
 * Dev-only desktop host for the Hub UI/UX competition entries. The entries live as packages in
 * `:ui:chat` commonMain and are deliberately unreachable from the shipped app; this window is
 * where the owner previews and judges them.
 *
 * Launch through the Gradle lock script (never `gradlew` directly):
 *
 * ```
 * powershell -File tools\flash-gradle.ps1 :ui:hub-competition-claude:runShowcase
 * ```
 *
 * An optional first argument (`nova` / `apex` / `deck` — wired to the `runNova` / `runApex` /
 * `runDeck` tasks) opens that entry directly. `Esc` inside an entry always returns to the picker.
 */

/** One judged entry: its picker row identity plus the composable that fills the window. */
private data class ShowcaseEntry(
    val id: String,
    val monogram: String,
    val title: String,
    val tagline: String,
    val screen: @Composable () -> Unit,
)

private val showcaseEntries = listOf(
    ShowcaseEntry(
        id = "nova",
        monogram = "NO",
        title = "NOVA",
        tagline = "Five pages over a six-scenario mock org — judge bar with scenario, split-pane, dark and motion toggles.",
        screen = { NovaShowcaseScreen() },
    ),
    ShowcaseEntry(
        id = "apex",
        monogram = "AZ",
        title = "Apex Zenith",
        tagline = "Command Center hero status, relay telemetry meters, trust vault tree and packet trace — six-scenario judge bar.",
        screen = { ApexShowcaseScreen() },
    ),
    ShowcaseEntry(
        id = "deck",
        monogram = "CD",
        title = "Command Deck",
        tagline = "Enterprise hub command deck with its own judge controls.",
        screen = { DeckShowcaseScreen() },
    ),
)

fun main(args: Array<String>) {
    val requestedEntryId = args.firstOrNull()?.lowercase()
    application {
        ShowcaseWindow(
            initialEntry = showcaseEntries.firstOrNull { it.id == requestedEntryId },
            onCloseRequest = ::exitApplication,
        )
    }
}

@Composable
private fun ShowcaseWindow(
    initialEntry: ShowcaseEntry?,
    onCloseRequest: () -> Unit,
) {
    var selected by remember { mutableStateOf(initialEntry) }
    Window(
        onCloseRequest = onCloseRequest,
        title = selected?.let { "Flash Hub Competition — ${it.title} (Esc = entries)" }
            ?: "Flash Hub Competition — pick an entry",
        state = rememberWindowState(width = 1200.dp, height = 840.dp),
        // Esc pops back to the picker regardless of which entry owns the content or where its
        // focus is: the handler sits on the window itself, not on a focus-dependent composable.
        onKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && selected != null) {
                selected = null
                true
            } else {
                false
            }
        },
    ) {
        val entry = selected
        if (entry == null) {
            ShowcaseMenu(onSelect = { selected = it })
        } else {
            entry.screen()
        }
    }
}

@Composable
private fun ShowcaseMenu(onSelect: (ShowcaseEntry) -> Unit) {
    val colors = FlashTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.backgroundApp)
            .padding(horizontal = FlashSpacing.space40, vertical = FlashSpacing.space32),
    ) {
        FlashText(
            text = "Flash Hub · UI/UX Competition",
            style = FlashTheme.typography.headingLarge,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(FlashSpacing.space4))
        FlashText(
            text = "Three design entries, one command. Pick one to open its showcase; press Esc inside an entry to return here.",
            style = FlashTheme.typography.metadataDefault,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(FlashSpacing.space24))
        showcaseEntries.forEach { entry ->
            ShowcaseEntryRow(entry = entry, onSelect = onSelect)
            Spacer(Modifier.height(FlashSpacing.space12))
        }
        Spacer(Modifier.weight(1f))
        FlashText(
            text = "Dev-only launcher (ui/hub-competition-claude). Nothing here is wired into the shipped app; entries are not produced by these previews.",
            style = FlashTheme.typography.metadataDefault,
            color = colors.textTertiary,
        )
    }
}

@Composable
private fun ShowcaseEntryRow(
    entry: ShowcaseEntry,
    onSelect: (ShowcaseEntry) -> Unit,
) {
    val colors = FlashTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = FlashDimensions.minTouchTarget)
            .flashPressScale(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null) { onSelect(entry) }
            .background(colors.backgroundSurface, RoundedCornerShape(FlashShapes.radius16))
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(FlashShapes.radius16))
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(colors.accentPrimary.copy(alpha = 0.14f), RoundedCornerShape(FlashShapes.radius12)),
            contentAlignment = Alignment.Center,
        ) {
            FlashText(
                text = entry.monogram,
                style = FlashTheme.typography.numericEmphasis,
                color = colors.accentPrimary,
            )
        }
        Spacer(Modifier.width(FlashSpacing.space16))
        Column(Modifier.weight(1f)) {
            FlashText(
                text = entry.title,
                style = FlashTheme.typography.bodyEmphasis,
                color = colors.textPrimary,
            )
            FlashText(
                text = entry.tagline,
                style = FlashTheme.typography.metadataDefault,
                color = colors.textSecondary,
            )
        }
        Spacer(Modifier.width(FlashSpacing.space8))
        FlashText(
            text = "Open",
            style = FlashTheme.typography.metadataEmphasis,
            color = colors.accentPrimary,
        )
    }
}
