package com.transfer.flash

/**
 * Which URIs the exported share target ([MainActivity], `ACTION_SEND` / `ACTION_SEND_MULTIPLE` for any MIME type) may open
 * on behalf of the app that started it (R-05, sweep 2026-10-09).
 *
 * Any installed app can start the share target with an `EXTRA_STREAM` of its choosing. The activity then opens that URI
 * with ITS OWN permissions, so a `file:///data/user/0/<package>/files/...` URI or a `content://<package>.fileprovider/...`
 * URI would make Flash read (and offer to send to a peer) its own private files: a confused deputy. Pure and
 * string-based so it can be tested on the JVM without Android's `Uri`.
 */
internal object ShareUriPolicy {

    /**
     * True when a shared URI with this [scheme] and [authority] is a normal "someone else's content" share.
     *
     *  - only `content:` is accepted; `file:` (our private storage, or anyone's path), `android.resource:`, `http(s):`
     *    and a missing scheme are refused;
     *  - a `content:` URI whose authority is ours ([ownPackage] or anything under it, e.g. `<package>.fileprovider`) is
     *    refused: no legitimate sharer hands Flash its own provider's URI.
     */
    fun accepts(scheme: String?, authority: String?, ownPackage: String): Boolean {
        if (scheme == null || !scheme.equals("content", ignoreCase = true)) return false
        val auth = authority?.trim()?.lowercase() ?: return false
        if (auth.isEmpty()) return false
        // A content authority may carry userinfo ("0@media"); compare the host part only.
        val host = auth.substringAfterLast('@')
        val own = ownPackage.lowercase()
        return host != own && !host.startsWith("$own.")
    }
}
