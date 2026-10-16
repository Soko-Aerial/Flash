package com.transfer.flash.desktop

import java.io.File
import java.util.Properties

/**
 * The width the person gave the chat list by dragging the splitter (AD-2). One number in `layout.properties` next to the other
 * desktop state; nothing secret. A missing, unreadable or non-numeric value means "no preference" (the automatic width), and a
 * write that fails is ignored: layout memory must never break the window.
 */
internal class DesktopPaneLayoutPrefs(stateDir: File = DesktopPaths.stateDir()) {
    private val file = File(stateDir, "layout.properties")
    private val lock = Any()

    fun listPaneWidthDp(): Float? = synchronized(lock) {
        val props = Properties()
        runCatching { if (file.isFile) file.inputStream().use { props.load(it) } }
        props.getProperty(KEY)?.toFloatOrNull()?.takeIf { it.isFinite() }
    }

    /** Saves [widthDp], or forgets the preference when it is null (double click on the splitter). */
    fun setListPaneWidthDp(widthDp: Float?) = synchronized(lock) {
        runCatching {
            val props = Properties()
            if (file.isFile) file.inputStream().use { props.load(it) }
            if (widthDp == null || !widthDp.isFinite()) props.remove(KEY) else props.setProperty(KEY, widthDp.toString())
            file.parentFile?.mkdirs()
            file.outputStream().use { props.store(it, "Flash desktop layout") }
        }
        Unit
    }

    private companion object {
        const val KEY = "list_pane_width_dp"
    }
}
