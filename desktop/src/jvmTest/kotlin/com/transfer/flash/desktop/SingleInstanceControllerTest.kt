package com.transfer.flash.desktop

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SingleInstanceControllerTest {

    @Before
    @After
    fun cleanup() {
        SingleInstanceController.release()
    }

    @Test
    fun acquireOrActivateSucceedsForFirstInstance() {
        val acquired = SingleInstanceController.acquireOrActivate()
        assertTrue("First instance should acquire lock", acquired)

        // Clean up
        SingleInstanceController.release()
    }

    @Test
    fun activationMessageTriggersCallback() {
        val acquired = SingleInstanceController.acquireOrActivate()
        assertTrue("Primary instance should acquire lock", acquired)

        val latch = CountDownLatch(1)
        SingleInstanceController.onActivate = {
            latch.countDown()
        }

        // Simulate a secondary instance attempting to acquire
        val secondaryAcquired = SingleInstanceController.acquireOrActivate()
        assertFalse("Second instance should fail lock acquisition", secondaryAcquired)

        // Verify the onActivate callback was triggered
        val activated = latch.await(3, TimeUnit.SECONDS)
        assertTrue("onActivate should be called when second instance attempts launch", activated)

        SingleInstanceController.release()
    }
}
