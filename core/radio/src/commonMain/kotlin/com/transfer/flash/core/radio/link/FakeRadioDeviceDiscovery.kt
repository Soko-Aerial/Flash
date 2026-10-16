package com.transfer.flash.core.radio.link

import com.transfer.flash.core.radio.connect.RememberedRadio
import com.transfer.flash.core.radio.sim.inMemoryLinkPair
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * In-memory test stand-in for [RadioDeviceDiscovery].
 */
public class FakeRadioDeviceDiscovery(
    public var pairedList: MutableList<DiscoveredDevice> = mutableListOf(),
    public var discoveredOnScan: List<DiscoveredDevice> = emptyList(),
    public var pairResult: PairResult = PairResult.Success,
    public var linkSupplier: (RememberedRadio) -> ByteLink = { radio ->
        val pair = inMemoryLinkPair("fake-${radio.displayName}")
        pair.first
    },
) : RadioDeviceDiscovery {

    public var isScanning: Boolean = false
        private set
    public var scanCancelled: Boolean = false
        private set
    public var lastScanTimeoutMs: Long? = null
        private set

    public val pairedAttempts: MutableList<DiscoveredDevice> = mutableListOf()
    public val openedRadios: MutableList<RememberedRadio> = mutableListOf()

    override suspend fun pairedDevices(): List<DiscoveredDevice> {
        return pairedList.toList()
    }

    override fun scan(timeoutMs: Long): Flow<DiscoveredDevice> = callbackFlow {
        isScanning = true
        scanCancelled = false
        lastScanTimeoutMs = timeoutMs

        for (device in discoveredOnScan) {
            trySend(device)
        }

        awaitClose {
            isScanning = false
            scanCancelled = true
        }
    }

    override suspend fun pair(device: DiscoveredDevice): PairResult {
        pairedAttempts.add(device)
        if (pairResult is PairResult.Success) {
            val updated = device.copy(isPaired = true)
            pairedList.removeAll { it.bluetoothAddress.equals(device.bluetoothAddress, ignoreCase = true) }
            pairedList.add(updated)
        }
        return pairResult
    }

    override suspend fun openLink(remembered: RememberedRadio): ByteLink {
        openedRadios.add(remembered)
        return linkSupplier(remembered)
    }
}
