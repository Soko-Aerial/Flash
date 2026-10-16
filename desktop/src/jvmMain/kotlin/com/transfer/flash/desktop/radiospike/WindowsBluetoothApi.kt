package com.transfer.flash.desktop.radiospike

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
        val countRef = IntByReference(0)
        // First probe call to query required count
        val firstRet = lib.BluetoothEnumerateInstalledServices(null, deviceInfo, countRef, null)
        val count = countRef.value
        if (count <= 0) return emptyList()

        val mem = Memory((count * 16).toLong())
        val secondRet = lib.BluetoothEnumerateInstalledServices(null, deviceInfo, countRef, mem)
        if (secondRet != 0) return emptyList()

        val list = mutableListOf<String>()
        val actualCount = countRef.value
        for (i in 0 until actualCount) {
            val offset = (i * 16).toLong()
            val d1 = mem.getInt(offset)
            val d2 = mem.getShort(offset + 4).toInt() and 0xFFFF
            val d3 = mem.getShort(offset + 6).toInt() and 0xFFFF
            val d4_0 = mem.getByte(offset + 8).toInt() and 0xFF
            val d4_1 = mem.getByte(offset + 9).toInt() and 0xFF
            val d4_2 = mem.getByte(offset + 10).toInt() and 0xFF
            val d4_3 = mem.getByte(offset + 11).toInt() and 0xFF
            val d4_4 = mem.getByte(offset + 12).toInt() and 0xFF
            val d4_5 = mem.getByte(offset + 13).toInt() and 0xFF
            val d4_6 = mem.getByte(offset + 14).toInt() and 0xFF
            val d4_7 = mem.getByte(offset + 15).toInt() and 0xFF

            val guidStr = String.format(
                "%08X-%04X-%04X-%02X%02X-%02X%02X%02X%02X%02X%02X",
                d1, d2, d3, d4_0, d4_1, d4_2, d4_3, d4_4, d4_5, d4_6, d4_7
            )
            list.add(guidStr)
        }
        return list
    }

    override fun authenticateDevice(deviceAddressLong: Long, pin: String?): AuthenticateOutcome {
        val lib = try {
            Win32BluetoothNative.INSTANCE
        } catch (t: Throwable) {
            return AuthenticateOutcome.Failed(-1, "Native library bthprops.cpl unavailable: ${t.message}")
        }

        val deviceInfo = Win32BluetoothNative.BLUETOOTH_DEVICE_INFO().apply {
            ullLong = deviceAddressLong
        }
        val passkeyW = if (pin != null) WString(pin) else null
        val passkeyLen = pin?.length ?: 0

        val ret = lib.BluetoothAuthenticateDevice(null, null, deviceInfo, passkeyW, passkeyLen)
        return when (ret) {
            0 -> AuthenticateOutcome.Success
            1244 -> AuthenticateOutcome.Failed(ret, "Device rejected authentication credentials.")
            1223 -> AuthenticateOutcome.Cancelled
            87 -> AuthenticateOutcome.Failed(ret, "Invalid parameter passed to authentication.")
            else -> AuthenticateOutcome.Failed(ret, BluetoothErrorMapper.mapErrorCode(ret))
        }
    }

    override fun setServiceState(deviceAddressLong: Long, serviceGuid: String, enable: Boolean): ServiceStateOutcome {
        val lib = try {
            Win32BluetoothNative.INSTANCE
        } catch (t: Throwable) {
            return ServiceStateOutcome.Failed(-1, "Native library bthprops.cpl unavailable: ${t.message}")
        }

        val deviceInfo = Win32BluetoothNative.BLUETOOTH_DEVICE_INFO().apply {
            ullLong = deviceAddressLong
        }
        val guid = Guid.GUID.fromString(serviceGuid)
        val flags = if (enable) 0x01 else 0x00

        val ret = lib.BluetoothSetServiceState(null, deviceInfo, guid, flags)
        return when (ret) {
            0 -> ServiceStateOutcome.Success
            87 -> ServiceStateOutcome.AlreadyInDesiredState
            else -> ServiceStateOutcome.Failed(ret, BluetoothErrorMapper.mapErrorCode(ret))
        }
    }
}

/**
 * Recording fake for unit testing.
 * Proves that read-only runs invoke zero mutating/active functions,
 * and allows unit testing of active flows without any hardware.
 */
public class FakeWindowsBluetoothApi : WindowsBluetoothApi {

    public sealed interface CallRecord {
        public data class ListRemembered(val timestamp: Long = System.currentTimeMillis()) : CallRecord
        public data class SearchDevices(val params: BluetoothSearchParams) : CallRecord
        public data class EnumerateServices(val address: Long) : CallRecord
        public data class Authenticate(val address: Long, val pin: String?) : CallRecord
        public data class SetServiceState(val address: Long, val guid: String, val enable: Boolean) : CallRecord
    }

    public val recordedCalls: MutableList<CallRecord> = mutableListOf()

    public val rememberedDevices: MutableList<DiscoveredBluetoothDevice> = mutableListOf()
    public val inquiryDevices: MutableList<DiscoveredBluetoothDevice> = mutableListOf()
    public val servicesMap: MutableMap<Long, List<String>> = mutableMapOf()

    public var nextAuthenticateOutcome: AuthenticateOutcome = AuthenticateOutcome.Success
    public var nextServiceStateOutcome: ServiceStateOutcome = ServiceStateOutcome.Success

    override fun listRememberedDevices(): List<DiscoveredBluetoothDevice> {
        recordedCalls.add(CallRecord.ListRemembered())
        return rememberedDevices.toList()
    }

    override fun searchDevices(params: BluetoothSearchParams): List<DiscoveredBluetoothDevice> {
        recordedCalls.add(CallRecord.SearchDevices(params))
        return inquiryDevices.toList()
    }

    override fun enumerateInstalledServices(deviceAddressLong: Long): List<String> {
        recordedCalls.add(CallRecord.EnumerateServices(deviceAddressLong))
        return servicesMap[deviceAddressLong] ?: emptyList()
    }

    override fun authenticateDevice(deviceAddressLong: Long, pin: String?): AuthenticateOutcome {
        recordedCalls.add(CallRecord.Authenticate(deviceAddressLong, pin))
        return nextAuthenticateOutcome
    }

    override fun setServiceState(deviceAddressLong: Long, serviceGuid: String, enable: Boolean): ServiceStateOutcome {
        recordedCalls.add(CallRecord.SetServiceState(deviceAddressLong, serviceGuid, enable))
        return nextServiceStateOutcome
    }

    public fun verifyNoMutationsCalled() {
        val mutatingCalls = recordedCalls.filterIsInstance<CallRecord.Authenticate>() +
                recordedCalls.filterIsInstance<CallRecord.SetServiceState>()
        check(mutatingCalls.isEmpty()) {
            "Safety violation: Mutating Bluetooth API calls were recorded: $mutatingCalls"
        }
    }
}
