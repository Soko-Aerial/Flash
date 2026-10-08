package com.transfer.flash.desktop

import java.io.File

/**
 * Where the desktop app keeps its files (Linux plan L1, `docs/LINUX-PORT-PLAN.md` section 3.3).
 *
 * - **Windows and macOS:** unchanged, `~/.flash` for state and `~/FlashReceived` for received files.
 * - **Linux:** the XDG Base Directory rules. State lives in `$XDG_DATA_HOME/flash` (default `~/.local/share/flash`),
 *   received files in `<XDG download dir>/Flash`. A non-empty `~/.flash` is still used when the XDG folder has nothing
 *   yet, so nobody who already has data loses it.
 *
 * One state folder, not three: the engine, the stores, the log and the single-instance lock all take a single `stateDir`
 * (`DesktopEngine`). Splitting config, data and cache into three XDG folders is a wider change and is left for later;
 * the data folder is the right single home for what is mostly the identity, trust and chat database.
 *
 * Every function takes its inputs (OS name, environment, home) so the rules are testable on any OS.
 */
internal object DesktopPaths {

    fun stateDir(
        osName: String = System.getProperty("os.name", ""),
        env: Map<String, String> = System.getenv(),
        home: File = File(System.getProperty("user.home", ".")),
    ): File {
        val legacy = File(home, ".flash")
        if (!isLinux(osName)) return legacy
        val xdg = File(xdgBase(env["XDG_DATA_HOME"], File(home, ".local/share")), "flash")
        // Stable choice: data already in the XDG folder wins; otherwise existing legacy data is kept; otherwise XDG.
        return when {
            hasContent(xdg) -> xdg
            hasContent(legacy) -> legacy
            else -> xdg
        }
    }

    fun receivedRoot(
        osName: String = System.getProperty("os.name", ""),
        env: Map<String, String> = System.getenv(),
        home: File = File(System.getProperty("user.home", ".")),
    ): File =
        if (isLinux(osName)) File(downloadsDir(env, home), "Flash") else File(home, "FlashReceived")

    /**
     * The user's download folder: `XDG_DOWNLOAD_DIR` from `user-dirs.dirs` when present (it is localised, for example
     * "Téléchargements"), else `~/Downloads`.
     */
    fun downloadsDir(env: Map<String, String>, home: File): File {
        val configHome = xdgBase(env["XDG_CONFIG_HOME"], File(home, ".config"))
        val userDirs = File(configHome, "user-dirs.dirs")
        val fromFile = runCatching {
            if (userDirs.isFile) parseUserDir(userDirs.readText(), "XDG_DOWNLOAD_DIR", home) else null
        }.getOrNull()
        return fromFile ?: File(home, "Downloads")
    }

    /** Reads `KEY="$HOME/Downloads"` style lines. Returns null for a missing key or a path that is not usable. */
    fun parseUserDir(text: String, key: String, home: File): File? {
        val line = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key=") } ?: return null
        val value = line.removePrefix("$key=").trim().removeSurrounding("\"")
        val expanded = when {
            value == "\$HOME" -> home.path
            value.startsWith("\$HOME/") -> File(home, value.removePrefix("\$HOME/")).path
            else -> value
        }
        val file = File(expanded)
        // user-dirs.dirs sets the folder to $HOME itself to mean "disabled"; do not dump files straight into home.
        if (!file.isAbsolute || file.path == home.path) return null
        return file
    }

    private fun isLinux(osName: String): Boolean = osName.startsWith("Linux", ignoreCase = true)

    /** XDG: an unset, empty or relative value is ignored and the default used. */
    private fun xdgBase(value: String?, default: File): File {
        val candidate = value?.takeIf { it.isNotBlank() }?.let { File(it) }
        return if (candidate != null && candidate.isAbsolute) candidate else default
    }

    private fun hasContent(dir: File): Boolean = dir.isDirectory && !dir.list().isNullOrEmpty()
}
