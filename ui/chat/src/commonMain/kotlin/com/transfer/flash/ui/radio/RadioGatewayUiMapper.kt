package com.transfer.flash.ui.radio

import com.transfer.flash.core.radio.connect.CandidateCategory
import com.transfer.flash.core.radio.connect.CandidateKind
import com.transfer.flash.core.radio.connect.RadioConnectFailureReason
import com.transfer.flash.core.radio.connect.RadioConnectState
import com.transfer.flash.core.radio.connect.RadioSecurityProfile as CoreProfile
import com.transfer.flash.core.radio.driver.TncLinkState
import com.transfer.flash.core.radio.gateway.RadioGatewayState
import com.transfer.flash.core.radio.link.DiscoveredDevice

/**
 * The one place that turns the gateway domain state ([RadioGatewayState], `:core:radio`) into what the Radio
 * gateway screen shows ([RadioGatewayUiState]). The Android and Windows controllers both call it, so the mapping
 * is unit-tested once on the JVM instead of being copied into two hosts.
 */
public object RadioGatewayUiMapper {

    /** Facts only the host knows. */
    public data class HostFacts(
        val scanCountdownSeconds: Int = 0,
        val selectedMode: RadioGatewayMode = RadioGatewayMode.FIELD_COMPANION,
        val isSendingTestMessage: Boolean = false,
        val testMessageSentSuccess: Boolean? = null,
        /** Android 12+ Nearby devices permissions; always true on desktop. */
        val nearbyPermissionGranted: Boolean = true,
    )

    public fun connectionStatus(m: RadioGatewayState): RadioConnectionStatus {
        if (!m.isEnabled) return RadioConnectionStatus.OFF
        return when (m.connectState) {
            is RadioConnectState.Off -> RadioConnectionStatus.OFF
            is RadioConnectState.ChecklistShown -> RadioConnectionStatus.CHECKLIST_READY
            is RadioConnectState.Detecting -> RadioConnectionStatus.DETECTING
            is RadioConnectState.ConfirmCandidate -> RadioConnectionStatus.CONFIRMING_CANDIDATE
            // The list itself is shown through RadioGatewayUiState.discoveredDevices; the screen stays on the pick-a-radio card.
            is RadioConnectState.ChooseFromList -> RadioConnectionStatus.CHECKLIST_READY
            is RadioConnectState.Scanning -> RadioConnectionStatus.SCANNING
            is RadioConnectState.Pairing -> RadioConnectionStatus.PAIRING
            is RadioConnectState.Connecting -> RadioConnectionStatus.CONNECTING
            is RadioConnectState.Connected -> when {
                m.isRetryingStopped -> RadioConnectionStatus.LINK_LOST
                m.linkState is TncLinkState.Connected -> RadioConnectionStatus.CONNECTED
                m.linkState is TncLinkState.Waiting -> RadioConnectionStatus.LINK_LOST
                else -> RadioConnectionStatus.CONNECTING
            }
            is RadioConnectState.Failed -> RadioConnectionStatus.FAILED
        }
    }

    public fun failureReason(m: RadioGatewayState, nearbyPermissionGranted: Boolean = true): RadioFailureReason {
        if (!nearbyPermissionGranted) return RadioFailureReason.PERMISSION_MISSING
        if (m.isRetryingStopped) return RadioFailureReason.LINK_LOST
        val cs = m.connectState
        if (cs is RadioConnectState.Failed) {
            return when (cs.reason) {
                RadioConnectFailureReason.NO_ADAPTER -> RadioFailureReason.NO_ADAPTER
                RadioConnectFailureReason.BLUETOOTH_OFF -> RadioFailureReason.BLUETOOTH_OFF
                RadioConnectFailureReason.PERMISSION_MISSING -> RadioFailureReason.PERMISSION_MISSING
                RadioConnectFailureReason.RADIO_NOT_FOUND -> RadioFailureReason.RADIO_NOT_FOUND
                RadioConnectFailureReason.PAIRING_REFUSED -> RadioFailureReason.PAIRING_REFUSED
                RadioConnectFailureReason.WRONG_PIN -> RadioFailureReason.WRONG_PIN
                RadioConnectFailureReason.PORT_IN_USE -> RadioFailureReason.PORT_IN_USE
                RadioConnectFailureReason.KISS_TNC_NOT_ENABLED -> RadioFailureReason.KISS_NOT_ENABLED
                RadioConnectFailureReason.LINK_LOST -> RadioFailureReason.LINK_LOST
            }
        }
        return RadioFailureReason.NONE
    }

    /** The devices the screen lists: scan results, or, after auto-detect found several paired radios, those candidates. */
    public fun deviceList(m: RadioGatewayState): List<DiscoveredRadioDevice> {
        val pairingAddress = (m.connectState as? RadioConnectState.Pairing)?.candidate?.addressOrPort
        if (m.scanResults.isNotEmpty()) {
            return m.scanResults.map { dev ->
                DiscoveredRadioDevice(
                    id = dev.bluetoothAddress,
                    name = dev.name,
                    category = when (dev.category) {
                        CandidateCategory.KNOWN_RADIO -> RadioDeviceCategory.KNOWN_RADIO
                        CandidateCategory.SERIAL_CAPABLE -> RadioDeviceCategory.SERIAL_SPP
                        CandidateCategory.OTHER -> RadioDeviceCategory.OTHER
                    },
                    isPaired = dev.isPaired,
                    rssi = dev.rssi,
                    isPairing = pairingAddress == dev.bluetoothAddress,
                )
            }
        }
        val choose = m.connectState as? RadioConnectState.ChooseFromList ?: return emptyList()
        return choose.candidates.map { cand ->
            DiscoveredRadioDevice(
                id = cand.addressOrPort,
                name = cand.name ?: cand.addressOrPort,
                category = when (cand.kind) {
                    CandidateKind.BLUETOOTH_SERIAL, CandidateKind.USB_SERIAL -> RadioDeviceCategory.SERIAL_SPP
                    else -> RadioDeviceCategory.OTHER
                },
                isPaired = cand.isPaired,
                rssi = cand.rssi,
                isPairing = pairingAddress == cand.addressOrPort,
            )
        }
    }

    /** The domain device behind a list or candidate [id]: a scan result, the confirm candidate, or a choose-list entry. */
    public fun deviceForId(m: RadioGatewayState, id: String): DiscoveredDevice? {
        m.scanResults.firstOrNull { it.bluetoothAddress.equals(id, ignoreCase = true) }?.let { return it }
        val cs = m.connectState
        val cand = when (cs) {
            is RadioConnectState.ConfirmCandidate -> cs.candidate
            is RadioConnectState.ChooseFromList -> cs.candidates.firstOrNull { it.addressOrPort.equals(id, ignoreCase = true) }
            else -> null
        } ?: return null
        if (!cand.addressOrPort.equals(id, ignoreCase = true)) return null
        return DiscoveredDevice(
            name = cand.name ?: cand.addressOrPort,
            bluetoothAddress = cand.addressOrPort,
            isPaired = cand.isPaired,
            rssi = cand.rssi,
            kind = cand.kind,
            category = CandidateCategory.KNOWN_RADIO,
            rawAddressHex = cand.addressOrPort.replace(":", "").replace("-", "").uppercase(),
        )
    }

    public fun map(current: RadioGatewayUiState, m: RadioGatewayState, host: HostFacts = HostFacts()): RadioGatewayUiState {
        val status = connectionStatus(m)
        val reason = failureReason(m, host.nearbyPermissionGranted)
        val radioName = m.rememberedRadio?.displayName
            ?: (m.connectState as? RadioConnectState.ConfirmCandidate)?.candidate?.name
            ?: (m.connectState as? RadioConnectState.Connected)?.radio?.displayName
        val candidate = (m.connectState as? RadioConnectState.ConfirmCandidate)?.candidate?.let { cand ->
            DiscoveredRadioDevice(
                id = cand.addressOrPort,
                name = cand.name ?: cand.addressOrPort,
                category = when (cand.kind) {
                    CandidateKind.BLUETOOTH_SERIAL, CandidateKind.USB_SERIAL -> RadioDeviceCategory.KNOWN_RADIO
                    else -> RadioDeviceCategory.OTHER
                },
                isPaired = cand.isPaired,
                rssi = cand.rssi,
            )
        }
        val auth = m.authorisation
        return current.copy(
            enabled = m.isEnabled,
            connectionStatus = status,
            failureReason = reason,
            statusSentence = m.plainSentence.ifBlank { FlashRadioMath.statusSentence(status, reason, radioName) },
            statusNextStep = m.nextStep.ifBlank { FlashRadioMath.statusNextStep(status, reason) },
            candidateRadio = candidate,
            rememberedRadioName = m.rememberedRadio?.displayName,
            rememberedRadioAddress = m.rememberedRadio?.bluetoothAddress,
            isScanning = m.connectState is RadioConnectState.Scanning || host.scanCountdownSeconds > 0,
            scanCountdownSeconds = host.scanCountdownSeconds,
            discoveredDevices = deviceList(m),
            selectedProfile = when (m.currentProfile) {
                CoreProfile.PROFILE_M_ENCRYPTED -> RadioSecurityProfile.PROFILE_M
                CoreProfile.PROFILE_A_PLAINTEXT_SIGNED -> RadioSecurityProfile.PROFILE_A
            },
            isAuthorisedForEncryption = auth != null && auth.confirmedAtEpochMs > 0L,
            authorisationConfirmedAtEpochMs = auth?.confirmedAtEpochMs ?: 0L,
            selectedMode = host.selectedMode,
            availableModes = setOf(RadioGatewayMode.FIELD_COMPANION),
            isSendingTestMessage = host.isSendingTestMessage,
            testMessageSentSuccess = host.testMessageSentSuccess,
            nearbyDevicesPermissionGranted = host.nearbyPermissionGranted,
        )
    }
}
