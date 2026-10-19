// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

enum class MicrophoneDiagnosticComparison { CHANNELS, RATE, PREFERRED, SOURCES, CUSTOM }
/** Finite local-only choices. Never infer or normalize a configuration. */
data class MicrophoneDiagnosticCustom(
    val source: Int = 6,
    val sampleRate: Int = 24000,
    val channelCount: Int = 1,
    val preferredNamedInput: Boolean = false,
) {
    init {
        require(source in setOf(1, 6, 7))
        require(sampleRate in setOf(16000, 24000, 48000))
        require(channelCount in 1..2)
    }
    internal fun shape() = MicrophoneDiagnosticShape(source, sampleRate, channelCount,
        if (channelCount == 2) 12 else 16, 0)
}
enum class MicrophoneDiagnosticPhase { IDLE, INSPECTING, RUNNING, STOPPING, COMPLETE }
enum class MicrophoneDiagnosticReason {
    NONE, CANCELLED, INELIGIBLE, BUSY, PERMISSION, MUTED, MODE,
    POLICY_UNKNOWN, SILENCED, DEADLINE, INVENTORY_UNAVAILABLE,
    PREFERRED_UNAVAILABLE, PREFERRED_REJECTED, SHAPE_MISMATCH, ROUTE_CHANGED, PLATFORM_FAILURE
}
enum class MicrophoneDiagnosticMatch { ABSENT, UNIQUE, MULTIPLE, TRUNCATED, UNAVAILABLE }
data class MicrophoneDiagnosticCapabilities(
    val sampleRates: List<Int>, val channelMasks: List<Int>, val channelIndexMasks: List<Int>,
    val ratesUnconstrained: Boolean, val masksUnconstrained: Boolean,
    val indexMasksUnconstrained: Boolean, val truncated: Boolean
)
data class MicrophoneDiagnosticInventory(
    val match: MicrophoneDiagnosticMatch,
    val inputs: List<MicrophoneDiagnosticCapabilities>
)
data class MicrophoneDiagnosticShape(
    val source: Int, val sampleRate: Int, val channelCount: Int,
    val channelMask: Int, val channelIndexMask: Int
)
data class MicrophoneDiagnosticLevel(
    val samples: Long, val nonzero: Long, val peak: Int, val rms: Double
)
data class MicrophoneDiagnosticStatistics(
    val frames: Long, val nonzeroFrames: Long,
    val channels: List<MicrophoneDiagnosticLevel>,
    val sum: MicrophoneDiagnosticLevel?, val difference: MicrophoneDiagnosticLevel?,
    val mean: MicrophoneDiagnosticLevel?
)
data class MicrophoneDiagnosticArm(
    val ordinal: Int, val requested: MicrophoneDiagnosticShape,
    val actual: MicrophoneDiagnosticShape?, val preferredRequested: Boolean,
    val preferredAccepted: Boolean?, val routeMatchesNamedInput: Boolean?,
    val routeSameAsFirst: Boolean?, val statistics: MicrophoneDiagnosticStatistics,
    val client: MicrophoneDiagnosticShape? = null,
    val device: MicrophoneDiagnosticShape? = null,
    val routeStable: Boolean? = null
)
data class MicrophoneDiagnosticResult(
    val comparison: MicrophoneDiagnosticComparison?,
    val inventory: MicrophoneDiagnosticInventory,
    val arms: List<MicrophoneDiagnosticArm>, val reason: MicrophoneDiagnosticReason
)
data class MicrophoneDiagnosticState(
    val phase: MicrophoneDiagnosticPhase = MicrophoneDiagnosticPhase.IDLE,
    val comparison: MicrophoneDiagnosticComparison? = null,
    val armOrdinal: Int? = null,
    val reason: MicrophoneDiagnosticReason = MicrophoneDiagnosticReason.NONE,
    val result: MicrophoneDiagnosticResult? = null
)
