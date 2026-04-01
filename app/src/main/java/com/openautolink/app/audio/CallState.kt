package com.openautolink.app.audio

/**
 * Phone call lifecycle state, derived from active audio purposes.
 *
 * Transitions:
 *   IDLE → RINGING: alert purpose starts (incoming/outgoing ring tone)
 *   IDLE → IN_CALL: phone_call purpose starts without prior ring
 *   RINGING → IN_CALL: phone_call purpose starts (call answered)
 *   IN_CALL → IDLE: phone_call purpose stops
 *   RINGING → IDLE: alert purpose stops without phone_call starting (rejected/timeout)
 */
enum class CallState {
    /** No call activity. */
    IDLE,

    /** Incoming or outgoing call ringing (alert purpose active, no phone_call yet). */
    RINGING,

    /** Active phone call (phone_call purpose active). */
    IN_CALL
}
