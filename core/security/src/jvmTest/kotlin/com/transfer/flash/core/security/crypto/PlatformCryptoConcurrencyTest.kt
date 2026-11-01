package com.transfer.flash.core.security.crypto

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/** Each crypto operation owns its keyed provider state even when a worker alternates keys. */
class PlatformCryptoConcurrencyTest {
    @Test
    fun concurrent_cached_public_keys_and_alternating_symmetric_operations_remain_independent() {
        val pairs = List(6) { ecP256GenerateKeyPair() }
        val data = "parallel-crypto".encodeToByteArray()
        val signatures = pairs.map { ecP256Sign(it.privateKey, data) }
        val keys = List(6) { secureRandomBytes(32) }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 4).map { worker -> Callable {
                repeat(60) { iteration ->
                    val index = (worker + iteration) % pairs.size
                    assertTrue(ecP256Verify(pairs[index].publicKeyEncoded.copyOf(), data, signatures[index]))
                    val nonce = secureRandomBytes(12)
                    val sealed = aesGcmSeal(keys[index], nonce, data, data)
                    assertTrue(aesGcmOpen(keys[index], nonce, data, sealed).contentEquals(data))
                    assertTrue(hmacSha256(keys[index], data).contentEquals(hmacSha256(keys[index], data)))
                    assertTrue(sha256(data).contentEquals(sha256(data)))
                }
            } }
            pool.invokeAll(tasks).forEach { it.get(30L, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
