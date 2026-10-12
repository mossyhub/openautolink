package com.openautolink.app.carplay

import com.shilapi.xcertplay.airplay.AirPlayContact

data class CarPlayPointer(val id: Int, val x: Float, val y: Float)

/** Stable two-slot HID ownership; never retain an Android MotionEvent past dispatch. */
class CarPlayTouchMapper {
    private val ids = arrayOfNulls<Int>(2)
    private val contacts = Array(2) { AirPlayContact(it, 0.0, 0.0, false) }

    @Synchronized fun reset(): List<AirPlayContact> {
        for (slot in 0..1) { ids[slot] = null; contacts[slot] = contacts[slot].copy(down = false) }
        return contacts.toList()
    }

    @Synchronized fun map(action: Int, actionIndex: Int, pointers: List<CarPlayPointer>, width: Int, height: Int): List<AirPlayContact> {
        if (action == 3 || width <= 0 || height <= 0) return reset()
        if (action == 0) reset()
        if (action !in setOf(0, 1, 2, 5, 6)) return emptyList()
        val changed = pointers.getOrNull(actionIndex)
        if (action == 0 || action == 5) {
            if (changed != null && changed.x.isFinite() && changed.y.isFinite() &&
                changed.x in 0f..width.toFloat() && changed.y in 0f..height.toFloat() && changed.id !in ids) {
                val free = ids.indexOfFirst { it == null }
                if (free >= 0) ids[free] = changed.id
            }
        }
        for (slot in 0..1) {
            val pointer = pointers.firstOrNull { it.id == ids[slot] }
            val releasing = action == 1 || action == 6 && pointer?.id == changed?.id
            if (pointer != null && pointer.x.isFinite() && pointer.y.isFinite()) {
                contacts[slot] = AirPlayContact(slot,
                    (pointer.x / width).coerceIn(0f, 1f).toDouble(),
                    (pointer.y / height).coerceIn(0f, 1f).toDouble(), !releasing)
            } else contacts[slot] = contacts[slot].copy(down = false)
            if (releasing || pointer == null) ids[slot] = null
        }
        return contacts.toList()
    }
}
