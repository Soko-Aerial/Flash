package com.transfer.flash.ui.radio

import kotlinx.coroutines.flow.StateFlow

/**
 * Controller port for the Radio Gateway UI (Task 3a).
 *
 * Implemented by the host/manager layer (Stream R-link) or by [FakeRadioGatewayController] for
 * tests, previews, and standalone demo environments.
 *
 * Exposes a reactive [state] stream and single-intent UI actions.
 * Contains only plain types; `:ui:chat` has zero dependency on `:core:radio`.
 */
public interface RadioGatewayController {
    /** Reactive stream of the current radio gateway UI state. */
    public val state: StateFlow<RadioGatewayUiState>

    /** Turns the radio gateway master switch on or off. */
    public fun setEnabled(enabled: Boolean)

    /** Starts an active Bluetooth inquiry scan for nearby radios with automatic timeout. */
    public fun startScan()

    /** Halts an active Bluetooth inquiry scan immediately. */
    public fun stopScan()

    /** Confirms use of an auto-detected candidate radio. */
    public fun confirmCandidate(id: String)

    /** Initiates in-app Bluetooth bonding / pairing with the selected peripheral. */
    public fun pair(id: String)

    /** Manually attempts to reconnect to the remembered radio. */
    public fun reconnect()

    /** Forgets the currently remembered radio hardware address. */
    public fun forget()

    /** Changes the active security profile (Profile M encrypted vs Profile A cleartext). */
    public fun selectProfile(profile: RadioSecurityProfile)

    /**
     * Records user regulatory authorization to transmit encrypted RF on the frequency in use, together with the
     * operator's [operatorCallsign] (station ID, ERROR-684). An invalid callsign is ignored: nothing is recorded.
     */
    public fun confirmAuthorisation(operatorCallsign: String)

    /** Selects the active operational mode (e.g. Field Companion). */
    public fun selectMode(mode: RadioGatewayMode)

    /** Sends a single diagnostic test message with an explicit RF transmit warning. */
    public fun sendTestMessage()

    /** Opens system Bluetooth settings (Android Bluetooth settings or Windows ms-settings:bluetooth). */
    public fun openSystemBluetoothSettings()

    /** Requests runtime Nearby devices permission at point of use. */
    public fun requestNearbyDevicesPermission()
}
