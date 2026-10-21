// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import android.media.AudioDeviceInfo
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MicrophoneInfo

/** A finite, process-local logical endpoint vocabulary. IDs never leave this map, never evict.
 * Android can reuse IDs after disconnect: equality does NOT identify a physical transducer.
 */
internal class LogicalRouteOrdinals(private val capacity: Int = 32) {
    init { require(capacity in 1..32) }
    private val ordinals = HashMap<Int, Int>()
    @Synchronized fun ordinal(id: Int): Int? {
        if (id <= 0) return null
        return ordinals[id] ?: if (ordinals.size < capacity) (ordinals.size + 1).also {
            ordinals[id] = it
        } else null
    }
}

/** Observe only this own recorder, on the existing passive policy worker. No allocation of recording
 * resources, inventory, setters, names, addresses or physical microphone claims. A new observer
 * resets stability for each owner epoch; the bounded map lives only for this process lifetime.
 */
class OwnRecorderRouteObserver internal constructor(private val ordinals: LogicalRouteOrdinals) {
    constructor() : this(processOrdinals)
    private var previous: Int? = null

    internal fun receipt(record: AudioRecord, config: Result<AudioRecordingConfiguration?>,
        current: () -> Boolean = { true }, routed: Result<AudioDeviceInfo?>? = null,
        activeDevice: Result<AudioDeviceInfo?>? = null): String? {
        if (!current()) return null
        val route = routed ?: runCatching { record.routedDevice }
        if (!current()) return null
        val active = activeDevice ?: config.mapCatching { if (current()) it?.audioDevice else null }
        if (!current()) return null
        var truncated = false
        fun endpoint(device: Result<AudioDeviceInfo?>): Int? {
            if (!current()) return null
            val id = device.mapCatching { it?.id }.getOrNull() ?: return null
            val ordinal = ordinals.ordinal(id)
            if (id > 0 && ordinal == null) truncated = true
            return ordinal
        }
        val own = endpoint(route)
        val configured = endpoint(active)
        if (!current()) return null
        fun equality(a: Int?, b: Int?): String = when {
            a == null || b == null -> "unknown"
            a == b -> "same"
            else -> "different"
        }
        val stability = equality(previous, own)
        previous = own
        return "media stage=microphone-route scope=own routed=${own ?: "unknown"}" +
            " active=${configured ?: "unknown"} equality=${equality(own, configured)}" +
            " stability=$stability truncated=$truncated"
    }

    /** Public API 28+, current minSdk 32. Row numbers are local observations, not microphone IDs.
     * OS return-list allocation is outside our control; we examine at most four rows/eight pairs.
     * No MicrophoneInfo field other than channelMapping is needed or read.
     */
    internal fun mappingReceipts(record: AudioRecord, current: () -> Boolean = { true }): List<String> {
        if (!current()) return emptyList()
        val microphones = runCatching { record.activeMicrophones }
        if (!current()) return emptyList()
        fun result(error: Throwable?): String = when (error) {
            is SecurityException -> "denied"
            is UnsupportedOperationException -> "unsupported"
            else -> "unavailable"
        }
        fun header(state: String, examined: Int, truncated: Boolean) =
            "media stage=microphone-mapping scope=own result=$state examined=$examined truncated=$truncated"
        if (microphones.isFailure) return listOf(header(result(microphones.exceptionOrNull()), 0, false))
        val rows = microphones.getOrNull() ?: return listOf(header("unavailable", 0, false))
        if (rows.isEmpty()) return listOf(header("empty", 0, false))
        val channels = ArrayList<String>(8)
        var truncated = rows.size > 4
        var examined = 0
        var state = "ok"
        for (row in 0 until minOf(rows.size, 4)) {
            if (!current()) return emptyList()
            examined++
            val mapping = runCatching { rows[row].channelMapping }
            if (!current()) return emptyList()
            if (mapping.isFailure) {
                state = result(mapping.exceptionOrNull())
                // Keep already observed bounded rows, but never call a further getter after failure.
                truncated = truncated || row + 1 < rows.size
                break
            }
            val pairs = mapping.getOrNull()
            if (pairs == null) { state = "unavailable"; truncated = true; break }
            val count = minOf(pairs.size, 8 - channels.size)
            if (pairs.size > count) truncated = true
            for (index in 0 until count) {
                val pair = pairs[index]
                val channel = pair?.first?.takeIf { it in 0..7 }?.toString() ?: "unknown"
                val kind = when (pair?.second) {
                    MicrophoneInfo.CHANNEL_MAPPING_DIRECT -> "direct"
                    MicrophoneInfo.CHANNEL_MAPPING_PROCESSED -> "processed"
                    else -> "unknown"
                }
                channels.add("media stage=microphone-channel scope=own row=$row channel=$channel mapping=$kind")
            }
            if (channels.size == 8 && row + 1 < rows.size) {
                truncated = true
                break // Do not query more microphone rows after the total mapping budget is spent.
            }
        }
        return channels + header(state, examined, truncated)
    }

    private companion object { val processOrdinals = LogicalRouteOrdinals() }
}
