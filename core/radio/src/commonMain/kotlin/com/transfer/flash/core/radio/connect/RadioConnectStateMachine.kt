package com.transfer.flash.core.radio.connect

/**
 * Standard failure reasons with user-facing plain English explanations and next steps (Plan R §6 Rule 5).
 */
public enum class RadioConnectFailureReason(
    public val sentence: String,
    public val nextStep: String,
) {
    NO_ADAPTER(
        sentence = "No Bluetooth adapter was found on this device.",
        nextStep = "Attach a Bluetooth adapter or connect via a supported serial cable.",
    ),
    BLUETOOTH_OFF(
        sentence = "Bluetooth is turned off.",
        nextStep = "Turn on Bluetooth in system settings and try again.",
    ),
    PERMISSION_MISSING(
        sentence = "Nearby devices permission was denied.",
        nextStep = "Grant the Nearby devices permission in system app settings to find your radio.",
    ),
    RADIO_NOT_FOUND(
        sentence = "The radio was not found.",
        nextStep = "Ensure your radio is powered on and within Bluetooth range, then check again.",
    ),
    PAIRING_REFUSED(
        sentence = "Pairing was refused by the radio or canceled.",
        nextStep = "Put the radio into pairing mode and try pairing again.",
    ),
    WRONG_PIN(
        sentence = "The Bluetooth pairing PIN was incorrect.",
        nextStep = "Enter the correct PIN required by your radio and retry.",
    ),
    PORT_IN_USE(
        sentence = "The serial port is currently in use by another program.",
        nextStep = "Close any other application using the port and try again.",
    ),
    KISS_TNC_NOT_ENABLED(
        sentence = "The radio connected but the KISS TNC is probably not enabled.",
        nextStep = "Enable KISS TNC in the radio's settings menu (VR-N76: Menu -> General Settings -> KISS TNC -> Enable).",
    ),
    LINK_LOST(
        sentence = "The radio link was lost.",
        nextStep = "Check radio power and distance, then reconnect.",
    );

    init {
        require(sentence.isNotBlank()) { "Failure sentence must not be blank" }
        require(nextStep.isNotBlank()) { "Failure nextStep must not be blank" }
    }
}

/**
 * States of the radio connection flow (Plan R §6, Steps 1-6).
 */
public sealed interface RadioConnectState {
    public data object Off : RadioConnectState
    public data object ChecklistShown : RadioConnectState
    public data object Detecting : RadioConnectState
    public data class ConfirmCandidate(val candidate: RadioCandidateDevice) : RadioConnectState
    public data class ChooseFromList(val candidates: List<RadioCandidateDevice>) : RadioConnectState
    public data class Scanning(val discovered: List<RadioCandidateDevice>) : RadioConnectState
    public data class Pairing(val candidate: RadioCandidateDevice) : RadioConnectState
    public data class Connecting(val target: RadioCandidateDevice) : RadioConnectState
    public data class Connected(val radio: RememberedRadio) : RadioConnectState
    public data class Failed(val reason: RadioConnectFailureReason) : RadioConnectState
}

/**
 * Commands emitted by the state machine to be executed by platform adapters.
 *
 * Rule 4: The machine never emits "send data" as a liveness probe. The only command that
 * transmits data is [UserRequestedTestMessage], which strictly requires explicit user authorisation.
 */
public sealed interface RadioConnectCommand {
    public data object DetectPairedDevices : RadioConnectCommand
    public data object StartDiscoveryScan : RadioConnectCommand
    public data object StopDiscoveryScan : RadioConnectCommand
    public data class PairDevice(val candidate: RadioCandidateDevice) : RadioConnectCommand
    public data class OpenLink(val candidate: RadioCandidateDevice) : RadioConnectCommand
    public data object CloseLink : RadioConnectCommand
    public data class SaveRememberedRadio(val radio: RememberedRadio) : RadioConnectCommand

    /**
     * User-requested test transmission (Plan R §6 Step 6 & §8).
     * Requires explicit authorisation confirmation ([isAuthorised] == true).
     */
    public data class UserRequestedTestMessage(
        val targetRadio: RememberedRadio,
        val text: String,
        val isAuthorised: Boolean,
    ) : RadioConnectCommand {
        init {
            require(isAuthorised) { "UserRequestedTestMessage strictly requires isAuthorised == true" }
            require(text.isNotBlank()) { "text must not be blank" }
        }
    }
}

/**
 * Input events driving the connection state machine.
 */
public sealed interface RadioConnectEvent {
    public data class ToggleGateway(val enable: Boolean) : RadioConnectEvent
    public data object ChecklistAcknowledged : RadioConnectEvent
    public data class DetectionResult(val candidates: List<RadioCandidateDevice>) : RadioConnectEvent
    public data class CandidateConfirmed(val candidate: RadioCandidateDevice) : RadioConnectEvent
    public data object CandidateRejected : RadioConnectEvent
    public data class SelectCandidate(val candidate: RadioCandidateDevice) : RadioConnectEvent
    public data object StartScanning : RadioConnectEvent
    public data class DeviceDiscovered(val device: RadioCandidateDevice) : RadioConnectEvent
    public data object StopScanning : RadioConnectEvent
    public data class PairingSucceeded(val candidate: RadioCandidateDevice) : RadioConnectEvent
    public data class PairingFailed(val reason: RadioConnectFailureReason) : RadioConnectEvent
    public data class ConnectionSucceeded(val radio: RememberedRadio) : RadioConnectEvent
    public data class ConnectionFailed(val reason: RadioConnectFailureReason) : RadioConnectEvent
    public data object LinkDropped : RadioConnectEvent
    public data class SendTestMessage(val text: String, val isAuthorised: Boolean) : RadioConnectEvent
    public data object Retry : RadioConnectEvent
}

/**
 * Platform-independent state machine for radio find, pair, and connect (Plan R §6).
 */
public class RadioConnectStateMachine(
    private val rememberedRadioStore: RememberedRadioStore,
    initialState: RadioConnectState = RadioConnectState.Off,
) {
    public var state: RadioConnectState = initialState
        private set

    /**
     * Processes [event] and returns the list of side-effect [RadioConnectCommand]s to execute.
     */
    public suspend fun transition(event: RadioConnectEvent): List<RadioConnectCommand> {
        val commands = mutableListOf<RadioConnectCommand>()

        // Global disable
        if (event is RadioConnectEvent.ToggleGateway && !event.enable) {
            val wasScanning = state is RadioConnectState.Scanning
            state = RadioConnectState.Off
            if (wasScanning) {
                commands += RadioConnectCommand.StopDiscoveryScan
            }
            commands += RadioConnectCommand.CloseLink
            return commands
        }

        when (val s = state) {
            is RadioConnectState.Off -> {
                if (event is RadioConnectEvent.ToggleGateway && event.enable) {
                    val remembered = rememberedRadioStore.load()
                    if (remembered != null) {
                        // Plan §6 Reconnecting: "When the app starts with the switch on, it connects to the remembered address only"
                        val target = RadioCandidateDevice(
                            name = remembered.displayName,
                            addressOrPort = remembered.bluetoothAddress,
                            kind = CandidateKind.BLUETOOTH_SERIAL,
                            isPaired = true,
                        )
                        state = RadioConnectState.Connecting(target)
                        commands += RadioConnectCommand.OpenLink(target)
                    } else {
                        // First run: show checklist
                        state = RadioConnectState.ChecklistShown
                    }
                }
            }

            is RadioConnectState.ChecklistShown -> {
                if (event is RadioConnectEvent.ChecklistAcknowledged) {
                    state = RadioConnectState.Detecting
                    commands += RadioConnectCommand.DetectPairedDevices
                }
            }

            is RadioConnectState.Detecting -> {
                if (event is RadioConnectEvent.DetectionResult) {
                    val candidate = RadioCandidateRanker.autoDetectCandidate(event.candidates)
                    if (candidate != null) {
                        // Exactly one candidate: show confirmation (Rule 3: NEVER open before confirm!)
                        state = RadioConnectState.ConfirmCandidate(candidate)
                    } else {
                        val ranked = RadioCandidateRanker.rank(event.candidates).map { it.device }
                        if (ranked.isNotEmpty()) {
                            state = RadioConnectState.ChooseFromList(ranked)
                        } else {
                            // No candidates: transition to scanning
                            state = RadioConnectState.Scanning(emptyList())
                            commands += RadioConnectCommand.StartDiscoveryScan
                        }
                    }
                }
            }

            is RadioConnectState.ConfirmCandidate -> {
                when (event) {
                    is RadioConnectEvent.CandidateConfirmed -> {
                        val c = event.candidate
                        if (c.isPaired) {
                            state = RadioConnectState.Connecting(c)
                            commands += RadioConnectCommand.OpenLink(c)
                        } else {
                            state = RadioConnectState.Pairing(c)
                            commands += RadioConnectCommand.PairDevice(c)
                        }
                    }

                    is RadioConnectEvent.CandidateRejected -> {
                        state = RadioConnectState.ChooseFromList(emptyList())
                    }

                    else -> Unit
                }
            }

            is RadioConnectState.ChooseFromList -> {
                when (event) {
                    is RadioConnectEvent.SelectCandidate -> {
                        val c = event.candidate
                        if (c.isPaired) {
                            state = RadioConnectState.Connecting(c)
                            commands += RadioConnectCommand.OpenLink(c)
                        } else {
                            state = RadioConnectState.Pairing(c)
                            commands += RadioConnectCommand.PairDevice(c)
                        }
                    }

                    is RadioConnectEvent.StartScanning -> {
                        state = RadioConnectState.Scanning(emptyList())
                        commands += RadioConnectCommand.StartDiscoveryScan
                    }

                    else -> Unit
                }
            }

            is RadioConnectState.Scanning -> {
                when (event) {
                    is RadioConnectEvent.DeviceDiscovered -> {
                        val updated = (s.discovered + event.device).distinctBy { it.addressOrPort }
                        state = RadioConnectState.Scanning(updated)
                    }

                    is RadioConnectEvent.StopScanning -> {
                        state = RadioConnectState.ChooseFromList(s.discovered)
                        commands += RadioConnectCommand.StopDiscoveryScan
                    }

                    is RadioConnectEvent.SelectCandidate -> {
                        commands += RadioConnectCommand.StopDiscoveryScan
                        val c = event.candidate
                        if (c.isPaired) {
                            state = RadioConnectState.Connecting(c)
                            commands += RadioConnectCommand.OpenLink(c)
                        } else {
                            state = RadioConnectState.Pairing(c)
                            commands += RadioConnectCommand.PairDevice(c)
                        }
                    }

                    else -> Unit
                }
            }

            is RadioConnectState.Pairing -> {
                when (event) {
                    is RadioConnectEvent.PairingSucceeded -> {
                        val target = event.candidate.copy(isPaired = true)
                        state = RadioConnectState.Connecting(target)
                        commands += RadioConnectCommand.OpenLink(target)
                    }

                    is RadioConnectEvent.PairingFailed -> {
                        state = RadioConnectState.Failed(event.reason)
                    }

                    else -> Unit
                }
            }

            is RadioConnectState.Connecting -> {
                when (event) {
                    is RadioConnectEvent.ConnectionSucceeded -> {
                        state = RadioConnectState.Connected(event.radio)
                        rememberedRadioStore.save(event.radio)
                        commands += RadioConnectCommand.SaveRememberedRadio(event.radio)
                    }

                    is RadioConnectEvent.ConnectionFailed -> {
                        state = RadioConnectState.Failed(event.reason)
                    }

                    else -> Unit
                }
            }

            is RadioConnectState.Connected -> {
                when (event) {
                    is RadioConnectEvent.LinkDropped -> {
                        state = RadioConnectState.Failed(RadioConnectFailureReason.LINK_LOST)
                    }

                    is RadioConnectEvent.SendTestMessage -> {
                        if (event.isAuthorised) {
                            commands += RadioConnectCommand.UserRequestedTestMessage(
                                targetRadio = s.radio,
                                text = event.text,
                                isAuthorised = true,
                            )
                        }
                    }

                    else -> Unit
                }
            }

            is RadioConnectState.Failed -> {
                when (event) {
                    is RadioConnectEvent.Retry -> {
                        val remembered = rememberedRadioStore.load()
                        if (remembered != null) {
                            val target = RadioCandidateDevice(
                                name = remembered.displayName,
                                addressOrPort = remembered.bluetoothAddress,
                                kind = CandidateKind.BLUETOOTH_SERIAL,
                                isPaired = true,
                            )
                            state = RadioConnectState.Connecting(target)
                            commands += RadioConnectCommand.OpenLink(target)
                        } else {
                            state = RadioConnectState.Detecting
                            commands += RadioConnectCommand.DetectPairedDevices
                        }
                    }

                    else -> Unit
                }
            }
        }

        return commands
    }
}
