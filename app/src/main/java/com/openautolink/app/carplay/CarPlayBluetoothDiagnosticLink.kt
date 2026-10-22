package com.openautolink.app.carplay

import com.openautolink.carplaylab.metadata.RuntimeMetadataLink

/** Passive device-wide evidence attached to one authenticated metadata link, not route authority. */
internal class CarPlayBluetoothDiagnosticLink(
    private val delegate: RuntimeMetadataLink,
    subscribe: ((String) -> Unit) -> AutoCloseable?,
    private val receipt: (String) -> Unit,
) : RuntimeMetadataLink by delegate {
    private val lock = Any()
    private var authenticated = false
    private var retired = false
    private var remaining = 512
    private val diagnosticSession = Any().also {
        com.openautolink.app.diagnostics.BluetoothReconnectService.session(it, "created")
    }
    private val subscription = subscribe { line ->
        synchronized(lock) {
            if (authenticated && !retired && remaining > 0) {
                remaining--
                receipt(line)
            }
        }
    }

    override fun authenticate() {
        delegate.authenticate()
        synchronized(lock) { if (!retired) authenticated = true }
        if (synchronized(lock) { authenticated && !retired }) {
            com.openautolink.app.diagnostics.BluetoothReconnectService.session(diagnosticSession, "authenticated")
        }
    }

    override fun revoke() {
        val detach = synchronized(lock) {
            if (retired) false else { retired = true; authenticated = false; true }
        }
        // Never hold the link monitor while taking the observer's subscription monitor.
        if (detach) {
            subscription?.close()
            com.openautolink.app.diagnostics.BluetoothReconnectService.session(diagnosticSession, "retired")
        }
        delegate.revoke()
    }

    override fun close() {
        revoke()
        delegate.close()
    }
}
