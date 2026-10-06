package com.transfer.flash.core.engine.swarm

import com.transfer.flash.core.swarm.driver.SwarmTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridges the swarm transport port to the host's networking session layer (§5.2, SW-8).
 */
public class NetworkSwarmTransport(
    private val sendFrameToPeer: suspend (peerId: String, frameBytes: ByteArray) -> Boolean,
    private val requestSessionForPeer: (peerId: String) -> Unit,
) : SwarmTransport {

    private val _connectedPeers = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    override val connectedPeers: StateFlow<Map<String, Set<String>>> = _connectedPeers.asStateFlow()

    public fun updateConnectedPeers(peers: Map<String, Set<String>>) {
        _connectedPeers.value = peers
    }

    override suspend fun send(peerId: String, frame: ByteArray): Boolean {
        return sendFrameToPeer(peerId, frame)
    }

    override fun requestSession(peerId: String) {
        requestSessionForPeer(peerId)
    }
}
