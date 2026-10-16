package com.transfer.flash.core.radio.link

import com.transfer.flash.core.radio.connect.RememberedRadio
import kotlinx.coroutines.flow.Flow

/**
 * Common platform port for finding, pairing, and connecting to hardware radio devices (Plan R §6).
 *
 * Implementations:
 * - Windows: WindowsRadioDiscovery (JVM) using Win32 Bluetooth API (JNA) + Registry BTHENUM.
 * - Android: AndroidRadioDiscovery (Android) using BluetoothAdapter discovery + createBond.
 * - Test: FakeRadioDeviceDiscovery (in-memory simulator).
 */
public interface RadioDeviceDiscovery {
    /**
     * Lists Bluetooth and serial devices already paired / remembered in the OS.
     * Guaranteed non-intrusive (never performs RF inquiry scans).
     */
    public suspend fun pairedDevices(): List<DiscoveredDevice>

    /**
     * Performs an active discovery scan for nearby radio devices with a time limit of [timeoutMs].
     * Cancelling the returned [Flow] immediately aborts the active OS scan and unregisters listeners.
     */
    public fun scan(timeoutMs: Long): Flow<DiscoveredDevice>

    /**
     * Initiates in-app pairing with [device].
     */
    public suspend fun pair(device: DiscoveredDevice): PairResult

    /**
     * Opens a bidirectional [ByteLink] to [remembered].
     * On Windows, resolves the current virtual COM port mapped to [RememberedRadio.bluetoothAddress]
     * dynamically, ensuring that port renumbering after re-pairing or OS updates does not break connectivity.
     * Incoming listener ports (with all-zero MACs) are strictly ignored and never opened.
     *
     * @throws LinkException on failure with an actionable failure message.
     */
    public suspend fun openLink(remembered: RememberedRadio): ByteLink
}
