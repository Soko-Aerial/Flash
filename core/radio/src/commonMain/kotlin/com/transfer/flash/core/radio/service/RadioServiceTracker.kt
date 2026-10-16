package com.transfer.flash.core.radio.service

import com.transfer.flash.core.radio.gateway.RadioGatewayState

/** What the host must do with its foreground service after a gateway state change. */
public sealed interface RadioServiceAction {
    /** Start the service, or refresh its notification when it already runs. */
    public data class Start(val notification: RadioServiceNotification) : RadioServiceAction

    /** Stop the service. */
    public data object Stop : RadioServiceAction
}

/**
 * Turns the stream of gateway states into the few service calls that are really needed.
 *
 * The state flow emits on every scan result and every status sentence; asking the OS to start or stop a
 * service on each emission wakes the service for nothing and, from the background, throws on Android 12+.
 * This tracker returns an action only when the service must start, when its notification text changes,
 * or when it must stop after having run. Not thread safe: call from one coroutine or one thread.
 */
public class RadioServiceTracker {
    private var shown: RadioServiceNotification? = null

    /** The action for [state], or null when the service is already in the wanted situation. */
    public fun next(state: RadioGatewayState): RadioServiceAction? {
        if (RadioServicePolicy.shouldServiceRun(state)) {
            val wanted = RadioServicePolicy.notificationContent(state)
            if (wanted == shown) return null
            shown = wanted
            return RadioServiceAction.Start(wanted)
        }
        if (shown == null) return null
        shown = null
        return RadioServiceAction.Stop
    }

    /** Forget what was shown (the host stopped the service itself, or the start failed). */
    public fun reset() {
        shown = null
    }
}
