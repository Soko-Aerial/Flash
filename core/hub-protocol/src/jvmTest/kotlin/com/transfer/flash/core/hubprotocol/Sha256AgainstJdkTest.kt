package com.transfer.flash.core.hubprotocol

import com.transfer.flash.core.hubprotocol.certs.Sha256
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** The plain-Kotlin SHA-256 against the JDK's for every length around the padding boundaries and some long inputs (ADR-129). */
class Sha256AgainstJdkTest {
    @Test
    fun agreesWithTheJdkForLengthsZeroTo300() {
        val random = Random(1234)
        for (length in 0..300) {
            val data = random.nextBytes(length)
            assertContentEquals(MessageDigest.getInstance("SHA-256").digest(data), Sha256.digest(data), "length $length")
        }
    }

    @Test
    fun agreesWithTheJdkForLongInputs() {
        val random = Random(99)
        for (length in listOf(1_000, 4_095, 4_096, 65_537, 300_001)) {
            val data = random.nextBytes(length)
            assertContentEquals(MessageDigest.getInstance("SHA-256").digest(data), Sha256.digest(data), "length $length")
        }
    }
}
