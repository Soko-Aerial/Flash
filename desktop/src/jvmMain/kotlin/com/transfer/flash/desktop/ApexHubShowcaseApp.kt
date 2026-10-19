package com.transfer.flash.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.transfer.flash.ui.hub.competition.apex.HubCompetitionShowcaseScreen

/**
 * Desktop standalone showcase launcher for the Apex Zenith Hub entry.
 *
 * Runs the interactive showcase window with the 5 Hub pages and floating Judge's Control Bar.
 * Run via: `./gradlew apexHubShowcase` or `./gradlew :desktop:apexHubShowcase`
 */
public fun main() {
    application {
        val windowState = rememberWindowState(width = 1240.dp, height = 860.dp)
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Flash Hub — Apex Zenith Showcase (Competition Entry)",
        ) {
            HubCompetitionShowcaseScreen()
        }
    }
}
