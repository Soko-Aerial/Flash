package com.transfer.flash.desktop.radio

import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.radio.connect.AuthorisationConfirmation
import com.transfer.flash.core.radio.connect.RadioCallsign
import com.transfer.flash.core.radio.connect.CandidateCategory
import com.transfer.flash.core.radio.connect.CandidateKind
import com.transfer.flash.core.radio.connect.DesktopPropertiesRadioStore
import com.transfer.flash.core.radio.connect.RadioAuthorisationStore
import com.transfer.flash.core.radio.connect.RadioConnectFailureReason
import com.transfer.flash.core.radio.connect.RadioConnectState
import com.transfer.flash.core.radio.connect.RememberedRadioStore
import com.transfer.flash.core.radio.driver.KissTncDriver
import com.transfer.flash.core.radio.driver.TncLinkState
import com.transfer.flash.core.radio.gateway.RadioGatewayManager
import com.transfer.flash.core.radio.gateway.RadioGatewayState
import com.transfer.flash.core.radio.gateway.TestMessageResult
import com.transfer.flash.core.radio.link.ByteLink
import com.transfer.flash.core.radio.link.DiscoveredDevice
import com.transfer.flash.core.radio.link.RadioDeviceDiscovery
import com.transfer.flash.core.radio.link.windows.WindowsRadioDiscovery
import com.transfer.flash.ui.radio.DiscoveredRadioDevice
import com.transfer.flash.ui.radio.FlashRadioMath
import com.transfer.flash.ui.radio.RadioConnectionStatus
import com.transfer.flash.ui.radio.RadioDeviceCategory
import com.transfer.flash.ui.radio.RadioFailureReason
import com.transfer.flash.ui.radio.RadioGatewayController
import com.transfer.flash.ui.radio.RadioGatewayMode
import com.transfer.flash.ui.radio.RadioGatewayUiState
import com.transfer.flash.ui.radio.RadioGatewayUiMapper
import com.transfer.flash.ui.radio.RadioSecurityProfile
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Desktop implementation of [RadioGatewayController] (Plan R §6 & §8, Task 3).
 *
 * Architecture and Lifetime Contract:
 * - Bridges [RadioGatewayState] from `:core:radio` to [RadioGatewayUiState] in `:ui:chat`.
 * - Created lazily when the Radio Gateway screen is shown or when the switch is stored ON.
 * - If the gateway switch is stored OFF at startup, [RadioGatewayManager] is NOT instantiated.
 * - When the switch is toggled OFF, [RadioGatewayManager.disable] is called and the manager is released.
 * - Uses Windows Win32 JNA and BTHENUM dynamic port resolution via [WindowsRadioDiscovery].
 * - Persists remembered radios and authorization in `~/.flash/radio.properties` via [DesktopPropertiesRadioStore]; the on/off switch lives in its own `~/.flash/radio-gateway.properties`.
 * - On desktop, nearby devices runtime permissions are always granted.
 * - Provides redirect to Windows Bluetooth Settings (`ms-settings:bluetooth`).
 */
public class DesktopRadioGatewayController(
    private val scope: CoroutineScope,
    private val stateDir: File = File(System.getProperty("user.home"), ".flash"),
    private val clock: FlashTimeSource = SystemTimeSource,
    private val discoveryFactory: () -> RadioDeviceDiscovery = { WindowsRadioDiscovery() },
    private val rememberedStoreFactory: () -> RememberedRadioStore = {
        DesktopPropertiesRadioStore(stateDir).rememberedStore
    },
    private val authorisationStoreFactory: () -> RadioAuthorisationStore = {
        DesktopPropertiesRadioStore(stateDir).authorisationStore
    },
    private val isSwitchEnabledStored: () -> Boolean = {
        readGatewayEnabled(stateDir)
    },
    private val setSwitchEnabledStored: (Boolean) -> Unit = { enabled ->
        writeGatewayEnabled(stateDir, enabled)
    },
    private val driverFactory: ((suspend () -> ByteLink) -> KissTncDriver)? = null,
    private val openSettingsHandler: (() -> Unit)? = null,
    /** Told whenever this controller creates or drops its gateway manager; the chat host follows it (ERROR-703). */
    private val onManagerChanged: ((RadioGatewayManager?) -> Unit)? = null,
) : RadioGatewayController {

    private val _uiState = MutableStateFlow(
        RadioGatewayUiState(
            enabled = false,
            connectionStatus = RadioConnectionStatus.OFF,
            failureReason = RadioFailureReason.NONE,
            statusSentence = FlashRadioMath.statusSentence(RadioConnectionStatus.OFF, RadioFailureReason.NONE),
            statusNextStep = FlashRadioMath.statusNextStep(RadioConnectionStatus.OFF, RadioFailureReason.NONE),
            nearbyDevicesPermissionGranted = true,
        )
    )
    override val state: StateFlow<RadioGatewayUiState> = _uiState.asStateFlow()

    private var activeManager: RadioGatewayManager? = null
    private var managerStateJob: Job? = null
    private var scanCountdownJob: Job? = null

    private var isSendingTestMsg: Boolean = false
    private var testMsgSuccess: Boolean? = null
    private var selectedGatewayMode: RadioGatewayMode = RadioGatewayMode.FIELD_COMPANION
    private var scanCountdownRemaining: Int = 0

    init {
        // Startup evaluation: only start if stored switch is explicitly enabled
        if (isSwitchEnabledStored()) {
            val mgr = getOrCreateManager()
            scope.launch {
                mgr.enable()
            }
        }
    }

    @Synchronized
    private fun getOrCreateManager(): RadioGatewayManager {
        activeManager?.let { return it }

        val discovery = discoveryFactory()
        val remStore = rememberedStoreFactory()
        val authStore = authorisationStoreFactory()

        val mgr = if (driverFactory != null) {
            RadioGatewayManager(
                scope = scope,
                discovery = discovery,
                rememberedStore = remStore,
                authorisationStore = authStore,
                driverFactory = driverFactory,
                clock = clock,
            )
        } else {
            RadioGatewayManager(
                scope = scope,
                discovery = discovery,
                rememberedStore = remStore,
                authorisationStore = authStore,
                clock = clock,
            )
        }

        activeManager = mgr
        onManagerChanged?.invoke(mgr)
        managerStateJob?.cancel()
        managerStateJob = scope.launch {
            mgr.state.collect { managerState ->
                handleManagerStateChange(managerState)
            }
        }

        return mgr
    }

    private fun handleManagerStateChange(managerState: RadioGatewayState) {
        if (managerState.connectState !is RadioConnectState.Scanning && scanCountdownJob != null) {
            stopScanCountdown()
        }
        updateUi(managerState)
    }

    private fun updateUi(managerState: RadioGatewayState) {
        _uiState.update { current ->
            RadioGatewayUiMapper.map(
                current,
                managerState,
            RadioGatewayUiMapper.HostFacts(
                scanCountdownSeconds = scanCountdownRemaining,
                selectedMode = selectedGatewayMode,
                isSendingTestMessage = isSendingTestMsg,
                testMessageSentSuccess = testMsgSuccess,
                nearbyPermissionGranted = true,
            ),
            )
        }
    }

    override fun setEnabled(enabled: Boolean) {
        setSwitchEnabledStored(enabled)
        if (enabled) {
            val mgr = getOrCreateManager()
            scope.launch {
                mgr.enable()
                if (mgr.state.value.rememberedRadio == null && mgr.state.value.connectState is RadioConnectState.ChecklistShown) {
                    mgr.acknowledgeChecklist()
                }
            }
        } else {
            val mgr = activeManager
            scope.launch {
                mgr?.disable()
            }
            stopScanCountdown()
            managerStateJob?.cancel()
            managerStateJob = null
            activeManager = null
            onManagerChanged?.invoke(null)

            _uiState.update {
                it.copy(
                    enabled = false,
                    connectionStatus = RadioConnectionStatus.OFF,
                    failureReason = RadioFailureReason.NONE,
                    statusSentence = FlashRadioMath.statusSentence(RadioConnectionStatus.OFF, RadioFailureReason.NONE),
                    statusNextStep = FlashRadioMath.statusNextStep(RadioConnectionStatus.OFF, RadioFailureReason.NONE),
                    isScanning = false,
                    scanCountdownSeconds = 0,
                )
            }
        }
    }

    override fun startScan() {
        if (!_uiState.value.enabled) return
        val mgr = getOrCreateManager()
        startScanCountdown(12)
        mgr.startScan(timeoutMs = 12_000L)
    }

    override fun stopScan() {
        stopScanCountdown()
        activeManager?.stopScan()
    }

    private fun startScanCountdown(seconds: Int) {
        scanCountdownJob?.cancel()
        scanCountdownRemaining = seconds
        _uiState.update { it.copy(isScanning = true, scanCountdownSeconds = scanCountdownRemaining) }

        scanCountdownJob = scope.launch {
            while (scanCountdownRemaining > 0) {
                delay(1000L)
                scanCountdownRemaining--
                _uiState.update { it.copy(scanCountdownSeconds = scanCountdownRemaining) }
            }
            stopScanCountdown()
        }
    }

    private fun stopScanCountdown() {
        scanCountdownJob?.cancel()
        scanCountdownJob = null
        scanCountdownRemaining = 0
        _uiState.update { it.copy(scanCountdownSeconds = 0) }
    }

    override fun confirmCandidate(id: String) {
        val mgr = activeManager ?: return
        val device = RadioGatewayUiMapper.deviceForId(mgr.state.value, id) ?: return
        scope.launch { mgr.confirmCandidate(device) }
    }

    override fun pair(id: String) {
        val mgr = activeManager ?: return
        val device = RadioGatewayUiMapper.deviceForId(mgr.state.value, id) ?: return
        scope.launch { mgr.pair(device) }
    }

    override fun reconnect() {
        val mgr = activeManager ?: return
        scope.launch {
            mgr.connectRemembered()
        }
    }

    override fun forget() {
        val mgr = activeManager ?: return
        scope.launch {
            mgr.forget()
        }
    }

    override fun selectProfile(profile: RadioSecurityProfile) {
        val mgr = activeManager ?: return
        val coreProfile = when (profile) {
            RadioSecurityProfile.PROFILE_M ->
                com.transfer.flash.core.radio.connect.RadioSecurityProfile.PROFILE_M_ENCRYPTED
            RadioSecurityProfile.PROFILE_A ->
                com.transfer.flash.core.radio.connect.RadioSecurityProfile.PROFILE_A_PLAINTEXT_SIGNED
        }
        scope.launch {
            runCatching {
                mgr.setProfile(coreProfile)
            }
        }
    }

    override fun confirmAuthorisation(operatorCallsign: String) {
        // ERROR-684: no callsign, no authorisation record (the dialog also disables Confirm).
        val callsign = RadioCallsign.normalise(operatorCallsign) ?: return
        val now = clock.nowMs()
        val confirmation = AuthorisationConfirmation(
            confirmedAtEpochMs = now,
            operatorCallsign = callsign,
        )
        val mgr = activeManager
        if (mgr != null) {
            scope.launch {
                mgr.recordAuthorisation(confirmation)
                mgr.setProfile(com.transfer.flash.core.radio.connect.RadioSecurityProfile.PROFILE_M_ENCRYPTED)
            }
        } else {
            scope.launch {
                authorisationStoreFactory().save(confirmation)
            }
        }
    }

    override fun selectMode(mode: RadioGatewayMode) {
        selectedGatewayMode = mode
        _uiState.update { it.copy(selectedMode = mode) }
    }

    override fun sendTestMessage() {
        val mgr = activeManager ?: return
        scope.launch {
            isSendingTestMsg = true
            testMsgSuccess = null
            _uiState.update { it.copy(isSendingTestMessage = true, testMessageSentSuccess = null) }

            val result = mgr.requestTestMessage("FLASH TEST DE RADIO", userConfirmedTransmit = true)
            isSendingTestMsg = false
            testMsgSuccess = (result == TestMessageResult.SENT)
            _uiState.update { it.copy(isSendingTestMessage = false, testMessageSentSuccess = testMsgSuccess) }
        }
    }

    override fun openSystemBluetoothSettings() {
        if (openSettingsHandler != null) {
            openSettingsHandler.invoke()
        } else {
            runCatching {
                val os = System.getProperty("os.name")?.lowercase() ?: ""
                if (os.contains("win")) {
                    ProcessBuilder("cmd.exe", "/c", "start", "ms-settings:bluetooth").start()
                } else if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().browse(java.net.URI("ms-settings:bluetooth"))
                }
            }
        }
    }

    override fun requestNearbyDevicesPermission() {
        // Desktop platforms require no runtime nearby devices permission.
    }

    public companion object {
        /**
         * Own file on purpose: `radio.properties` belongs to DesktopPropertiesRadioStore, which rewrites the whole
         * file under its own lock and uses the same temp name; two writers in one file lose updates.
         */
        public const val KEY_GATEWAY_ENABLED: String = "gateway.enabled"

        private fun readGatewayEnabled(stateDir: File): Boolean {
            val file = File(stateDir, "radio-gateway.properties")
            if (!file.isFile) return false
            return runCatching {
                val props = Properties()
                file.inputStream().use { props.load(it) }
                props.getProperty(KEY_GATEWAY_ENABLED)?.toBoolean() ?: false
            }.getOrDefault(false)
        }

        private fun writeGatewayEnabled(stateDir: File, enabled: Boolean) {
            runCatching {
                stateDir.mkdirs()
                val file = File(stateDir, "radio-gateway.properties")
                val props = Properties()
                if (file.isFile) {
                    file.inputStream().use { props.load(it) }
                }
                props.setProperty(KEY_GATEWAY_ENABLED, enabled.toString())
                val tmpFile = File(stateDir, "radio-gateway.properties.tmp")
                tmpFile.outputStream().use { out ->
                    props.store(out, "Flash Radio Gateway configuration")
                }
                try {
                    Files.move(
                        tmpFile.toPath(),
                        file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (_: Exception) {
                    Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
