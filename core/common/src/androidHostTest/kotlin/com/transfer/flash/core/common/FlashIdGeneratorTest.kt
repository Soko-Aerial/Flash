@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.common

import com.transfer.flash.core.common.id.FlashIdGenerator
import com.transfer.flash.core.common.id.UuidIdGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashIdGeneratorTest {

    @Test
    fun `1000 generated ids are all distinct`() {
        val ids = HashSet<String>(1000)

        repeat(1000) { ids.add(UuidIdGenerator.newId()) }

        assertEquals(1000, ids.size)
    }

    @Test
    fun generated_ids_are_valid_uuid_v4_format() {
        val uuidRegex = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
        )

        repeat(50) {
            val id = UuidIdGenerator.newId()
            assertTrue("not a UUID v4: $id", uuidRegex.matches(id))
        }
    }

    @Test
    fun interface_contract_satisfied_by_uuid_generator() {
        val generator: FlashIdGenerator = UuidIdGenerator

        assertTrue(generator.newId().isNotBlank())
    }
}
