@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.shims.FlashBackHandler
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * Compose Multiplatform screen for the Hardware Radio KISS TNC Gateway (Plan R §6, §8).
 * Shared by Android and desktop hosts.
 */
@Composable
public fun FlashRadioGatewayScreen(
    controller: RadioGatewayController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val state by controller.state.collectAsState()
    var showAuthorisationDialog by remember { mutableStateOf(false) }
    var showTestMessageConfirm by remember { mutableStateOf(false) }

    // Intercept hardware / gesture back on Android
    FlashBackHandler(onBack = onBack)

    // Stop active discovery scan whenever the screen is disposed / left (Plan R §6, RADCON-10)
    DisposableEffect(controller) {
        onDispose {
            controller.stopScan()
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FlashTheme.colors.backgroundSurface)
            .statusBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = FlashSpacing.space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.semantics {
                        contentDescription = FlashRadioCopy.A11Y_BACK
                    },
                ) {
                    FlashIcon(
                        icon = FlashIcons.Back,
                        tint = FlashTheme.colors.textPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(modifier = Modifier.width(FlashSpacing.space8))
                FlashText(
                    text = FlashRadioCopy.SCREEN_TITLE,
                    style = FlashTheme.typography.headingLarge,
                    color = FlashTheme.colors.textPrimary,
                )
            }

            // Main Content Area with Adaptive Split
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                val isWide = maxWidth >= 840.dp

                if (isWide) {
                    // Two-Column Layout for Desktop and Large Tablets
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 1100.dp)
                            .align(Alignment.TopCenter)
                            .padding(horizontal = FlashSpacing.space24, vertical = FlashSpacing.space16),
                        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space24),
                    ) {
                        // Left Column: Transport, Checklist, Scan, Devices
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(FlashSpacing.space16),
                        ) {
                            item(key = "master-switch") {
                                FlashRadioMasterSwitchCard(
                                    enabled = state.enabled,
                                    onToggle = { controller.setEnabled(it) },
                                )
                            }
                            if (state.enabled) {
                                item(key = "status-card") {
                                    FlashRadioStatusCard(
                                        state = state,
                                        onReconnect = { controller.reconnect() },
                                    )
                                }
                                if (!state.nearbyDevicesPermissionGranted ||
                                    state.failureReason == RadioFailureReason.PERMISSION_MISSING ||
                                    state.failureReason == RadioFailureReason.PERMISSION_DENIED
                                ) {
                                    item(key = "permission-card") {
                                        FlashRadioPermissionCard(
                                            isDenied = state.failureReason == RadioFailureReason.PERMISSION_DENIED,
                                            onRequestPermission = { controller.requestNearbyDevicesPermission() },
                                            onOpenSettings = { controller.openSystemBluetoothSettings() },
                                        )
                                    }
                                }
                                if (state.candidateRadio != null) {
                                    item(key = "candidate-card") {
                                        FlashRadioCandidateCard(
                                            candidate = state.candidateRadio!!,
                                            onConfirm = { controller.confirmCandidate(state.candidateRadio!!.id) },
                                            onDismiss = { controller.startScan() },
                                        )
                                    }
                                }
                                if (state.rememberedRadioAddress != null) {
                                    item(key = "remembered-card") {
                                        FlashRadioRememberedCard(
                                            name = state.rememberedRadioName ?: "Radio",
                                            address = state.rememberedRadioAddress!!,
                                            onForget = { controller.forget() },
                                            onReconnect = { controller.reconnect() },
                                            canReconnect = FlashRadioMath.canReconnect(state.connectionStatus, true),
                                        )
                                    }
                                }
                                if (state.rememberedRadioAddress == null && state.candidateRadio == null) {
                                    item(key = "checklist-card") {
                                        FlashRadioChecklistCard()
                                    }
                                }
                                item(key = "find-section") {
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
                            }
                        }

                        // Right Column: Security, Modes, Diagnostics
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(FlashSpacing.space16),
                        ) {
                            if (state.enabled) {
                                item(key = "security-section") {
                                    FlashRadioSecurityCard(
                                        selectedProfile = state.selectedProfile,
                                        isAuthorised = state.isAuthorisedForEncryption,
                                        onSelectProfile = { controller.selectProfile(it) },
                                        onOpenAuthorisation = { showAuthorisationDialog = true },
                                    )
                                }
                                item(key = "modes-section") {
                                    FlashRadioModesCard(
                                        selectedMode = state.selectedMode,
                                        availableModes = state.availableModes,
                                        onSelectMode = { controller.selectMode(it) },
                                    )
                                }
                                item(key = "diagnostics-section") {
                                    FlashRadioDiagnosticsCard(
                                        canSend = FlashRadioMath.canSendTestMessage(state.connectionStatus, state.isSendingTestMessage),
                                        isSending = state.isSendingTestMessage,
                                        lastSuccess = state.testMessageSentSuccess,
                                        onRequestSend = { showTestMessageConfirm = true },
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Single Column Layout for Phones and Narrow Windows
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8),
                        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space16),
                    ) {
                        item(key = "master-switch") {
                            FlashRadioMasterSwitchCard(
                                enabled = state.enabled,
                                onToggle = { controller.setEnabled(it) },
                            )
                        }
                        if (state.enabled) {
                            item(key = "status-card") {
                                FlashRadioStatusCard(
                                    state = state,
                                    onReconnect = { controller.reconnect() },
                                )
                            }
                            if (!state.nearbyDevicesPermissionGranted ||
                                state.failureReason == RadioFailureReason.PERMISSION_MISSING ||
                                state.failureReason == RadioFailureReason.PERMISSION_DENIED
                            ) {
                                item(key = "permission-card") {
                                    FlashRadioPermissionCard(
                                        isDenied = state.failureReason == RadioFailureReason.PERMISSION_DENIED,
                                        onRequestPermission = { controller.requestNearbyDevicesPermission() },
                                        onOpenSettings = { controller.openSystemBluetoothSettings() },
                                    )
                                }
                            }
                            if (state.candidateRadio != null) {
                                item(key = "candidate-card") {
                                    FlashRadioCandidateCard(
                                        candidate = state.candidateRadio!!,
                                        onConfirm = { controller.confirmCandidate(state.candidateRadio!!.id) },
                                        onDismiss = { controller.startScan() },
                                    )
                                }
                            }
                            if (state.rememberedRadioAddress != null) {
                                item(key = "remembered-card") {
                                    FlashRadioRememberedCard(
                                        name = state.rememberedRadioName ?: "Radio",
                                        address = state.rememberedRadioAddress!!,
                                        onForget = { controller.forget() },
                                        onReconnect = { controller.reconnect() },
                                        canReconnect = FlashRadioMath.canReconnect(state.connectionStatus, true),
                                    )
                                }
                            }
                            if (state.rememberedRadioAddress == null && state.candidateRadio == null) {
                                item(key = "checklist-card") {
                                    FlashRadioChecklistCard()
                                }
                            }
                            item(key = "find-section") {
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
                            item(key = "security-section") {
                                FlashRadioSecurityCard(
                                    selectedProfile = state.selectedProfile,
                                    isAuthorised = state.isAuthorisedForEncryption,
                                    onSelectProfile = { controller.selectProfile(it) },
                                    onOpenAuthorisation = { showAuthorisationDialog = true },
                                )
                            }
                            item(key = "modes-section") {
                                FlashRadioModesCard(
                                    selectedMode = state.selectedMode,
                                    availableModes = state.availableModes,
                                    onSelectMode = { controller.selectMode(it) },
                                )
                            }
                            item(key = "diagnostics-section") {
                                FlashRadioDiagnosticsCard(
                                    canSend = FlashRadioMath.canSendTestMessage(state.connectionStatus, state.isSendingTestMessage),
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
}
