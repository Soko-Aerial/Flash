package com.transfer.flash.core.network

public data class FlashNetworkState(
    val isRunning: Boolean = false,
    val localPort: Int = 0,
    val localAddresses: List<String> = emptyList(),
    val activePeerCount: Int = 0,
)
