// SPDX-License-Identifier: GPL-3.0-only
// Adapted from pinned DiPlay media/OpusEncoder.kt; all codec ownership stays on its worker.
package com.shilapi.xcertplay.media

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import java.io.Closeable

internal class OpusEncoder(name: String, bitrate: Int) : Closeable {
    data class Frame(val bytes: ByteArray, val presentationTimeUs: Long)
    private val codec = MediaCodec.createByCodecName(name)
    private var pts = 0L
    private val info = MediaCodec.BufferInfo()
    init {
        try { codec.configure(format(bitrate),null,null,MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start() }
        catch (failure: Exception) { runCatching { codec.release() }; throw failure }
    }
    fun encode(pcm: ByteArray): List<Frame> {
        require(pcm.size == 1920)
        val output = drain()
        val index = codec.dequeueInputBuffer(10000)
        check(index >= 0) { "Microphone encoder backpressure" }
        val input = checkNotNull(codec.getInputBuffer(index)); input.clear()
        require(input.remaining() >= pcm.size)
        input.put(pcm); codec.queueInputBuffer(index,0,pcm.size,pts,0); pts += 20000
        return output+drain()
    }
    private fun drain(): List<Frame> {
        val result = ArrayList<Frame>()
        repeat(4) {
            val index = codec.dequeueOutputBuffer(info,0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                index >= 0 -> try {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val buffer = checkNotNull(codec.getOutputBuffer(index))
                        require(info.size <= 1275 && info.offset >= 0 && info.size <= buffer.capacity()-info.offset)
                        buffer.position(info.offset); buffer.limit(info.offset+info.size)
                        val bytes = ByteArray(info.size); buffer.get(bytes)
                        require(MicrophoneSupport.opusSamples(bytes) == 960) { "Unsupported Opus packet duration" }
                        result.add(Frame(bytes,info.presentationTimeUs))
                    }
                } finally { codec.releaseOutputBuffer(index,false) }
                else -> return result
            }
        }
        return result
    }
    override fun close() { runCatching { codec.stop() }; runCatching { codec.release() } }
    companion object {
        fun format(bitrate: Int): MediaFormat = MediaFormat.createAudioFormat("audio/opus",48000,1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE,bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,1920)
            setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
        }
        fun inventory(): List<String> = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { info ->
                info.isEncoder && info.supportedTypes.any { it.equals("audio/opus",true) }
            }.map { it.name }
        }.getOrDefault(emptyList())
        fun probe(name: String, bitrate: Int): Boolean {
            // Inventory alone is not configure evidence. Release even a partially configured candidate.
            var candidate: OpusEncoder? = null
            return try {
                val info = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.first { it.name == name && it.isEncoder }
                if (!info.getCapabilitiesForType("audio/opus").isFormatSupported(format(bitrate))) false
                else { candidate = OpusEncoder(name,bitrate); true }
            } catch (_: Exception) { false } finally { candidate?.close() }
        }
    }
}
