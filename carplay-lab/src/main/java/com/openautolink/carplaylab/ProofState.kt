package com.openautolink.carplaylab
class ProofState {
    var identified = false; private set
    var authenticated = false; private set
    @Volatile private var canceled = false
    @Synchronized fun cancel() { canceled = true }
    fun ensureActive() { check(!canceled) { "Attempt canceled" } }
    @Synchronized fun identificationAccepted() { ensureActive(); identified = true }
    @Synchronized fun authenticationAccepted() { ensureActive(); check(identified); authenticated = true }
}
