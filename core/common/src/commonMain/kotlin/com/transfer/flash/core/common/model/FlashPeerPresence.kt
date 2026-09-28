package com.transfer.flash.core.common.model

/**
 * Live presence state of a peer device on the network.
 *
 * The owner's three states (PRESENCE-CONNECTIONS-PLAN §3.1, UI doc `chat-screen.md` UI-030b) map as
 * **Connected = [Online]**, **Online = [Reachable]**, **Offline = [Offline]**. [Online] keeps its
 * pre-PC3 name because this enum is published API, and a rename that swapped meanings would compile
 * silently at every missed call site.
 */
public enum class FlashPeerPresence {
    /** I hold a live session with this peer. Shown as a solid dot labelled "Connected". */
    Online,
    Offline,
    Typing,
    /** A session just dropped and recovery is under way (ERROR-031 grace). */
    Connecting,

    /**
     * No session, but the peer is seen right now (PC3: discovery; PC4: mutual contacts). A send
     * dials on demand. Shown as a ring labelled "Online".
     */
    Reachable,
}
