package com.openautolink.app.transport.carplay.iap2

/**
 * iAP2 Control Session Messages — serialization and deserialization.
 *
 * iAP2 control messages are carried inside DATA frames on session 0.
 *
 * Message format:
 * ```
 * Byte 0-1:   Message start marker (0x40 0x40)
 * Byte 2-3:   Message length (big-endian, includes header)
 * Byte 4-5:   Message ID (big-endian)
 * Byte 6..N:  Parameters
 * ```
 *
 * Parameter format:
 * ```
 * Byte 0-1:   Parameter length (big-endian, includes this header)
 * Byte 2-3:   Parameter ID (big-endian)
 * Byte 4..N:  Parameter data
 * ```
 *
 * Some parameters are "groups" containing nested parameters.
 */

/** Message start marker */
const val IAP2_MSG_MARKER_1: Byte = 0x40
const val IAP2_MSG_MARKER_2: Byte = 0x40

/**
 * iAP2 Message IDs.
 * Extracted from Cinemo SDK constants and Apple MFi spec.
 *
 * Convention: messages sent by accessory (us) have IDs starting from 0x4xxx.
 * Messages sent by device (iPhone) start from 0x4xxx as well but different ranges.
 */
object Iap2MessageId {
    // --- Authentication ---
    const val REQUEST_AUTH_CERTIFICATE = 0xAA00.toInt()
    const val AUTH_CERTIFICATE = 0xAA01.toInt()
    const val REQUEST_AUTH_CHALLENGE_RESPONSE = 0xAA02.toInt()
    const val AUTH_CHALLENGE_RESPONSE = 0xAA03.toInt()
    const val AUTH_FAILED = 0xAA04.toInt()
    const val AUTH_SUCCEEDED = 0xAA05.toInt()

    // --- Identification ---
    const val START_IDENTIFICATION = 0x1D00.toInt()
    const val IDENTIFICATION_INFORMATION = 0x1D01.toInt()
    const val IDENTIFICATION_ACCEPTED = 0x1D02.toInt()
    const val IDENTIFICATION_REJECTED = 0x1D03.toInt()
    const val CANCEL_IDENTIFICATION = 0x1D05.toInt()

    // --- CarPlay ---
    const val START_CARPLAY = 0x4301.toInt()
    const val STOP_CARPLAY = 0x4302.toInt()
    const val CARPLAY_AVAILABILITY = 0x4303.toInt()

    // --- Power ---
    const val POWER_UPDATE = 0x4E00.toInt()
    const val START_POWER_UPDATES = 0x4E01.toInt()
    const val STOP_POWER_UPDATES = 0x4E02.toInt()

    // --- Vehicle Status ---
    const val START_VEHICLE_STATUS_UPDATES = 0x6000.toInt()
    const val STOP_VEHICLE_STATUS_UPDATES = 0x6001.toInt()
    const val VEHICLE_STATUS_UPDATE = 0x6002.toInt()

    // --- Location ---
    const val START_LOCATION_INFO = 0x6100.toInt()
    const val STOP_LOCATION_INFO = 0x6101.toInt()
    const val LOCATION_INFO_UPDATE = 0x6102.toInt()

    // --- Communications ---
    const val CALL_STATE_UPDATE = 0x5600.toInt()
    const val LIST_UPDATE = 0x5601.toInt()
    const val COMMUNICATIONS_UPDATE = 0x5602.toInt()

    // --- Route Guidance ---
    const val ROUTE_GUIDANCE_UPDATE = 0x6700.toInt()
    const val MANEUVER_UPDATE = 0x6701.toInt()
    const val LANE_GUIDANCE = 0x6702.toInt()

    // --- Device Information ---
    const val DEVICE_INFO_UPDATE = 0x4004.toInt()
    const val DEVICE_LANGUAGE_UPDATE = 0x4005.toInt()
    const val DEVICE_TIME_UPDATE = 0x4006.toInt()

    // --- Bluetooth ---
    const val BT_COMPONENT_INFO = 0xAE00.toInt()
    const val BT_PAIRING_START = 0xAE01.toInt()
    const val BT_PAIRING_STOP = 0xAE02.toInt()
    const val BT_CONNECTION_UPDATE = 0xAE03.toInt()

    // --- WiFi ---
    const val WIFI_CONFIG_INFO = 0x4700.toInt()

    fun name(id: Int): String = when (id) {
        REQUEST_AUTH_CERTIFICATE -> "RequestAuthCertificate"
        AUTH_CERTIFICATE -> "AuthCertificate"
        REQUEST_AUTH_CHALLENGE_RESPONSE -> "RequestAuthChallengeResponse"
        AUTH_CHALLENGE_RESPONSE -> "AuthChallengeResponse"
        AUTH_FAILED -> "AuthFailed"
        AUTH_SUCCEEDED -> "AuthSucceeded"
        START_IDENTIFICATION -> "StartIdentification"
        IDENTIFICATION_INFORMATION -> "IdentificationInformation"
        IDENTIFICATION_ACCEPTED -> "IdentificationAccepted"
        IDENTIFICATION_REJECTED -> "IdentificationRejected"
        START_CARPLAY -> "StartCarPlay"
        STOP_CARPLAY -> "StopCarPlay"
        CARPLAY_AVAILABILITY -> "CarPlayAvailability"
        VEHICLE_STATUS_UPDATE -> "VehicleStatusUpdate"
        LOCATION_INFO_UPDATE -> "LocationInfoUpdate"
        ROUTE_GUIDANCE_UPDATE -> "RouteGuidanceUpdate"
        else -> "Unknown(0x${id.toString(16)})"
    }
}

/**
 * iAP2 Parameter IDs for identification.
 */
object Iap2ParamId {
    // Identification parameters
    const val NAME = 0x0000.toInt()
    const val MODEL_IDENTIFIER = 0x0001.toInt()
    const val MANUFACTURER = 0x0002.toInt()
    const val SERIAL_NUMBER = 0x0003.toInt()
    const val FIRMWARE_VERSION = 0x0004.toInt()
    const val HARDWARE_VERSION = 0x0005.toInt()
    const val MESSAGES_SENT_BY_ACCESSORY = 0x0006.toInt() // Group
    const val MESSAGES_RECEIVED_BY_ACCESSORY = 0x0007.toInt() // Group
    const val POWER_PROVIDING_CAPABILITY = 0x0008.toInt()
    const val MAX_CURRENT_DRAWN_FROM_DEVICE = 0x0009.toInt()
    const val SUPPORTED_EXTERNAL_ACCESSORY_PROTOCOL = 0x000A.toInt() // Group
    const val APP_MATCH_TEAM_ID = 0x000B.toInt()
    const val CURRENT_LANGUAGE = 0x000C.toInt()
    const val SUPPORTED_LANGUAGE = 0x000D.toInt()
    const val BLUETOOTH_TRANSPORT_COMPONENT = 0x0010.toInt() // Group
    const val IAP2_HID_COMPONENT = 0x0011.toInt() // Group
    const val LOCATION_INFO_COMPONENT = 0x0016.toInt() // Group
    const val VEHICLE_STATUS_COMPONENT = 0x0019.toInt() // Group
    const val VEHICLE_INFORMATION_COMPONENT = 0x001A.toInt() // Group
    const val USB_DEVICE_TRANSPORT_COMPONENT = 0x0012.toInt() // Group
    const val USB_HOST_TRANSPORT_COMPONENT = 0x0013.toInt() // Group
    const val ROUTE_GUIDANCE_DISPLAY_COMPONENT = 0x001B.toInt() // Group

    // Auth parameters
    const val AUTH_CERTIFICATE_DATA = 0x0000.toInt()
    const val AUTH_CHALLENGE_DATA = 0x0000.toInt()
    const val AUTH_RESPONSE_DATA = 0x0000.toInt()

    // CarPlay start parameters
    const val CARPLAY_PUBLIC_KEY = 0x0000.toInt()
    const val CARPLAY_PORT = 0x0001.toInt()
    const val CARPLAY_SOURCE_VERSION = 0x0002.toInt()
    const val CARPLAY_DEVICE_IDENTIFIER = 0x0003.toInt()
    const val CARPLAY_WIRED_ATTRIBUTES = 0x0004.toInt() // Group
    const val CARPLAY_WIRELESS_ATTRIBUTES = 0x0005.toInt() // Group
    const val CARPLAY_IP_ADDRESS = 0x0000.toInt() // Inside wired/wireless group
    const val CARPLAY_WIFI_SSID = 0x0001.toInt()
    const val CARPLAY_WIFI_PASSPHRASE = 0x0002.toInt()
    const val CARPLAY_WIFI_CHANNEL = 0x0003.toInt()
    const val CARPLAY_WIFI_SECURITY_TYPE = 0x0004.toInt()
}

/**
 * An iAP2 parameter (TLV).
 */
data class Iap2Param(
    val id: Int,
    val data: ByteArray = ByteArray(0),
    val subParams: List<Iap2Param> = emptyList(), // For group parameters
) {
    val isGroup: Boolean get() = subParams.isNotEmpty()

    /** Encode this parameter (and nested sub-params) to bytes. */
    fun encode(): ByteArray {
        val content = if (isGroup) {
            // Group: concatenate encoded sub-params
            val parts = subParams.map { it.encode() }
            val total = parts.sumOf { it.size }
            val buf = ByteArray(total)
            var pos = 0
            for (part in parts) {
                System.arraycopy(part, 0, buf, pos, part.size)
                pos += part.size
            }
            buf
        } else {
            data
        }

        val paramLen = 4 + content.size // length(2) + id(2) + data
        val buf = ByteArray(paramLen)
        buf[0] = (paramLen shr 8).toByte()
        buf[1] = (paramLen and 0xFF).toByte()
        buf[2] = (id shr 8).toByte()
        buf[3] = (id and 0xFF).toByte()
        if (content.isNotEmpty()) {
            System.arraycopy(content, 0, buf, 4, content.size)
        }
        return buf
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Iap2Param) return false
        return id == other.id && data.contentEquals(other.data) && subParams == other.subParams
    }

    override fun hashCode(): Int = id * 31 + data.contentHashCode()

    companion object {
        /** Create a string parameter. */
        fun string(id: Int, value: String): Iap2Param =
            Iap2Param(id, value.toByteArray(Charsets.UTF_8))

        /** Create a uint8 parameter. */
        fun uint8(id: Int, value: Int): Iap2Param =
            Iap2Param(id, byteArrayOf(value.toByte()))

        /** Create a uint16 big-endian parameter. */
        fun uint16(id: Int, value: Int): Iap2Param =
            Iap2Param(id, byteArrayOf((value shr 8).toByte(), (value and 0xFF).toByte()))

        /** Create a uint32 big-endian parameter. */
        fun uint32(id: Int, value: Long): Iap2Param =
            Iap2Param(id, byteArrayOf(
                (value shr 24).toByte(),
                (value shr 16).toByte(),
                (value shr 8).toByte(),
                (value and 0xFF).toByte(),
            ))

        /** Create a group parameter containing sub-params. */
        fun group(id: Int, vararg params: Iap2Param): Iap2Param =
            Iap2Param(id, subParams = params.toList())

        /** Create a raw bytes parameter. */
        fun bytes(id: Int, data: ByteArray): Iap2Param =
            Iap2Param(id, data)
    }
}

/**
 * An iAP2 control session message.
 */
data class Iap2Message(
    val messageId: Int,
    val params: List<Iap2Param> = emptyList(),
) {
    /** Encode to bytes (including message header). */
    fun encode(): ByteArray {
        val paramBytes = params.map { it.encode() }
        val paramsTotal = paramBytes.sumOf { it.size }
        val msgLen = 6 + paramsTotal // marker(2) + length(2) + id(2) + params
        val buf = ByteArray(msgLen)

        buf[0] = IAP2_MSG_MARKER_1
        buf[1] = IAP2_MSG_MARKER_2
        buf[2] = (msgLen shr 8).toByte()
        buf[3] = (msgLen and 0xFF).toByte()
        buf[4] = (messageId shr 8).toByte()
        buf[5] = (messageId and 0xFF).toByte()

        var pos = 6
        for (pb in paramBytes) {
            System.arraycopy(pb, 0, buf, pos, pb.size)
            pos += pb.size
        }

        return buf
    }

    override fun toString(): String =
        "Msg(${Iap2MessageId.name(messageId)} params=${params.size})"

    companion object {
        /**
         * Decode a message from raw bytes (excluding link-layer framing).
         */
        fun decode(data: ByteArray): Iap2Message? {
            if (data.size < 6) return null
            if (data[0] != IAP2_MSG_MARKER_1 || data[1] != IAP2_MSG_MARKER_2) return null

            val msgLen = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            if (data.size < msgLen) return null

            val messageId = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)

            // Parse parameters
            val params = mutableListOf<Iap2Param>()
            var pos = 6
            while (pos + 4 <= msgLen) {
                val paramLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                if (paramLen < 4 || pos + paramLen > msgLen) break
                val paramId = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
                val paramData = data.copyOfRange(pos + 4, pos + paramLen)
                params.add(Iap2Param(paramId, paramData))
                pos += paramLen
            }

            return Iap2Message(messageId, params)
        }
    }
}
