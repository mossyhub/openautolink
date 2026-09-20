package com.openautolink.app.transport.usb

/** USB identities positively known to be head-unit infrastructure, not phones. */
internal object UsbDevicePolicy {
    private val knownNonPhoneDevices = setOf(
        0x0424 to 0x4911, // Microchip USB2517 hub observed on GM AAOS
        0x0424 to 0x49A0, // Microchip USB2 Controller Hub observed on Equinox AAOS
    )

    fun isKnownNonPhoneDevice(vendorId: Int, productId: Int): Boolean =
        vendorId to productId in knownNonPhoneDevices
}
