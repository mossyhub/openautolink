package com.openautolink.app.transport.aasdk

import java.util.UUID
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter

/** Exact native-session + stream selection. Equality of dimensions is not ownership. */
class AasdkClusterVideoSelection internal constructor(
    val sessionToken: String,
    val streamEpoch: Long,
    val codecType: Int,
    val configurationIndex: Int,
    val mediaSessionId: Int,
    val offer: AasdkClusterVideoOffer,
)

class AasdkClusterVideoFrame internal constructor(
    val selection: AasdkClusterVideoSelection,
    val data: ByteArray,
    val timestampUs: Long,
    val flags: Int,
)

/**
 * Nonblocking JNI ingress. At most four 2 MiB packets; no coroutine per frame,
 * no replay to a successor decoder, and no path to main reconnect/audio/touch.
 * Consumers MUST revalidate [isCurrent] after their own asynchronous queues.
 */
internal class AasdkClusterVideoStream {
    companion object {
        const val MAX_FRAME_BYTES = 2 * 1024 * 1024
        const val QUEUE_CAPACITY = 4
    }
    private val lock = Any()
    private var owner = ""
    private var offer: AasdkClusterVideoOffer? = null
    private var retired = true
    private var lastEpoch = 0L
    private var activeAtEpoch = false
    private val selected = MutableStateFlow<AasdkClusterVideoSelection?>(null)
    val selection: StateFlow<AasdkClusterVideoSelection?> = selected.asStateFlow()
    private val queue = MutableSharedFlow<AasdkClusterVideoFrame>(
        extraBufferCapacity = QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    // Recheck AFTER SharedFlow buffering, not just before enqueue.
    val frames: Flow<AasdkClusterVideoFrame> = queue.filter { isCurrent(it) }

    fun begin(offer: AasdkClusterVideoOffer?): String = synchronized(lock) {
        owner = UUID.randomUUID().toString()
        this.offer = offer
        retired = offer == null
        lastEpoch = 0
        activeAtEpoch = false
        selected.value = null
        owner
    }
    fun retire(expectedOwner: String? = null) = synchronized(lock) {
        if (expectedOwner == null || expectedOwner == owner) {
            retired = true
            selected.value = null
        }
    }
    fun onState(expectedOwner: String, epoch: Long, codec: Int, index: Int, mediaSessionId: Int, active: Boolean) = synchronized(lock) {
        if (retired || expectedOwner != owner || epoch <= 0 || epoch < lastEpoch) return@synchronized
        // Native Start publishes inactive then active for the SAME epoch. Once
        // active, another callback with that epoch cannot revoke or replace it.
        if (epoch == lastEpoch && activeAtEpoch) return@synchronized
        if (epoch > lastEpoch) activeAtEpoch = false
        lastEpoch = epoch
        selected.value = null
        if (active && codec == AasdkClusterVideoOffer.CODEC_TYPE && index == 0) {
            activeAtEpoch = true
            selected.value = AasdkClusterVideoSelection(owner, epoch, codec, index, mediaSessionId, requireNotNull(offer))
        }
    }
    fun onFrame(expectedOwner: String, data: ByteArray, timestampUs: Long, epoch: Long, flags: Int) = synchronized(lock) {
        if (retired || expectedOwner != owner) return@synchronized
        val selection = selected.value ?: return@synchronized
        if (selection.streamEpoch != epoch) return@synchronized
        if (data.size > MAX_FRAME_BYTES) {
            retired = true
            selected.value = null
        } else if (data.isNotEmpty()) {
            queue.tryEmit(AasdkClusterVideoFrame(selection, data, timestampUs, flags))
        }
    }
    fun isCurrent(frame: AasdkClusterVideoFrame): Boolean = synchronized(lock) {
        !retired && selected.value === frame.selection
    }
}
