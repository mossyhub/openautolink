package com.shilapi.xcertplay.transport

/** Finite metadata-only pairing receipts; no values, keys, identifiers or remote text. */
enum class LockdownPairingStage(val receipt: String) {
    CONNECTING("connecting"), HOST_BUID("host-buid"), DEVICE_KEY("device-key"),
    WIFI_ADDRESS("wifi-address"), RECORD_PREPARED("record-prepared"),
    PAIR_REQUESTED("pair-requested"), TRUST_PENDING("trust-pending"), PAIRED("paired")
}
