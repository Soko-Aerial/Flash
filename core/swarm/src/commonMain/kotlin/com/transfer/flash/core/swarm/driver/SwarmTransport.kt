package com.transfer.flash.core.swarm.driver

import kotlinx.coroutines.flow.StateFlow

/**
 * Transport port for sending raw FSW1 frames and requesting sessions (§5.2, SW-8).
 */
public interface SwarmTransport {
    /** Sends one encoded FSW1 frame. false = the session is gone. */
    public suspend fun send(peerId: String, frame: ByteArray): Boolean

    /** Asks the host connection planner for a session with a peer. */
    public fun requestSession(peerId: String)

    /** Connected peers and the advertised features of each in HELLO. */
    public val connectedPeers: StateFlow<Map<String, Set<String>>>
}
