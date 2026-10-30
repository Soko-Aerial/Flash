@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.hub.FlashHubIconButton
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.shims.FlashBackHandler
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * Compose Multiplatform screen for the Hardware Radio KISS TNC Gateway (Plan R §6, §8).
 * Shared by Android and desktop hosts.
 *
 * ## Layout (redesign v2, 2026-10-28)
 *
 * One column at every size, capped at [PAGE_MAX_WIDTH] and centred, instead of the old phone stack plus a
 * two-column desktop split whose left column was twice as long as its right one. The page is a single station
 * plate (the radio and its state), two attention strips that only appear when something needs an answer, and
 * then sections of rows. See `docs/ui/radio-gateway.md`, "Redesign v2".
 *
 * Nothing about the wiring changed: the same controller calls, the same dispose behaviour ([RadioGatewayController.stopScan]
 * and [RadioGatewayController.setVoiceListening] when the screen is left) and the same two sheets.
 */
@Composable
public fun FlashRadioGatewayScreen(
    controller: RadioGatewayController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    /** Space the hosting shell's bottom bar occupies; the last row scrolls clear of it. */
    bottomInset: Dp = 0.dp,
) {
    val state by controller.state.collectAsState()
    val voice by controller.voice.collectAsState()
    var showAuthorisationDialog by remember { mutableStateOf(false) }
    var showTestMessageConfirm by remember { mutableStateOf(false) }

    // Intercept hardware / gesture back on Android
    FlashBackHandler(onBack = onBack)

    // Stop active discovery scan whenever the screen is disposed / left (Plan R §6, RADCON-10)
    DisposableEffect(controller) {
        onDispose {
            controller.stopScan()
            // Voice is foreground-only for now: leaving the screen releases the audio link, speaker and microphone.
            controller.setVoiceListening(false)
        }
    }

    if (showAuthorisationDialog) {
        FlashRadioAuthorisationDialog(
            initialCallsign = state.operatorCallsign.orEmpty(),
            onConfirm = { callsign ->
                showAuthorisationDialog = false
                controller.confirmAuthorisation(callsign)
            },
            onDismiss = {
                showAuthorisationDialog = false
            },
        )
    }

    if (showTestMessageConfirm) {
        FlashRadioTestMessageWarningDialog(
            onTransmitNow = {
                showTestMessageConfirm = false
                controller.sendTestMessage()
            },
            onDismiss = {
                showTestMessageConfirm = false
            },
        )
    }

    val permissionNeeded = !state.nearbyDevicesPermissionGranted ||
        state.failureReason == RadioFailureReason.PERMISSION_MISSING ||
        state.failureReason == RadioFailureReason.PERMISSION_DENIED

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FlashTheme.colors.backgroundSurface)
            .statusBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header: the shell's back affordance and the page title. No card, no second title inside the page.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Row(
                    modifier = Modifier
                        .widthIn(max = PAGE_MAX_WIDTH)
                        .fillMaxWidth()
                        .height(FlashDimensions.headerHeight)
                        .padding(horizontal = FlashSpacing.space8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FlashHubIconButton(FlashIcons.Back, FlashRadioCopy.A11Y_BACK, onBack)
                    Spacer(modifier = Modifier.width(FlashSpacing.space4))
                    FlashText(
                        text = FlashRadioCopy.SCREEN_TITLE,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() },
                        style = FlashTheme.typography.headingMedium,
                        color = FlashTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .widthIn(max = PAGE_MAX_WIDTH)
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(
                        start = FlashSpacing.space16,
                        end = FlashSpacing.space16,
                        top = FlashSpacing.space4,
                        bottom = FlashSpacing.space16 + bottomInset,
                    ),
                    verticalArrangement = Arrangement.spacedBy(FlashSpacing.space20),
                ) {
                    item(key = "station") {
                        FlashRadioStationBlock(
                            state = state,
                            onToggle = { controller.setEnabled(it) },
                            onReconnect = { controller.reconnect() },
                        )
                    }

                    if (!state.enabled) {
                        // Off: the readiness steps instead of an empty page. Nothing to scan while off.
                        item(key = "checklist") {
                            FlashRadioChecklistSection()
                        }
                    } else {
                        if (permissionNeeded) {
                            item(key = "permission") {
                                FlashRadioPermissionBanner(
                                    isDenied = state.failureReason == RadioFailureReason.PERMISSION_DENIED,
                                    onRequestPermission = { controller.requestNearbyDevicesPermission() },
                                    onOpenSettings = { controller.openSystemBluetoothSettings() },
                                )
                            }
                        }
                        state.candidateRadio?.let { candidate ->
                            item(key = "candidate") {
                                FlashRadioCandidateBanner(
                                    candidate = candidate,
                                    onConfirm = { controller.confirmCandidate(candidate.id) },
                                    onDismiss = { controller.startScan() },
                                )
                            }
                        }
                        if (state.rememberedRadioAddress != null) {
                            item(key = "link") {
                                FlashRadioLinkSection(
                                    state = state,
                                    onForget = { controller.forget() },
                                )
                            }
                        }
                        if (state.rememberedRadioAddress == null && state.candidateRadio == null) {
                            item(key = "checklist") {
                                FlashRadioChecklistSection()
                            }
                        }
                        item(key = "find") {
                            FlashRadioFindSection(
                                isScanning = state.isScanning,
                                countdownSeconds = state.scanCountdownSeconds,
                                devices = state.discoveredDevices,
                                onStartScan = { controller.startScan() },
                                onStopScan = { controller.stopScan() },
                                onPair = { controller.pair(it) },
                                onOpenSettings = { controller.openSystemBluetoothSettings() },
                            )
                        }
                        item(key = "security") {
                            FlashRadioSecuritySection(
                                selectedProfile = state.selectedProfile,
                                isAuthorised = state.isAuthorisedForEncryption,
                                onSelectProfile = { controller.selectProfile(it) },
                                onOpenAuthorisation = { showAuthorisationDialog = true },
                            )
                        }
                        item(key = "modes") {
                            FlashRadioModesSection(
                                selectedMode = state.selectedMode,
                                availableModes = state.availableModes,
                                onSelectMode = { controller.selectMode(it) },
                            )
                        }
                        if (voice.available && state.rememberedRadioAddress != null) {
                            item(key = "voice") {
                                FlashRadioVoiceSection(
                                    voice = voice,
                                    onListenChange = { controller.setVoiceListening(it) },
                                    onTalkDown = { controller.voicePushToTalk(true) },
                                    onTalkUp = { controller.voicePushToTalk(false) },
                                    onAllowMicrophone = { controller.requestMicrophonePermission() },
                                )
                            }
                        }
                        item(key = "diagnostics") {
                            FlashRadioDiagnosticsSection(
                                canSend = FlashRadioMath.canSendTestMessage(
                                    state.connectionStatus,
                                    state.isSendingTestMessage,
                                ),
                                isSending = state.isSendingTestMessage,
                                lastSuccess = state.testMessageSentSuccess,
                                onRequestSend = { showTestMessageConfirm = true },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Wide desktop windows get the same single column, capped; nothing is stretched across a 1400 dp pane. */
private val PAGE_MAX_WIDTH = 720.dp
