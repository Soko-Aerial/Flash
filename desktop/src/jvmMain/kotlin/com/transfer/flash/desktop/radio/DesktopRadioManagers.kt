package com.transfer.flash.desktop.radio

import com.transfer.flash.core.radio.gateway.RadioGatewayManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The gateway manager the Radio screen's controller currently owns, shared with the engine so the radio chat engine
 * can follow it (ERROR-703). The shell creates the controller, the engine builds the chat host; neither owns the other.
 */
public object DesktopRadioManagers {
    private val _current = MutableStateFlow<RadioGatewayManager?>(null)
    public val current: StateFlow<RadioGatewayManager?> = _current

    public fun publish(manager: RadioGatewayManager?) {
        _current.value = manager
    }
}
