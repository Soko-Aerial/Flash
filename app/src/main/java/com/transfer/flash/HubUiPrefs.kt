package com.transfer.flash

import android.content.Context
import com.transfer.flash.ui.hub.FlashHubUiPrefs

/**
 * What the Hub tab remembers on this phone (UI-058): the segment last used and whether the one-time "Transfers moved" tip was
 * dismissed. Two plain preferences in the app's private storage; nothing secret, nothing synced, and a read that fails
 * simply gives the defaults.
 */
internal class HubUiPrefs(context: Context) : FlashHubUiPrefs {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun lastSegment(): String? = runCatching { prefs.getString(KEY_SEGMENT, null) }.getOrNull()

    override fun setLastSegment(name: String) {
        prefs.edit().putString(KEY_SEGMENT, name).apply()
    }

    override fun movedTipSeen(): Boolean = runCatching { prefs.getBoolean(KEY_TIP, false) }.getOrDefault(false)

    override fun setMovedTipSeen() {
        prefs.edit().putBoolean(KEY_TIP, true).apply()
    }

    private companion object {
        const val FILE = "flash_hub_ui"
        const val KEY_SEGMENT = "hub_last_segment"
        const val KEY_TIP = "hub_moved_tip_seen"
    }
}
