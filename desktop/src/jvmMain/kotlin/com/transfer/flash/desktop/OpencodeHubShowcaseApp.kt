package com.transfer.flash.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.transfer.flash.ui.hub.competition.opencode.HubCompetitionShowcaseScreen

/**
 * Desktop standalone launcher for the "Command Deck" Hub competition entry (package `opencode`).
 *
 * A design preview, not a feature: the window is the entry's own showcase (five pages, the floating Judge's control
 * bar), it reads no repository, network or settings state, and this class is excluded from the desktop jar.
 *
 * Run via: `./gradlew :desktop:opencodeHubShowcase`
 * Design doc: `docs/ui/hub-competition-command-deck.md`
 */
public fun main() {
    application {
        val windowState = rememberWindowState(width = 1280.dp, height = 900.dp)
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Flash Hub — Command Deck (competition entry \"opencode\")",
        ) {
            HubCompetitionShowcaseScreen()
        }
    }
}
