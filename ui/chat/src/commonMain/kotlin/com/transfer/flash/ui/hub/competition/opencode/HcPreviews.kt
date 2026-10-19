package com.transfer.flash.ui.hub.competition.opencode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.rememberFlashMotion
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Previews for the whole deck: every page in every hub state the Judge's control bar can select, plus the theme and
 * motion switches and the harness itself.
 *
 * The stage is sized to the real frame (412dp phone, 1180dp desktop) rather than a scaled mock, so a preview shows
 * exactly what the harness shows — including the phone's pushed detail and the desktop's second pane, which are the
 * same composables. A preview is also the fastest way to check a page against a state it was not designed around:
 * the six states below are the six the judge can pick, so no page can hide behind a friendly fixture.
 */

@Composable
private fun HcPreviewStage(
    scenario: HcScenario,
    page: HcPage = HcPage.COMMAND,
    form: HcFormFactor = HcFormFactor.COMPACT,
    theme: HcTheme = HcTheme.DARK,
    motion: HcMotionMode = HcMotionMode.HIGH,
) {
    val deck = hcDeckFor(scenario)
    FlashTheme(darkTheme = theme == HcTheme.DARK, motion = rememberFlashMotion(motion == HcMotionMode.REDUCED)) {
        Box(Modifier.fillMaxSize().background(FlashTheme.colors.backgroundApp).padding(8.dp)) {
            HcShowcaseFrame(form) { HcShowcasePage(page, deck, hcFrameWidth(form).value) }
        }
    }
}

@Preview(name = "Command Center · desktop split-pane", group = "Command Deck", widthDp = 1240, heightDp = 900, showBackground = true)
@Composable
private fun PreviewCommandDesktop() = HcPreviewStage(HcScenario.ACTIVE_SEALED, HcPage.COMMAND, HcFormFactor.DESKTOP)

@Preview(name = "Directory · desktop split-pane", group = "Command Deck", widthDp = 1240, heightDp = 900, showBackground = true)
@Composable
private fun PreviewDirectoryDesktop() = HcPreviewStage(HcScenario.ACTIVE_SEALED, HcPage.DIRECTORY, HcFormFactor.DESKTOP)

@Preview(name = "Announcements · desktop split-pane", group = "Command Deck", widthDp = 1240, heightDp = 900, showBackground = true)
@Composable
private fun PreviewAnnouncementsDesktop() = HcPreviewStage(HcScenario.ACTIVE_SEALED, HcPage.ANNOUNCEMENTS, HcFormFactor.DESKTOP)

@Preview(name = "Trust Vault · desktop split-pane", group = "Command Deck", widthDp = 1240, heightDp = 900, showBackground = true)
@Composable
private fun PreviewTrustDesktop() = HcPreviewStage(HcScenario.ACTIVE_SEALED, HcPage.TRUST, HcFormFactor.DESKTOP)

@Preview(name = "Rosters · desktop split-pane", group = "Command Deck", widthDp = 1240, heightDp = 900, showBackground = true)
@Composable
private fun PreviewRostersDesktop() = HcPreviewStage(HcScenario.ACTIVE_SEALED, HcPage.ROSTERS, HcFormFactor.DESKTOP)

// ---------------------------------------------------------------------------------------------------------------
// The page x state matrix — page 1, Command Center
// ---------------------------------------------------------------------------------------------------------------

@Preview(name = "Command Center · unenrolled", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewCommandUnenrolled() = HcPreviewStage(HcScenario.UNENROLLED, HcPage.COMMAND)

@Preview(name = "Command Center · enrolling", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewCommandEnrolling() = HcPreviewStage(HcScenario.ENROLLING, HcPage.COMMAND)

@Preview(name = "Command Center · active & sealed", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewCommandActive() = HcPreviewStage(HcScenario.ACTIVE_SEALED, HcPage.COMMAND)

@Preview(name = "Command Center · relay degraded", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewCommandDegraded() = HcPreviewStage(HcScenario.RELAY_DEGRADED, HcPage.COMMAND)

@Preview(name = "Command Center · cert revoked / expired", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewCommandRevoked() = HcPreviewStage(HcScenario.CERT_REVOKED, HcPage.COMMAND)

@Preview(name = "Command Center · empty & offline", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewCommandEmpty() = HcPreviewStage(HcScenario.EMPTY_OFFLINE, HcPage.COMMAND)

// ---------------------------------------------------------------------------------------------------------------
// Page 2, Directory and peers
// ---------------------------------------------------------------------------------------------------------------

@Preview(name = "Directory · unenrolled", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewDirectoryUnenrolled() = HcPreviewStage(HcScenario.UNENROLLED, HcPage.DIRECTORY)

@Preview(name = "Directory · enrolling", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewDirectoryEnrolling() = HcPreviewStage(HcScenario.ENROLLING, HcPage.DIRECTORY)

@Preview(name = "Directory · active & sealed", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewDirectoryActive() = HcPreviewStage(HcScenario.ACTIVE_SEALED, HcPage.DIRECTORY)

@Preview(name = "Directory · relay degraded", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewDirectoryDegraded() = HcPreviewStage(HcScenario.RELAY_DEGRADED, HcPage.DIRECTORY)

@Preview(name = "Directory · cert revoked / expired", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewDirectoryRevoked() = HcPreviewStage(HcScenario.CERT_REVOKED, HcPage.DIRECTORY)

@Preview(name = "Directory · empty & offline", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewDirectoryEmpty() = HcPreviewStage(HcScenario.EMPTY_OFFLINE, HcPage.DIRECTORY)

// ---------------------------------------------------------------------------------------------------------------
// Page 3, Announcements
// ---------------------------------------------------------------------------------------------------------------

@Preview(name = "Announcements · unenrolled", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewAnnouncementsUnenrolled() = HcPreviewStage(HcScenario.UNENROLLED, HcPage.ANNOUNCEMENTS)

@Preview(name = "Announcements · enrolling", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewAnnouncementsEnrolling() = HcPreviewStage(HcScenario.ENROLLING, HcPage.ANNOUNCEMENTS)

@Preview(name = "Announcements · active & sealed (department head)", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewAnnouncementsActive() = HcPreviewStage(HcScenario.ACTIVE_SEALED, HcPage.ANNOUNCEMENTS)

@Preview(name = "Announcements · organisation-wide poster", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewAnnouncementsOrgWide() = HcPreviewStage(HcScenario.RELAY_DEGRADED, HcPage.ANNOUNCEMENTS)

@Preview(name = "Announcements · cert revoked / expired", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewAnnouncementsRevoked() = HcPreviewStage(HcScenario.CERT_REVOKED, HcPage.ANNOUNCEMENTS)

@Preview(name = "Announcements · empty & offline", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewAnnouncementsEmpty() = HcPreviewStage(HcScenario.EMPTY_OFFLINE, HcPage.ANNOUNCEMENTS)

// ---------------------------------------------------------------------------------------------------------------
// Page 4, Trust vault
// ---------------------------------------------------------------------------------------------------------------

@Preview(name = "Trust Vault · unenrolled", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewTrustUnenrolled() = HcPreviewStage(HcScenario.UNENROLLED, HcPage.TRUST)

@Preview(name = "Trust Vault · enrolling", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewTrustEnrolling() = HcPreviewStage(HcScenario.ENROLLING, HcPage.TRUST)

@Preview(name = "Trust Vault · active & sealed", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewTrustActive() = HcPreviewStage(HcScenario.ACTIVE_SEALED, HcPage.TRUST)

@Preview(name = "Trust Vault · relay degraded", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewTrustDegraded() = HcPreviewStage(HcScenario.RELAY_DEGRADED, HcPage.TRUST)

@Preview(name = "Trust Vault · cert revoked / expired", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewTrustRevoked() = HcPreviewStage(HcScenario.CERT_REVOKED, HcPage.TRUST)

@Preview(name = "Trust Vault · empty & offline", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewTrustEmpty() = HcPreviewStage(HcScenario.EMPTY_OFFLINE, HcPage.TRUST)

// ---------------------------------------------------------------------------------------------------------------
// Page 5, Rosters and relay bridge
// ---------------------------------------------------------------------------------------------------------------

@Preview(name = "Rosters · unenrolled", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewRostersUnenrolled() = HcPreviewStage(HcScenario.UNENROLLED, HcPage.ROSTERS)

@Preview(name = "Rosters · enrolling", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewRostersEnrolling() = HcPreviewStage(HcScenario.ENROLLING, HcPage.ROSTERS)

@Preview(name = "Rosters · active & sealed", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewRostersActive() = HcPreviewStage(HcScenario.ACTIVE_SEALED, HcPage.ROSTERS)

@Preview(name = "Rosters · relay degraded", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewRostersDegraded() = HcPreviewStage(HcScenario.RELAY_DEGRADED, HcPage.ROSTERS)

@Preview(name = "Rosters · cert revoked / expired", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewRostersRevoked() = HcPreviewStage(HcScenario.CERT_REVOKED, HcPage.ROSTERS)

@Preview(name = "Rosters · empty & offline", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewRostersEmpty() = HcPreviewStage(HcScenario.EMPTY_OFFLINE, HcPage.ROSTERS)

// ---------------------------------------------------------------------------------------------------------------
// Theme and motion switches of the judge's bar
// ---------------------------------------------------------------------------------------------------------------

@Preview(name = "Light · command center", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewCommandLight() = HcPreviewStage(HcScenario.ACTIVE_SEALED, HcPage.COMMAND, theme = HcTheme.LIGHT)

@Preview(name = "Light · trust vault", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewTrustLight() = HcPreviewStage(HcScenario.ACTIVE_SEALED, HcPage.TRUST, theme = HcTheme.LIGHT)

@Preview(name = "Light · announcements", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewAnnouncementsLight() = HcPreviewStage(HcScenario.ACTIVE_SEALED, HcPage.ANNOUNCEMENTS, theme = HcTheme.LIGHT)

@Preview(name = "Motion reduced · command center", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewCommandReducedMotion() =
    HcPreviewStage(HcScenario.ACTIVE_SEALED, HcPage.COMMAND, motion = HcMotionMode.REDUCED)

@Preview(name = "Motion reduced · relay degraded", group = "Command Deck", widthDp = 440, heightDp = 1000, showBackground = true)
@Composable
private fun PreviewDegradedReducedMotion() =
    HcPreviewStage(HcScenario.RELAY_DEGRADED, HcPage.COMMAND, motion = HcMotionMode.REDUCED)

// ---------------------------------------------------------------------------------------------------------------
// The harness itself
// ---------------------------------------------------------------------------------------------------------------

@Preview(name = "Showcase · Judge's control bar", group = "Command Deck", widthDp = 1180, heightDp = 820, showBackground = true)
@Composable
private fun PreviewShowcase() = HubCompetitionShowcaseScreen()

@Preview(name = "Showcase · light with the control bar", group = "Command Deck", widthDp = 900, heightDp = 820, showBackground = true)
@Composable
private fun PreviewShowcaseLight() = FlashTheme(darkTheme = false) { HubCompetitionShowcaseScreen() }
