package com.transfer.flash.ui.hub.competition.apex

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.rememberFlashMotion
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * APEX ZENITH — Comprehensive Compose Multiplatform Previews.
 */

@Composable
private fun ApexPreviewStage(
    scenario: ApexScenario = ApexScenario.ACTIVE_SEALED,
    theme: ApexTheme = ApexTheme.DARK,
    motion: ApexMotionMode = ApexMotionMode.HIGH_MOTION,
    content: @Composable (ApexHubDeck) -> Unit,
) {
    val deck = ApexMockWorld.createDeck(scenario)
    FlashTheme(
        darkTheme = theme == ApexTheme.DARK,
        motion = rememberFlashMotion(reduceMotion = motion == ApexMotionMode.REDUCED_MOTION),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FlashTheme.colors.backgroundApp)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            content(deck)
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Top-Level Showcase Previews
// -------------------------------------------------------------------------------------------------

@Preview
@Composable
public fun Preview_HubShowcase_ActiveSealed_Mobile() {
    HubCompetitionShowcaseScreen()
}

@Preview
@Composable
public fun Preview_HubShowcase_DesktopSplit() {
    val deck = ApexMockWorld.createDeck(ApexScenario.ACTIVE_SEALED)
    FlashTheme(darkTheme = true) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FlashTheme.colors.backgroundApp),
            contentAlignment = Alignment.Center,
        ) {
            ApexHubCompetitionShowcaseScreen()
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Individual Page Previews
// -------------------------------------------------------------------------------------------------

@Preview
@Composable
public fun Preview_Page1_CommandCenter_Sealed() {
    ApexPreviewStage(scenario = ApexScenario.ACTIVE_SEALED) { deck ->
        Box(
            modifier = Modifier
                .width(412.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            ApexPageCommandCenter(deck = deck)
        }
    }
}

@Preview
@Composable
public fun Preview_Page1_CommandCenter_Degraded_Light() {
    ApexPreviewStage(scenario = ApexScenario.RELAY_DEGRADED, theme = ApexTheme.LIGHT) { deck ->
        Box(
            modifier = Modifier
                .width(412.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            ApexPageCommandCenter(deck = deck)
        }
    }
}

@Preview
@Composable
public fun Preview_Page2_Directory_Peers() {
    ApexPreviewStage(scenario = ApexScenario.ACTIVE_SEALED) { deck ->
        Box(
            modifier = Modifier
                .width(412.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            ApexPageDirectory(deck = deck)
        }
    }
}

@Preview
@Composable
public fun Preview_Page3_Announcements_BroadcastFeed() {
    ApexPreviewStage(scenario = ApexScenario.ACTIVE_SEALED) { deck ->
        Box(
            modifier = Modifier
                .width(412.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            ApexPageAnnouncements(deck = deck)
        }
    }
}

@Preview
@Composable
public fun Preview_Page4_TrustVault_CertChain() {
    ApexPreviewStage(scenario = ApexScenario.ACTIVE_SEALED) { deck ->
        Box(
            modifier = Modifier
                .width(412.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            ApexPageTrustVault(deck = deck)
        }
    }
}

@Preview
@Composable
public fun Preview_Page4_TrustVault_Unenrolled() {
    ApexPreviewStage(scenario = ApexScenario.UNENROLLED) { deck ->
        Box(
            modifier = Modifier
                .width(412.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            ApexPageTrustVault(deck = deck)
        }
    }
}

@Preview
@Composable
public fun Preview_Page4_TrustVault_Revoked() {
    ApexPreviewStage(scenario = ApexScenario.CERT_REVOKED) { deck ->
        Box(
            modifier = Modifier
                .width(412.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            ApexPageTrustVault(deck = deck)
        }
    }
}

@Preview
@Composable
public fun Preview_Page5_Rosters_RelayBridge() {
    ApexPreviewStage(scenario = ApexScenario.ACTIVE_SEALED) { deck ->
        Box(
            modifier = Modifier
                .width(412.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            ApexPageRostersBridge(deck = deck)
        }
    }
}
