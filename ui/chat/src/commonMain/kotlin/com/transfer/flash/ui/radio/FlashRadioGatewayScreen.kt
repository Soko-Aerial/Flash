@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.chat.FlashConfirmHost
import com.transfer.flash.ui.hub.FlashHubButton
import com.transfer.flash.ui.hub.FlashHubChip
import com.transfer.flash.ui.hub.FlashHubIconButton
import com.transfer.flash.ui.hub.FlashHubTextAction
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
 * ## Layout (redesign v3, 2026-11-05)
 *
 * The owner's brief: turning the gateway on should show the radios already paired and offer to pair a new
 * one; pairing opens a sheet that scans; the security profile and the operational mode belong in settings;
 * hold-to-talk needs an obvious place. So the page is now four things and one dock:
 *
 * 1. the **station plate** — the radio, its live state and the master switch (and "Turn on" while it is off);
 * 2. the **radios** the device owns, with the one "Pair a new radio" action;
 * 3. the **readiness steps** (expanded until something is paired);
 * 4. **This link** — profile, callsign, mode;
 * 5. the **audio panel**: in the thumb zone above the shell's bottom bar on a phone, under the plate on a wide
 *    window. It is where listen and hold-to-talk live, and it is deliberately never offered while the link is
 *    down.
 *
 * Nothing about the wiring changed: the same controller calls, the same dispose behaviour
 * ([RadioGatewayController.stopScan] and [RadioGatewayController.setVoiceListening] when the screen is left)
 * and the same authorisation / transmit-warning gates.
 */
@Composable
public fun FlashRadioGatewayScreen(
    controller: RadioGatewayController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    /** Space the hosting shell's bottom bar occupies; the dock scrolls clear of it and sits above it. */
    bottomInset: Dp = 0.dp,
) {
    val state by controller.state.collectAsState()
    val voice by controller.voice.collectAsState()
    val colors = FlashTheme.colors

    var sheet by remember { mutableStateOf(FlashRadioOverlay.NONE) }
    var showAuthorisationDialog by remember { mutableStateOf(false) }
    var showTestMessageConfirm by remember { mutableStateOf(false) }
    var readinessExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingSwitch by remember { mutableStateOf<DiscoveredRadioDevice?>(null) }

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
            onDismiss = { showAuthorisationDialog = false },
        )
    }

    if (showTestMessageConfirm) {
        FlashRadioTestMessageWarningDialog(
            onTransmitNow = {
                showTestMessageConfirm = false
                controller.sendTestMessage()
            },
            onDismiss = { showTestMessageConfirm = false },
        )
    }

    pendingSwitch?.let { device ->
        FlashConfirmHost(
            onDismiss = { pendingSwitch = null },
            containerColor = colors.sheetSurface,
            title = {
                FlashText(
                    text = FlashRadioCopy.SWITCH_RADIO_TITLE,
                    style = FlashTheme.typography.headingSmall,
                    color = colors.textPrimary,
                )
            },
            text = {
                FlashText(
                    text = FlashRadioCopy.candidateQuestion(device.name),
                    style = FlashTheme.typography.bodyDefault,
                    color = colors.textSecondary,
                )
            },
            confirmButton = {
                FlashHubButton(
                    label = FlashRadioCopy.ACTION_USE_CANDIDATE,
                    onClick = {
                        pendingSwitch = null
                        controller.pair(device.id)
                    },
                )
            },
            dismissButton = {
                FlashHubTextAction(
                    label = FlashRadioCopy.ACTION_CANCEL_TRANSMIT,
                    onClick = { pendingSwitch = null },
                )
            },
        )
    }

    val permissionNeeded = (
        !state.nearbyDevicesPermissionGranted ||
            state.failureReason == RadioFailureReason.PERMISSION_MISSING ||
            state.failureReason == RadioFailureReason.PERMISSION_DENIED
        ) && state.connectionStatus != RadioConnectionStatus.CONNECTED
    val hasRadio = state.rememberedRadioName != null
    val audioVisible = voice.available && state.enabled && hasRadio
    val linkLive = state.connectionStatus == RadioConnectionStatus.CONNECTED

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backgroundSurface)
            .statusBarsPadding(),
    ) {
        val wide = maxWidth >= WIDE_LAYOUT_WIDTH
        val dense = maxWidth < COMPACT_LAYOUT_WIDTH

        Column(modifier = Modifier.fillMaxSize()) {
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
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    FlashHubChip(text = FlashRadioCopy.BADGE_BETA)
                    Spacer(modifier = Modifier.width(FlashSpacing.space4))
                    FlashHubIconButton(
                        icon = FlashIcons.Settings,
                        description = FlashRadioCopy.A11Y_OPEN_SETTINGS,
                        onClick = { sheet = FlashRadioOverlay.SETTINGS },
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .widthIn(max = PAGE_MAX_WIDTH)
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(
                        start = FlashSpacing.space16,
                        end = FlashSpacing.space16,
                        top = FlashSpacing.space4,
                        bottom = FlashSpacing.space24,
                    ),
                    verticalArrangement = Arrangement.spacedBy(30.dp),
                ) {
                    item(key = "station") {
                        FlashRadioStationBlock(
                            state = state,
                            onToggle = { controller.setEnabled(it) },
                            onReconnect = { controller.reconnect() },
                        )
                    }

                    if (!state.enabled) {
                        item(key = "intro") {
                            FlashText(
                                text = FlashRadioCopy.INTRO_TEXT,
                                modifier = Modifier.padding(horizontal = FlashSpacing.space4),
                                style = FlashTheme.typography.captionDefault,
                                color = colors.textSecondary,
                            )
                        }
                        item(key = "readiness") {
                            FlashRadioReadinessSection(
                                title = FlashRadioCopy.READINESS_OFF_TITLE,
                                expanded = true,
                                alwaysExpanded = true,
                                onToggle = {},
                            )
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

                        if (wide && audioVisible) {
                            item(key = "audio") {
                                FlashRadioAudioPanel(
                                    voice = voice,
                                    linkLive = linkLive,
                                    onListenChange = { controller.setVoiceListening(it) },
                                    onTalkDown = { controller.voicePushToTalk(true) },
                                    onTalkUp = { controller.voicePushToTalk(false) },
                                    onAllowMicrophone = { controller.requestMicrophonePermission() },
                                )
                            }
                        }

                        item(key = "radios") {
                            FlashRadioRadiosSection(
                                state = state,
                                onPair = { id ->
                                    val device = state.discoveredDevices.firstOrNull { it.id == id }
                                    if (device != null) pendingSwitch = device else controller.pair(id)
                                },
                                onPairNew = { sheet = FlashRadioOverlay.PAIR },
                                fillWidth = dense,
                            )
                        }

                        item(key = "readiness") {
                            FlashRadioReadinessSection(
                                title = FlashRadioCopy.READINESS_PAIR_TITLE,
                                expanded = readinessExpanded,
                                alwaysExpanded = !hasRadio && state.candidateRadio == null,
                                onToggle = { readinessExpanded = !readinessExpanded },
                            )
                        }

                        if (hasRadio) {
                            item(key = "link") {
                                FlashRadioLinkFactsSection(state)
                            }
                        }
                    }
                }
            }

            if (!wide && audioVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = FlashSpacing.space12,
                            end = FlashSpacing.space12,
                            top = FlashSpacing.space8,
                            bottom = bottomInset + FlashSpacing.space8,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    FlashRadioAudioPanel(
                        voice = voice,
                        linkLive = linkLive,
                        onListenChange = { controller.setVoiceListening(it) },
                        onTalkDown = { controller.voicePushToTalk(true) },
                        onTalkUp = { controller.voicePushToTalk(false) },
                        onAllowMicrophone = { controller.requestMicrophonePermission() },
                        modifier = Modifier.widthIn(max = PAGE_MAX_WIDTH),
                        dense = dense,
                        stacked = true,
                    )
                }
            }
        }
    }

    when (sheet) {
        FlashRadioOverlay.PAIR -> FlashRadioPairSheet(
            state = state,
            onDismiss = { sheet = FlashRadioOverlay.NONE },
            onStartScan = { controller.startScan() },
            onStopScan = { controller.stopScan() },
            onPair = { id ->
                sheet = FlashRadioOverlay.NONE
                controller.pair(id)
            },
            onOpenSettings = { controller.openSystemBluetoothSettings() },
            onRequestPermission = { controller.requestNearbyDevicesPermission() },
        )

        FlashRadioOverlay.SETTINGS -> FlashRadioSettingsSheet(
            state = state,
            canSendTestMessage = FlashRadioMath.canSendTestMessage(
                state.connectionStatus,
                state.isSendingTestMessage,
            ),
            onDismiss = { sheet = FlashRadioOverlay.NONE },
            onSelectProfile = { controller.selectProfile(it) },
            onOpenAuthorisation = {
                sheet = FlashRadioOverlay.NONE
                showAuthorisationDialog = true
            },
            onSelectMode = { controller.selectMode(it) },
            onRequestSendTest = {
                sheet = FlashRadioOverlay.NONE
                showTestMessageConfirm = true
            },
            onForget = {
                sheet = FlashRadioOverlay.NONE
                controller.forget()
            },
        )

        FlashRadioOverlay.NONE -> Unit
    }
}

/** The one overlay this screen can show over itself. */
private enum class FlashRadioOverlay { NONE, PAIR, SETTINGS }

/** Wide windows get the audio panel in the flow under the plate; narrower ones get the dock. */
private val WIDE_LAYOUT_WIDTH = 900.dp

/** Below this the audio panel is denser (it sits above the shell's bottom bar). */
private val COMPACT_LAYOUT_WIDTH = 600.dp

/** One column at every size, capped; nothing is stretched across a 1400 dp pane. */
private val PAGE_MAX_WIDTH = 780.dp
