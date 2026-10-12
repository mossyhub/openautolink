package com.openautolink.app.carplay

enum class CarPlayFailureKind {
    USB_PASSWORD_PROTECTED, USB_USER_DENIED, USB_TRUST_PENDING_TIMEOUT, USB_INVALID_RECORD, USB_TRANSPORT_LOSS, USB_PERMISSION, USB_SETUP,
    NETWORK_SELECTION, BOOTSTRAP_IDENTIFICATION, BOOTSTRAP_AUTHENTICATION, WIRELESS_REQUEST_TIMEOUT,
    LISTENER_BIND, ADMISSION_POLICY, RUNTIME_IDENTIFICATION, RUNTIME_AUTHENTICATION, RUNTIME_TRANSPORT, RUNTIME_STARTUP_TIMEOUT,
}

/** Finite safe UI contract. Preserve the kind; never infer an IP change from a timeout. */
class CarPlayConnectionFailure(val kind: CarPlayFailureKind, val manualPeer: Boolean, cause: Throwable? = null) :
    java.io.IOException(kind.name, cause) {
    val userGuidance: String get() = when (kind) {
        CarPlayFailureKind.USB_PASSWORD_PROTECTED -> "The iPhone reported passcode protection. Enter the passcode on the iPhone. Automatic USB retry is paused; unplug and reconnect once when ready."
        CarPlayFailureKind.USB_USER_DENIED -> "The iPhone reported that Trust was declined. Automatic USB retry is paused; reconnect only when you want to approve Trust."
        CarPlayFailureKind.USB_TRUST_PENDING_TIMEOUT -> "The iPhone Trust response remained pending until the pairing deadline. Automatic USB retry is paused; finish on-phone approval, then reconnect once."
        CarPlayFailureKind.USB_INVALID_RECORD -> "The USB pairing record is invalid or was rejected. It has not been deleted or replaced automatically. Check pairing recovery before reconnecting."
        CarPlayFailureKind.USB_TRANSPORT_LOSS -> "The USB data stream ended. This does not establish a cable or power fault. Check the USB connection before reconnecting."
        CarPlayFailureKind.USB_PERMISSION -> "USB permission was not granted. Automatic USB retry is paused; reconnect when ready to approve permission."
        CarPlayFailureKind.USB_SETUP -> "USB setup failed. Automatic USB retry is paused for this attachment; check diagnostics before reconnecting."
        CarPlayFailureKind.NETWORK_SELECTION -> "Check the selected car Wi-Fi interface, address family and local address."
        CarPlayFailureKind.BOOTSTRAP_IDENTIFICATION -> "Selected-phone Bluetooth identification failed; check the selected bond and retry."
        CarPlayFailureKind.BOOTSTRAP_AUTHENTICATION -> "Receiver MFi authentication failed on the selected Bluetooth link; check private provisioning, not the phone IP."
        CarPlayFailureKind.WIRELESS_REQUEST_TIMEOUT -> "No selected-phone wireless request arrived. Accept iPhone CarPlay permission; if automatic setup is unavailable, join car Wi-Fi and configure the numeric phone IP."
        CarPlayFailureKind.LISTENER_BIND -> "CarPlay listener could not bind; check the selected interface and local address."
        CarPlayFailureKind.ADMISSION_POLICY -> "CarPlay admission policy rejected setup; check authorization and saved controller trust."
        CarPlayFailureKind.RUNTIME_IDENTIFICATION -> "Wireless iAP2 identification failed; check receiver compatibility and retry."
        CarPlayFailureKind.RUNTIME_AUTHENTICATION -> "Wireless runtime authentication failed; check receiver MFi provisioning and saved controller trust."
        CarPlayFailureKind.RUNTIME_TRANSPORT -> "Authenticated wireless transport ended unexpectedly; check the connection and reconnect."
        CarPlayFailureKind.RUNTIME_STARTUP_TIMEOUT -> if (manualPeer)
            "CarPlay connection timed out. Check iPhone Wi-Fi is joined to the car AP and verify the current iPhone IP in OAL, then reconnect."
            else "CarPlay startup timed out. Check iPhone CarPlay permission and car Wi-Fi; automatic endpoint setup is preferred, or use explicit numeric phone IP after joining the car AP."
    }
}
