package com.transfer.flash.core.radio.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RADFIX-U01: telling a radio's control device from its audio endpoint by the services it advertises.
 *
 * The handhelds present two Bluetooth entries with the same name, and only the control one carries a serial channel.
 * These cases are the observed shapes (vendor service, SPP in its 16-bit and 128-bit spellings, an audio-only device,
 * and a device with no SDP cache).
 */
class RadioServiceIdentityTest {

    private val vendor = "39144315-32fa-40db-85ed-fbfeba2d86e6"
    private val a2dp = "0000110a-0000-1000-8000-00805f9b34fb"
    private val hfp = "0000111e-0000-1000-8000-00805f9b34fb"
    private val avrcp = "0000110e-0000-1000-8000-00805f9b34fb"

    @Test
    fun anAbsentSdpCacheConcludesNothing() {
        assertEquals(RadioServiceIdentity.Kind.UNKNOWN, RadioServiceIdentity.classify(null))
        assertEquals(RadioServiceIdentity.Kind.UNKNOWN, RadioServiceIdentity.classify(emptyList()))
    }

    @Test
    fun theVendorServiceIdentifiesTheControlDevice() {
        assertEquals(RadioServiceIdentity.Kind.CONTROL_DEVICE, RadioServiceIdentity.classify(listOf(vendor)))
        assertTrue(RadioServiceIdentity.advertisesVendorService(listOf(a2dp, vendor)))
    }

    @Test
    fun serialPortProfileIsAcceptedInEverySpelling() {
        assertEquals(RadioServiceIdentity.Kind.CONTROL_DEVICE, RadioServiceIdentity.classify(listOf("1101")))
        assertEquals(RadioServiceIdentity.Kind.CONTROL_DEVICE, RadioServiceIdentity.classify(listOf("00001101")))
        assertEquals(RadioServiceIdentity.Kind.CONTROL_DEVICE, RadioServiceIdentity.classify(listOf(SPP_UUID_TEXT)))
        assertEquals(
            RadioServiceIdentity.Kind.CONTROL_DEVICE,
            RadioServiceIdentity.classify(listOf("00001101-0000-1000-8000-00805f9b34fb")),
        )
        assertEquals(
            "00001101-0000-1000-8000-00805f9b34fb",
            RadioServiceIdentity.normalise("00001101-0000-1000-8000-00805F9B34FB"),
        )
    }

    @Test
    fun anAudioEndpointIsNotAControlDevice() {
        assertEquals(
            RadioServiceIdentity.Kind.NOT_A_CONTROL_DEVICE,
            RadioServiceIdentity.classify(listOf(a2dp, hfp, avrcp)),
        )
        assertFalse(RadioServiceIdentity.advertisesVendorService(listOf(a2dp, hfp)))
        assertFalse(RadioServiceIdentity.advertisesVendorService(null))
    }

    @Test
    fun aDeviceAdvertisingBothIsStillTheControlDevice() {
        assertEquals(
            RadioServiceIdentity.Kind.CONTROL_DEVICE,
            RadioServiceIdentity.classify(listOf(a2dp, hfp, "1101", vendor)),
        )
    }

    @Test
    fun shortFormsExpandToTheBaseUuid() {
        assertEquals("00001101-0000-1000-8000-00805f9b34fb", RadioServiceIdentity.normalise("1101"))
        assertEquals("0000110a-0000-1000-8000-00805f9b34fb", RadioServiceIdentity.normalise("0000110A"))
        assertEquals(vendor, RadioServiceIdentity.normalise(vendor.uppercase()))
    }
}
