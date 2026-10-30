package com.transfer.flash.core.network.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeAdmissionTest {

    @Test
    fun oneHostCannotTakeMoreThanItsShare() {
        val admission = HandshakeAdmission(maxTotal = 10, maxPerHost = 2)
        assertTrue(admission.tryAdmit("10.0.0.5"))
        assertTrue(admission.tryAdmit("10.0.0.5"))
        assertFalse("a third socket from one address is refused", admission.tryAdmit("10.0.0.5"))
        assertTrue("another address is unaffected", admission.tryAdmit("10.0.0.6"))
    }

    @Test
    fun theTotalIsBoundedAndAReleaseFreesASlot() {
        val admission = HandshakeAdmission(maxTotal = 2, maxPerHost = 2)
        assertTrue(admission.tryAdmit("a"))
        assertTrue(admission.tryAdmit("b"))
        assertFalse(admission.tryAdmit("c"))
        admission.release("a")
        assertTrue(admission.tryAdmit("c"))
        assertEquals(2, admission.pending())
    }

    @Test
    fun releasingMoreThanAdmittedNeverGoesNegative() {
        val admission = HandshakeAdmission(maxTotal = 2, maxPerHost = 2)
        admission.release("ghost")
        assertEquals(0, admission.pending())
        assertTrue(admission.tryAdmit("a"))
    }
}
