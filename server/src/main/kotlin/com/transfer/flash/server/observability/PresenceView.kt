package com.transfer.flash.server.observability

/**
 * Read-only view of who is connected to the relay right now. Implemented by
 * [com.transfer.flash.server.relay.RelayConnectionPool]; consumed by the directory (`online` flags) and the admin
 * dashboard. It exposes no session object, so a consumer can not send to or close a connection through it.
 */
interface PresenceView {
    fun isOnline(deviceId: String): Boolean

    /** A snapshot of the connected device ids (a point in time: it may be stale a moment later). */
    fun onlineDeviceIds(): Set<String>
}
