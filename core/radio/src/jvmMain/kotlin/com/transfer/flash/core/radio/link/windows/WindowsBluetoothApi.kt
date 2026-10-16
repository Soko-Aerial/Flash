package com.transfer.flash.core.radio.link.windows

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference

/**
 * Interface wrapping Win32 Bluetooth API calls (bluetoothapis.h / bthprops.cpl).
 * Every call is mediated through this interface so all logic (discovery, port mapping,
 * ranking, error handling, safety checks) is 100% unit-testable without Bluetooth hardware.
 */
public interface WindowsBluetoothApi {
    /**
     * Enumerate already-remembered or authenticated Bluetooth devices known to Windows.
     * Guaranteed to be non-intrusive (no inquiry, no RF emissions).
     */
    public fun listRememberedDevices(): List<DiscoveredBluetoothDevice>

    /**
     * Perform an active inquiry scan to discover nearby Bluetooth devices.
     * Intrusive / active RF operation.
     */
    public fun searchDevices(params: BluetoothSearchParams): List<DiscoveredBluetoothDevice>

    /**
     * Enumerate service GUIDs enabled on a specific remote Bluetooth device.
     */
    public fun enumerateInstalledServices(deviceAddressLong: Long): List<String>

    /**
     * Authenticate (pair) a device via Win32 BluetoothAuthenticateDevice.
     * If pin is null, Wizard mode is used; if pin is non-null, Transparent mode is used.
     */
    public fun authenticateDevice(deviceAddressLong: Long, pin: String?): AuthenticateOutcome

    /**
     * Enable or disable a service (e.g. SerialPortServiceClass_UUID) on a device.
     */
    public fun setServiceState(deviceAddressLong: Long, serviceGuid: String, enable: Boolean): ServiceStateOutcome
}

/**
 * Standard Bluetooth Serial Port Profile GUID.
 */
public const val SPP_SERVICE_GUID_STRING: String = "00001101-0000-1000-8000-00805F9B34FB"

/**
 * Win32 JNA structures and library interface.
 */
internal object Win32BluetoothNative {

    @Structure.FieldOrder(
        "dwSize",
        "fReturnAuthenticated",
        "fReturnRemembered",
        "fReturnUnknown",
        "fReturnConnected",
        "fIssueInquiry",
        "cTimeoutMultiplier",
        "hRadio"
    )
    open class BLUETOOTH_DEVICE_SEARCH_PARAMS : Structure() {
        @JvmField var dwSize: Int = 0
        @JvmField var fReturnAuthenticated: Int = 1
        @JvmField var fReturnRemembered: Int = 1
        @JvmField var fReturnUnknown: Int = 0
        @JvmField var fReturnConnected: Int = 1
        @JvmField var fIssueInquiry: Int = 0
        @JvmField var cTimeoutMultiplier: Byte = 4
        @JvmField var hRadio: WinNT.HANDLE? = null

        init {
            dwSize = size()
        }
    }

    @Structure.FieldOrder(
        "dwSize",
        "ullLong",
        "ulClassofDevice",
        "fConnected",
        "fRemembered",
        "fAuthenticated",
        "stLastSeen",
        "stLastUsed",
        "szName"
    )
    open class BLUETOOTH_DEVICE_INFO : Structure() {
        @JvmField var dwSize: Int = 0
        @JvmField var ullLong: Long = 0L
        @JvmField var ulClassofDevice: Int = 0
        @JvmField var fConnected: Int = 0
        @JvmField var fRemembered: Int = 0
        @JvmField var fAuthenticated: Int = 0
        @JvmField var stLastSeen: WinBase.SYSTEMTIME = WinBase.SYSTEMTIME()
        @JvmField var stLastUsed: WinBase.SYSTEMTIME = WinBase.SYSTEMTIME()
        @JvmField var szName: CharArray = CharArray(248)

        init {
            dwSize = size()
        }

        fun getDeviceName(): String {
            val end = szName.indexOf('\u0000')
            return if (end >= 0) String(szName, 0, end).trim() else String(szName).trim()
        }
    }

    interface BthpropsLibrary : Library {
        fun BluetoothFindFirstDevice(
            pbtsp: BLUETOOTH_DEVICE_SEARCH_PARAMS,
            pbtdi: BLUETOOTH_DEVICE_INFO
        ): WinNT.HANDLE?

        fun BluetoothFindNextDevice(
            hFind: WinNT.HANDLE,
            pbtdi: BLUETOOTH_DEVICE_INFO
        ): Boolean

        fun BluetoothFindDeviceClose(
            hFind: WinNT.HANDLE
        ): Boolean

        fun BluetoothGetDeviceInfo(
            hRadio: WinNT.HANDLE?,
            pbtdi: BLUETOOTH_DEVICE_INFO
        ): Int

        fun BluetoothEnumerateInstalledServices(
            hRadio: WinNT.HANDLE?,
            pbtdi: BLUETOOTH_DEVICE_INFO,
            pcServiceInout: IntByReference,
            pGuidServices: Memory?
        ): Int

        fun BluetoothSetServiceState(
            hRadio: WinNT.HANDLE?,
            pbtdi: BLUETOOTH_DEVICE_INFO,
            pGuidService: Guid.GUID,
            dwServiceFlags: Int
        ): Int

        fun BluetoothAuthenticateDevice(
            hwndParent: WinDef.HWND?,
            hRadio: WinNT.HANDLE?,
            pbtdi: BLUETOOTH_DEVICE_INFO,
            pszPasskey: WString?,
            ulPasskeyLength: Int
        ): Int
    }

    val INSTANCE: BthpropsLibrary by lazy {
        val systemRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
        val bthpropsFile = java.io.File(systemRoot, "System32\\bthprops.cpl")
        val sysNativeFile = java.io.File(systemRoot, "Sysnative\\bthprops.cpl")
        val libraryPath = when {
            bthpropsFile.exists() -> bthpropsFile.absolutePath
            sysNativeFile.exists() -> sysNativeFile.absolutePath
            else -> "bthprops.cpl"
        }
        Native.load(libraryPath, BthpropsLibrary::class.java)
    }
}

/**
 * Real production implementation calling Windows Win32 Bluetooth APIs via JNA.
 */
public class RealWindowsBluetoothApi : WindowsBluetoothApi {

    override fun listRememberedDevices(): List<DiscoveredBluetoothDevice> {
        val params = BluetoothSearchParams(
            returnAuthenticated = true,
            returnRemembered = true,
            returnUnknown = false,
            returnConnected = true,
            issueInquiry = false
        )
        return queryDevices(params)
    }

    override fun searchDevices(params: BluetoothSearchParams): List<DiscoveredBluetoothDevice> {
        return queryDevices(params)
    }

    private fun queryDevices(params: BluetoothSearchParams): List<DiscoveredBluetoothDevice> {
        val lib = try {
            Win32BluetoothNative.INSTANCE
        } catch (t: Throwable) {
            System.err.println("[RealWindowsBluetoothApi] Unable to load bthprops.cpl: ${t.message}")
            return emptyList()
        }

        val searchParams = Win32BluetoothNative.BLUETOOTH_DEVICE_SEARCH_PARAMS().apply {
            fReturnAuthenticated = if (params.returnAuthenticated) 1 else 0
            fReturnRemembered = if (params.returnRemembered) 1 else 0
            fReturnUnknown = if (params.returnUnknown) 1 else 0
            fReturnConnected = if (params.returnConnected) 1 else 0
            fIssueInquiry = if (params.issueInquiry) 1 else 0
            cTimeoutMultiplier = params.timeoutMultiplier
            hRadio = null
        }

        val deviceInfo = Win32BluetoothNative.BLUETOOTH_DEVICE_INFO()
        val hFind = lib.BluetoothFindFirstDevice(searchParams, deviceInfo)
        if (hFind == null) {
            val err = Native.getLastError()
            if (err != 259) { // 259 is ERROR_NO_MORE_ITEMS (normal when no devices match criteria)
                println("  [DEBUG] BluetoothFindFirstDevice returned null with Win32 error code: $err (${BluetoothErrorMapper.mapErrorCode(err)})")
            }
            return emptyList()
        }

        val results = mutableListOf<DiscoveredBluetoothDevice>()
        try {
            do {
                val addrLong = deviceInfo.ullLong
                val addrStr = formatBluetoothAddress(addrLong)
                val name = deviceInfo.getDeviceName().ifBlank { "Unknown Device" }
                val services = enumerateInstalledServices(addrLong)

                results.add(
                    DiscoveredBluetoothDevice(
                        name = name,
                        addressLong = addrLong,
                        addressFormatted = addrStr,
                        classOfDevice = deviceInfo.ulClassofDevice,
                        isConnected = deviceInfo.fConnected != 0,
                        isRemembered = deviceInfo.fRemembered != 0,
                        isAuthenticated = deviceInfo.fAuthenticated != 0,
                        installedServiceGuids = services
                    )
                )
            } while (lib.BluetoothFindNextDevice(hFind, deviceInfo))
        } finally {
            lib.BluetoothFindDeviceClose(hFind)
        }
        return results
    }

    override fun enumerateInstalledServices(deviceAddressLong: Long): List<String> {
        val lib = try {
            Win32BluetoothNative.INSTANCE
        } catch (_: Throwable) {
            return emptyList()
        }

        val deviceInfo = Win32BluetoothNative.BLUETOOTH_DEVICE_INFO().apply {
            ullLong = deviceAddressLong
        }

        // First call with null to obtain count
        val numServices = IntByReference(0)
        val initialResult = lib.BluetoothEnumerateInstalledServices(null, deviceInfo, numServices, null)
        val count = numServices.value
        if (initialResult != 0 || count <= 0) {
            return emptyList()
        }

        // GUID is 16 bytes
        val guidSize = 16
        val buffer = Memory((count * guidSize).toLong())
        val queryResult = lib.BluetoothEnumerateInstalledServices(null, deviceInfo, numServices, buffer)
        if (queryResult != 0) {
            return emptyList()
        }

        val actualCount = numServices.value
        val guids = mutableListOf<String>()
        for (i in 0 until actualCount) {
            val offset = (i * guidSize).toLong()
            val data1 = buffer.getInt(offset)
            val data2 = buffer.getShort(offset + 4)
            val data3 = buffer.getShort(offset + 6)
            val data4 = ByteArray(8)
            buffer.read(offset + 8, data4, 0, 8)

            val guidString = String.format(
                "%08X-%04X-%04X-%02X%02X-%02X%02X%02X%02X%02X%02X",
                data1,
                data2.toInt() and 0xFFFF,
                data3.toInt() and 0xFFFF,
                data4[0].toInt() and 0xFF,
                data4[1].toInt() and 0xFF,
                data4[2].toInt() and 0xFF,
                data4[3].toInt() and 0xFF,
                data4[4].toInt() and 0xFF,
                data4[5].toInt() and 0xFF,
                data4[6].toInt() and 0xFF,
                data4[7].toInt() and 0xFF
            )
            guids.add(guidString)
        }
        return guids
    }

    override fun authenticateDevice(deviceAddressLong: Long, pin: String?): AuthenticateOutcome {
        val lib = try {
            Win32BluetoothNative.INSTANCE
        } catch (t: Throwable) {
            return AuthenticateOutcome.Failed(-1, "bthprops.cpl not available: ${t.message}")
        }

        val deviceInfo = Win32BluetoothNative.BLUETOOTH_DEVICE_INFO().apply {
            ullLong = deviceAddressLong
        }

        // Refresh device info
        val getInfoRes = lib.BluetoothGetDeviceInfo(null, deviceInfo)
        if (getInfoRes != 0) {
            val err = Native.getLastError()
            return AuthenticateOutcome.Failed(err, "BluetoothGetDeviceInfo failed with code $err (${BluetoothErrorMapper.mapErrorCode(err)})")
        }

        val passkeyW = pin?.let { WString(it) }
        val passkeyLen = pin?.length ?: 0

        val result = lib.BluetoothAuthenticateDevice(null, null, deviceInfo, passkeyW, passkeyLen)
        return when (result) {
            0 -> AuthenticateOutcome.Success
            1223 -> AuthenticateOutcome.Cancelled
            1244 -> AuthenticateOutcome.Failed(1244, BluetoothErrorMapper.mapErrorCode(1244))
            else -> {
                val err = if (result != 0) result else Native.getLastError()
                AuthenticateOutcome.Failed(err, BluetoothErrorMapper.mapErrorCode(err))
            }
        }
    }

    override fun setServiceState(deviceAddressLong: Long, serviceGuid: String, enable: Boolean): ServiceStateOutcome {
        val lib = try {
            Win32BluetoothNative.INSTANCE
        } catch (t: Throwable) {
            return ServiceStateOutcome.Failed(-1, "bthprops.cpl not available: ${t.message}")
        }

        val deviceInfo = Win32BluetoothNative.BLUETOOTH_DEVICE_INFO().apply {
            ullLong = deviceAddressLong
        }

        val getInfoRes = lib.BluetoothGetDeviceInfo(null, deviceInfo)
        if (getInfoRes != 0) {
            val err = Native.getLastError()
            return ServiceStateOutcome.Failed(err, "BluetoothGetDeviceInfo failed: ${BluetoothErrorMapper.mapErrorCode(err)}")
        }

        val guid = Guid.GUID.fromString(serviceGuid)
        // BLUETOOTH_SERVICE_DISABLE = 0x00, BLUETOOTH_SERVICE_ENABLE = 0x01
        val flags = if (enable) 0x01 else 0x00

        val result = lib.BluetoothSetServiceState(null, deviceInfo, guid, flags)
        return when (result) {
            0 -> ServiceStateOutcome.Success
            // ERROR_SERVICE_ALREADY_RUNNING / ERROR_ALREADY_EXISTS / 1056
            1056, 183 -> ServiceStateOutcome.AlreadyInDesiredState
            else -> {
                val err = if (result != 0) result else Native.getLastError()
                if (err == 1056 || err == 183) {
                    ServiceStateOutcome.AlreadyInDesiredState
                } else {
                    ServiceStateOutcome.Failed(err, BluetoothErrorMapper.mapErrorCode(err))
                }
            }
        }
    }
}
