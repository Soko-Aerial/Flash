package com.transfer.flash.core.radio.service

import com.transfer.flash.core.radio.connect.RadioConnectState
import com.transfer.flash.core.radio.driver.TncLinkState
import com.transfer.flash.core.radio.gateway.RadioGatewayState

/**
 * Pure Kotlin policy determining Android foreground service lifecycle and honest notifications (Plan R §4 & §6).
 */
public data class RadioServiceNotification(
    val title: String,
    val text: String,
)

public object RadioServicePolicy {

    /**
     * Determines whether the background foreground service should be active.
     * Android 14/15 connectedDevice FGS requires an active attempt or established connection to an external peripheral.
     */
    public fun shouldServiceRun(state: RadioGatewayState): Boolean {
        if (!state.isEnabled) return false
        if (state.isRetryingStopped) return false
        return state.connectState is RadioConnectState.Connected ||
                state.connectState is RadioConnectState.Connecting
    }

    /**
     * Builds honest, accurate notification text reflecting current connection state.
     */
    public fun notificationContent(state: RadioGatewayState): RadioServiceNotification {
        val radioName = state.rememberedRadio?.displayName ?: "Radio"
        return when {
            state.linkState is TncLinkState.Connected -> {
                RadioServiceNotification(
                    title = "Radio Gateway Active",
                    text = "Connected to $radioName"
                )
            }
            state.linkState is TncLinkState.Waiting -> {
                val attempt = (state.linkState as TncLinkState.Waiting).attempt
                RadioServiceNotification(
                    title = "Radio Gateway Active",
                    text = "Radio disconnected — retrying (attempt $attempt)..."
                )
            }
            state.connectState is RadioConnectState.Connecting || state.linkState is TncLinkState.Connecting -> {
                RadioServiceNotification(
                    title = "Radio Gateway Active",
                    text = "Connecting to $radioName..."
                )
            }
            else -> {
                RadioServiceNotification(
                    title = "Radio Gateway",
                    text = state.plainSentence
                )
            }
        }
    }
}
