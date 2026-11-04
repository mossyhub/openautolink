package com.openautolink.app.transport.aasdk

import java.util.UUID
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*

class AasdkSecondaryVideoSelection internal constructor(
    val channelId: Int, val sessionToken: String, val streamEpoch: Long,
    val codecType: Int, val configurationIndex: Int, val mediaSessionId: Int,
    val offer: AasdkSecondaryVideoOffer,
)
class AasdkSecondaryVideoFrame internal constructor(
    val selection: AasdkSecondaryVideoSelection, val data: ByteArray,
    val timestampUs: Long, val flags: Int,
)

/** Independent bounded ingress for each frozen optional channel; no frame coroutines. */
internal class AasdkSecondaryVideoStreams(offers: List<AasdkSecondaryVideoOffer> = emptyList()) {
    private class Stream(val offer: AasdkSecondaryVideoOffer) {
        var retired = true
        var epoch = 0L
        var activeAtEpoch = false
        var selection: AasdkSecondaryVideoSelection? = null
        val queue = MutableSharedFlow<AasdkSecondaryVideoFrame>(extraBufferCapacity=4,
            onBufferOverflow=BufferOverflow.DROP_OLDEST)
    }
    private val lock = Any()
    private var streams = AasdkSecondaryVideoOffer.validated(offers).associate { it.channelId to Stream(it) }
    private val ingress = MutableStateFlow(streams.values.toList())
    private var owner = ""
    private var revision = 0L
    private data class Snapshot(val revision: Long, val values: Map<Int,AasdkSecondaryVideoSelection>)
    private val published = MutableStateFlow(Snapshot(0, emptyMap()))
    @OptIn(InternalCoroutinesApi::class)
    val selections: StateFlow<Map<Int,AasdkSecondaryVideoSelection>> = object : StateFlow<Map<Int,AasdkSecondaryVideoSelection>> {
        override val value get() = published.value.values
        override val replayCache get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<Map<Int,AasdkSecondaryVideoSelection>>): Nothing {
            published.map { it.values }.distinctUntilChanged().collect(collector)
            error("StateFlow completed")
        }
    }
    // Merge only after per-output DROP_OLDEST buffers; merged handoff is rendezvous.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val frames: Flow<AasdkSecondaryVideoFrame> = ingress.flatMapLatest { channels ->
        merge(*channels.map { it.queue }.toTypedArray()).buffer(0)
    }.buffer(0).filter { isCurrent(it) }
    fun configure(offers: List<AasdkSecondaryVideoOffer>) {
        val channels = synchronized(lock) {
            streams.values.forEach { it.retired=true;it.selection=null }
            streams=AasdkSecondaryVideoOffer.validated(offers).associate { it.channelId to Stream(it) }
            streams.values.toList()
        }
        ingress.value=channels
    }
    private fun snapshot() = Snapshot(++revision, streams.mapNotNull { (id,s) -> s.selection?.let { id to it } }.toMap())
    private fun publish(snapshot: Snapshot) {
        while (true) {
            val old = published.value
            if (old.revision >= snapshot.revision || published.compareAndSet(old,snapshot)) return
        }
    }
    fun begin(): String {
        val (token,state) = synchronized(lock) {
            owner = UUID.randomUUID().toString()
            streams.values.forEach { it.retired=false;it.epoch=0;it.activeAtEpoch=false;it.selection=null }
            owner to snapshot()
        }
        publish(state)
        return token
    }
    fun retire(channelId: Int? = null, expectedOwner: String? = null) {
        val state = synchronized(lock) {
            if (expectedOwner != null && expectedOwner != owner) return
            streams.filterKeys { channelId == null || it == channelId }.values.forEach { it.retired=true;it.selection=null }
            snapshot()
        }
        publish(state)
    }
    fun onState(expectedOwner: String, channelId: Int, epoch: Long, codec: Int, index: Int, mediaSessionId: Int, active: Boolean) {
        val state = synchronized(lock) {
            val s=streams[channelId] ?: return
            if (s.retired || expectedOwner != owner || epoch <= 0 || epoch < s.epoch || (epoch == s.epoch && s.activeAtEpoch)) return
            if (epoch > s.epoch) s.activeAtEpoch=false
            s.epoch=epoch;s.selection=null
            if (active && codec == AasdkSecondaryVideoOffer.CODEC_TYPE && index == 0) {
                s.activeAtEpoch=true
                s.selection=AasdkSecondaryVideoSelection(channelId,owner,epoch,codec,index,mediaSessionId,s.offer)
            }
            snapshot()
        }
        publish(state)
    }
    fun onFrame(expectedOwner: String, channelId: Int, data: ByteArray, timestampUs: Long, epoch: Long, flags: Int) {
        var revoked: Snapshot? = null
        val packet = synchronized(lock) {
            val s=streams[channelId] ?: return
            val selection=s.selection ?: return
            if (s.retired || expectedOwner != owner || selection.streamEpoch != epoch) return
            if (data.size > 2*1024*1024) {s.retired=true;s.selection=null;revoked=snapshot();null}
            else if(data.isNotEmpty()) s.queue to AasdkSecondaryVideoFrame(selection,data,timestampUs,flags) else null
        }
        revoked?.let(::publish)
        packet?.let { (queue,frame) -> queue.tryEmit(frame) }
    }
    fun isCurrent(frame: AasdkSecondaryVideoFrame): Boolean = synchronized(lock) {
        streams[frame.selection.channelId]?.let { !it.retired && it.selection === frame.selection } == true
    }
}
