package com.openautolink.app.carplay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/** Finite OS outcomes only; no source strings, identifiers, exceptions or historical mask union. */
enum class CarPlayForegroundStage { START, BASE, MICROPHONE, RETIREMENT, SERVICE, NOTIFICATION }
enum class CarPlayForegroundStatus { REQUESTED, ACKNOWLEDGED, DENIED, LOST, PENDING, FAILED }
enum class CarPlayForegroundMask { UNKNOWN, NONE, BASE, BASE_MICROPHONE }
data class CarPlayForegroundReceipt(val stage: CarPlayForegroundStage, val status: CarPlayForegroundStatus, val mask: CarPlayForegroundMask)

object CarPlayForegroundReceipts {
    private val listeners = CopyOnWriteArrayList<(CarPlayForegroundReceipt) -> Unit>()
    fun observe(listener: (CarPlayForegroundReceipt) -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }
    internal fun emit(stage: CarPlayForegroundStage, status: CarPlayForegroundStatus, mask: Int? = null) {
        val finite = when(mask) {
            null -> CarPlayForegroundMask.UNKNOWN
            0 -> CarPlayForegroundMask.NONE
            CarPlayForegroundPolicy.BASE -> CarPlayForegroundMask.BASE
            CarPlayForegroundPolicy.BASE or CarPlayForegroundPolicy.MIC -> CarPlayForegroundMask.BASE_MICROPHONE
            else -> CarPlayForegroundMask.UNKNOWN
        }
        val receipt = CarPlayForegroundReceipt(stage,status,finite)
        listeners.forEach { runCatching { it(receipt) } }
    }
}

/** Current acknowledged purpose differs from receiver-frozen input. Reading is Binder-free. */
data class CarPlayMicrophoneSnapshot(
    val baseReady: Boolean = false,
    val micReady: Boolean = false,
    val permissionGranted: Boolean = false,
    val inputAdvertised: Boolean? = null,
    val retiring: Boolean = false,
)
internal object CarPlayMicrophoneSnapshots {
    val mutable = MutableStateFlow(CarPlayMicrophoneSnapshot())
    val state: StateFlow<CarPlayMicrophoneSnapshot> = mutable.asStateFlow()
}
