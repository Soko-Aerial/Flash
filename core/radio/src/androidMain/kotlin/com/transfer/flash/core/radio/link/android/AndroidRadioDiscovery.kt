package com.transfer.flash.core.radio.link.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.transfer.flash.core.radio.connect.CandidateCategory
import com.transfer.flash.core.radio.connect.CandidateKind
import com.transfer.flash.core.radio.connect.RadioCandidateDevice
import com.transfer.flash.core.radio.connect.RadioCandidateRanker
import com.transfer.flash.core.radio.connect.RadioConnectFailureReason
import com.transfer.flash.core.radio.connect.RememberedRadio
import com.transfer.flash.core.radio.link.AndroidBluetoothCatalog
import com.transfer.flash.core.radio.link.BondedListing
import com.transfer.flash.core.radio.link.ByteLink
import com.transfer.flash.core.radio.link.DiscoveredDevice
import com.transfer.flash.core.radio.link.LinkException
import com.transfer.flash.core.radio.link.PairResult
import com.transfer.flash.core.radio.link.RadioDeviceDiscovery
import com.transfer.flash.core.radio.link.RadioServiceIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android implementation of [RadioDeviceDiscovery] (Plan R §6, ADR-183).
 *
 * Handles:
 * - Listing bonded Bluetooth devices using [AndroidBluetoothCatalog].
 * - Active discovery scanning with timeout and lifecycle cancellation (stops scan on leaving/cancelling).
 * - In-app pairing via [BluetoothDevice.createBond] and [BluetoothDevice.ACTION_BOND_STATE_CHANGED].
 * - RFCOMM connection opening via [AndroidBluetoothCatalog.openRadioLink] (insecure-first with a fixed-channel
 *   fallback; the radios reject the secure socket and can advertise a channel they then refuse).
 */
public class AndroidRadioDiscovery(
    private val context: Context,
    private val bluetoothCatalog: AndroidBluetoothCatalog = AndroidBluetoothCatalog(context),
) : RadioDeviceDiscovery {

    private val bluetoothAdapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    override suspend fun pairedDevices(): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val listing = bluetoothCatalog.listBonded()
        if (listing !is BondedListing.Devices) {
            return@withContext emptyList()
        }

        listing.devices.map { bonded ->
            val candidate = RadioCandidateDevice(
                name = bonded.name,
                addressOrPort = bonded.address,
                kind = CandidateKind.BLUETOOTH_SERIAL,
                isPaired = true,
                rssi = null
            )
            // The radio shows up as two devices with the same name: the control device advertises the vendor service
            // (and/or SPP), the audio endpoint advertises neither and can never carry KISS. Rank the audio endpoint last
            // instead of hiding it, so the list still explains what is there. An empty SDP cache (UNKNOWN) keeps the
            // name-based ranking.
            val services = bonded.serviceUuids
            val category = when {
                RadioServiceIdentity.advertisesVendorService(services) -> CandidateCategory.KNOWN_RADIO
                RadioServiceIdentity.classify(services) == RadioServiceIdentity.Kind.NOT_A_CONTROL_DEVICE ->
                    CandidateCategory.OTHER
                else -> RadioCandidateRanker.categorize(candidate)
            }

            DiscoveredDevice(
                name = bonded.name,
                bluetoothAddress = bonded.address,
                isPaired = true,
                rssi = null,
                kind = CandidateKind.BLUETOOTH_SERIAL,
                category = category,
                rawAddressHex = bonded.address.replace(":", "").replace("-", "").uppercase()
            )
        }
    }

    @SuppressLint("MissingPermission")
    override fun scan(timeoutMs: Long): Flow<DiscoveredDevice> = callbackFlow {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled || bluetoothCatalog.missingPermissions().isNotEmpty()) {
            close()
            return@callbackFlow
        }

        val seenAddresses = mutableSetOf<String>()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        val rssiShort = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                        val rssi = if (rssiShort != Short.MIN_VALUE) rssiShort.toInt() else null

                        if (device != null) {
                            val address = device.address ?: return
                            if (seenAddresses.add(address.uppercase())) {
                                val name = try {
                                    device.name ?: "Unknown Device"
                                } catch (_: SecurityException) {
                                    "Unknown Device"
                                }

                                val isPaired = try {
                                    device.bondState == BluetoothDevice.BOND_BONDED
                                } catch (_: SecurityException) {
                                    false
                                }

                                val candidate = RadioCandidateDevice(
                                    name = name,
                                    addressOrPort = address,
                                    kind = CandidateKind.BLUETOOTH_SERIAL,
                                    isPaired = isPaired,
                                    rssi = rssi
                                )
                                val category = RadioCandidateRanker.categorize(candidate)

                                trySend(
                                    DiscoveredDevice(
                                        name = name,
                                        bluetoothAddress = address,
                                        isPaired = isPaired,
                                        rssi = rssi,
                                        kind = CandidateKind.BLUETOOTH_SERIAL,
                                        category = category,
                                        rawAddressHex = address.replace(":", "").replace("-", "").uppercase()
                                    )
                                )
                            }
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        close()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(receiver, filter)

        try {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            adapter.startDiscovery()
        } catch (_: SecurityException) {
            try { context.unregisterReceiver(receiver) } catch (_: Throwable) {}
            close()
            return@callbackFlow
        }

        val timeoutJob = launch {
            kotlinx.coroutines.delay(timeoutMs)
            try {
                if (adapter.isDiscovering) {
                    adapter.cancelDiscovery()
                }
            } catch (_: SecurityException) {}
            close()
        }

        awaitClose {
            timeoutJob.cancel()
            try {
                if (adapter.isDiscovering) {
                    adapter.cancelDiscovery()
                }
            } catch (_: SecurityException) {}
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Throwable) {}
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun pair(device: DiscoveredDevice): PairResult = withContext(Dispatchers.IO) {
        val adapter = bluetoothAdapter
            ?: return@withContext PairResult.Failed(RadioConnectFailureReason.NO_ADAPTER)
        if (!adapter.isEnabled) {
            return@withContext PairResult.Failed(RadioConnectFailureReason.BLUETOOTH_OFF)
        }
        if (bluetoothCatalog.missingPermissions().isNotEmpty()) {
            return@withContext PairResult.Failed(RadioConnectFailureReason.PERMISSION_MISSING)
        }

        val targetDevice = try {
            adapter.getRemoteDevice(device.bluetoothAddress)
        } catch (_: Throwable) {
            return@withContext PairResult.Failed(RadioConnectFailureReason.RADIO_NOT_FOUND)
        }

        try {
            if (targetDevice.bondState == BluetoothDevice.BOND_BONDED) {
                return@withContext PairResult.AlreadyPaired
            }
        } catch (_: SecurityException) {
            return@withContext PairResult.Failed(RadioConnectFailureReason.PERMISSION_MISSING)
        }

        val pairCompletion = CompletableDeferred<PairResult>()

        val bondReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                    val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (dev?.address.equals(device.bluetoothAddress, ignoreCase = true)) {
                        val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                        when (state) {
                            BluetoothDevice.BOND_BONDED -> {
                                pairCompletion.complete(PairResult.Success)
                            }
                            BluetoothDevice.BOND_NONE -> {
                                pairCompletion.complete(PairResult.Failed(RadioConnectFailureReason.PAIRING_REFUSED))
                            }
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        context.registerReceiver(bondReceiver, filter)

        try {
            val started = targetDevice.createBond()
            if (!started) {
                return@withContext PairResult.Failed(RadioConnectFailureReason.PAIRING_REFUSED)
            }

            val result = withTimeoutOrNull(30_000L) {
                pairCompletion.await()
            }
            return@withContext result ?: PairResult.Failed(RadioConnectFailureReason.PAIRING_REFUSED)
        } catch (_: SecurityException) {
            return@withContext PairResult.Failed(RadioConnectFailureReason.PERMISSION_MISSING)
        } finally {
            try {
                context.unregisterReceiver(bondReceiver)
            } catch (_: Throwable) {}
        }
    }

    override suspend fun openLink(remembered: RememberedRadio): ByteLink = withContext(Dispatchers.IO) {
        try {
            bluetoothCatalog.openRadioLink(remembered.bluetoothAddress)
        } catch (e: LinkException) {
            throw e
        } catch (t: Throwable) {
            throw LinkException("Failed to connect to ${remembered.displayName}: ${t.message}", t)
        }
    }
}
