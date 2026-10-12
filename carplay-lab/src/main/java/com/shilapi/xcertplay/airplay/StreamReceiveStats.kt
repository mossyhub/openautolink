// SPDX-License-Identifier: GPL-3.0-only
// Adapted from DiPlay e2fd8eadf5a6b705666f292055e72aa93387533d; see MEDIA-PROVENANCE.md.
package com.shilapi.xcertplay.airplay

/** Receive-thread timing only: no payloads, endpoint addresses, or route data. */
internal class StreamReceiveStats(
    private val label: String,
    private val report: (String) -> Unit,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private var windowStart = nowNs()
    private var readStart = windowStart
    private var processingStart = windowStart
    private var packets = 0
    private var bytes = 0L
    private var maxReadNs = 0L
    private var readsOver250Ms = 0
    private var seqGapAfterReadOver250Ms = 0
    private var seqMissingAfterReadOver250Ms = 0
    private var lastReceivedNs = 0L
    private var maxInterArrivalNs = 0L
    private var maxProcessNs = 0L
    private var nextSequence: Int? = null
    private var forwardGapPackets = 0
    private var sequenceGapEvents = 0
    private var maxSequenceGap = 0
    private var lateOrDuplicate = 0
    private var decryptSamples = 0
    private var decryptBytes = 0L
    private var decryptSumNs = 0L
    private var maxDecryptNs = 0L

    fun reading() { readStart = nowNs() }

    fun received(size: Int, sequence: Int? = null, timestamp: Int? = null) {
        processingStart = nowNs()
        val readNs = processingStart - readStart
        maxReadNs = maxOf(maxReadNs, readNs)
        if (readNs >= LONG_READ_NS) readsOver250Ms++
        if (lastReceivedNs != 0L) {
            maxInterArrivalNs = maxOf(maxInterArrivalNs, processingStart - lastReceivedNs)
        }
        lastReceivedNs = processingStart
        packets++
        bytes += size
        if (sequence != null) {
            val expected = nextSequence
            val delta = if (expected == null) 0 else (sequence - expected) and 0xffff
            if (delta < 0x8000) {
                forwardGapPackets += delta
                if (delta > 0) {
                    sequenceGapEvents++
                    if (readNs >= LONG_READ_NS) {
                        seqGapAfterReadOver250Ms++
                        seqMissingAfterReadOver250Ms += delta
                    }
                    maxSequenceGap = maxOf(maxSequenceGap, delta)
                }
                nextSequence = (sequence + 1) and 0xffff
            } else lateOrDuplicate++
        }
    }

    /** One payload opened in [durationNs]; reported only for streams that call it. */
    fun decrypted(durationNs: Long, size: Int) {
        decryptSamples++
        decryptBytes += size
        decryptSumNs += durationNs
        maxDecryptNs = maxOf(maxDecryptNs, durationNs)
    }

    fun processed() {
        maxProcessNs = maxOf(maxProcessNs, nowNs() - processingStart)
        flush()
    }

    fun flush(ended: Boolean = false) {
        val now = nowNs()
        if (!ended && now - windowStart < 5_000_000_000L) return
        // Labels originate at fixed callers; never echo an unknown label.
        val qualification = when (label) {
            "video" -> "kind=video type=110"
            "audio type=100" -> "kind=audio type=100"
            "audio type=101" -> "kind=audio type=101"
            "audio type=102" -> "kind=audio type=102"
            else -> null
        }
        if (qualification != null) runCatching {
            report("media stage=receive $qualification packets=$packets bytes=$bytes " +
                "missing=$forwardGapPackets gaps=$sequenceGapEvents late=$lateOrDuplicate maxGap=$maxSequenceGap " +
                "readMs=${maxReadNs / 1_000_000} arrivalMs=${maxInterArrivalNs / 1_000_000} " +
                "processMs=${maxProcessNs / 1_000_000} longReads=$readsOver250Ms ended=$ended")
        }
        windowStart = now
        decryptSamples = 0
        decryptBytes = 0
        decryptSumNs = 0
        maxDecryptNs = 0
        packets = 0
        bytes = 0
        maxReadNs = 0
        readsOver250Ms = 0
        seqGapAfterReadOver250Ms = 0
        seqMissingAfterReadOver250Ms = 0
        maxInterArrivalNs = 0
        maxProcessNs = 0
        forwardGapPackets = 0
        sequenceGapEvents = 0
        maxSequenceGap = 0
        lateOrDuplicate = 0
    }

    private fun Int.toUnsignedLong(): Long = toLong() and 0xffff_ffffL

    private fun decryptSummary(): String {
        if (decryptSamples == 0) return ""
        val mbPerSecond = if (decryptSumNs == 0L) 0L else decryptBytes * 1_000L / decryptSumNs
        return " decryptAvgUs=${decryptSumNs / decryptSamples / 1000} decryptMaxUs=${maxDecryptNs / 1000} " +
            "decryptMBps=$mbPerSecond"
    }

    private companion object { const val LONG_READ_NS = 250_000_000L }

}
