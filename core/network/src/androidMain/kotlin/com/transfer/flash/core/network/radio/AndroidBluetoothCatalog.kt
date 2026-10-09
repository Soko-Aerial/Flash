package com.transfer.flash.core.network.radio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/** A paired Bluetooth device offered for an RFCOMM link. */
public class BondedBluetoothDevice(public val address: String, public val name: String)

/** Result of [AndroidBluetoothCatalog.listBonded]. */
public sealed interface BondedListing {
    /** The phone has no Bluetooth adapter. */
    public data object NoAdapter : BondedListing

    /** Bluetooth is switched off. */
    public data object BluetoothOff : BondedListing

    /** A runtime permission is missing; ask for [permissions] and retry. */
    public class PermissionMissing(public val permissions: List<String>) : BondedListing

    /** Paired devices (possibly none). */
    public class Devices(public val devices: List<BondedBluetoothDevice>) : BondedListing
}

/**
 * Android RFCOMM (Bluetooth Classic serial) access for the radio link: lists devices the user already paired in system
 * settings and opens a [ByteLink] to one over a [BluetoothSocket].
 *
 * Why only paired devices: discovery needs BLUETOOTH_SCAN (and location on Android 11 and lower) and the radio is paired once
 * in system settings anyway. The pairing itself is done by the OS and is NOT Flash trust (ADR-101): the Flash session layer
 * authenticates the peer on its own.
 *
 * Status: compiles; **never run on a phone or against a radio** (BT-03, BT-04). Platform facts and their dates are in the
 * report `docs/reports/2026-10-09-bluetooth-radio.md` (paste-ready text for `docs/android-platform-notes.md`).
 */
public class AndroidBluetoothCatalog(private val context: Context) : SerialPortCatalog {

    private val adapter: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    /** Permissions that must be granted at runtime before [listBonded] and [openRfcomm] work on this device. */
    public fun missingPermissions(): List<String> =
        BluetoothPermissions.mandatory(Build.VERSION.SDK_INT).filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

    /** Everything to request in one "Nearby devices" prompt on this device (empty below Android 12 or when all are granted). */
    public fun permissionsToRequest(): List<String> =
        BluetoothPermissions.runtimePermissions(Build.VERSION.SDK_INT).filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

    /** Lists paired devices, or says why it cannot. */
    @SuppressLint("MissingPermission") // checked just above through missingPermissions()
    public fun listBonded(): BondedListing {
        val a = adapter ?: return BondedListing.NoAdapter
        val missing = missingPermissions()
        if (missing.isNotEmpty()) return BondedListing.PermissionMissing(missing)
        if (!a.isEnabled) return BondedListing.BluetoothOff
        return try {
            BondedListing.Devices(
                a.bondedDevices.orEmpty()
                    .map { BondedBluetoothDevice(it.address, it.name ?: "(unnamed)") }
                    .sortedBy { it.name.lowercase() },
            )
        } catch (_: SecurityException) {
            BondedListing.PermissionMissing(listOf(BluetoothPermissions.CONNECT))
        }
    }

    override fun listPorts(): List<PortInfo> =
        (listBonded() as? BondedListing.Devices)?.devices.orEmpty().map { PortInfo(it.address, it.name, PortKind.BLUETOOTH) }

    /** [systemName] is the device MAC address; [settings] are ignored (RFCOMM has no baud rate). Blocks; call off the main thread. */
    override fun open(systemName: String, settings: SerialSettings): ByteLink = openRfcomm(systemName)

    /**
     * Connects to the paired device [address]. Blocking (the Android docs say `connect()` blocks until success or failure), so
     * call it from `Dispatchers.IO`.
     *
     * @param uuid [SPP_UUID_TEXT] for a serial radio; [FLASH_RFCOMM_UUID_TEXT] for another phone running Flash.
     * @param secure true uses an authenticated, encrypted link (the OS pairing key); false is for devices that do not pair.
     * @throws LinkException with a plain-language reason for every failure.
     */
    @SuppressLint("MissingPermission") // checked at the top
    public fun openRfcomm(address: String, uuid: String = SPP_UUID_TEXT, secure: Boolean = true): ByteLink {
        val a = adapter ?: throw LinkException("this device has no Bluetooth")
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            throw LinkException("Bluetooth permission needed: ${missing.joinToString { it.substringAfterLast('.') }}")
        }
        if (!a.isEnabled) throw LinkException("Bluetooth is off")
        val device: BluetoothDevice = try {
            a.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw LinkException("not a Bluetooth address: $address", e)
        }
        // Discovery slows or breaks connects (official BluetoothAdapter docs). Cancelling needs SCAN on Android 12+.
        runCatching {
            if (Build.VERSION.SDK_INT < BluetoothPermissions.API_SPLIT_PERMISSIONS ||
                context.checkSelfPermission(BluetoothPermissions.SCAN) == PackageManager.PERMISSION_GRANTED
            ) {
                a.cancelDiscovery()
            }
        }
        var socket: BluetoothSocket? = null
        try {
            val id = UUID.fromString(uuid)
            val s = if (secure) device.createRfcommSocketToServiceRecord(id) else device.createInsecureRfcommSocketToServiceRecord(id)
            socket = s
            s.connect()
            return RfcommLink(s, "rfcomm ${device.address}")
        } catch (e: SecurityException) {
            runCatching { socket?.close() }
            throw LinkException("Bluetooth permission denied by the system", e)
        } catch (e: IOException) {
            runCatching { socket?.close() }
            throw LinkException(
                "could not connect to $address over RFCOMM (radio off, out of range, busy with another app, " +
                    "or it does not offer the serial service): ${e.message}",
                e,
            )
        }
    }
}

/** [ByteLink] over a connected [BluetoothSocket]. */
internal class RfcommLink(private val socket: BluetoothSocket, override val description: String) : ByteLink {
    private val input = socket.inputStream
    private val output = socket.outputStream

    @Volatile
    private var closed = false

    // BluetoothSocket streams have no read timeout: poll available() so a read can give up and honour cancellation.
    override suspend fun read(buffer: ByteArray, timeoutMs: Long): Int = withContext(Dispatchers.IO) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        try {
            var result = 0
            while (true) {
                if (closed) {
                    result = -1
                    break
                }
                val n = input.available()
                if (n > 0) {
                    result = input.read(buffer, 0, minOf(n, buffer.size))
                    break
                }
                if (System.nanoTime() >= deadline) break
                delay(15)
            }
            result
        } catch (e: IOException) {
            if (closed) -1 else throw LinkException("Bluetooth read failed: ${e.message}", e)
        }
    }

    // R3: OutputStream.write on a BluetoothSocket ignores coroutine cancellation and can stall until the OS gives up (a
    // supervision timeout). What wakes it is close(): KissTncDriver.serve closes the link the moment its scope is
    // cancelled or either side ends, so the IOException below arrives at once and a stalled write cannot hold the driver.
    override suspend fun write(data: ByteArray) {
        withContext(Dispatchers.IO) {
            if (closed) throw LinkException("link closed")
            try {
                output.write(data)
                output.flush()
            } catch (e: IOException) {
                throw LinkException("Bluetooth write failed: ${e.message}", e)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
    }
}
