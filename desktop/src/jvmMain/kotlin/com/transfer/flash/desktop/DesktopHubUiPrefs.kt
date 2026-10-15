package com.transfer.flash.desktop

import com.transfer.flash.ui.hub.FlashHubUiPrefs
import java.io.File
import java.util.Properties

/**
 * What the Hub tab remembers on this PC (UI-058): the segment last used and whether the one-time "Transfers moved" tip was
 * dismissed. A small properties file next to the other desktop state; nothing secret. A file that cannot be read or written
 * gives the defaults and never throws into the UI.
 */
internal class DesktopHubUiPrefs(stateDir: File = DesktopPaths.stateDir()) : FlashHubUiPrefs {
    private val file = File(stateDir, "hub-ui.properties")
    private val lock = Any()

    private fun load(): Properties = Properties().also { props ->
        runCatching { if (file.isFile) file.inputStream().use { props.load(it) } }
    }

    private fun save(props: Properties) {
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { props.store(it, "Flash Hub tab") }
        }
    }

    override fun lastSegment(): String? = synchronized(lock) { load().getProperty(KEY_SEGMENT) }

    override fun setLastSegment(name: String) = synchronized(lock) {
        save(load().apply { setProperty(KEY_SEGMENT, name) })
    }

    override fun movedTipSeen(): Boolean = synchronized(lock) { load().getProperty(KEY_TIP, "false").toBoolean() }

    override fun setMovedTipSeen() = synchronized(lock) {
        save(load().apply { setProperty(KEY_TIP, "true") })
    }

    private companion object {
        const val KEY_SEGMENT = "hub_last_segment"
        const val KEY_TIP = "hub_moved_tip_seen"
    }
}
