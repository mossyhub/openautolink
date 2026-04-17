package com.openautolink.app.input

import android.os.SystemClock
import android.view.MotionEvent
import com.openautolink.app.transport.ControlMessage
import java.util.concurrent.Executors

/**
 * Converts Android MotionEvents to OAL touch control messages and sends them
 * via the provided callback. Handles single-touch and multi-touch events.
 *
 * Touch events are forwarded on a dedicated thread to avoid blocking the
 * main thread with JNI calls (prevents ANR on emulator/slow devices).
 * MOVE events are throttled to 60Hz — AA doesn't need higher resolution.
 *
 * OAL action codes (matching Android MotionEvent):
 * - 0 = ACTION_DOWN
 * - 1 = ACTION_UP
 * - 2 = ACTION_MOVE
 * - 3 = ACTION_CANCEL
 * - 5 = ACTION_POINTER_DOWN
 * - 6 = ACTION_POINTER_UP
 *
 * Single-touch uses x/y/pointer_id fields.
 * Multi-touch (2+ pointers) uses the pointers array.
 */
class TouchForwarderImpl(
    private val sendMessage: (ControlMessage.Touch) -> Unit
) : TouchForwarder {

    private val touchExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OAL-touch").apply { isDaemon = true }
    }

    // Throttle MOVE events to 60Hz (16ms minimum interval)
    @Volatile private var lastMoveTimeMs = 0L
    private val moveIntervalMs = 16L

    override fun onTouch(
        event: MotionEvent,
        surfaceWidth: Int,
        surfaceHeight: Int,
        videoWidth: Int,
        videoHeight: Int
    ) {
        if (surfaceWidth <= 0 || surfaceHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return

        val actionMasked = event.actionMasked

        // Throttle MOVE events — AA doesn't need >60Hz touch resolution
        if (actionMasked == MotionEvent.ACTION_MOVE) {
            val now = SystemClock.uptimeMillis()
            if (now - lastMoveTimeMs < moveIntervalMs) return
            lastMoveTimeMs = now
        }

        // Map Android action to OAL action code
        val oalAction = when (actionMasked) {
            MotionEvent.ACTION_DOWN -> 0
            MotionEvent.ACTION_UP -> 1
            MotionEvent.ACTION_MOVE -> 2
            MotionEvent.ACTION_CANCEL -> 3
            MotionEvent.ACTION_POINTER_DOWN -> 5
            MotionEvent.ACTION_POINTER_UP -> 6
            else -> return // Ignore hover, scroll, etc.
        }

        val pointerCount = event.pointerCount

        if (pointerCount == 1 && actionMasked != MotionEvent.ACTION_POINTER_DOWN
            && actionMasked != MotionEvent.ACTION_POINTER_UP
        ) {
            // Single-touch: use x/y/pointer_id fields
            val (scaledX, scaledY) = TouchScaler.scalePoint(
                event.x, event.y,
                surfaceWidth, surfaceHeight,
                videoWidth, videoHeight
            )
            val msg = ControlMessage.Touch(
                action = oalAction,
                x = scaledX,
                y = scaledY,
                pointerId = event.getPointerId(0),
                pointers = null
            )
            touchExecutor.execute { sendMessage(msg) }
        } else {
            // Multi-touch: use pointers array
            val pointers = (0 until pointerCount).map { i ->
                val (scaledX, scaledY) = TouchScaler.scalePoint(
                    event.getX(i), event.getY(i),
                    surfaceWidth, surfaceHeight,
                    videoWidth, videoHeight
                )
                ControlMessage.Pointer(
                    id = event.getPointerId(i),
                    x = scaledX,
                    y = scaledY
                )
            }
            val msg = ControlMessage.Touch(
                action = oalAction,
                x = null,
                y = null,
                pointerId = null,
                pointers = pointers,
                actionIndex = event.actionIndex
            )
            touchExecutor.execute { sendMessage(msg) }
        }
    }
}
