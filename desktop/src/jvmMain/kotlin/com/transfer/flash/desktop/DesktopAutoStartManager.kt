package com.transfer.flash.desktop

import java.io.File

/**
 * Manages launching Flash on Windows login via the user registry Run key
 * (`HKCU\Software\Microsoft\Windows\CurrentVersion\Run`).
 */
public object DesktopAutoStartManager {

    private const val REG_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val VALUE_NAME = "Flash"

    public val isSupported: Boolean
        get() = System.getProperty("os.name", "").lowercase().contains("win")

    /** Checks whether the Flash Run key is currently registered in Windows registry. */
    public fun isAutoStartRegistered(): Boolean {
        if (!isSupported) return false
        return runCatching {
            val process = ProcessBuilder("reg.exe", "query", REG_KEY, "/v", VALUE_NAME)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            exitCode == 0 && output.contains(VALUE_NAME)
        }.getOrDefault(false)
    }

    /** Enables or disables launching Flash on Windows startup. */
    public fun setAutoStart(enabled: Boolean): Boolean {
        if (!isSupported) return false
        return runCatching {
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
    }

    /** Resolves the executable path or launch command for the running process. */
    private fun resolveLaunchCommand(): String? {
        val currentCommand = runCatching {
            ProcessHandle.current().info().command().orElse(null)
        }.getOrNull()

        if (currentCommand != null && !currentCommand.endsWith("java.exe", ignoreCase = true) && !currentCommand.endsWith("javaw.exe", ignoreCase = true)) {
            // Packaged application executable (e.g. Flash.exe from jpackage or InnoSetup)
            return "\"$currentCommand\""
        }

        // Running via JAR or IDE / gradle
        val codeSource = runCatching {
            DesktopAutoStartManager::class.java.protectionDomain.codeSource.location.toURI()
        }.getOrNull()
        val jarFile = codeSource?.let { runCatching { File(it) }.getOrNull() }
        if (jarFile != null && jarFile.isFile && jarFile.name.endsWith(".jar", ignoreCase = true)) {
            val javaw = currentCommand?.replace("java.exe", "javaw.exe") ?: "javaw.exe"
            return "\"$javaw\" -jar \"${jarFile.absolutePath}\""
        }

        return if (currentCommand != null) "\"$currentCommand\"" else null
    }
}
