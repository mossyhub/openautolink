package com.openautolink.app.ui.diagnostics

// Share the aggregate-only library contract: no lossy duplicate DTO conversion.
typealias MicrophoneDiagnosticComparison = com.shilapi.xcertplay.media.MicrophoneDiagnosticComparison
typealias MicrophoneDiagnosticCustom = com.shilapi.xcertplay.media.MicrophoneDiagnosticCustom
typealias MicrophoneDiagnosticPhase = com.shilapi.xcertplay.media.MicrophoneDiagnosticPhase
typealias MicrophoneDiagnosticReason = com.shilapi.xcertplay.media.MicrophoneDiagnosticReason
typealias MicrophoneDiagnosticMatch = com.shilapi.xcertplay.media.MicrophoneDiagnosticMatch
typealias MicrophoneDiagnosticCapabilities = com.shilapi.xcertplay.media.MicrophoneDiagnosticCapabilities
typealias MicrophoneDiagnosticInventory = com.shilapi.xcertplay.media.MicrophoneDiagnosticInventory
typealias MicrophoneDiagnosticShape = com.shilapi.xcertplay.media.MicrophoneDiagnosticShape
typealias MicrophoneDiagnosticLevel = com.shilapi.xcertplay.media.MicrophoneDiagnosticLevel
typealias MicrophoneDiagnosticStatistics = com.shilapi.xcertplay.media.MicrophoneDiagnosticStatistics
typealias MicrophoneDiagnosticArm = com.shilapi.xcertplay.media.MicrophoneDiagnosticArm
typealias MicrophoneDiagnosticResult = com.shilapi.xcertplay.media.MicrophoneDiagnosticResult
typealias MicrophoneDiagnosticState = com.shilapi.xcertplay.media.MicrophoneDiagnosticState

interface MicrophoneDiagnosticBackend {
    val state: MicrophoneDiagnosticState
    fun inspect(): Boolean
    fun start(comparison: MicrophoneDiagnosticComparison): Boolean
    fun startCustom(config: MicrophoneDiagnosticCustom): Boolean = false
    fun cancel()
}
