package com.transfer.flash

import android.content.Context
import com.transfer.flash.ui.hub.FlashHubDraftStore
import com.transfer.flash.ui.hub.FlashHubUiPrefs

/**
 * What the Hub tab remembers on this phone (UI-058): the segment last used and whether the one-time "Transfers moved" tip was
 * dismissed. Two plain preferences in the app's private storage; nothing secret, nothing synced, and a read that fails
 * simply gives the defaults. It also keeps unsent announcement drafts (plain text the person typed, capped in size) so a failed
 * or interrupted post survives a restart; [clearDrafts] runs on sign-out.
 */
internal class HubUiPrefs(context: Context) : FlashHubUiPrefs, FlashHubDraftStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun lastSegment(): String? = runCatching { prefs.getString(KEY_SEGMENT, null) }.getOrNull()

    override fun setLastSegment(name: String) {
        prefs.edit().putString(KEY_SEGMENT, name).apply()
    }

    override fun movedTipSeen(): Boolean = runCatching { prefs.getBoolean(KEY_TIP, false) }.getOrDefault(false)

    override fun setMovedTipSeen() {
        prefs.edit().putBoolean(KEY_TIP, true).apply()
    }

    override fun load(key: String): Pair<String, String>? = runCatching {
        val title = prefs.getString(draftKey(key, TITLE), null)
        val body = prefs.getString(draftKey(key, BODY), null)
        if (title == null && body == null) null else (title.orEmpty() to body.orEmpty())
    }.getOrNull()

    override fun save(key: String, title: String, body: String) {
        prefs.edit()
            .putString(draftKey(key, TITLE), title.take(DRAFT_MAX))
            .putString(draftKey(key, BODY), body.take(DRAFT_MAX))
            .apply()
    }

    override fun clear(key: String) {
        prefs.edit().remove(draftKey(key, TITLE)).remove(draftKey(key, BODY)).apply()
    }

    /** Forgets every draft (sign-out: drafts belong to the organisation the person leaves). */
    fun clearDrafts() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(DRAFT_PREFIX) }.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun draftKey(key: String, part: String) = "$DRAFT_PREFIX$part.$key"

    private companion object {
        const val DRAFT_PREFIX = "hub_draft."
        const val TITLE = "title"
        const val BODY = "body"
        const val DRAFT_MAX = 8_000
        const val FILE = "flash_hub_ui"
        const val KEY_SEGMENT = "hub_last_segment"
        const val KEY_TIP = "hub_moved_tip_seen"
    }
}
