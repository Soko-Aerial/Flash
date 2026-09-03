package com.transfer.flash.core.discovery.nsd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.discovery.FlashDiscovery
import com.transfer.flash.core.discovery.FlashDiscoveryState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.charset.StandardCharsets
import kotlin.concurrent.Volatile

/**
 * Headless Android NSD (mDNS / DNS-SD) discovery and advertising engine.
 * Encapsulates Wi-Fi multicast locks, serialized service resolution, and stale callback protection.
 */
public class NsdFlashDiscovery(
    context: Context,
    private val localDeviceId: FlashDeviceId,
    private val friendlyNameProvider: () -> String,
    private val serviceType: String = SERVICE_TYPE_LAN,
    private val serviceInstancePrefix: String = "Flash",
    private val capabilities: String = "probe",
    private val transportType: FlashTransportType = FlashTransportType.LAN,
    private val protocolVersion: Int = 1,
    private val onEndpointFound: ((FlashDiscoveredEndpoint) -> Unit)? = null,
    private val onEndpointLost: ((String) -> Unit)? = null,
    private val onStatusChanged: ((String) -> Unit)? = null,
) : FlashDiscovery {

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val _state = MutableStateFlow(FlashDiscoveryState())
    override val state: StateFlow<FlashDiscoveryState> = _state.asStateFlow()

    private val _discoveredEndpoints = MutableStateFlow<List<FlashDiscoveredEndpoint>>(emptyList())
    override val discoveredEndpoints: StateFlow<List<FlashDiscoveredEndpoint>> = _discoveredEndpoints.asStateFlow()

    private val endpointsByServiceName = mutableMapOf<String, FlashDiscoveredEndpoint>()
    private val lock = Any()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var generation = 0

    private val resolveQueue = NsdResolveQueue(
        nsdManager = nsdManager,
        tag = TAG,
        onResolvedCallback = ::handleServiceResolved,
    )

    override suspend fun startDiscovery(): FlashResult<Unit> {
        if (_state.value.isDiscovering && discoveryListener != null) {
            return FlashResult.Success(Unit)
        }

        generation += 1
        val activeGen = generation
        resolveQueue.clear()
        acquireMulticastLock()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                if (!isCurrent(activeGen)) return
                updateStatus("Scanning network")
                _state.update { it.copy(isDiscovering = true, statusMessage = "Scanning network") }
                Log.i(TAG, "NSD discovery started type=$regType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (!isCurrent(activeGen)) return
                updateStatus("Discovery start failed: $errorCode")
                _state.update { it.copy(isDiscovering = false, statusMessage = "Discovery start failed: $errorCode") }
                Log.w(TAG, "NSD discovery start failed error=$errorCode")
                discoveryListener = null
                releaseMulticastLockIfIdle()
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!isCurrent(activeGen)) return
                if (serviceInfo.serviceType != serviceType) return
                resolveQueue.enqueue(serviceInfo) { isCurrent(activeGen) }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                if (!isCurrent(activeGen)) return
                handleServiceLost(serviceInfo.serviceName)
                Log.i(TAG, "NSD service lost name=${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "NSD discovery stopped type=$serviceType")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD discovery stop failed error=$errorCode")
            }
        }

        discoveryListener = listener
        return runCatching {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            FlashResult.Success(Unit)
        }.getOrElse { error ->
            discoveryListener = null
            releaseMulticastLockIfIdle()
            FlashResult.Failure(FlashError.NetworkUnavailable("Failed to start discovery: ${error.message}"))
        }
    }

    override suspend fun stopDiscovery(): FlashResult<Unit> {
        if (!_state.value.isDiscovering && discoveryListener == null) {
            return FlashResult.Success(Unit)
        }

        generation += 1
        discoveryListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
                .onFailure { Log.w(TAG, "Unable to stop NSD discovery", it) }
        }
        discoveryListener = null
        resolveQueue.clear()
        _state.update { it.copy(isDiscovering = false, statusMessage = "Discovery stopped") }
        updateStatus("Discovery stopped")
        releaseMulticastLockIfIdle()
        return FlashResult.Success(Unit)
    }

    override suspend fun startAdvertising(listenPort: Int): FlashResult<Unit> {
        stopAdvertising()
        generation += 1
        val activeGen = generation
        acquireMulticastLock()

        val name = friendlyNameProvider()
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$serviceInstancePrefix ${name.take(24)}"
            this.serviceType = this@NsdFlashDiscovery.serviceType
            port = listenPort
            setAttribute("device_id", localDeviceId.value)
            setAttribute("name", name)
            setAttribute("proto", protocolVersion.toString())
            setAttribute("caps", capabilities)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                if (!isCurrent(activeGen)) return
                val status = "Advertising as ${info.serviceName}"
                updateStatus(status)
                _state.update { it.copy(isAdvertising = true, advertisedPort = info.port, statusMessage = status) }
                Log.i(TAG, "NSD service registered name=${info.serviceName} port=${info.port}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                if (!isCurrent(activeGen)) return
                val status = "Advertising failed: $errorCode"
                updateStatus(status)
                _state.update { it.copy(isAdvertising = false, statusMessage = status) }
                Log.w(TAG, "NSD registration failed error=$errorCode")
                registrationListener = null
                releaseMulticastLockIfIdle()
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "NSD service unregistered name=${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD unregistration failed error=$errorCode")
            }
        }

        registrationListener = listener
        return runCatching {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            FlashResult.Success(Unit)
        }.getOrElse { error ->
            registrationListener = null
            releaseMulticastLockIfIdle()
            FlashResult.Failure(FlashError.NetworkUnavailable("Failed to register service: ${error.message}"))
        }
    }

    override suspend fun stopAdvertising(): FlashResult<Unit> {
        if (!_state.value.isAdvertising && registrationListener == null) {
            return FlashResult.Success(Unit)
        }

        generation += 1
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
                .onFailure { Log.w(TAG, "Unable to unregister NSD service", it) }
        }
        registrationListener = null
        _state.update { it.copy(isAdvertising = false, advertisedPort = 0, statusMessage = "Advertising stopped") }
        updateStatus("Advertising stopped")
        releaseMulticastLockIfIdle()
        return FlashResult.Success(Unit)
    }

    override suspend fun stopAll(): FlashResult<Unit> {
        stopAdvertising()
        stopDiscovery()
        return FlashResult.Success(Unit)
    }

    private fun handleServiceResolved(info: NsdServiceInfo) {
        val endpoint = info.toDiscoveredEndpoint() ?: return
        if (endpoint.deviceId == localDeviceId) return

        synchronized(lock) {
            endpointsByServiceName[info.serviceName] = endpoint
            _discoveredEndpoints.value = endpointsByServiceName.values.toList()
        }
        onEndpointFound?.invoke(endpoint)
        Log.i(TAG, "NSD endpoint resolved id=${endpoint.deviceId.value} address=${endpoint.hostAddress}:${endpoint.port}")
    }

    private fun handleServiceLost(serviceName: String) {
        synchronized(lock) {
            endpointsByServiceName.remove(serviceName)
            _discoveredEndpoints.value = endpointsByServiceName.values.toList()
        }
        onEndpointLost?.invoke(serviceName)
    }

    private fun NsdServiceInfo.toDiscoveredEndpoint(): FlashDiscoveredEndpoint? {
        val hostAddress = host?.hostAddress ?: return null
        val deviceIdStr = attribute("device_id") ?: return null
        val advertisedName = attribute("name") ?: serviceName
        val proto = attribute("proto")?.toIntOrNull() ?: protocolVersion

        val device = FlashDevice(
            id = FlashDeviceId(deviceIdStr),
            friendlyName = advertisedName,
            transportType = transportType,
            presence = FlashPeerPresence.Online,
            protocolVersion = proto,
        )

        return FlashDiscoveredEndpoint(
            device = device,
            hostAddress = hostAddress,
            port = port,
            serviceName = serviceName,
        )
    }

    private fun NsdServiceInfo.attribute(key: String): String? {
        return attributes[key]?.toString(StandardCharsets.UTF_8)
    }

    private fun updateStatus(status: String) {
        onStatusChanged?.invoke(status)
    }

    private fun isCurrent(activeGeneration: Int): Boolean = generation == activeGeneration

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        multicastLock = wifiManager.createMulticastLock("flash-discovery-$serviceInstancePrefix").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLockIfIdle() {
        if (_state.value.isDiscovering || _state.value.isAdvertising) return
        multicastLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        multicastLock = null
    }

    public companion object {
        public const val TAG: String = "DISCOVERY"
        public const val SERVICE_TYPE_LAN: String = "_flash-transfer._tcp."
        public const val SERVICE_TYPE_WS: String = "_flashws._tcp."
    }
}
