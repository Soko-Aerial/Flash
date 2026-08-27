package com.transfer.flash.core.transfer.model

import com.transfer.flash.core.network.ws.WsTransferServer

internal enum class WsTransferDirection { SENDING, RECEIVING }

internal enum class WsTransferStatus { ACTIVE, COMPLETED, FAILED }

internal data class WsPeer(
    val deviceId: String,
    val friendlyName: String,
    val address: String,
    val outbound: Boolean,
)

internal data class WsTransferItem(
    val id: String,
    val peerName: String,
    val fileName: String,
    val direction: WsTransferDirection,
    val bytesDone: Long,
    val bytesTotal: Long,
    val status: WsTransferStatus,
    val detail: String = "",
    val filePath: String? = null,
)

internal data class WsDiscoveredDevice(
    val deviceId: String,
    val friendlyName: String,
    val address: String,
    val paired: Boolean,
    val connected: Boolean,
    val connecting: Boolean,
)

internal data class WsTransferUiState(
    val localDeviceId: String = "",
    val friendlyName: String = "",
    val isServerRunning: Boolean = false,
    val listenPort: Int = 0,
    val localAddresses: List<String> = emptyList(),
    val status: String = "Server stopped",
    val hostInput: String = "",
    val portInput: String = WsTransferServer.PREFERRED_PORT.toString(),
    val isConnecting: Boolean = false,
    val lastConnectResult: String? = null,
    val peers: List<WsPeer> = emptyList(),
    val discovered: List<WsDiscoveredDevice> = emptyList(),
    val transfers: List<WsTransferItem> = emptyList(),
)
