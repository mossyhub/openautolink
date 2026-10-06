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

    @Test
    fun `GM F7 maps to media next`() {
        val downEvent = mockKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F7)
        assertTrue(controller.onKeyEvent(downEvent))

        assertEquals(1, sentMessages.size)
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, sentMessages[0].keycode)
    }

    @Test
    fun `GM F6 maps to media previous`() {
        val downEvent = mockKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F6)
        assertTrue(controller.onKeyEvent(downEvent))

        assertEquals(1, sentMessages.size)
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, sentMessages[0].keycode)
    }

    @Test
    fun `GM F8 maps to media previous`() {
        val downEvent = mockKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F8)
        assertTrue(controller.onKeyEvent(downEvent))

        assertEquals(1, sentMessages.size)
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, sentMessages[0].keycode)
    }

    @Test
    fun `GM F8 sends previous on both edges`() {
        assertTrue(controller.onKeyEvent(mockKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F8)))
        assertTrue(controller.onKeyEvent(mockKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F8)))

        assertEquals(
            listOf(
                ControlMessage.Button(keycode = 88, down = true, metastate = 0, longpress = false),
                ControlMessage.Button(keycode = 88, down = false, metastate = 0, longpress = false),
            ),
            sentMessages,
        )
    }

    @Test
    fun `custom F8 play pause overrides fallback on both edges`() {
        controller.customKeyMap = mapOf(KeyEvent.KEYCODE_F8 to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        assertTrue(controller.onKeyEvent(mockKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F8)))
        assertTrue(controller.onKeyEvent(mockKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F8)))

        assertEquals(
            listOf(
                ControlMessage.Button(keycode = 85, down = true, metastate = 0, longpress = false),
                ControlMessage.Button(keycode = 85, down = false, metastate = 0, longpress = false),
            ),
            sentMessages,
        )
    }

    @Test
    fun `clearing custom F8 override restores previous live`() {
        controller.customKeyMap = mapOf(KeyEvent.KEYCODE_F8 to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        assertTrue(controller.onKeyEvent(mockKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F8)))
        assertTrue(controller.onKeyEvent(mockKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F8)))
        assertEquals(listOf(85, 85), sentMessages.map { it.keycode })

        controller.customKeyMap = emptyMap()
        sentMessages.clear()
        assertTrue(controller.onKeyEvent(mockKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F8)))
        assertTrue(controller.onKeyEvent(mockKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F8)))
        assertEquals(listOf(88, 88), sentMessages.map { it.keycode })
        assertEquals(listOf(true, false), sentMessages.map { it.down })
    }

    @Test
    fun `unrelated custom key preserves F8 fallback`() {
        controller.customKeyMap = mapOf(KeyEvent.KEYCODE_A to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        assertTrue(controller.onKeyEvent(mockKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))
        assertTrue(controller.onKeyEvent(mockKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A)))
        assertTrue(controller.onKeyEvent(mockKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F8)))
        assertTrue(controller.onKeyEvent(mockKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F8)))

        assertEquals(listOf(85, 85, 88, 88), sentMessages.map { it.keycode })
        assertEquals(listOf(true, false, true, false), sentMessages.map { it.down })
    }

    @Test
    fun `GM F7 keeps next on both edges`() {
        assertTrue(controller.onKeyEvent(mockKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F7)))
        assertTrue(controller.onKeyEvent(mockKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F7)))

        assertEquals(listOf(87, 87), sentMessages.map { it.keycode })
        assertEquals(listOf(true, false), sentMessages.map { it.down })
        assertTrue(sentMessages.none { it.longpress })
    }

    @Test
    fun `standard media keys retain direct paired forwarding`() {
        for (keycode in listOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
        )) {
            sentMessages.clear()
            assertTrue(controller.onKeyEvent(mockKeyEvent(KeyEvent.ACTION_DOWN, keycode)))
            assertTrue(controller.onKeyEvent(mockKeyEvent(KeyEvent.ACTION_UP, keycode)))
            assertEquals(listOf(keycode, keycode), sentMessages.map { it.keycode })
            assertEquals(listOf(true, false), sentMessages.map { it.down })
            assertTrue(sentMessages.all { it.metastate == 0 && !it.longpress })
        }
    }

    @Test
    fun `F keys and standard media retain repeat down semantics`() {
        for ((keycode, expected) in listOf(
            KeyEvent.KEYCODE_F8 to 88,
            KeyEvent.KEYCODE_F7 to 87,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS to 88,
            KeyEvent.KEYCODE_MEDIA_NEXT to 87,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to 85,
        )) {
            sentMessages.clear()
            assertTrue(controller.onKeyEvent(mockKeyEvent(KeyEvent.ACTION_DOWN, keycode)))
            assertTrue(controller.onKeyEvent(mockKeyEvent(KeyEvent.ACTION_DOWN, keycode, repeatCount = 2)))
            assertTrue(controller.onKeyEvent(mockKeyEvent(KeyEvent.ACTION_UP, keycode, repeatCount = 2)))
            assertEquals(listOf(expected, expected, expected), sentMessages.map { it.keycode })
            assertEquals(listOf(true, true, false), sentMessages.map { it.down })
            assertEquals(listOf(false, true, false), sentMessages.map { it.longpress })
            assertTrue(sentMessages.all { it.metastate == 0 })
        }
    }
}
