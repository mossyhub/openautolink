package com.shilapi.xcertplay.airplay

/** Exact AirPlayHid array selectors, NOT HID usage IDs or GM's two-bit descriptor.
 * USB HUT1.13 §14.3: Hook Switch off-hook; Drop disconnects active call.
 * Incoming Reject semantics are not established; deliberately no REJECT control.
 */
enum class PhoneControl(internal val selector: Int) { ANSWER(1), END(3) }

/** Actual local write outcomes, never phone acknowledgement. Failure may have emitted bytes. */
enum class PhoneControlOutcome {
    WITHHELD, WRITTEN,
    PRESS_WRITTEN_RELEASE_FAILED, PRESS_WRITTEN_RELEASE_UNAVAILABLE,
    PRESS_FAILED_RELEASE_WRITTEN, PRESS_FAILED_RELEASE_FAILED, PRESS_FAILED_RELEASE_UNAVAILABLE,
}
