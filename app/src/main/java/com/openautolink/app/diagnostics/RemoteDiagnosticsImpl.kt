package com.openautolink.app.diagnostics

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Local diagnostics implementation.
 *
 * Historically this routed log lines into a TCP log server (since
 * removed). Today there is no remote destination and the in-app ring
 * buffer + file writer are populated directly by [DiagnosticLog.addLocal];
 * we MUST NOT call back into DiagnosticLog from log() — DiagnosticLog
 * also fans out to `instance?.log`, so doing so creates an infinite
 * recursion that's only bounded by [maxMessagesPerWindow] (i.e. every
 * log line was being written ~21 times to the file). log() is therefore
 * a no-op now; the type is kept so TelemetryCollector and other
 * consumers don't have to be refactored.
 */
class RemoteDiagnosticsImpl : RemoteDiagnostics {

    private val _enabled = AtomicBoolean(true)
    override val enabled: Boolean get() = _enabled.get()

    private val _minLevel = AtomicReference(DiagnosticLevel.INFO)
    override val minLevel: DiagnosticLevel get() = _minLevel.get()

    override fun setEnabled(enabled: Boolean) {
        _enabled.set(enabled)
    }

    override fun setMinLevel(level: DiagnosticLevel) {
        _minLevel.set(level)
    }

    override fun log(level: DiagnosticLevel, tag: String, msg: String) {
        // No remote destination. DiagnosticLog.addLocal already handles the
        // local ring buffer + file writer; calling back through
        // DiagnosticLog here would re-enter via `instance?.log` and recurse.
    }

    override fun sendTelemetry(telemetry: TelemetrySnapshot) {
        // Local-only — no remote destination
    }
}
