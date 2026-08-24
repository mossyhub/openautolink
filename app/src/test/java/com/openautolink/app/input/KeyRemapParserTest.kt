package com.openautolink.app.input

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyRemapParserTest {

    @Test
    fun `new serialized mapping replaces the previous hardware actions`() {
        val previous = KeyRemapParser.parse("{\"137\":88,\"138\":87}")
        val updated = KeyRemapParser.parse("{\"137\":87,\"138\":88}")

        assertEquals(mapOf(137 to 88, 138 to 87), previous)
        assertEquals(mapOf(137 to 87, 138 to 88), updated)
    }

    @Test
    fun `blank mapping clears all custom actions`() {
        assertEquals(emptyMap<Int, Int>(), KeyRemapParser.parse(""))
    }

    @Test
    fun `invalid mapping fails closed to built in defaults`() {
        assertEquals(emptyMap<Int, Int>(), KeyRemapParser.parse("not-json"))
    }
}
