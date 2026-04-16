package com.openautolink.app.session

import com.openautolink.app.transport.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStateTest {

    @Test
    fun `ConnectionState DISCONNECTED maps to SessionState IDLE`() {
        assertEquals(SessionState.IDLE, ConnectionState.DISCONNECTED.toSessionState())
    }

    @Test
    fun `ConnectionState CONNECTING maps to SessionState CONNECTING`() {
        assertEquals(SessionState.CONNECTING, ConnectionState.CONNECTING.toSessionState())
    }

    @Test
    fun `ConnectionState LISTENING maps to SessionState LISTENING`() {
        assertEquals(SessionState.LISTENING, ConnectionState.LISTENING.toSessionState())
    }

    @Test
    fun `ConnectionState PHONE_CONNECTED maps to SessionState PHONE_CONNECTED`() {
        assertEquals(SessionState.PHONE_CONNECTED, ConnectionState.PHONE_CONNECTED.toSessionState())
    }

    @Test
    fun `ConnectionState STREAMING maps to SessionState STREAMING`() {
        assertEquals(SessionState.STREAMING, ConnectionState.STREAMING.toSessionState())
    }

    @Test
    fun `all ConnectionState values have a mapping`() {
        ConnectionState.entries.forEach { state ->
            // Should not throw
            state.toSessionState()
        }
    }
}
