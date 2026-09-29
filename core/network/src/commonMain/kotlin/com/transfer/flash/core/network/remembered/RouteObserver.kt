package com.transfer.flash.core.network.remembered

/**
 * The network layer's report of what a dial proved about an address (DR1). Implemented by
 * [RememberedRoutes]; the WebSocket networks call it so the policy never has to guess from
 * discovery sightings, which prove nothing about identity.
 */
public interface RouteObserver {
    /**
     * A dial to [host]:[port] completed TLS and the HELLO exchange, and the key that answered matches
     * the pin for [deviceId]. Called before the session is admitted, so a dial that lost a glare
     * race (the peer's own inbound session won) still counts: the address demonstrably works.
     */
    public fun onAuthenticated(deviceId: String, host: String, port: Int)

    /**
     * Something answered at [host]:[port] but its key is not the one pinned for [deviceId]: the
     * address now belongs to someone else. The route must be dropped.
     */
    public fun onIdentityMismatch(deviceId: String, host: String, port: Int)
}
