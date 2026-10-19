// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

object CarPlayMicrophoneExperimentPolicy {
    fun resolveCapability(profile: CarPlayMicrophoneExperimentProfile?, wired: Boolean,
        baseline: MicrophoneCapability, rows: List<Map<String, Any?>>): MicrophoneCapability {
        val codec = profile?.codec ?: CarPlayMicrophoneCodec.AUTO
        if (codec == CarPlayMicrophoneCodec.AUTO) return baseline
        val allowed = when (codec) {
            CarPlayMicrophoneCodec.OPUS -> MicrophoneSupport.opusBits
            CarPlayMicrophoneCodec.LPCM -> if (wired) MicrophoneSupport.pcmBits else emptySet()
            CarPlayMicrophoneCodec.AUTO -> error("unreachable")
        }
        val filtered = MicrophoneCapability.of(listOf("default", "telephony", "speechrecognition").associateWith { kind ->
            val rowMask = rows.filter { it["type"] == 100 && (it["audioType"] as? String)?.lowercase(java.util.Locale.ROOT) == kind }
                .fold(0L) { mask, row -> mask or ((row["audioOutputFormats"] as? Number)?.toLong() ?: 0L) }
            baseline.bits(kind).intersect(allowed).filter { it and rowMask != 0L }.toSet()
        })
        require(filtered.available) { "CarPlay microphone experiment has no qualified input" }
        return filtered
    }
}
