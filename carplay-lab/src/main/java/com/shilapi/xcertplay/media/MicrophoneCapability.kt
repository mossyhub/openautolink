// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import java.util.Locale

/** Successful exact encoder configure/start and actual source initialization, not decoder inventory. */
class MicrophoneCapability private constructor(private val formats: Map<String, Set<Long>>) {
    val available: Boolean get() = formats.isNotEmpty()
    fun bits(audioType: String): Set<Long> = formats[audioType.lowercase(Locale.ROOT)].orEmpty().toSet()
    companion object {
        val NONE = MicrophoneCapability(emptyMap())
        fun of(probed: Map<String, Set<Long>>): MicrophoneCapability {
            val safe = linkedMapOf<String, Set<Long>>()
            listOf("default", "telephony", "speechrecognition").forEach { kind ->
                val allowed = if (kind == "speechrecognition") setOf(0x20000000L) else setOf(0x20000000L, 0x40000000L)
                val bits = probed[kind].orEmpty().intersect(allowed)
                if (bits.isNotEmpty()) safe[kind] = bits
            }
            return MicrophoneCapability(safe)
        }
    }
}
