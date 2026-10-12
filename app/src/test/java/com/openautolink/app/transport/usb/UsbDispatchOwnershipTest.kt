package com.openautolink.app.transport.usb

import org.junit.Assert.*
import org.junit.Test

class UsbDispatchOwnershipTest {
    @Test fun `selection deduplicates attach and permission while unrelated detach is ignored`() {
        val state = UsbDispatchOwnership()
        val token = state.select("android", 0x18d1)!!
        assertNull(state.select("android", 0x18d1))
        assertNull(state.detach("other"))
        assertTrue(state.beginPermission(token))
        assertFalse(state.beginPermission(token))
        assertNull(state.permission("other", token.generation))
        assertSame(token, state.permission("android", token.generation))
        assertNull(state.permission("android", token.generation))
        assertTrue(state.isCurrent(token))
    }
    @Test fun `reenumeration keeps the same owner and timeout is token fenced`() {
        val state = UsbDispatchOwnership()
        val first = state.select("apple-old", 0x05ac)!!
        assertTrue(state.beginReenumeration(first))
        assertNull(state.detach("apple-old"))
        assertNull(state.select("android", 0x18d1))
        assertTrue(state.adoptReenumeration(first, "apple-new", 0x05ac))
        assertNull(state.detach("apple-old"))
        assertSame(first, state.detach("apple-new"))
        assertNull(state.detach("apple-new"))
        assertTrue(state.release(first))
        val second = state.select("android", 0x18d1)!!
        assertFalse(state.release(first))
        assertTrue(state.isCurrent(second))
    }
    @Test fun `stop keeps retirement barrier until exact owner acknowledges`() {
        val state = UsbDispatchOwnership()
        val first = state.select("apple", 0x05ac)!!
        assertSame(first, state.retire())
        assertNull(state.retire())
        assertNull(state.select("android", 0x18d1))
        assertTrue(state.release(first))
        assertNotNull(state.select("android", 0x18d1))
    }
    @Test fun `Apple route cannot fall through to Android when disabled`() {
        assertEquals(UsbDispatchOwnership.Route.APPLE, UsbDispatchOwnership.route(0x05ac))
        assertEquals(UsbDispatchOwnership.Route.ANDROID, UsbDispatchOwnership.route(0x18d1))
    }
}
