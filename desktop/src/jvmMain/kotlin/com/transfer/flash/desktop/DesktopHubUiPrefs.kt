package com.transfer.flash.desktop

import com.transfer.flash.ui.hub.FlashHubDraftStore
import com.transfer.flash.ui.hub.FlashHubUiPrefs
import java.io.File
import java.util.Properties

/**
 * What the Hub tab remembers on this PC (UI-058): the segment last used and whether the one-time "Transfers moved" tip was
 * dismissed. A small properties file next to the other desktop state; nothing secret. A file that cannot be read or written
 * gives the defaults and never throws into the UI. It also keeps unsent announcement drafts (capped in size) so a failed or
 * interrupted post survives a restart; [clearDrafts] runs on sign-out.
 */
internal class DesktopHubUiPrefs(stateDir: File = DesktopPaths.stateDir()) : FlashHubUiPrefs, FlashHubDraftStore {
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

    override fun load(key: String): Pair<String, String>? = synchronized(lock) {
        val props = load()
        val title = props.getProperty(draftKey(key, TITLE))
        val body = props.getProperty(draftKey(key, BODY))
        if (title == null && body == null) null else (title.orEmpty() to body.orEmpty())
    }

    override fun save(key: String, title: String, body: String) = synchronized(lock) {
        save(load().apply {
            setProperty(draftKey(key, TITLE), title.take(DRAFT_MAX))
            setProperty(draftKey(key, BODY), body.take(DRAFT_MAX))
        })
    }

    override fun clear(key: String) = synchronized(lock) {
        save(load().apply {
            remove(draftKey(key, TITLE))
            remove(draftKey(key, BODY))
        })
    }

    /** Forgets every draft (sign-out: drafts belong to the organisation the person leaves). */
    fun clearDrafts() = synchronized(lock) {
        save(load().apply { stringPropertyNames().filter { it.startsWith(DRAFT_PREFIX) }.forEach { remove(it) } })
    }

    private fun draftKey(key: String, part: String) = "$DRAFT_PREFIX$part.$key"

    private companion object {
        const val DRAFT_PREFIX = "hub_draft."
        const val TITLE = "title"
        const val BODY = "body"
        const val DRAFT_MAX = 8_000
        const val KEY_SEGMENT = "hub_last_segment"
        const val KEY_TIP = "hub_moved_tip_seen"
    }
}
