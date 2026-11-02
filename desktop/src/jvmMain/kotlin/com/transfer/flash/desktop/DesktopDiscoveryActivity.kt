package com.transfer.flash.desktop

import com.transfer.flash.core.discovery.core.FlashDiscoveryMode

/** The desktop caller must preserve GHOST's non-advertising intent and both workload edges. */
internal fun desktopDiscoveryScreenActive(
    windowActive: Boolean,
    preferredMode: FlashDiscoveryMode,
    hasActiveTransfer: Boolean,
    hasActiveCall: Boolean,
    hasActivePtt: Boolean,
): Boolean = windowActive || preferredMode == FlashDiscoveryMode.GHOST ||
    hasActiveTransfer || hasActiveCall || hasActivePtt
