package com.transfer.flash.core.radio.chat

import com.transfer.flash.core.radio.connect.RadioSecurityProfile
import com.transfer.flash.core.radio.driver.KissTncDriver
import com.transfer.flash.core.radio.gateway.RadioGatewayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The two gateway settings the chat engine follows: the master switch and the security profile. */
public data class RadioChatSettings(
    public val enabled: Boolean,
    public val profile: RadioSecurityProfile,
)

/**
 * Connects the radio gateway to the chat engine (ERROR-703).
 *
 * The gateway ([RadioGatewayManager]) creates a fresh [KissTncDriver] every time a radio is connected and drops it
 * on disconnect. A [RadioChatEngine] is bound to one driver for its whole life, so this host builds an engine when a
 * driver appears, closes it when the driver goes, and keeps the engine's enabled flag and profile equal to the
 * gateway's. Callers see one stable [inboundMessages] / [deliveryReceipts] stream and an [engine] that is null while
 * no radio is connected (a send is then refused as "radio not connected", never queued for a link that is not there).
 *
 * @param drivers the gateway's current driver, null when none
 * @param settings the gateway's switch and profile
 * @param engineFactory builds an engine over a driver in the given scope (the host supplies identity, trust, crypto)
 */
public class RadioChatHost(
    private val scope: CoroutineScope,
    drivers: Flow<KissTncDriver?>,
    settings: Flow<RadioChatSettings>,
    private val engineFactory: (engineScope: CoroutineScope, driver: KissTncDriver) -> RadioChatEngine,
) : AutoCloseable {

    private val lock = Any()
    private var engineScopeJob: Job? = null
    private var currentEngine: RadioChatEngine? = null
    private var latestSettings = RadioChatSettings(enabled = false, profile = RadioSecurityProfile.PROFILE_A_PLAINTEXT_SIGNED)

    private val _engine = MutableStateFlow<RadioChatEngine?>(null)

    /** The engine over the live driver, or null while no radio is connected. */
    public val engine: StateFlow<RadioChatEngine?> = _engine.asStateFlow()

    private val _enabled = MutableStateFlow(false)

    /** The gateway switch. */
    public val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _inbound = MutableSharedFlow<RadioChatInbound>(extraBufferCapacity = 64)
    public val inboundMessages: SharedFlow<RadioChatInbound> = _inbound.asSharedFlow()

    private val _receipts = MutableSharedFlow<RadioChatReceipt>(extraBufferCapacity = 64)
    public val deliveryReceipts: SharedFlow<RadioChatReceipt> = _receipts.asSharedFlow()

    /** OFF when the switch is off, DISCONNECTED while there is no engine, else the engine's own status. */
    @OptIn(ExperimentalCoroutinesApi::class)
    public val status: StateFlow<RadioChatStatus> =
        combine(_enabled, _engine.flatMapLatest { it?.status ?: flowOf(RadioChatStatus.DISCONNECTED) }) { on, s ->
            if (!on) RadioChatStatus.OFF else s
        }.stateIn(scope, SharingStarted.Eagerly, RadioChatStatus.OFF)

    private val collectors: List<Job> = listOf(
        scope.launch { drivers.distinctUntilChanged().collect { swap(it) } },
        scope.launch { settings.distinctUntilChanged().collect { apply(it) } },
    )

    private fun swap(driver: KissTncDriver?) {
        synchronized(lock) {
            currentEngine?.close()
            engineScopeJob?.cancel()
            currentEngine = null
            engineScopeJob = null
            _engine.value = null
            if (driver == null) return
            val job = SupervisorJob(scope.coroutineContext[Job])
            val engineScope = CoroutineScope(scope.coroutineContext + job)
            val created = engineFactory(engineScope, driver)
            created.setEnabled(latestSettings.enabled)
            created.setProfile(latestSettings.profile)
            // Forwarders first, so a frame that arrives right after the engine starts is not missed.
            engineScope.launch { created.inboundMessages.collect { _inbound.emit(it) } }
            engineScope.launch { created.deliveryReceipts.collect { _receipts.emit(it) } }
            engineScopeJob = job
            currentEngine = created
            _engine.value = created
        }
    }

    private fun apply(s: RadioChatSettings) {
        synchronized(lock) {
            latestSettings = s
            _enabled.value = s.enabled
            currentEngine?.setEnabled(s.enabled)
            currentEngine?.setProfile(s.profile)
        }
    }

    override fun close() {
        collectors.forEach { it.cancel() }
        swap(null)
    }

    public companion object {
        /**
         * A host that follows whichever [RadioGatewayManager] is current (the host controllers create theirs lazily).
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        public fun over(
            scope: CoroutineScope,
            managers: Flow<RadioGatewayManager?>,
            engineFactory: (engineScope: CoroutineScope, driver: KissTncDriver) -> RadioChatEngine,
        ): RadioChatHost = RadioChatHost(
            scope = scope,
            drivers = managers.flatMapLatest { it?.driver ?: emptyFlow() },
            settings = managers.flatMapLatest { m ->
                m?.state?.map { RadioChatSettings(it.isEnabled, it.currentProfile) } ?: emptyFlow()
            },
            engineFactory = engineFactory,
        )
    }
}
