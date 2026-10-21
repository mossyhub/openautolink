// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import android.media.MediaCodecList
import android.media.MediaFormat
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import java.nio.ByteBuffer

/** One immutable device capability snapshot shared by /info, SETUP admission and decoder creation.
 * Enumeration never creates/configures a decoder on the RTSP/UDP callback thread.
 */
object AndroidAudioDecoderSupport {
    private val decoderInventory = lazy {
        runCatching {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
            AudioOutputSupport.implementedBits.mapNotNull { bits ->
                val format = AudioOutputSupport.format(bits, if (bits in setOf(0x400000L,0x800000L)) 102 else 100)!!
                val config = AudioOutputSupport.configuration(format) ?: return@mapNotNull null
                val name = codecs.firstOrNull { codec ->
                    runCatching {
                        val caps = codec.getCapabilitiesForType(config.mime)
                        (config.aacProfile == null || caps.profileLevels.any { it.profile == config.aacProfile }) &&
                            caps.isFormatSupported(mediaFormat(format,config))
                    }.getOrDefault(false)
                }?.name ?: return@mapNotNull null
                bits to name
            }.toMap()
        }.getOrDefault(emptyMap()) // a failed/unavailable platform never becomes a codec offer
    }
    private val decoders: Map<Long,String> get() = decoderInventory.value
    // LPCM is already rendered by AudioTrack, not MediaCodec. Codec discovery failure must not
    // erase those exact implemented outputs or turn decoder inventory into microphone evidence.
    val availableBits: Set<Long> get() {
        CaptureAcquisitionLedger.current.get()?.root?.event(CaptureAcquisitionLedger.Action.DECODER_CACHE,
            if (decoderInventory.isInitialized()) CaptureAcquisitionLedger.Result.HIT else CaptureAcquisitionLedger.Result.MISS)
        return AudioOutputSupport.implementedPcmBits + decoders.keys
    }
    fun decoderName(format: AudioFormat): String? = decoders.entries.firstOrNull { (bits,_) ->
        AudioOutputSupport.format(bits,format.payloadType,format.audioType)?.copy(samplesPerFrame=format.samplesPerFrame) == format
    }?.value
    fun supports(format: AudioFormat): Boolean = format.codec == AudioCodecKind.LPCM || decoderName(format) != null
    internal fun mediaFormat(format: AudioFormat, config: AudioOutputSupport.Configuration): MediaFormat =
        MediaFormat.createAudioFormat(config.mime,format.sampleRate,format.channels).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,4096)
            setInteger(MediaFormat.KEY_PCM_ENCODING,android.media.AudioFormat.ENCODING_PCM_16BIT)
            config.aacProfile?.let { setInteger(MediaFormat.KEY_AAC_PROFILE,it) }
            // Raw AAC access units with ASC, not ADTS (especially important for AAC-ELD).
            if (config.aacProfile != null) setInteger(MediaFormat.KEY_IS_ADTS,0)
            config.csd.forEachIndexed { index,bytes -> setByteBuffer("csd-$index",ByteBuffer.wrap(bytes)) }
        }
}
