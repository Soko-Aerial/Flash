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
     * **Nothing calls this yet.** As of 2026-10-31 no screen-state receiver exists in either host
     * (the Android `app` module or the `desktop` module), so the seam is unwired; the screen-state
     * receiver is Stream G's wiring task in wave 3. Until then this is inert in every shipped build.
     *
     * **The mode it selects is NOT discovery-only.** Downscaling uses `FlashDiscoveryMode.ECO`, which
     * goes through `DiscoveryModePolicy` *and* `ConnectionModePolicy` / `ConnectionStrategy.ECO`
     * (`limitsSessions = true`, so idle sessions that were dialled get parked). The caller — not this
     * layer — is responsible for not calling it while a call or a transfer is in flight.
     *
     * The restore path re-applies the user's last [setMode] choice, not a fixed default, so a mode the
     * operator picked before screen-off is honoured on screen-on.
     */
    // TODO(wave-3): wire the screen-state receiver in both hosts (Stream G). See OPT-O10-01.
    public fun setScreenActive(active: Boolean) {}
}
