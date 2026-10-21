// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import java.util.Collections

/** Immutable numeric census of one successfully released live owner. No PCM or platform identity. */
class CaptureTerminalSummary(
    val receiver: Long, val owner: Long, val history: History, val profile: Profile,
    val source: MicrophoneSource, val captureRate: Int, val captureChannels: Int, val wireRate: Int,
    val selection: Selection, val reason: CaptureAcquisitionLedger.Termination,
    val blocks: Long, val nonzeroBlocks: Long, val partialBytes: Int,
    raw: Pcm16Window.Snapshot, converted: Pcm16Window.Snapshot,
    val route: Route = Route.UNKNOWN,
) {
    enum class History { NORMAL, MANUAL }
    enum class Profile { DEFAULT, EXPERIMENT }
    enum class Selection { AVERAGE, FIRST, SECOND }
    enum class Route { UNKNOWN, SAME, CHANGED, UNAVAILABLE }
    val raw = freeze(raw)
    val converted = freeze(converted)
    init {
        require(receiver > 0 && owner > 0 && captureRate in listOf(24000,48000) && wireRate in listOf(24000,48000))
        require(captureChannels in 1..2 && this.raw.channels.size == captureChannels && this.converted.channels.size == 1)
        require(blocks >= 0 && nonzeroBlocks in 0..blocks && partialBytes >= 0)
    }
    fun withPcm(raw: Pcm16Window.Snapshot, converted: Pcm16Window.Snapshot) = CaptureTerminalSummary(
        receiver,owner,history,profile,source,captureRate,captureChannels,wireRate,selection,reason,
        blocks,nonzeroBlocks,partialBytes,raw,converted,route)
    fun lines(): List<String> {
        val prefix = "media stage=microphone-terminal receiver=$receiver owner=$owner"
        val signal = if (raw.observedFrames == 0L) "no-frames" else if (raw.channels.all { it.nonzero == 0 }) "zeros" else "nonzero"
        val summary = "$prefix part=summary history=${history.name.lowercase()} profile=${profile.name.lowercase()} source=$source captureRate=$captureRate captureChannels=$captureChannels wireRate=$wireRate selection=$selection reason=${reason.name.lowercase()} state=released blocks=$blocks nonzeroBlocks=$nonzeroBlocks partialBytes=$partialBytes signal=$signal route=${route.name.lowercase()}"
        fun pcm(boundary: String, value: Pcm16Window.Snapshot): String {
            fun ch(index:Int):String {
                val c=value.channels.getOrNull(index) ?: Pcm16Window.Channel(0,0,0,0,0)
                return "ch${index}Samples=${c.samples} ch${index}Nonzero=${c.nonzero} ch${index}Peak=${c.peak} ch${index}Rms=${c.rms} ch${index}Clip=${c.clipped}"
            }
            return "$prefix part=$boundary observed=${value.observedFrames} scanned=${value.scannedFrames} tail=${value.tailBytes} channels=${value.channels.size} limit=2048 coverage=${if(value.observedFrames > value.scannedFrames) "prefix" else "full"} ${ch(0)} ${ch(1)}"
        }
        return listOf(summary,pcm("raw",raw),pcm("converted",converted))
    }
    companion object {
        private fun freeze(value:Pcm16Window.Snapshot):Pcm16Window.Snapshot {
            require(value.observedFrames >= 0 && value.scannedFrames in 0..2048 && value.scannedFrames <= value.observedFrames && value.tailBytes >= 0 && value.channels.size in 1..2)
            value.channels.forEach { require(it.samples == value.scannedFrames && it.nonzero in 0..it.samples && it.peak in 0..32768 && it.rms in 0..32768 && it.clipped in 0..it.samples) }
            return value.copy(channels=Collections.unmodifiableList(ArrayList(value.channels)))
        }
    }
}
