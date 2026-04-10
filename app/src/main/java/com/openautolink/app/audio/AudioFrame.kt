package com.openautolink.app.audio

import com.openautolink.app.transport.AudioPurpose

/**
 * A single audio frame received from the bridge via OAL protocol.
 * 8-byte header: direction(u8) + purpose(u8) + sample_rate(u16le) + channels(u8) + payload_length(u24le)
 */
data class AudioFrame(
    val direction: Int,       // 0=playback (bridge→app), 1=mic (app→bridge)
    val purpose: AudioPurpose,
    val sampleRate: Int,
    val channels: Int,
    val data: ByteArray
) {
    val isPlayback: Boolean get() = direction == DIRECTION_PLAYBACK
    val isMic: Boolean get() = direction == DIRECTION_MIC

    companion object {
        const val HEADER_SIZE = 8
        const val DIRECTION_PLAYBACK = 0
        const val DIRECTION_MIC = 1
        const val MAX_PAYLOAD_SIZE = 16 * 1024 * 1024 // 16MB sanity limit (u24le max)

        /**
         * Maps the wire purpose byte to AudioPurpose enum.
         * 0=media, 1=nav, 2=assistant, 3=call, 4=alert
         */
        fun purposeFromByte(b: Int): AudioPurpose? = when (b) {
            0 -> AudioPurpose.MEDIA
            1 -> AudioPurpose.NAVIGATION
            2 -> AudioPurpose.ASSISTANT
            3 -> AudioPurpose.PHONE_CALL
            4 -> AudioPurpose.ALERT
            else -> null
        }

        /**
         * Maps AudioPurpose enum to the wire purpose byte.
         */
        fun purposeToByte(purpose: AudioPurpose): Int = when (purpose) {
            AudioPurpose.MEDIA -> 0
            AudioPurpose.NAVIGATION -> 1
            AudioPurpose.ASSISTANT -> 2
            AudioPurpose.PHONE_CALL -> 3
            AudioPurpose.ALERT -> 4
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioFrame) return false
        return direction == other.direction && purpose == other.purpose &&
                sampleRate == other.sampleRate && channels == other.channels &&
                data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = direction
        result = 31 * result + purpose.hashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channels
        result = 31 * result + data.contentHashCode()
        return result
    }
}

data class AudioStats(
    val activePurposes: Set<AudioPurpose> = emptySet(),
    val underruns: Map<AudioPurpose, Long> = emptyMap(),
    val framesWritten: Map<AudioPurpose, Long> = emptyMap(),
    val sampleRate: Int = 0,
    val maxWriteMs: Map<AudioPurpose, Long> = emptyMap(),
    val slowWrites: Map<AudioPurpose, Long> = emptyMap(),
    val maxGapMs: Map<AudioPurpose, Long> = emptyMap(),
    val hwUnderruns: Map<AudioPurpose, Long> = emptyMap(),
)
