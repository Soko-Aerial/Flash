@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.radio.gateway

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashProbe
import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.radio.RadioEvidence
import com.transfer.flash.core.radio.connect.AuthorisationConfirmation
import com.transfer.flash.core.radio.connect.CandidateKind
import com.transfer.flash.core.radio.connect.RadioAuthorisationGate
import com.transfer.flash.core.radio.connect.RadioAuthorisationStore
import com.transfer.flash.core.radio.connect.RadioCandidateDevice
import com.transfer.flash.core.radio.connect.RadioCandidateRanker
import com.transfer.flash.core.radio.connect.RadioConnectCommand
import com.transfer.flash.core.radio.connect.RadioConnectEvent
import com.transfer.flash.core.radio.connect.RadioConnectFailureReason
import com.transfer.flash.core.radio.connect.RadioConnectState
import com.transfer.flash.core.radio.connect.RadioConnectStateMachine
import com.transfer.flash.core.radio.connect.RadioSecurityProfile
import com.transfer.flash.core.radio.connect.RememberedRadio
import com.transfer.flash.core.radio.connect.RememberedRadioStore
import com.transfer.flash.core.radio.driver.KissTncDriver
import com.transfer.flash.core.radio.driver.TncLinkState
import com.transfer.flash.core.radio.driver.TxResult
import com.transfer.flash.core.radio.kiss.Ax25Address
import com.transfer.flash.core.radio.kiss.Ax25FrameCodec
import com.transfer.flash.core.radio.link.ByteLink
import com.transfer.flash.core.radio.link.DiscoveredDevice
import com.transfer.flash.core.radio.link.PairResult
import com.transfer.flash.core.radio.link.RadioDeviceDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Common facade and manager for the Flash Radio Gateway (Plan R §3 and §6).
 *
 * Core Safety Rules:
 * - Rule 3: Detecting is not trusting and not connecting blindly. No port is opened
 *   before user confirmation.
 * - Rule 4: Flash never sends a data frame to "see if it is a TNC". Test messages are
 *   strictly user-requested, require authorization, and are NEVER transmitted to real RF.
 * - Switch Off: While switch is off, nothing opens, scans or polls.
 * - Single candidate auto-detect preselects with confirmation; multiple candidates show list.
 * - Power-cycle recovery: Reconnects automatically using driver backoff up to [maxRetryAttempts].
 */
public class RadioGatewayManager(
    private val scope: CoroutineScope,
    private val discovery: RadioDeviceDiscovery,
    private val rememberedStore: RememberedRadioStore,
    private val authorisationStore: RadioAuthorisationStore,
    private val driverFactory: (suspend () -> ByteLink) -> KissTncDriver = { openLink ->
        KissTncDriver(scope = scope, openLink = openLink)
    },
    private val authorisationGate: RadioAuthorisationGate = RadioAuthorisationGate(),
    private val maxRetryAttempts: Int = 5,
    private val clock: FlashTimeSource = SystemTimeSource,
) {
    private val stateMachine = RadioConnectStateMachine(rememberedStore)
    private val _state = MutableStateFlow(RadioGatewayState())
    public val state: StateFlow<RadioGatewayState> = _state.asStateFlow()

    private var activeDriver: KissTncDriver? = null
    private var currentByteLink: ByteLink? = null
    private var driverStateJob: Job? = null
    private var scanJob: Job? = null
    private var consecutiveReconnectAttempts: Int = 0

    init {
        scope.launch {
            val remembered = rememberedStore.load()
            val auth = authorisationStore.load()
            updateState {
                copy(
                    rememberedRadio = remembered,
                    authorisation = auth,
                    currentProfile = if (auth != null && auth.confirmedAtEpochMs > 0) {
                        currentProfile
                    } else {
                        RadioSecurityProfile.PROFILE_A_PLAINTEXT_SIGNED
                    }
                )
            }
        }
    }

    /**
     * Turns on the Radio Gateway switch.
     * If a radio is remembered, reconnects to it directly. Otherwise shows the checklist.
     */
    public suspend fun enable() {
        if (_state.value.isEnabled) return
        val remembered = rememberedStore.load()
        updateState {
            copy(
                isEnabled = true,
                rememberedRadio = remembered,
                isRetryingStopped = false,
                scanResults = emptyList()
            )
        }
        val commands = stateMachine.transition(RadioConnectEvent.ToggleGateway(true))
        updateState { copy(connectState = stateMachine.state) }
        executeCommands(commands)
    }

    /**
     * Turns off the Radio Gateway switch.
     * Stops all active scans, stops TNC driver, and closes the link.
     */
    public suspend fun disable() {
        if (!_state.value.isEnabled) return
        stopScanInternal()
        stopDriverInternal()
        consecutiveReconnectAttempts = 0
        stateMachine.transition(RadioConnectEvent.ToggleGateway(false))
        updateState {
            copy(
                isEnabled = false,
                connectState = RadioConnectState.Off,
                linkState = TncLinkState.Stopped,
                scanResults = emptyList(),
                isRetryingStopped = false
            )
        }
    }

    /**
     * Acknowledges the "Is your radio ready?" checklist and initiates automatic detection.
     */
    public suspend fun acknowledgeChecklist() {
        if (!_state.value.isEnabled) return
        val commands = stateMachine.transition(RadioConnectEvent.ChecklistAcknowledged)
        updateState { copy(connectState = stateMachine.state) }
        executeCommands(commands)
    }

    /**
     * Starts a Bluetooth discovery scan for nearby radios.
     * Stops automatically on timeout or when [stopScan] is called.
     */
    public fun startScan(timeoutMs: Long = 12_000L) {
        if (!_state.value.isEnabled) return
        stopScanInternal()

        scope.launch {
            stateMachine.transition(RadioConnectEvent.StartScanning)
            updateState {
                copy(
                    connectState = RadioConnectState.Scanning(emptyList()),
                    scanResults = emptyList()
                )
            }

            scanJob = scope.launch {
                discovery.scan(timeoutMs).collect { device ->
                    val currentList = _state.value.scanResults.toMutableList()
                    val idx = currentList.indexOfFirst {
                        it.bluetoothAddress.equals(device.bluetoothAddress, ignoreCase = true)
                    }
                    if (idx >= 0) {
                        currentList[idx] = device
                    } else {
                        currentList.add(device)
                    }

                    // Rank with radios first
                    val ranked = RadioCandidateRanker.rank(currentList.map { it.toCandidateDevice() })
                    val sorted = currentList.sortedBy { devItem ->
                        val pos = ranked.indexOfFirst { it.device.addressOrPort.equals(devItem.bluetoothAddress, ignoreCase = true) }
                        if (pos >= 0) pos else 999
                    }

                    updateState {
                        copy(
                            scanResults = sorted,
                            connectState = RadioConnectState.Scanning(sorted.map { it.toCandidateDevice() })
                        )
                    }
                    stateMachine.transition(RadioConnectEvent.DeviceDiscovered(device.toCandidateDevice()))
                }
                // Scan finished or timed out
                stopScanInternal()
            }
        }
    }

    /**
     * Stops an in-progress discovery scan.
     */
    public fun stopScan() {
        stopScanInternal()
    }

    private fun stopScanInternal() {
        scanJob?.cancel()
        scanJob = null
        scope.launch {
            stateMachine.transition(RadioConnectEvent.StopScanning)
            updateState {
                if (connectState is RadioConnectState.Scanning) {
                    copy(connectState = RadioConnectState.ChooseFromList(scanResults.map { it.toCandidateDevice() }))
                } else {
                    this
                }
            }
        }
    }

    /**
     * Confirms a discovered radio candidate (Rule 3: explicit user confirmation).
     */
    public suspend fun confirmCandidate(device: DiscoveredDevice) {
        if (!_state.value.isEnabled) return
        stopScanInternal()
        val candidate = device.toCandidateDevice()
        val commands = stateMachine.transition(RadioConnectEvent.CandidateConfirmed(candidate))
        updateState { copy(connectState = stateMachine.state) }
        executeCommands(commands)
    }

    /**
     * Rejects an auto-detected candidate, returning to candidate list or scan.
     */
    public suspend fun rejectCandidate() {
        if (!_state.value.isEnabled) return
        val commands = stateMachine.transition(RadioConnectEvent.CandidateRejected)
        updateState { copy(connectState = stateMachine.state) }
        executeCommands(commands)
    }

    /**
     * Pairs with an unpaired radio device.
     */
    public suspend fun pair(device: DiscoveredDevice) {
        if (!_state.value.isEnabled) return
        stopScanInternal()
        val candidate = device.toCandidateDevice()
        updateState { copy(connectState = RadioConnectState.Pairing(candidate)) }

        val result = discovery.pair(device)
        when (result) {
            is PairResult.Success, is PairResult.AlreadyPaired -> {
                val pairedCandidate = candidate.copy(isPaired = true)
                val commands = stateMachine.transition(RadioConnectEvent.PairingSucceeded(pairedCandidate))
                updateState { copy(connectState = stateMachine.state) }
                executeCommands(commands)
            }
            is PairResult.Failed -> {
                val commands = stateMachine.transition(RadioConnectEvent.PairingFailed(result.reason))
                updateState { copy(connectState = stateMachine.state) }
                executeCommands(commands)
            }
        }
    }

    /**
     * Reconnects to the remembered radio (e.g. after retry limit reached or manual retry).
     */
    public suspend fun connectRemembered() {
        if (!_state.value.isEnabled) return
        consecutiveReconnectAttempts = 0
        updateState { copy(isRetryingStopped = false) }

        val remembered = rememberedStore.load()
        if (remembered != null) {
            val target = RadioCandidateDevice(
                name = remembered.displayName,
                addressOrPort = remembered.bluetoothAddress,
                kind = CandidateKind.BLUETOOTH_SERIAL,
                isPaired = true,
            )
            connectTarget(target)
        } else {
            acknowledgeChecklist()
        }
    }

    /**
     * Forgets the remembered radio configuration.
     */
    public suspend fun forget() {
        rememberedStore.clear()
        stopDriverInternal()
        updateState { copy(rememberedRadio = null) }
        if (_state.value.isEnabled) {
            stateMachine.transition(RadioConnectEvent.ToggleGateway(false))
            stateMachine.transition(RadioConnectEvent.ToggleGateway(true))
            updateState {
                copy(
                    connectState = stateMachine.state,
                    linkState = TncLinkState.Stopped,
                    isRetryingStopped = false
                )
            }
        }
    }

    /**
     * Sets the active security profile (Plan R §8).
     * Enforces that Profile M requires verified user authorization confirmation.
     */
    public suspend fun setProfile(profile: RadioSecurityProfile) {
        val auth = authorisationStore.load()
        authorisationGate.requireProfileAllowed(profile, auth)
        updateState { copy(currentProfile = profile) }
    }

    /**
     * Records user authorization for encrypted Profile M operation.
     */
    public suspend fun recordAuthorisation(confirmation: AuthorisationConfirmation) {
        require(confirmation.confirmedAtEpochMs > 0) {
            "Authorization confirmation epoch date is mandatory"
        }
        authorisationStore.save(confirmation)
        updateState { copy(authorisation = confirmation) }
    }

    /**
     * Sends a user-requested test message (Plan R §6 Step 6 & §8).
     *
     * Strict Safety Enforcement (Rule 4):
     * NEVER transmits test frames over real RF.
     * This method succeeds and writes bytes to the wire ONLY when connected to an
     * in-memory simulated test link. On real serial / Bluetooth links, it records
     * a PROBE and suppresses transmission without sending any bytes.
     */
    public suspend fun requestTestMessage(text: String): Boolean {
        val current = _state.value
        if (!current.isConnectedAndReady) return false
        val driver = activeDriver ?: return false
        val link = currentByteLink

        // Security check: strictly prohibit live RF test transmissions
        val isTestLink = link != null && (
            link::class.simpleName?.contains("InMemory") == true ||
            link.description.contains("mem") ||
            link.description.contains("fake")
        )

        if (!isTestLink) {
            FlashProbe.emit(
                "radio.tx.suppressed_on_real_rf",
                "reason" to "Rule 4: no live probe frames on real RF",
                "textLength" to text.length
            )
            return false
        }

        // On in-memory simulated links only: encode UI frame and send
        val dest = Ax25Address.parseOrNull("FLASH") ?: Ax25Address("FLASH", 0)
        val callsign = current.authorisation?.operatorCallsign ?: "NOCALL"
        val src = Ax25Address.parseOrNull(callsign) ?: Ax25Address("NOCALL", 0)
        val ax25 = Ax25FrameCodec.encodeUi(
            destination = dest,
            source = src,
            info = text.encodeToByteArray()
        )
        val res = driver.sendAx25(ax25, label = "test-msg")
        return res is TxResult.Sent
    }

    private suspend fun executeCommands(commands: List<RadioConnectCommand>) {
        for (cmd in commands) {
            when (cmd) {
                is RadioConnectCommand.DetectPairedDevices -> {
                    detectPairedDevicesInternal()
                }
                is RadioConnectCommand.StartDiscoveryScan -> {
                    startScan()
                }
                is RadioConnectCommand.StopDiscoveryScan -> {
                    stopScanInternal()
                }
                is RadioConnectCommand.PairDevice -> {
                    val dev = DiscoveredDevice(
                        name = cmd.candidate.name ?: cmd.candidate.addressOrPort,
                        bluetoothAddress = cmd.candidate.addressOrPort,
                        isPaired = cmd.candidate.isPaired,
                        kind = cmd.candidate.kind,
                    )
                    pair(dev)
                }
                is RadioConnectCommand.OpenLink -> {
                    connectTarget(cmd.candidate)
                }
                is RadioConnectCommand.CloseLink -> {
                    stopDriverInternal()
                }
                is RadioConnectCommand.SaveRememberedRadio -> {
                    rememberedStore.save(cmd.radio)
                    updateState { copy(rememberedRadio = cmd.radio) }
                }
                is RadioConnectCommand.UserRequestedTestMessage -> {
                    requestTestMessage(cmd.text)
                }
            }
        }
    }

    private suspend fun detectPairedDevicesInternal() {
        val paired = discovery.pairedDevices()
        val candidates = paired.map { it.toCandidateDevice() }
        val commands = stateMachine.transition(RadioConnectEvent.DetectionResult(candidates))
        executeCommands(commands)
        updateState { copy(connectState = stateMachine.state) }
    }

    private suspend fun connectTarget(target: RadioCandidateDevice) {
        stopDriverInternal()
        val remembered = RememberedRadio(
            bluetoothAddress = target.addressOrPort,
            displayName = target.name ?: target.addressOrPort,
            lastKnownPortName = null,
        )

        updateState {
            copy(
                connectState = RadioConnectState.Connecting(target),
                linkState = TncLinkState.Connecting(1)
            )
        }

        val driver = driverFactory {
            val link = discovery.openLink(remembered)
            currentByteLink = link
            link
        }
        activeDriver = driver

        driverStateJob = scope.launch {
            driver.state.collect { linkState ->
                handleDriverLinkState(linkState, remembered)
            }
        }

        driver.start()
    }

    private suspend fun handleDriverLinkState(linkState: TncLinkState, targetRadio: RememberedRadio) {
        when (linkState) {
            is TncLinkState.Connected -> {
                consecutiveReconnectAttempts = 0
                val commands = stateMachine.transition(RadioConnectEvent.ConnectionSucceeded(targetRadio))
                executeCommands(commands)
                updateState {
                    copy(
                        connectState = RadioConnectState.Connected(targetRadio),
                        linkState = linkState,
                        rememberedRadio = targetRadio,
                        isRetryingStopped = false
                    )
                }
            }
            is TncLinkState.Waiting -> {
                consecutiveReconnectAttempts++
                if (consecutiveReconnectAttempts >= maxRetryAttempts) {
                    // Stop driver and display "Reconnect" button
                    updateState {
                        copy(
                            linkState = linkState,
                            isRetryingStopped = true
                        )
                    }
                    activeDriver?.stop()
                } else {
                    updateState {
                        copy(
                            linkState = linkState,
                            isRetryingStopped = false
                        )
                    }
                }
            }
            is TncLinkState.Stopped -> {
                updateState { copy(linkState = linkState) }
            }
            is TncLinkState.Connecting, is TncLinkState.Idle -> {
                updateState { copy(linkState = linkState) }
            }
        }
    }

    private suspend fun stopDriverInternal() {
        driverStateJob?.cancel()
        driverStateJob = null
        activeDriver?.stop()
        activeDriver = null
        currentByteLink?.close()
        currentByteLink = null
    }

    private fun updateState(transform: RadioGatewayState.() -> RadioGatewayState) {
        val next = _state.value.transform()
        val (sentence, step) = derivePlainSentenceAndNextStep(next)
        _state.value = next.copy(
            plainSentence = sentence,
            nextStep = step
        )
    }

    private fun derivePlainSentenceAndNextStep(s: RadioGatewayState): Pair<String, String> {
        if (!s.isEnabled) {
            return "Radio gateway is turned off." to "Turn on the radio gateway switch to connect to your radio."
        }
        if (s.isRetryingStopped) {
            return "Radio disconnected after repeated attempts." to "Ensure your radio is turned on and in range, then tap Reconnect."
        }
        return when (val cs = s.connectState) {
            is RadioConnectState.Off -> {
                "Radio gateway is turned off." to "Turn on the radio gateway switch to connect to your radio."
            }
            is RadioConnectState.ChecklistShown -> {
                "Ensure your radio is ready." to "Turn on your radio and enable KISS TNC in the radio menu, then continue."
            }
            is RadioConnectState.Detecting -> {
                "Looking for paired radios..." to "Wait while paired devices are checked."
            }
            is RadioConnectState.ConfirmCandidate -> {
                "Found radio: ${cs.candidate.name}." to "Confirm to connect to this radio."
            }
            is RadioConnectState.ChooseFromList -> {
                "Select your radio from the list." to "Choose a radio to pair and connect."
            }
            is RadioConnectState.Scanning -> {
                "Scanning for nearby radios..." to "Wait for your radio to appear, then select it."
            }
            is RadioConnectState.Pairing -> {
                "Pairing with ${cs.candidate.name}..." to "Confirm pairing on your device if prompted."
            }
            is RadioConnectState.Connecting -> {
                "Connecting to ${cs.target.name}..." to "Opening the radio serial link."
            }
            is RadioConnectState.Connected -> {
                when (val ls = s.linkState) {
                    is TncLinkState.Connected -> {
                        "Connected to ${cs.radio.displayName}." to "Ready for radio communication."
                    }
                    is TncLinkState.Waiting -> {
                        "Radio disconnected, trying again (attempt ${ls.attempt})..." to "Ensure the radio is powered on and in range."
                    }
                    else -> {
                        "Connected to ${cs.radio.displayName}." to "Opening radio data channel."
                    }
                }
            }
            is RadioConnectState.Failed -> {
                cs.reason.sentence to cs.reason.nextStep
            }
        }
    }
}
