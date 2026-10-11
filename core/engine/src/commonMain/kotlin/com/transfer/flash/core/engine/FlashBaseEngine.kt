package com.transfer.flash.core.engine

import com.transfer.flash.core.discovery.FlashDiscovery
import com.transfer.flash.core.network.FlashNetwork
import com.transfer.flash.core.security.trust.FlashTrustStore

/**
 * Lightweight foundation interface representing the identity, discovery, network transport,
 * and trust store core of Flash.
 *
 * Decouples the fundamental transport and peer discovery capabilities from optional higher-level
 * subsystems (such as chat, file transfer, PTT, calling, and swarm). Subsystems attach onto this
 * base engine via clean pluggable attachment seams.
 *
 * Implements [AutoCloseable] to cleanly tear down network sockets and discovery services upon termination.
 */
public interface FlashBaseEngine : AutoCloseable {
    /** Continuous local peer discovery and service advertising across radios. */
    public val discovery: FlashDiscovery

    /** Network connection manager, active sessions, and resilience engine. */
    public val network: FlashNetwork

    /** Cryptographic trust store tracking verified and paired devices. */
    public val trustStore: FlashTrustStore

    /**
     * Updates the local device friendly name across the network transport and discovery advertising.
     * Returns false when the name is blank or the operation fails.
     */
    public fun updateFriendlyName(name: String): Boolean = false
}
