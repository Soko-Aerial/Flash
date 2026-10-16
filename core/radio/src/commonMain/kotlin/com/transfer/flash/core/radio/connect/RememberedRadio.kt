package com.transfer.flash.core.radio.connect

/**
 * A remembered radio configuration (Plan R §6 Rule 1).
 *
 * Identity is the [bluetoothAddress], never the COM name or port number, because ports
 * are renumbered by the OS and by re-pairing.
 */
public data class RememberedRadio(
    /** The persistent Bluetooth hardware address (e.g. "AA:BB:CC:DD:EE:FF" or 12-hex string). */
    val bluetoothAddress: String,
    /** Display name shown to the user, e.g. "VR-N76". */
    val displayName: String,
    /** Last-known serial port name (e.g. "COM16") for the Windows fallback, or null. */
    val lastKnownPortName: String? = null,
) {
    init {
        require(bluetoothAddress.isNotBlank()) { "bluetoothAddress must not be blank" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
    }
}

/**
 * Port interface for persistent storage of the remembered radio.
 */
public interface RememberedRadioStore {
    /** Loads the currently remembered radio, or null if none is saved. */
    public suspend fun load(): RememberedRadio?

    /** Persists [radio]. */
    public suspend fun save(radio: RememberedRadio)

    /** Clears the remembered radio. */
    public suspend fun clear()
}

/**
 * In-memory test stand-in for [RememberedRadioStore].
 */
public class InMemoryRememberedRadioStore(
    initial: RememberedRadio? = null,
) : RememberedRadioStore {
    private var current: RememberedRadio? = initial

    override suspend fun load(): RememberedRadio? = current

    override suspend fun save(radio: RememberedRadio) {
        current = radio
    }

    override suspend fun clear() {
        current = null
    }
}
