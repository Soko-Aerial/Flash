package com.transfer.flash.debug.radiospike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for Android radio spike ranking and classification logic.
 * Executes on JVM via :app:testDebugUnitTest without requiring an Android emulator or device.
 */
class AndroidRadioDeviceRankerTest {

    @Test
    fun testAddressMaskingTruncatesToLast3Bytes() {
        val addr1 = "38:D2:00:01:35:B5"
        assertEquals("**:**:**:01:35:B5", DiscoveredRadioCandidate.maskAddress(addr1))

        val addr2 = "A1-B2-C3-D4-E5-F6"
        assertEquals("**:**:**:D4:E5:F6", DiscoveredRadioCandidate.maskAddress(addr2))

        val raw = "38D2000135B5"
        assertEquals("******0135B5", DiscoveredRadioCandidate.maskAddress(raw))
    }

    @Test
    fun testClassifyKnownRadios() {
        val vrN76 = DiscoveredRadioCandidate(
            name = "VR-N76 #1",
            address = "38:D2:00:01:35:B5"
        )
        val rankedVr = AndroidRadioDeviceRanker.classify(vrN76)
        assertEquals(AndroidRadioCategory.RADIO, rankedVr.category)
        assertEquals(1, rankedVr.rank)
        assertTrue(rankedVr.reason.contains("VR-N76"))

        val uvPro = DiscoveredRadioCandidate(
            name = "BTECH UV-Pro TNC",
            address = "AA:BB:CC:11:22:33"
        )
        val rankedUv = AndroidRadioDeviceRanker.classify(uvPro)
        assertEquals(AndroidRadioCategory.RADIO, rankedUv.category)
        assertEquals(1, rankedUv.rank)
    }

    @Test
    fun testClassifySerialSppDevices() {
        val sppDevice = DiscoveredRadioCandidate(
            name = "Wireless Adapter",
            address = "11:22:33:44:55:66",
            serviceUuids = listOf(AndroidRadioDeviceRanker.SPP_UUID_STRING)
        )
        val rankedSpp = AndroidRadioDeviceRanker.classify(sppDevice)
        assertEquals(AndroidRadioCategory.SERIAL_SPP, rankedSpp.category)
        assertEquals(2, rankedSpp.rank)
        assertTrue(rankedSpp.reason.contains("Serial Port Profile"))

        val hc05 = DiscoveredRadioCandidate(
            name = "HC-05",
            address = "99:88:77:66:55:44"
        )
        val rankedHc = AndroidRadioDeviceRanker.classify(hc05)
        assertEquals(AndroidRadioCategory.SERIAL_SPP, rankedHc.category)
        assertEquals(2, rankedHc.rank)
    }

    @Test
    fun testClassifyOtherPeripherals() {
        val headset = DiscoveredRadioCandidate(
            name = "Bose QC45 Headset",
            address = "12:34:56:78:9A:BC"
        )
        val rankedHeadset = AndroidRadioDeviceRanker.classify(headset)
        assertEquals(AndroidRadioCategory.OTHER, rankedHeadset.category)
        assertEquals(3, rankedHeadset.rank)
        assertTrue(rankedHeadset.reason.contains("Audio"))

        val phone = DiscoveredRadioCandidate(
            name = "Pixel 7 Phone",
            address = "00:11:22:33:44:55"
        )
        val rankedPhone = AndroidRadioDeviceRanker.classify(phone)
        assertEquals(AndroidRadioCategory.OTHER, rankedPhone.category)
        assertEquals(3, rankedPhone.rank)
    }

    @Test
    fun testRankCandidatesPrioritizesRadiosThenBondedThenRssi() {
        val radioUnbondedWeak = DiscoveredRadioCandidate(
            name = "VR-N76 Field",
            address = "38:D2:00:01:35:B5",
            bondState = DiscoveredRadioCandidate.BOND_NONE,
            rssi = -80
        )
        val radioBondedStrong = DiscoveredRadioCandidate(
            name = "VR-N76 Base",
            address = "38:D2:00:01:35:C0",
            bondState = DiscoveredRadioCandidate.BOND_BONDED,
            rssi = -45
        )
        val sppAdapter = DiscoveredRadioCandidate(
            name = "HC-06 Serial",
            address = "AA:BB:CC:DD:EE:FF",
            bondState = DiscoveredRadioCandidate.BOND_NONE,
            rssi = -30
        )
        val audioHeadset = DiscoveredRadioCandidate(
            name = "Sony WH-1000XM4 Headset",
            address = "11:22:33:44:55:66",
            bondState = DiscoveredRadioCandidate.BOND_BONDED,
            rssi = -20
        )

        val unranked = listOf(audioHeadset, radioUnbondedWeak, sppAdapter, radioBondedStrong)
        val ranked = AndroidRadioDeviceRanker.rankCandidates(unranked)

        assertEquals(4, ranked.size)
        // 1st: Bonded radio (VR-N76 Base)
        assertEquals("VR-N76 Base", ranked[0].candidate.name)
        assertEquals(AndroidRadioCategory.RADIO, ranked[0].category)

        // 2nd: Unbonded radio (VR-N76 Field)
        assertEquals("VR-N76 Field", ranked[1].candidate.name)
        assertEquals(AndroidRadioCategory.RADIO, ranked[1].category)

        // 3rd: SPP Adapter
        assertEquals("HC-06 Serial", ranked[2].candidate.name)
        assertEquals(AndroidRadioCategory.SERIAL_SPP, ranked[2].category)

        // 4th: Audio headset
        assertEquals("Sony WH-1000XM4 Headset", ranked[3].candidate.name)
        assertEquals(AndroidRadioCategory.OTHER, ranked[3].category)
    }
}
