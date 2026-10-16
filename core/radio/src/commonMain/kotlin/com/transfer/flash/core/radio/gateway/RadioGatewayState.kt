package com.transfer.flash.core.radio.gateway

import com.transfer.flash.core.radio.connect.AuthorisationConfirmation
import com.transfer.flash.core.radio.connect.RadioConnectState
import com.transfer.flash.core.radio.connect.RadioSecurityProfile
import com.transfer.flash.core.radio.connect.RememberedRadio
import com.transfer.flash.core.radio.driver.TncLinkState
import com.transfer.flash.core.radio.link.DiscoveredDevice

/**
 * Public observable state of the Flash Radio Gateway (Plan R §3 and §6).
 * Contains only platform-independent data; NO UI types, NO Android/desktop classes.
 */
public data class RadioGatewayState(
    /** Whether the radio gateway master switch is turned on. */
    val isEnabled: Boolean = false,
    /** High-level connection lifecycle state. */
    val connectState: RadioConnectState = RadioConnectState.Off,
    /** Low-level TNC driver link state. */
    val linkState: TncLinkState = TncLinkState.Idle,
    /** Currently remembered radio hardware address and name, or null. */
    val rememberedRadio: RememberedRadio? = null,
    /** Plain-language status sentence for the user (Plan R §6 Rule 5). */
    val plainSentence: String = "Radio gateway is turned off.",
    /** Clear single next step for the user (Plan R §6 Rule 5). */
    val nextStep: String = "Turn on the radio gateway switch to connect to your radio.",
    /** Nearby devices found during active discovery scans. */
    val scanResults: List<DiscoveredDevice> = emptyList(),
    /** Current security profile (Profile A or Profile M). */
    val currentProfile: RadioSecurityProfile = RadioSecurityProfile.PROFILE_A_PLAINTEXT_SIGNED,
    /** Recorded user authorization confirmation for encrypted transmission (Plan R §8). */
    val authorisation: AuthorisationConfirmation? = null,
    /** True when automatic reconnection attempts stopped after reaching the limit; user must tap Reconnect. */
    val isRetryingStopped: Boolean = false,
) {
    /** True when the radio link is fully open and ready for data exchange. */
    public val isConnectedAndReady: Boolean
        get() = isEnabled && connectState is RadioConnectState.Connected && linkState is TncLinkState.Connected
}
