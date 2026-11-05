package com.transfer.flash.desktop

import java.io.File

/**
 * Manages launching Flash on login:
 * - Windows: user registry Run key (`HKCU\Software\Microsoft\Windows\CurrentVersion\Run`).
 * - Linux: FreeDesktop XDG Autostart (`~/.config/autostart/flash.desktop`).
 */
public object DesktopAutoStartManager {

    private const val REG_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val VALUE_NAME = "Flash"

    public val isSupported: Boolean
        get() {
            val os = System.getProperty("os.name", "").lowercase()
            return os.contains("win") || os.contains("linux")
        }

    /** Checks whether Flash is registered for autostart on system login. */
    public fun isAutoStartRegistered(): Boolean {
        if (!isSupported) return false
        val os = System.getProperty("os.name", "").lowercase()
        return if (os.contains("win")) {
            runCatching {
                val process = ProcessBuilder("reg.exe", "query", REG_KEY, "/v", VALUE_NAME)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val exitCode = process.waitFor()
                exitCode == 0 && output.contains(VALUE_NAME)
            }.getOrDefault(false)
        } else if (os.contains("linux")) {
            linuxAutostartFile().isFile
        } else {
            false
        }
    }

    /** Enables or disables launching Flash on startup. */
    public fun setAutoStart(enabled: Boolean): Boolean {
        if (!isSupported) return false
        val os = System.getProperty("os.name", "").lowercase()
        return if (os.contains("win")) {
            runCatching {
                if (enabled) {
                    val command = resolveLaunchCommand() ?: return false
                    val process = ProcessBuilder(
                        "reg.exe", "add", REG_KEY, "/v", VALUE_NAME, "/t", "REG_SZ", "/d", command, "/f"
                    ).start()
                    process.waitFor() == 0
                } else {
                    val process = ProcessBuilder(
                        "reg.exe", "delete", REG_KEY, "/v", VALUE_NAME, "/f"
                    ).start()
                    process.waitFor() == 0
                }
            }.getOrDefault(false)
        } else if (os.contains("linux")) {
            runCatching {
                val file = linuxAutostartFile()
                if (enabled) {
                    val command = resolveLaunchCommand() ?: return false
                    file.parentFile?.mkdirs()
                    file.writeText(
                        """
                        |[Desktop Entry]
                        |Type=Application
                        |Name=Flash
                        |Comment=Offline LAN peer-to-peer file transfer & messaging
                        |Exec=$command --minimized
                        |Icon=flash
                        |Terminal=false
                        |Categories=Network;FileTransfer;InstantMessaging;
                        |X-GNOME-Autostart-enabled=true
                        |
                        """.trimMargin()
                    )
                    true
                } else {
                    if (file.exists()) file.delete() else true
                }
            }.getOrDefault(false)
        } else {
            false
        }
    }

    private fun linuxAutostartFile(): File {
        val configHome = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            ?: (System.getProperty("user.home") + "/.config")
        return File(File(configHome, "autostart"), "flash.desktop")
    }

    /** Resolves the executable path or launch command for the running process. */
    private fun resolveLaunchCommand(): String? {
        val currentCommand = runCatching {
            ProcessHandle.current().info().command().orElse(null)
        }.getOrNull()

        if (currentCommand != null && !currentCommand.endsWith("java.exe", ignoreCase = true) && !currentCommand.endsWith("javaw.exe", ignoreCase = true) && !currentCommand.endsWith("java", ignoreCase = true)) {
            // Packaged application executable (e.g. Flash.exe from jpackage or InnoSetup, or Linux Flash binary)
            return "\"$currentCommand\""
        }

        // Running via JAR or IDE / gradle
        val codeSource = runCatching {
            DesktopAutoStartManager::class.java.protectionDomain.codeSource.location.toURI()
        }.getOrNull()
        val jarFile = codeSource?.let { runCatching { File(it) }.getOrNull() }
        if (jarFile != null && jarFile.isFile && jarFile.name.endsWith(".jar", ignoreCase = true)) {
            val javaw = currentCommand?.replace("java.exe", "javaw.exe") ?: currentCommand ?: "java"
            return "\"$javaw\" -jar \"${jarFile.absolutePath}\""
        }

        return if (currentCommand != null) "\"$currentCommand\"" else null
    }
}
