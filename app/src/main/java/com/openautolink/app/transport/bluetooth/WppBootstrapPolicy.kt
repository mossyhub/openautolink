package com.openautolink.app.transport.bluetooth

enum class LateCompanionAction {
    DIAL_COMPANION,
    QUEUE_READVERTISE,
    READVERTISE,
    IGNORE,
}

object WppBootstrapPolicy {
    /**
     * One startup opportunity, measured from the actual credential handoff, not
     * resolver/scanner launch. AaProxy waits 30s after AA attaches; attachment
     * timing is not observable here, so this is a bounded opportunity, not proof
     * the phone's waiter is still live. Control/attempt retirement ends it early.
     */
    const val DISCOVERY_DEADLINE_MS = 30_000L

    fun onCompanionReachable(
        bootstrapLoopbackPending: Boolean,
        usesReservedProxyPort: Boolean,
        handshakeInFlight: Boolean,
        sessionStreaming: Boolean,
    ): LateCompanionAction = when {
        sessionStreaming || !bootstrapLoopbackPending -> LateCompanionAction.IGNORE
        usesReservedProxyPort -> LateCompanionAction.DIAL_COMPANION
        handshakeInFlight -> LateCompanionAction.QUEUE_READVERTISE
        else -> LateCompanionAction.READVERTISE
    }
}
