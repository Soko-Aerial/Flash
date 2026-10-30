package com.transfer.flash.core.security.group

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddressHintTest {

    @Test
    fun privateLanAddressesAndLocalNamesAreDialable() {
        for (ok in listOf("192.168.1.100:4433", "10.0.0.1:45822", "172.16.5.9:45822", "172.31.255.1:45822", "100.64.0.2:45822", "pixel-7.local:45822")) {
            assertTrue(AddressHint.isDialable(ok), ok)
        }
    }

    @Test
    fun everythingElseIsRefused() {
        for (bad in listOf(
            "127.0.0.1:45822", "0.0.0.0:45822", "169.254.169.254:8080", "8.8.8.8:45822", "172.32.0.1:45822", "100.128.0.1:45822",
            "192.168.1.1:80", "192.168.1.1:99999", "192.168.1.1", "192.168.1.1:", ":4433", "http://192.168.1.1:4433",
            "192.168.1.1:4433/x", "user@192.168.1.1:4433", "example.com:45822", "a..local:45822", "192.168.1.256:4433",
            "[fe80::1]:4433", "192.168.1.1:4433 x", "",
        )) {
            assertFalse(AddressHint.isDialable(bad), bad)
        }
    }
}
