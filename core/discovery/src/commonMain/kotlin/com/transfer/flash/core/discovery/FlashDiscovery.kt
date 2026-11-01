package com.transfer.flash.core.discovery

import com.transfer.flash.core.common.result.FlashResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Public discovery interface for peer advertising and scanning across local network mediums.
 */
public interface FlashDiscovery {
    public val state: StateFlow<FlashDiscoveryState>
    public val discoveredEndpoints: StateFlow<List<FlashDiscoveredEndpoint>>

    public suspend fun startDiscovery(): FlashResult<Unit>
    public suspend fun stopDiscovery(): FlashResult<Unit>
    public suspend fun startAdvertising(listenPort: Int): FlashResult<Unit>
    public suspend fun stopAdvertising(): FlashResult<Unit>
    public suspend fun stopAll(): FlashResult<Unit>

    /**
     * Informs discovery of the display power state: `false` on screen-off, `true` on screen-on.
     *
     * **Wired on Android since 2026-11-01** (wave 3, Stream G, `OPT-O10-01`): `DiscoveryEngineHolder`
     * calls it from the `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF` receivers and again when a transfer
     * finishes with the screen already off. **Not wired on desktop** - that host is still inert, and
     * the "minimised to tray" case is `O19-07` in stream E.
     *
     * **The mode it selects is NOT discovery-only.** Downscaling uses `FlashDiscoveryMode.ECO`, which
     * goes through `DiscoveryModePolicy` *and* `ConnectionModePolicy` / `ConnectionStrategy.ECO`
     * (`limitsSessions = true`, so idle sessions that were dialled get parked). The caller — not this
     * layer — is responsible for not calling it while a call or a transfer is in flight.
     *
     * The restore path re-applies the user's last [setMode] choice, not a fixed default, so a mode the
     * operator picked before screen-off is honoured on screen-on.
     */
    // TODO(wave-4, stream E): wire the desktop host (minimised to tray). See O19-07.
    public fun setScreenActive(active: Boolean) {}
}
