package com.transfer.flash.core.transfer.wslegacy

import android.content.Context
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.discovery.nsd.NsdFlashDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * LEGACY relocation (C5.1): pre-chunked whole-file 64KiB-frame engine retained as
 * fallback/reference; superseded by transfer.chunked pipelines (C5.3+) — scheduled for
 * deletion after parity.
 *
 * Compatibility adapter for [NsdFlashDiscovery] in the legacy WebSocket transfer manager.
 */
internal class WsDiscovery(
    context: Context,
    localDeviceId: String,
    friendlyName: String,
    onDeviceFound: (LegacyDiscoveredDevice) -> Unit,
    onDeviceLost: (String) -> Unit,
    onStatusChanged: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engine = NsdFlashDiscovery(
        context = context,
        localDeviceId = FlashDeviceId(localDeviceId),
        friendlyNameProvider = { friendlyName },
        serviceType = NsdFlashDiscovery.SERVICE_TYPE_WS,
        serviceInstancePrefix = "FlashWS",
        capabilities = "ws-transfer",
        transportType = FlashTransportType.WEBSOCKET,
        protocolVersion = 1,
        onEndpointFound = { endpoint ->
            onDeviceFound(endpoint.toLegacyModel())
        },
        onEndpointLost = onDeviceLost,
        onStatusChanged = onStatusChanged,
    )

    val isDiscoveryRunning: Boolean
        get() = engine.state.value.isDiscovering

    fun startDiscovery() {
        scope.launch { engine.startDiscovery() }
    }

    fun stopDiscovery() {
        scope.launch { engine.stopDiscovery() }
    }

    fun startAdvertising(listenPort: Int) {
        scope.launch { engine.startAdvertising(listenPort) }
    }

    fun stopAdvertising() {
        scope.launch { engine.stopAdvertising() }
    }

    fun stopAll() {
        scope.launch { engine.stopAll() }
    }

    private fun FlashDiscoveredEndpoint.toLegacyModel(): LegacyDiscoveredDevice {
        return LegacyDiscoveredDevice(
            deviceId = deviceId.value,
            friendlyName = friendlyName,
            hostAddress = hostAddress,
            port = port,
            serviceName = serviceName,
            transportType = LegacyTransportType.LAN,
            protocolVersion = device.protocolVersion,
        )
    }
}
