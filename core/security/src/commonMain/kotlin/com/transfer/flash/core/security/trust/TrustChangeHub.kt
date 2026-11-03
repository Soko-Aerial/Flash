package com.transfer.flash.core.security.trust

import kotlin.concurrent.Volatile

/**
 * Process-local notification that a peer's trust state changed in a way that makes any cached TLS
 * session state for it unsafe to reuse.
 *
 * `FlashTlsContextFactory` caches one [javax.net.ssl.SSLContext] per peer so TLS 1.3 session
 * resumption survives reconnects. A resumed session does **not** run the trust manager, so a cached
 * context can outlive "Forget", a pin replacement or a withdrawn vouch for up to the 24 h session
 * lifetime. `:core:security` cannot depend on `:core:network` (the network layer already depends on
 * security), so the trust stores publish here and the network layer subscribes.
 *
 * Registration is expected during host startup, before connections are served. Publishing may happen
 * on any thread; listeners are invoked from a snapshot and a failing listener never blocks the trust
 * change itself.
 */
public object TrustChangeHub {

    @Volatile
    private var listeners: List<(deviceId: String) -> Unit> = emptyList()

    /** Adds [listener]. Register once during startup; there is no removal on shutdown. */
    public fun addListener(listener: (deviceId: String) -> Unit) {
        listeners = listeners + listener
    }

    /** Removes a previously added [listener]; intended for tests and host teardown. */
    public fun removeListener(listener: (deviceId: String) -> Unit) {
        listeners = listeners - listener
    }

    /**
     * Announces that [deviceId]'s pin, trust or vouch state changed. Subscribers must drop any state
     * that was derived from the old pin, including a resumable TLS session.
     */
    public fun publish(deviceId: String) {
        val snapshot = listeners
        for (listener in snapshot) {
            runCatching { listener(deviceId) }
        }
    }
}
