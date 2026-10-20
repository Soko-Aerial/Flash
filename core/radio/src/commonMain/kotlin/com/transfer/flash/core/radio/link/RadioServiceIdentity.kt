package com.transfer.flash.core.radio.link

/**
 * Identifies a Bluetooth radio's **control** device from the services it advertises in its SDP record.
 *
 * The handhelds Flash supports (VR-N76, UV-Pro, GA-5WB, VR-N7500 and their rebrands) present themselves as **two**
 * Bluetooth devices with the same name: an audio endpoint (A2DP / Hands-Free) and a data device. Only the data device
 * offers a serial channel, so bonding the audio one produces a pair that can never carry KISS (seen on Windows; the
 * same trap exists on Android when the two profiles do not collapse into one `BluetoothDevice`).
 *
 * The reliable discriminator is the advertised services, not the name: the data device advertises Serial Port Profile
 * and the vendor service `39144315-32fa-40db-85ed-fbfeba2d86e6` that also carries the radio's audio stream. That is
 * how HTCommander (Apache-2.0, `BluetoothClassicPlugin.kt`) recognises the device it must talk to.
 *
 * An unpaired device has no SDP cache, so [classify] answers [Kind.UNKNOWN] for it and the caller must keep offering
 * it — a scan result has to stay available for pairing.
 */
public object RadioServiceIdentity {

    /** The vendor service (Bluetooth SIG name "BS AOC") a supported radio advertises next to Serial Port Profile. */
    public const val VENDOR_SERVICE_UUID: String = "39144315-32fa-40db-85ed-fbfeba2d86e6"

    /** What the advertised services say about the device. */
    public enum class Kind {
        /** Advertises Serial Port Profile or the vendor service: the radio's control (data) device. */
        CONTROL_DEVICE,

        /** Advertises services but neither of those: an audio endpoint, headset, printer or computer. */
        NOT_A_CONTROL_DEVICE,

        /** No SDP cache (unpaired, or never queried): nothing can be concluded. */
        UNKNOWN,
    }

    /**
     * Classifies [serviceUuids], the strings from `BluetoothDevice.uuids` (or any equivalent SDP listing).
     *
     * Accepts the 16-bit form (`1101`), the 32-bit form and the full 128-bit form, in any case.
     */
    public fun classify(serviceUuids: List<String>?): Kind {
        if (serviceUuids.isNullOrEmpty()) return Kind.UNKNOWN
        val normalised = serviceUuids.map { normalise(it) }
        return if (normalised.any { it == normalise(SPP_UUID_TEXT) || it == normalise(VENDOR_SERVICE_UUID) }) {
            Kind.CONTROL_DEVICE
        } else {
            Kind.NOT_A_CONTROL_DEVICE
        }
    }

    /** True when [serviceUuids] contains the radio vendor service, which names a supported radio even behind a rebrand. */
    public fun advertisesVendorService(serviceUuids: List<String>?): Boolean =
        !serviceUuids.isNullOrEmpty() && serviceUuids.any { normalise(it) == normalise(VENDOR_SERVICE_UUID) }

    /**
     * Lower-cases a UUID and expands the short Bluetooth forms to the full 128-bit base UUID, so `1101`, `00001101`
     * and `00001101-0000-1000-8000-00805F9B34FB` compare equal.
     */
    public fun normalise(uuid: String): String {
        val u = uuid.trim().lowercase()
        return when (u.length) {
            4 -> "0000$u-0000-1000-8000-00805f9b34fb"
            8 -> "$u-0000-1000-8000-00805f9b34fb"
            else -> u
        }
    }
}
