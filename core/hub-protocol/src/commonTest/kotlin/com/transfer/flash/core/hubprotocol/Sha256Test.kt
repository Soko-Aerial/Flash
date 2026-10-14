package com.transfer.flash.core.hubprotocol

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.certs.Sha256
import com.transfer.flash.core.hubprotocol.certs.toHubHex
import kotlin.test.Test
import kotlin.test.assertEquals

/** FIPS 180-4 / NIST example vectors for the plain-Kotlin SHA-256 (ADR-129). */
@OptIn(FlashInternalApi::class)
class Sha256Test {
    private fun hex(s: String) = Sha256.digest(s.encodeToByteArray()).toHubHex()

    @Test
    fun emptyMessage() = assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(""))

    @Test
    fun abc() = assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex("abc"))

    @Test
    fun theTwoBlockNistMessage() = assertEquals(
        "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
        hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
    )

    @Test
    fun oneMillionA() = assertEquals(
        "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
        Sha256.digest(ByteArray(1_000_000) { 'a'.code.toByte() }).toHubHex(),
    )
}
