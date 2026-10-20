package com.transfer.flash.core.radio.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A paired Bluetooth device offered for an RFCOMM link.
 *
 * @property serviceUuids the service UUID strings from the device's SDP cache (`BluetoothDevice.uuids`), or null when
 *   the cache cannot be read. [RadioServiceIdentity] uses them to tell a radio's control device from its audio endpoint.
 */
public class BondedBluetoothDevice(
    public val address: String,
    public val name: String,
    public val serviceUuids: List<String>? = null,
)

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
                    .map {
                        BondedBluetoothDevice(
                            address = it.address,
                            name = it.name ?: "(unnamed)",
                            // The pairing-time SDP cache; reading it does not trigger a query. Needs BLUETOOTH_CONNECT.
                            serviceUuids = runCatching { it.uuids.orEmpty().map { p -> p.uuid.toString() } }.getOrNull(),
                        )
                    }
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

    /**
     * Opens the serial link to a **radio**, trying the strategies the hardware actually accepts.
     *
     * These handhelds accept only a null-authentication RFCOMM connection (the secure variants force an authentication
     * the radio rejects), and their SDP record can advertise a server channel that the firmware then refuses — it answers
     * with an RFCOMM DM frame, which reaches us as an immediate EOF. A single secure
     * `createRfcommSocketToServiceRecord`, which is what [openRfcomm] does by default, is therefore the one strategy
     * known to fail. The order below mirrors the reference implementation (HTCommander, Apache-2.0,
     * `BluetoothClassicPlugin.connectSocket`):
     *
     * 1. the channel that last worked for this radio (fast reconnect),
     * 2. insecure SDP, 3. secure SDP,
     * 4. insecure fixed channels 1..12,
     * 5. secure fixed channel 1.
     *
     * Each failed attempt closes its socket and pauses briefly so the radio can reset its RFCOMM state. Blocking (the
     * Android docs say `connect()` blocks), so call it from `Dispatchers.IO`. Probing is capped at
     * [RADIO_CONNECT_BUDGET_MS]: a radio that never answers would otherwise hold the driver's `Connecting` state for
     * minutes, because the platform's own connect timeout is far longer than a radio needs.
     *
     * @throws LinkException with a plain-language reason when every strategy fails.
     */
    @OptIn(FlashInternalApi::class)
    @SuppressLint("MissingPermission") // checked at the top through missingPermissions()
    public fun openRadioLink(address: String, uuid: String = SPP_UUID_TEXT): ByteLink {
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
        runCatching {
            if (Build.VERSION.SDK_INT < BluetoothPermissions.API_SPLIT_PERMISSIONS ||
                context.checkSelfPermission(BluetoothPermissions.SCAN) == PackageManager.PERMISSION_GRANTED
            ) {
                a.cancelDiscovery()
            }
        }

        val id = UUID.fromString(uuid)
        val key = address.uppercase()
        val deadline = System.currentTimeMillis() + RADIO_CONNECT_BUDGET_MS
        var lastError: Exception? = null
        var ranOutOfTime = false

        fun attempt(strategy: String, open: () -> BluetoothSocket): BluetoothSocket? {
            if (System.currentTimeMillis() > deadline) {
                ranOutOfTime = true
                return null
            }
            var candidate: BluetoothSocket? = null
            return try {
                candidate = open()
                candidate.connect()
                FlashProbe.emit("radio.link.attempt", "strategy" to strategy, "result" to "ok")
                candidate
            } catch (e: SecurityException) {
                runCatching { candidate?.close() }
                throw e
            } catch (e: Exception) {
                runCatching { candidate?.close() }
                lastError = e
                FlashProbe.emit(
                    "radio.link.attempt",
                    "strategy" to strategy,
                    "result" to "failed",
                    "error" to (e.message ?: e.javaClass.simpleName),
                )
                // Let the radio reset its RFCOMM state before the next attempt (as the reference implementation does).
                Thread.sleep(ATTEMPT_PAUSE_MS)
                null
            }
        }

        // createInsecureRfcommSocket(int) and createRfcommSocket(int) are not in the public SDK; the reference
        // implementation reaches them the same way, and they are the only way to probe a fixed channel.
        val insecureByChannel: java.lang.reflect.Method? = runCatching {
            device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
        }.getOrNull()

        fun insecureChannel(channel: Int): BluetoothSocket {
            val m = insecureByChannel
                ?: throw IOException("this Android build does not expose createInsecureRfcommSocket(int)")
            val made = m.invoke(device, channel)
            return made as? BluetoothSocket ?: throw IOException("createInsecureRfcommSocket($channel) returned no socket")
        }

        try {
            val cached = lastGoodChannel[key]
            if (cached != null) {
                attempt("insecure-ch$cached") { insecureChannel(cached) }?.let {
                    return RfcommLink(it, "rfcomm $address ch$cached")
                }
            }

            attempt("insecure-sdp") { device.createInsecureRfcommSocketToServiceRecord(id) }?.let {
                return RfcommLink(it, "rfcomm $address insecure-sdp")
            }
            attempt("secure-sdp") { device.createRfcommSocketToServiceRecord(id) }?.let {
                return RfcommLink(it, "rfcomm $address secure-sdp")
            }

            for (channel in 1..12) {
                if (channel == cached) continue // already tried above
                attempt("insecure-ch$channel") { insecureChannel(channel) }?.let {
                    lastGoodChannel[key] = channel
                    return RfcommLink(it, "rfcomm $address ch$channel")
                }
            }

            attempt("secure-ch1") {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                m.invoke(device, 1) as BluetoothSocket
            }?.let { return RfcommLink(it, "rfcomm $address secure-ch1") }
        } catch (e: SecurityException) {
            throw LinkException("Bluetooth permission denied by the system", e)
        }

        val why = lastError?.message ?: "no strategy was accepted"
        val budgetNote = if (ranOutOfTime) " Probing stopped after ${RADIO_CONNECT_BUDGET_MS / 1000} s." else ""
        throw LinkException(
            "the radio refused every Bluetooth serial channel ($why). Check that KISS TNC is enabled on the radio, " +
                "that it is switched on and in range, and that this is the radio's control device — if BT lists the " +
                "radio twice, the other entry is its audio device.$budgetNote",
            lastError,
        )
    }

    private companion object {
        /** The RFCOMM channel that last carried a link, per address, so a reconnect skips the 1..12 probe. */
        val lastGoodChannel: ConcurrentHashMap<String, Int> = ConcurrentHashMap()

        /** Pause between attempts, so the radio can reset its RFCOMM state. */
        const val ATTEMPT_PAUSE_MS: Long = 250L

        /** How long all the radio connect strategies together may take before the driver is told the link failed. */
        const val RADIO_CONNECT_BUDGET_MS: Long = 25_000L
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
