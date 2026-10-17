package com.transfer.flash.radio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.radio.connect.AuthorisationConfirmation
import com.transfer.flash.core.radio.connect.CandidateCategory
import com.transfer.flash.core.radio.connect.CandidateKind
import com.transfer.flash.core.radio.connect.RadioAuthorisationStore
import com.transfer.flash.core.radio.connect.RadioCandidateDevice
import com.transfer.flash.core.radio.connect.RadioConnectFailureReason
import com.transfer.flash.core.radio.connect.RadioConnectState
import com.transfer.flash.core.radio.connect.RememberedRadioStore
import com.transfer.flash.core.radio.connect.AndroidSharedPreferencesRadioStore
import com.transfer.flash.core.radio.driver.KissTncDriver
import com.transfer.flash.core.radio.driver.TncLinkState
import com.transfer.flash.core.radio.gateway.RadioGatewayManager
import com.transfer.flash.core.radio.gateway.RadioGatewayState
import com.transfer.flash.core.radio.gateway.TestMessageResult
import com.transfer.flash.core.radio.link.ByteLink
import com.transfer.flash.core.radio.link.DiscoveredDevice
import com.transfer.flash.core.radio.link.RadioDeviceDiscovery
import com.transfer.flash.core.radio.link.android.AndroidRadioDiscovery
import com.transfer.flash.core.radio.service.RadioServiceAction
import com.transfer.flash.core.radio.service.RadioServiceTracker
import com.transfer.flash.service.RadioLinkService
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Android implementation of [RadioGatewayController] (Plan R §6 & §8, Task 2).
 *
 * Architecture and Lifetime Contract:
 * - Bridges [RadioGatewayState] from `:core:radio` to [RadioGatewayUiState] in `:ui:chat`.
 * - Created lazily when the Radio Gateway screen is shown or when the switch is stored ON.
 * - If the gateway switch is stored OFF at startup, [RadioGatewayManager] is NOT instantiated.
 * - When the switch is toggled OFF, [RadioGatewayManager.disable] is called and the manager is released.
 * - Listens to [RadioGatewayManager.state] and starts/stops [RadioLinkService] per [RadioServicePolicy.shouldServiceRun].
 * - Handles Android 12+ runtime Nearby devices permissions (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`).
 * - Provides redirect to system Bluetooth settings.
 */
public class AndroidRadioGatewayController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val clock: FlashTimeSource = SystemTimeSource,
    permissionRequester: (() -> Unit)? = null,
    private val discoveryFactory: () -> RadioDeviceDiscovery = { AndroidRadioDiscovery(context) },
    private val rememberedStoreFactory: () -> RememberedRadioStore = {
        AndroidSharedPreferencesRadioStore(context).rememberedStore
    },
    private val authorisationStoreFactory: () -> RadioAuthorisationStore = {
        AndroidSharedPreferencesRadioStore(context).authorisationStore
    },
    private val isSwitchEnabledStored: () -> Boolean = {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_GATEWAY_ENABLED, false)
    },
    private val setSwitchEnabledStored: (Boolean) -> Unit = { enabled ->
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_GATEWAY_ENABLED, enabled)
            .commit()
    },
    private val driverFactory: ((suspend () -> ByteLink) -> KissTncDriver)? = null,
    private val onServiceStateChanged: ((shouldRun: Boolean, state: RadioGatewayState) -> Unit)? = null,
    private val openSettingsHandler: (() -> Unit)? = null,
) : RadioGatewayController {

    private val _uiState = MutableStateFlow(
        RadioGatewayUiState(
            enabled = false,
            connectionStatus = RadioConnectionStatus.OFF,
            failureReason = RadioFailureReason.NONE,
            statusSentence = FlashRadioMath.statusSentence(RadioConnectionStatus.OFF, RadioFailureReason.NONE),
            statusNextStep = FlashRadioMath.statusNextStep(RadioConnectionStatus.OFF, RadioFailureReason.NONE),
            nearbyDevicesPermissionGranted = checkNearbyPermissions(),
        )
    )
    override val state: StateFlow<RadioGatewayUiState> = _uiState.asStateFlow()

    /** Set by the foreground Activity (it owns the permission launcher); the controller outlives Activities. */
    @Volatile
    public var permissionRequester: (() -> Unit)? = permissionRequester

    private val serviceTracker = RadioServiceTracker()

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

    private fun checkNearbyPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val connect = ContextCompat.checkSelfPermission(
            context,
            "android.permission.BLUETOOTH_CONNECT"
        ) == PackageManager.PERMISSION_GRANTED
        val scan = ContextCompat.checkSelfPermission(
            context,
            "android.permission.BLUETOOTH_SCAN"
        ) == PackageManager.PERMISSION_GRANTED
        return connect && scan
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
        managerStateJob?.cancel()
        managerStateJob = scope.launch {
            mgr.state.collect { managerState ->
                handleManagerStateChange(managerState)
            }
        }

        return mgr
    }

    private fun handleManagerStateChange(managerState: RadioGatewayState) {
        // Service lifecycle per RadioServicePolicy; the tracker turns the flood of states into the few real calls.
        applyServiceAction(serviceTracker.next(managerState), managerState)

        // If scan stopped in manager, cancel countdown timer
        if (managerState.connectState !is RadioConnectState.Scanning && scanCountdownJob != null) {
            stopScanCountdown()
        }

        updateUi(managerState)
    }

    /**
     * Starting a foreground service from the background throws on Android 12+ (and the OS may refuse for other
     * reasons). The gateway keeps working without the service, so a refusal is logged and must never escape: an
     * exception here would end the state collector.
     */
    private fun applyServiceAction(action: RadioServiceAction?, managerState: RadioGatewayState) {
        if (action == null) return
        val hook = onServiceStateChanged
        if (hook != null) {
            hook.invoke(action is RadioServiceAction.Start, managerState)
            return
        }
        try {
            when (action) {
                is RadioServiceAction.Start -> RadioLinkService.start(context, action.notification)
                RadioServiceAction.Stop -> RadioLinkService.stop(context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Radio foreground service ${action::class.simpleName} refused: ${e.javaClass.simpleName}")
            serviceTracker.reset()
        }
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
                nearbyPermissionGranted = checkNearbyPermissions(),
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
                // If there's no remembered radio, acknowledge checklist to start auto-detecting paired radios
                if (mgr.state.value.rememberedRadio == null && mgr.state.value.connectState is RadioConnectState.ChecklistShown) {
                    mgr.acknowledgeChecklist()
                }
            }
        } else {
            val mgr = activeManager
            scope.launch {
                mgr?.disable()
                applyServiceAction(serviceTracker.next(RadioGatewayState()), mgr?.state?.value ?: RadioGatewayState())
            }
            stopScanCountdown()
            managerStateJob?.cancel()
            managerStateJob = null
            activeManager = null

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
        if (!checkNearbyPermissions()) {
            requestNearbyDevicesPermission()
            return
        }

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
        val mgr = getOrCreateManager()
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
        val mgr = getOrCreateManager()
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

    override fun confirmAuthorisation() {
        val mgr = getOrCreateManager()
        val now = clock.nowMs()
        val confirmation = AuthorisationConfirmation(
            confirmedAtEpochMs = now,
            operatorCallsign = null,
        )
        scope.launch {
            mgr.recordAuthorisation(confirmation)
            mgr.setProfile(com.transfer.flash.core.radio.connect.RadioSecurityProfile.PROFILE_M_ENCRYPTED)
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
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }

    override fun requestNearbyDevicesPermission() {
        permissionRequester?.invoke()
    }

    public fun onNearbyDevicesPermissionResult(granted: Boolean) {
        _uiState.update { current ->
            current.copy(
                nearbyDevicesPermissionGranted = granted,
                failureReason = if (!granted) RadioFailureReason.PERMISSION_DENIED else RadioFailureReason.NONE,
            )
        }
        if (granted && _uiState.value.enabled) {
            startScan()
        }
    }

    public fun updateNearbyPermissionStatus() {
        val granted = checkNearbyPermissions()
        _uiState.update { it.copy(nearbyDevicesPermissionGranted = granted) }
    }

    public companion object {
        private const val TAG = "RadioGateway"
        public const val PREFS_NAME: String = "flash_radio_settings"
        public const val KEY_GATEWAY_ENABLED: String = "gateway_enabled"
    }
}
