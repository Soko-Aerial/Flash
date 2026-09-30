package com.transfer.flash.core.network.ws

import kotlin.test.Test
import kotlin.test.assertEquals

class HelloGroupProtocolTest {
    @Test
    fun aHelloWithoutTheFieldIsAnOlderBuild() {
        assertEquals(1, parseGroupProtocol(null))
    }

    @Test
    fun aValidLevelIsKept() {
        assertEquals(1, parseGroupProtocol("1"))
        assertEquals(2, parseGroupProtocol("2"))
        assertEquals(7, parseGroupProtocol("7"))
    }

    @Test
    fun nonsenseFallsBackToLevelOne() {
        assertEquals(1, parseGroupProtocol(""))
        assertEquals(1, parseGroupProtocol("two"))
        assertEquals(1, parseGroupProtocol("0"))
        assertEquals(1, parseGroupProtocol("-3"))
        assertEquals(1, parseGroupProtocol("2.5"))
    }

    @Test
    fun anAbsurdLevelIsCapped() {
        assertEquals(255, parseGroupProtocol("2147483647"))
    }
}
