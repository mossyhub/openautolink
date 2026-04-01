package com.openautolink.app.input

import android.util.Log
import android.view.KeyEvent
import com.openautolink.app.transport.ControlMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SteeringWheelControllerTest {

    private val sentMessages = mutableListOf<ControlMessage.Button>()
    private lateinit var controller: SteeringWheelController

    @Before
    fun setup() {
        sentMessages.clear()
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        controller = SteeringWheelController(
            sendMessage = { sentMessages.add(it) },
            audioManager = null
        )
    }

    private fun mockKeyEvent(action: Int, keyCode: Int, repeatCount: Int = 0): KeyEvent {
        val event = mockk<KeyEvent>()
        every { event.action } returns action
        every { event.keyCode } returns keyCode
        every { event.repeatCount } returns repeatCount
        return event
    }

    @Test
    fun `media next key sends button with correct keycode`() {
        val downEvent = mockKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT)
        assertTrue(controller.onKeyEvent(downEvent))

        assertEquals(1, sentMessages.size)
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, sentMessages[0].keycode)
        assertTrue(sentMessages[0].down)
    }

    @Test
    fun `media previous key sends button`() {
        val downEvent = mockKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        assertTrue(controller.onKeyEvent(downEvent))

        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, sentMessages[0].keycode)
        assertTrue(sentMessages[0].down)
    }

    @Test
    fun `media play pause sends both down and up`() {
        val downEvent = mockKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        val upEvent = mockKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)

        assertTrue(controller.onKeyEvent(downEvent))
        assertTrue(controller.onKeyEvent(upEvent))

        assertEquals(2, sentMessages.size)
        assertTrue(sentMessages[0].down)
        assertFalse(sentMessages[1].down)
    }

    @Test
    fun `voice assist maps to AA KEYCODE_SEARCH`() {
        val downEvent = mockKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOICE_ASSIST)
        assertTrue(controller.onKeyEvent(downEvent))

        assertEquals(1, sentMessages.size)
        assertEquals(84, sentMessages[0].keycode) // AA KEYCODE_SEARCH
        assertTrue(sentMessages[0].down)
    }

    @Test
    fun `search key maps to AA KEYCODE_SEARCH`() {
        val downEvent = mockKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SEARCH)
        assertTrue(controller.onKeyEvent(downEvent))

        assertEquals(84, sentMessages[0].keycode)
    }

    @Test
    fun `volume keys consumed but not sent to bridge`() {
        val downEvent = mockKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)
        assertTrue(controller.onKeyEvent(downEvent))

        // Volume handled locally — no bridge message sent
        assertEquals(0, sentMessages.size)
    }

    @Test
    fun `unhandled key not consumed`() {
        val downEvent = mockKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)
        assertFalse(controller.onKeyEvent(downEvent))
        assertEquals(0, sentMessages.size)
    }

    @Test
    fun `media fast forward sends correct keycode`() {
        val downEvent = mockKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
        assertTrue(controller.onKeyEvent(downEvent))

        assertEquals(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, sentMessages[0].keycode)
    }

    @Test
    fun `media rewind sends correct keycode`() {
        val downEvent = mockKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_REWIND)
        assertTrue(controller.onKeyEvent(downEvent))

        assertEquals(KeyEvent.KEYCODE_MEDIA_REWIND, sentMessages[0].keycode)
    }

    @Test
    fun `long press detected from repeat count`() {
        val longPressEvent = mockKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, repeatCount = 1)
        assertTrue(controller.onKeyEvent(longPressEvent))

        assertEquals(1, sentMessages.size)
        assertTrue(sentMessages[0].longpress)
    }
}
