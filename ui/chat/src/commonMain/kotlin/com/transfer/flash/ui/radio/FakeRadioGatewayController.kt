package com.transfer.flash.ui.radio

import com.transfer.flash.core.radio.connect.RadioCallsign
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Deterministic in-memory fake implementing [RadioGatewayController] (Task 3b).
 * Supports tests, Compose previews, and standalone demo paths on Android and Desktop.
 */
public class FakeRadioGatewayController(
    initialState: RadioGatewayUiState = RadioGatewayUiState(),
) : RadioGatewayController {

    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<RadioGatewayUiState> = _state.asStateFlow()

    /** Records method names of user actions invoked on this controller. */
    public val actionsLog: MutableList<String> = mutableListOf()

    override fun setEnabled(enabled: Boolean) {
        actionsLog.add("setEnabled($enabled)")
        _state.update { current ->
            if (!enabled) {
                current.copy(
                    enabled = false,
                    isScanning = false,
                    connectionStatus = RadioConnectionStatus.OFF,
                    failureReason = RadioFailureReason.NONE,
                    statusSentence = FlashRadioMath.statusSentence(RadioConnectionStatus.OFF, RadioFailureReason.NONE),
                    statusNextStep = FlashRadioMath.statusNextStep(RadioConnectionStatus.OFF, RadioFailureReason.NONE),
                )
            } else {
                val newStatus = when {
                    current.rememberedRadioAddress != null -> RadioConnectionStatus.CONNECTED
                    current.candidateRadio != null -> RadioConnectionStatus.CONFIRMING_CANDIDATE
                    else -> RadioConnectionStatus.CHECKLIST_READY
                }
                current.copy(
                    enabled = true,
                    connectionStatus = newStatus,
                    failureReason = RadioFailureReason.NONE,
                    statusSentence = FlashRadioMath.statusSentence(newStatus, RadioFailureReason.NONE, current.candidateRadio?.name ?: current.rememberedRadioName),
                    statusNextStep = FlashRadioMath.statusNextStep(newStatus, RadioFailureReason.NONE),
                )
            }
        }
    }

    override fun startScan() {
        actionsLog.add("startScan")
        _state.update { current ->
            val sampleDevices = if (current.discoveredDevices.isEmpty()) {
                listOf(
                    DiscoveredRadioDevice(
                        id = "38:D2:00:01:35:B5",
                        name = "VR-N76",
                        category = RadioDeviceCategory.KNOWN_RADIO,
                        isPaired = false,
                        rssi = -47,
                    ),
                    DiscoveredRadioDevice(
                        id = "38:D2:00:01:34:FD",
                        name = "UV-Pro",
                        category = RadioDeviceCategory.KNOWN_RADIO,
                        isPaired = false,
                        rssi = -78,
                    ),
                    DiscoveredRadioDevice(
                        id = "00:1B:DC:06:55:12",
                        name = "Serial-TNC-SPP",
                        category = RadioDeviceCategory.SERIAL_SPP,
                        isPaired = false,
                        rssi = -60,
                    ),
                    DiscoveredRadioDevice(
                        id = "AA:BB:CC:DD:EE:FF",
                        name = "Office-Headset",
                        category = RadioDeviceCategory.OTHER,
                        isPaired = false,
                        rssi = -85,
                    ),
                )
            } else {
                current.discoveredDevices
            }
            current.copy(
                isScanning = true,
                scanCountdownSeconds = 12,
                connectionStatus = RadioConnectionStatus.SCANNING,
                discoveredDevices = sampleDevices,
                statusSentence = FlashRadioMath.statusSentence(RadioConnectionStatus.SCANNING, RadioFailureReason.NONE),
                statusNextStep = FlashRadioMath.statusNextStep(RadioConnectionStatus.SCANNING, RadioFailureReason.NONE),
            )
        }
    }

    override fun stopScan() {
        actionsLog.add("stopScan")
        _state.update { current ->
            val nextStatus = if (current.rememberedRadioAddress != null) {
                RadioConnectionStatus.CONNECTED
            } else {
                RadioConnectionStatus.CHECKLIST_READY
            }
            current.copy(
                isScanning = false,
                scanCountdownSeconds = 0,
                connectionStatus = nextStatus,
                statusSentence = FlashRadioMath.statusSentence(nextStatus, RadioFailureReason.NONE, current.rememberedRadioName),
                statusNextStep = FlashRadioMath.statusNextStep(nextStatus, RadioFailureReason.NONE),
            )
        }
    }

    override fun confirmCandidate(id: String) {
        actionsLog.add("confirmCandidate($id)")
        _state.update { current ->
            val candidate = current.candidateRadio ?: current.discoveredDevices.firstOrNull { it.id == id }
            val name = candidate?.name ?: "Radio"
            current.copy(
                candidateRadio = null,
                rememberedRadioName = name,
                rememberedRadioAddress = id,
                connectionStatus = RadioConnectionStatus.CONNECTED,
                failureReason = RadioFailureReason.NONE,
                statusSentence = FlashRadioMath.statusSentence(RadioConnectionStatus.CONNECTED, RadioFailureReason.NONE, name),
                statusNextStep = FlashRadioMath.statusNextStep(RadioConnectionStatus.CONNECTED, RadioFailureReason.NONE),
            )
        }
    }

    override fun pair(id: String) {
        actionsLog.add("pair($id)")
        _state.update { current ->
            val target = current.discoveredDevices.firstOrNull { it.id == id }
            val name = target?.name ?: "Radio"
            val updatedList = current.discoveredDevices.map {
                if (it.id == id) it.copy(isPaired = true, isPairing = false) else it
            }
            current.copy(
                isScanning = false,
                discoveredDevices = updatedList,
                rememberedRadioName = name,
                rememberedRadioAddress = id,
                connectionStatus = RadioConnectionStatus.CONNECTED,
                statusSentence = FlashRadioMath.statusSentence(RadioConnectionStatus.CONNECTED, RadioFailureReason.NONE, name),
                statusNextStep = FlashRadioMath.statusNextStep(RadioConnectionStatus.CONNECTED, RadioFailureReason.NONE),
            )
        }
    }

    override fun reconnect() {
        actionsLog.add("reconnect")
        _state.update { current ->
            val name = current.rememberedRadioName ?: "Radio"
            current.copy(
                connectionStatus = RadioConnectionStatus.CONNECTED,
                failureReason = RadioFailureReason.NONE,
                statusSentence = FlashRadioMath.statusSentence(RadioConnectionStatus.CONNECTED, RadioFailureReason.NONE, name),
                statusNextStep = FlashRadioMath.statusNextStep(RadioConnectionStatus.CONNECTED, RadioFailureReason.NONE),
            )
        }
    }

    override fun forget() {
        actionsLog.add("forget")
        _state.update { current ->
            current.copy(
                rememberedRadioName = null,
                rememberedRadioAddress = null,
                candidateRadio = null,
                connectionStatus = if (current.enabled) RadioConnectionStatus.CHECKLIST_READY else RadioConnectionStatus.OFF,
                failureReason = RadioFailureReason.NONE,
                statusSentence = FlashRadioMath.statusSentence(if (current.enabled) RadioConnectionStatus.CHECKLIST_READY else RadioConnectionStatus.OFF, RadioFailureReason.NONE),
                statusNextStep = FlashRadioMath.statusNextStep(if (current.enabled) RadioConnectionStatus.CHECKLIST_READY else RadioConnectionStatus.OFF, RadioFailureReason.NONE),
            )
        }
    }

    override fun selectProfile(profile: RadioSecurityProfile) {
        actionsLog.add("selectProfile($profile)")
        _state.update { current ->
            if (profile == RadioSecurityProfile.PROFILE_M && !current.isAuthorisedForEncryption) {
                // Cannot select Profile M without prior confirmation (ADR-178)
                current
            } else {
                current.copy(selectedProfile = profile)
            }
        }
    }

    override fun confirmAuthorisation(operatorCallsign: String) {
        actionsLog.add("confirmAuthorisation")
        val callsign = RadioCallsign.normalise(operatorCallsign) ?: return
        _state.update { current ->
            current.copy(
                operatorCallsign = callsign,
                isAuthorisedForEncryption = true,
                authorisationConfirmedAtEpochMs = 1729094400000L, // Fixed positive epoch for testing
                selectedProfile = RadioSecurityProfile.PROFILE_M,
            )
        }
    }

    override fun selectMode(mode: RadioGatewayMode) {
        actionsLog.add("selectMode($mode)")
        _state.update { current ->
            if (mode in current.availableModes) {
                current.copy(selectedMode = mode)
            } else {
                current
            }
        }
    }

    override fun sendTestMessage() {
        actionsLog.add("sendTestMessage")
        _state.update { current ->
            current.copy(
                isSendingTestMessage = true,
                testMessageSentSuccess = true,
            )
        }
    }

    override fun openSystemBluetoothSettings() {
        actionsLog.add("openSystemBluetoothSettings")
    }

    override fun requestNearbyDevicesPermission() {
        actionsLog.add("requestNearbyDevicesPermission")
        _state.update { current ->
            current.copy(
                nearbyDevicesPermissionGranted = true,
                failureReason = if (current.failureReason == RadioFailureReason.PERMISSION_MISSING ||
                    current.failureReason == RadioFailureReason.PERMISSION_DENIED
                ) {
                    RadioFailureReason.NONE
                } else {
                    current.failureReason
                },
            )
        }
    }

    // --- Test & Preview Mutation Helpers ------------------------------------------------------------------

    public fun setCustomState(newState: RadioGatewayUiState) {
        _state.value = newState
    }

    public fun simulateFailure(reason: RadioFailureReason) {
        _state.update { current ->
            current.copy(
                connectionStatus = RadioConnectionStatus.FAILED,
                failureReason = reason,
                statusSentence = FlashRadioMath.statusSentence(RadioConnectionStatus.FAILED, reason),
                statusNextStep = FlashRadioMath.statusNextStep(RadioConnectionStatus.FAILED, reason),
            )
        }
    }

    public fun simulateDiscoveredDevices(devices: List<DiscoveredRadioDevice>) {
        _state.update { it.copy(discoveredDevices = devices) }
    }

    public fun simulateCandidate(device: DiscoveredRadioDevice) {
        _state.update { current ->
            current.copy(
                candidateRadio = device,
                connectionStatus = RadioConnectionStatus.CONFIRMING_CANDIDATE,
                statusSentence = FlashRadioMath.statusSentence(RadioConnectionStatus.CONFIRMING_CANDIDATE, RadioFailureReason.NONE, device.name),
                statusNextStep = FlashRadioMath.statusNextStep(RadioConnectionStatus.CONFIRMING_CANDIDATE, RadioFailureReason.NONE),
            )
        }
    }
}
