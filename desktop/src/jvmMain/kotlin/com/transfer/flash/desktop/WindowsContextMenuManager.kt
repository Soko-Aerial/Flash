@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import com.transfer.flash.core.common.logging.FlashLog
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Manages Windows Explorer shell context menu integration.
 *
 * Adds "Send with Flash" to Windows File Explorer context menus for files and directories:
 * - `HKCU\Software\Classes\*\shell\Flash` (files)
 * - `HKCU\Software\Classes\Directory\shell\Flash` (directories)
 *
 * Operates strictly in `HKCU`, requiring NO administrative privileges.
 */
public object WindowsContextMenuManager {

    private const val TAG = "CONTEXT_MENU"
    private const val REG_KEY_FILE = "HKCU\\Software\\Classes\\*\\shell\\Flash"
    private const val REG_KEY_DIR = "HKCU\\Software\\Classes\\Directory\\shell\\Flash"
    private const val MENU_LABEL = "Send with Flash"

    public val isSupported: Boolean
        get() = System.getProperty("os.name", "").lowercase().contains("win")

    /**
     * Checks whether the Flash shell context menu is registered.
     */
    public fun isContextMenuRegistered(): Boolean {
        if (!isSupported) return false
        return runCatching {
            val process = ProcessBuilder("reg.exe", "query", "$REG_KEY_FILE\\command", "/ve")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor() == 0 && output.contains("%1")
        }.getOrDefault(false)
    }

    /**
     * Enables or disables the "Send with Flash" context menu in Windows Explorer.
     *
     * Uses an atomic .reg file import to guarantee proper quote escaping on all Windows versions.
     */
    public fun setContextMenuEnabled(
        enabled: Boolean,
        stateDir: File = File(System.getProperty("user.home", "."), ".flash"),
    ): Boolean {
        if (!isSupported) return false
        return runCatching {
            if (enabled) {
                val launchCommand = resolveLaunchCommand(stateDir) ?: return false
                val iconPath = ensureIconFile(stateDir)

                val regFile = File.createTempFile("flash_ctx_", ".reg")
                try {
                    val fileKey = toRegFileRoot(REG_KEY_FILE)
                    val dirKey = toRegFileRoot(REG_KEY_DIR)
                    val regContent = buildString {
                        appendLine("Windows Registry Editor Version 5.00")
                        appendLine()
                        // Register for files (*)
                        appendLine("[$fileKey]")
                        appendLine("@=\"$MENU_LABEL\"")
                        if (iconPath != null) {
                            appendLine("\"Icon\"=\"${escapeForReg(iconPath)}\"")
                        }
                        appendLine()
                        appendLine("[$fileKey\\command]")
                        appendLine("@=\"${escapeForReg(launchCommand)}\"")
                        appendLine()
                        // Register for directories
                        appendLine("[$dirKey]")
                        appendLine("@=\"$MENU_LABEL\"")
                        if (iconPath != null) {
                            appendLine("\"Icon\"=\"${escapeForReg(iconPath)}\"")
                        }
                        appendLine()
                        appendLine("[$dirKey\\command]")
                        appendLine("@=\"${escapeForReg(launchCommand)}\"")
                    }
                    regFile.writeText(regContent, Charsets.UTF_8)

                    val process = ProcessBuilder("reg.exe", "import", regFile.absolutePath)
                        .redirectErrorStream(true)
                        .start()
                    val output = process.inputStream.bufferedReader().use { it.readText() }
                    val exitCode = process.waitFor()
                    if (exitCode == 0) {
                        FlashLog.i(TAG, "Registered Windows context menu successfully via registry import.")
                        true
                    } else {
                        FlashLog.e(TAG, "Failed to import registry file (code $exitCode): $output")
                        false
                    }
                } finally {
                    regFile.delete()
                }
            } else {
                val del1 = ProcessBuilder("reg.exe", "delete", REG_KEY_FILE, "/f").start().waitFor()
                val del2 = ProcessBuilder("reg.exe", "delete", REG_KEY_DIR, "/f").start().waitFor()
                FlashLog.i(TAG, "Removed Windows context menu.")
                del1 == 0 && del2 == 0
            }
        }.getOrDefault(false)
    }

    private fun escapeForReg(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun toRegFileRoot(key: String): String {
        return if (key.startsWith("HKCU\\")) {
            "HKEY_CURRENT_USER\\" + key.removePrefix("HKCU\\")
        } else {
            key
        }
    }

    /**
     * Resolves the command string to put in the registry command value.
     *
     * Windows Explorer context menu handlers require an executable binary (.exe).
     * In development mode (e.g. running via `./gradlew :desktop:run`), a native Java `@argfile`
     * with `javaw.exe` is used to execute the exact current classpath with no character limits
     * and zero console window flashing.
     */
    public fun resolveLaunchCommand(stateDir: File): String? {
        val currentCommand = runCatching {
            ProcessHandle.current().info().command().orElse(null)
        }.getOrNull()

        // 1. Packaged executable (Flash.exe) currently running
        if (currentCommand != null && !currentCommand.endsWith("java.exe", true) && !currentCommand.endsWith("javaw.exe", true)) {
            return "\"$currentCommand\" \"%1\""
        }

        // 2. Standalone Runnable JAR
        val codeSource = runCatching {
            WindowsContextMenuManager::class.java.protectionDomain.codeSource.location.toURI()
        }.getOrNull()
        val jarFile = codeSource?.let { runCatching { File(it) }.getOrNull() }
        if (jarFile != null && jarFile.isFile && jarFile.name.endsWith(".jar", true) && jarFile.name.contains("windows-x64")) {
            val javaw = currentCommand?.replace("java.exe", "javaw.exe") ?: "javaw.exe"
            return "\"$javaw\" -jar \"${jarFile.absolutePath}\" \"%1\""
        }

        // 3. Development / Classpath mode (running via Gradle or IDE):
        // Write a standard Java argument file (`@flash-args.txt`) so javaw executes with full classpath
        // without cmd.exe line-length limits and without quote-stripping errors.
        return runCatching {
            stateDir.mkdirs()
            val javaw = currentCommand?.replace("java.exe", "javaw.exe") ?: "javaw.exe"
            val classPath = System.getProperty("java.class.path", "")
            val argsFile = File(stateDir, "flash-args.txt")
            argsFile.writeText(
                "-cp\n" +
                "\"${classPath.replace("\\", "\\\\")}\"\n" +
                "com.transfer.flash.desktop.DesktopMainKt\n"
            )
            "\"$javaw\" \"@${argsFile.absolutePath}\" \"%1\""
        }.getOrNull()
    }

    /**
     * Generates an ICO file at `~/.flash/flash.ico` from the high-resolution app icon.
     */
    public fun ensureIconFile(stateDir: File): String? {
        val iconFile = File(stateDir, "flash.ico")
        if (iconFile.exists() && iconFile.length() > 0) return iconFile.absolutePath
        return runCatching {
            stateDir.mkdirs()
            val img = DesktopTaskbarBadgeManager.renderIcon(32, badgeCount = 0)
            val baos = ByteArrayOutputStream()
            ImageIO.write(img, "PNG", baos)
            val pngBytes = baos.toByteArray()

            val icoBytes = ByteArrayOutputStream()
            val dos = DataOutputStream(icoBytes)
            dos.writeShort(java.lang.Short.reverseBytes(0.toShort()).toInt())
            dos.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
            dos.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
            dos.writeByte(32)
            dos.writeByte(32)
            dos.writeByte(0)
            dos.writeByte(0)
            dos.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
            dos.writeShort(java.lang.Short.reverseBytes(32.toShort()).toInt())
            dos.writeInt(java.lang.Integer.reverseBytes(pngBytes.size))
            dos.writeInt(java.lang.Integer.reverseBytes(22))
            dos.flush()
            icoBytes.write(pngBytes)
            iconFile.writeBytes(icoBytes.toByteArray())
            iconFile.absolutePath
        }.getOrNull()
    }
}
