package com.transfer.flash.ui.hub.competition.novahub

/**
 * NOVA — Hub UI/UX competition entry (OpenCode). Previews.
 *
 * One preview per page × per judge state (5 × 6), dark and wide spot checks, and the showcase
 * itself in all four toggle shapes. The mock world is fixed, so preview text is stable; the demo
 * ticker starts at zero, so "live for N s" lines read the same on every render.
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.theme.FlashTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/** Bounded host so the scrolling page scaffolds get finite constraints in every preview. */
@Composable
private fun NovaPageHost(
    page: NovaPage,
    scenario: NovaScenario,
    dark: Boolean = false,
    width: Dp = 420.dp,
    height: Dp = 920.dp,
) {
    FlashTheme(darkTheme = dark) {
        Box(
            Modifier
                .width(width)
                .height(height)
                .background(FlashTheme.colors.backgroundApp),
        ) {
            when (page) {
                NovaPage.Command -> NovaCommandPage(NovaMockWorld.worldFor(scenario), onDemoAction = {})
                NovaPage.Directory -> NovaDirectoryPage(NovaMockWorld.worldFor(scenario), onDemoAction = {})
                NovaPage.Broadcast -> NovaNewsPage(NovaMockWorld.worldFor(scenario), onDemoAction = {})
                NovaPage.Vault -> NovaVaultPage(NovaMockWorld.worldFor(scenario), onDemoAction = {})
                NovaPage.Bridge -> NovaBridgePage(NovaMockWorld.worldFor(scenario), onDemoAction = {})
            }
        }
    }
}

@Composable
private fun NovaShowcaseHost(
    scenario: NovaScenario,
    form: NovaForm,
    dark: Boolean,
    reduceMotion: Boolean,
    width: Dp,
    height: Dp = 900.dp,
) {
    Box(Modifier.width(width).height(height)) {
        HubCompetitionShowcaseScreen(
            initialScenario = scenario,
            initialForm = form,
            initialDark = dark,
            initialReduceMotion = reduceMotion,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// --- Command Center · six states ----------------------------------------------------------------------

@Preview(name = "01 Command · Unenrolled", showBackground = true)
@Composable
private fun CommandUnenrolledPreview() = NovaPageHost(NovaPage.Command, NovaScenario.Unenrolled)

@Preview(name = "02 Command · Enrolling", showBackground = true)
@Composable
private fun CommandEnrollingPreview() = NovaPageHost(NovaPage.Command, NovaScenario.Enrolling)

@Preview(name = "03 Command · Active & Sealed", showBackground = true)
@Composable
private fun CommandActiveSealedPreview() = NovaPageHost(NovaPage.Command, NovaScenario.ActiveSealed)

@Preview(name = "04 Command · Relay Degraded", showBackground = true)
@Composable
private fun CommandRelayDegradedPreview() = NovaPageHost(NovaPage.Command, NovaScenario.RelayDegraded)

@Preview(name = "05 Command · Cert Revoked", showBackground = true)
@Composable
private fun CommandCertRevokedPreview() = NovaPageHost(NovaPage.Command, NovaScenario.CertRevoked)

@Preview(name = "06 Command · Empty / Offline", showBackground = true)
@Composable
private fun CommandOffEmptyPreview() = NovaPageHost(NovaPage.Command, NovaScenario.OffEmpty)

// --- Directory · six states ---------------------------------------------------------------------------

@Preview(name = "07 Directory · Unenrolled", showBackground = true)
@Composable
private fun DirectoryUnenrolledPreview() = NovaPageHost(NovaPage.Directory, NovaScenario.Unenrolled)

@Preview(name = "08 Directory · Enrolling", showBackground = true)
@Composable
private fun DirectoryEnrollingPreview() = NovaPageHost(NovaPage.Directory, NovaScenario.Enrolling)

@Preview(name = "09 Directory · Active & Sealed", showBackground = true)
@Composable
private fun DirectoryActiveSealedPreview() = NovaPageHost(NovaPage.Directory, NovaScenario.ActiveSealed)

@Preview(name = "10 Directory · Relay Degraded", showBackground = true)
@Composable
private fun DirectoryRelayDegradedPreview() = NovaPageHost(NovaPage.Directory, NovaScenario.RelayDegraded)

@Preview(name = "11 Directory · Cert Revoked", showBackground = true)
@Composable
private fun DirectoryCertRevokedPreview() = NovaPageHost(NovaPage.Directory, NovaScenario.CertRevoked)

@Preview(name = "12 Directory · Empty / Offline", showBackground = true)
@Composable
private fun DirectoryOffEmptyPreview() = NovaPageHost(NovaPage.Directory, NovaScenario.OffEmpty)

// --- Broadcast · six states ---------------------------------------------------------------------------

@Preview(name = "13 Broadcast · Unenrolled", showBackground = true)
@Composable
private fun BroadcastUnenrolledPreview() = NovaPageHost(NovaPage.Broadcast, NovaScenario.Unenrolled)

@Preview(name = "14 Broadcast · Enrolling", showBackground = true)
@Composable
private fun BroadcastEnrollingPreview() = NovaPageHost(NovaPage.Broadcast, NovaScenario.Enrolling)

@Preview(name = "15 Broadcast · Active & Sealed", showBackground = true)
@Composable
private fun BroadcastActiveSealedPreview() = NovaPageHost(NovaPage.Broadcast, NovaScenario.ActiveSealed)

@Preview(name = "16 Broadcast · Relay Degraded", showBackground = true)
@Composable
private fun BroadcastRelayDegradedPreview() = NovaPageHost(NovaPage.Broadcast, NovaScenario.RelayDegraded)

@Preview(name = "17 Broadcast · Cert Revoked", showBackground = true)
@Composable
private fun BroadcastCertRevokedPreview() = NovaPageHost(NovaPage.Broadcast, NovaScenario.CertRevoked)

@Preview(name = "18 Broadcast · Empty / Offline", showBackground = true)
@Composable
private fun BroadcastOffEmptyPreview() = NovaPageHost(NovaPage.Broadcast, NovaScenario.OffEmpty)

// --- Vault · six states -------------------------------------------------------------------------------

@Preview(name = "19 Vault · Unenrolled", showBackground = true)
@Composable
private fun VaultUnenrolledPreview() = NovaPageHost(NovaPage.Vault, NovaScenario.Unenrolled)

@Preview(name = "20 Vault · Enrolling", showBackground = true)
@Composable
private fun VaultEnrollingPreview() = NovaPageHost(NovaPage.Vault, NovaScenario.Enrolling)

@Preview(name = "21 Vault · Active & Sealed", showBackground = true)
@Composable
private fun VaultActiveSealedPreview() = NovaPageHost(NovaPage.Vault, NovaScenario.ActiveSealed)

@Preview(name = "22 Vault · Relay Degraded", showBackground = true)
@Composable
private fun VaultRelayDegradedPreview() = NovaPageHost(NovaPage.Vault, NovaScenario.RelayDegraded)

@Preview(name = "23 Vault · Cert Revoked", showBackground = true)
@Composable
private fun VaultCertRevokedPreview() = NovaPageHost(NovaPage.Vault, NovaScenario.CertRevoked)

@Preview(name = "24 Vault · Empty / Offline", showBackground = true)
@Composable
private fun VaultOffEmptyPreview() = NovaPageHost(NovaPage.Vault, NovaScenario.OffEmpty)

// --- Bridge · six states ------------------------------------------------------------------------------

@Preview(name = "25 Bridge · Unenrolled", showBackground = true)
@Composable
private fun BridgeUnenrolledPreview() = NovaPageHost(NovaPage.Bridge, NovaScenario.Unenrolled)

@Preview(name = "26 Bridge · Enrolling", showBackground = true)
@Composable
private fun BridgeEnrollingPreview() = NovaPageHost(NovaPage.Bridge, NovaScenario.Enrolling)

@Preview(name = "27 Bridge · Active & Sealed", showBackground = true)
@Composable
private fun BridgeActiveSealedPreview() = NovaPageHost(NovaPage.Bridge, NovaScenario.ActiveSealed)

@Preview(name = "28 Bridge · Relay Degraded", showBackground = true)
@Composable
private fun BridgeRelayDegradedPreview() = NovaPageHost(NovaPage.Bridge, NovaScenario.RelayDegraded)

@Preview(name = "29 Bridge · Cert Revoked", showBackground = true)
@Composable
private fun BridgeCertRevokedPreview() = NovaPageHost(NovaPage.Bridge, NovaScenario.CertRevoked)

@Preview(name = "30 Bridge · Empty / Offline", showBackground = true)
@Composable
private fun BridgeOffEmptyPreview() = NovaPageHost(NovaPage.Bridge, NovaScenario.OffEmpty)

// --- Dark & wide spot checks --------------------------------------------------------------------------

@Preview(name = "31 Command · Relay Degraded · dark", showBackground = true)
@Composable
private fun CommandDarkPreview() = NovaPageHost(NovaPage.Command, NovaScenario.RelayDegraded, dark = true)

@Preview(name = "32 Vault · Cert Revoked · dark", showBackground = true)
@Composable
private fun VaultDarkPreview() = NovaPageHost(NovaPage.Vault, NovaScenario.CertRevoked, dark = true)

@Preview(name = "33 Directory · Active · wide", showBackground = true)
@Composable
private fun DirectoryWidePreview() =
    NovaPageHost(NovaPage.Directory, NovaScenario.ActiveSealed, width = 900.dp, height = 760.dp)

@Preview(name = "34 Bridge · Relay Degraded · wide", showBackground = true)
@Composable
private fun BridgeWidePreview() =
    NovaPageHost(NovaPage.Bridge, NovaScenario.RelayDegraded, width = 900.dp, height = 760.dp)

// --- The showcase itself ------------------------------------------------------------------------------

@Preview(name = "35 Showcase · phone · light", showBackground = true)
@Composable
private fun ShowcasePhoneLightPreview() =
    NovaShowcaseHost(NovaScenario.ActiveSealed, NovaForm.MobileCompact, dark = false, reduceMotion = false, width = 420.dp)

@Preview(name = "36 Showcase · phone · dark", showBackground = true)
@Composable
private fun ShowcasePhoneDarkPreview() =
    NovaShowcaseHost(NovaScenario.RelayDegraded, NovaForm.MobileCompact, dark = true, reduceMotion = false, width = 420.dp)

@Preview(name = "37 Showcase · phone · reduced motion", showBackground = true)
@Composable
private fun ShowcasePhoneReducedPreview() =
    NovaShowcaseHost(NovaScenario.Enrolling, NovaForm.MobileCompact, dark = false, reduceMotion = true, width = 420.dp)

@Preview(name = "38 Showcase · desktop split", showBackground = true)
@Composable
private fun ShowcaseDesktopPreview() =
    NovaShowcaseHost(NovaScenario.ActiveSealed, NovaForm.DesktopSplit, dark = false, reduceMotion = false, width = 1100.dp, height = 760.dp)
