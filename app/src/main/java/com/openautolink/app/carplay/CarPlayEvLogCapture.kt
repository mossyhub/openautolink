package com.openautolink.app.carplay

import com.openautolink.app.diagnostics.DiagnosticFileLogging
import com.openautolink.carplaylab.intake.OalEvWireDiagnostics.Kind
import com.openautolink.carplaylab.intake.OalEvWireDiagnostics.Receipt
import java.util.concurrent.atomic.AtomicLong

/** A file-logging run's bounded, memory-only EV capture. No logger, UI or disk under this lock. */
internal class CarPlayEvLogCapture {
    private class Records {
        var offer: String? = null
        var firstModel: List<String>? = null
        var latestStatus: String? = null
    }

    private val links = linkedMapOf<Long, Records>()
    private var evictedLinks = 0L

    /** Construct per actual iAP2 link; wired bootstrap and its authenticated handoff share one. */
    class Link {
        private val id = nextLink.incrementAndGet()

        fun record(receipt: Receipt): Boolean {
            // Sample the existing file-logging run, without entering FileLogWriter's monitor.
            val capture = DiagnosticFileLogging.writer?.evWireCapture ?: return false
            return capture.retain(id, receipt)
        }
    }

    private fun retain(id: Long, receipt: Receipt): Boolean {
        val expected = if (receipt.kind == Kind.WRITTEN_MODEL) 9 else 1
        if (receipt.lines.size != expected) return false
        val lines = receipt.lines.map { message ->
            // Reuse the existing whole-line numeric allowlist without its rate limiter or cap
            // at the ordinary file sink. A failed line rejects the entire batch.
            val safe = CarPlayAudioDiagnostics.sanitize(id, message) ?: return false
            "CARPLAY_EV link=$id " + safe.removePrefix("CARPLAY_AUDIO session=$id ")
        }
        synchronized(links) {
            val row = links[id] ?: run {
                if (links.size == MAX_LINKS) {
                    links.remove(links.keys.first())
                    if (evictedLinks < Long.MAX_VALUE) evictedLinks++
                }
                Records().also { links[id] = it }
            }
            when (receipt.kind) {
                Kind.PREPARED_OFFER -> if (row.offer == null) row.offer = lines.single()
                Kind.WRITTEN_STATUS -> row.latestStatus = lines.single()
                Kind.WRITTEN_MODEL -> {
                    if (row.firstModel == null) row.firstModel = lines
                    row.latestStatus = lines.first()
                }
            }
        }
        return true
    }

    /** Membership fixed here; bytes are written only by the manual uploader. */
    fun snapshot(): List<String> = synchronized(links) {
        if (links.isEmpty()) return@synchronized emptyList()
        buildList {
            add("carplay_ev_wire_capture=1 links=${links.size} evicted_links=$evictedLinks")
            for (row in links.values) {
                row.offer?.let(::add)
                row.firstModel?.let(::addAll)
                row.latestStatus?.takeIf { it != row.firstModel?.first() }?.let(::add)
            }
        }
    }

    companion object {
        const val FILE_NAME = "carplay_ev_wire.log"
        private const val MAX_LINKS = 8
        private val nextLink = AtomicLong()
    }
}
