package com.transfer.flash

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.transfer.flash.notifications.FlashNotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferNotificationIdentityInstrumentedTest {
    @Test
    fun cancelTokensRemainDistinctForCollidingTransferIds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val first = FlashNotificationManager.createCancelTransferPendingIntent(context, "Aa")
        val second = FlashNotificationManager.createCancelTransferPendingIntent(context, "BB")
        try {
            assertNotEquals(first, second)
            assertEquals(first, FlashNotificationManager.createCancelTransferPendingIntent(context, "Aa"))
        } finally {
            first.cancel()
            second.cancel()
        }
    }
}
