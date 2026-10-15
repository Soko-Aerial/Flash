package com.transfer.flash.core.enterprise

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonDepthGuardTest {
    @Test
    fun flatDocumentsPassAndHostileNestingIsRefused() {
        assertFalse(jsonTooDeep("""{"a":[{"b":1}],"c":"{{{{"}"""))
        assertFalse(jsonTooDeep("[" .repeat(16) + "]".repeat(16)))
        assertTrue(jsonTooDeep("[".repeat(17) + "]".repeat(17)))
        assertTrue(jsonTooDeep("[".repeat(20_000)))
    }

    @Test
    fun bracketsInsideStringsAndEscapedQuotesDoNotCount() {
        assertFalse(jsonTooDeep("{\"k\":\"" + "[".repeat(100) + "\"}"))
        assertFalse(jsonTooDeep("{\"k\":\"a\\\"" + "[".repeat(100) + "\"}"))
    }
}
