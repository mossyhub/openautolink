package com.openautolink.carplaylab

import com.shilapi.xcertplay.iap2.message.Iap2CarPlayMessages
import com.shilapi.xcertplay.iap2.message.Iap2WirelessMessages
import com.shilapi.xcertplay.iap2.message.Iap2WirelessSessionParameters
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Only constructed after a real listener has bound. Never log this object or its frames. */
class EndpointOffer(private val ssid: String, private val passphrase: String,
    private val channel: Int, private val security: Int, addresses: List<String>,
    private val port: Int, private val pairingId: String, private val publicKeyHex: String) {
    private val addresses = addresses.toList()
    /** One fresh bounded reply budget per bootstrap transport. No internal policy exposure. */
    fun newReplyChannel(): (Iap2Frame) -> Iap2Frame? {
        val policy = WirelessReplyPolicy()
        return { frame -> if (policy.allow(frame.messageId)) replyTo(frame) else null }
    }
    fun replyTo(frame: Iap2Frame): Iap2Frame? = when (frame.messageId) {
        0x4300 -> Iap2CarPlayMessages.startSession(
            airPlayPort = port, deviceIdentifier = pairingId, publicKey = publicKeyHex,
            sourceVersion = com.openautolink.carplaylab.media.AIRPLAY_SOURCE_VERSION, wireless = Iap2WirelessSessionParameters(
                ssid, passphrase, channel, addresses, security))
        0x5702, 0x4e0e -> Iap2WirelessMessages.accessoryWiFiConfiguration(ssid, passphrase, channel, security)
        else -> null
    }
}
