package com.transfer.flash.core.radio.link

import com.transfer.flash.core.radio.link.linux.LinuxRadioDiscovery
import com.transfer.flash.core.radio.link.windows.WindowsRadioDiscovery

/**
 * Factory for OS-appropriate [RadioDeviceDiscovery] on Desktop JVM (Windows vs Linux).
 */
public object DesktopRadioDiscovery {

    public fun forCurrentOs(
        serialPortCatalog: SerialPortCatalog = JvmSerialPortCatalog(),
    ): RadioDeviceDiscovery {
        val os = System.getProperty("os.name", "").lowercase()
        return if (os.contains("win")) {
            WindowsRadioDiscovery(serialPortCatalog = serialPortCatalog)
        } else {
            LinuxRadioDiscovery(serialPortCatalog = serialPortCatalog)
        }
    }
}
