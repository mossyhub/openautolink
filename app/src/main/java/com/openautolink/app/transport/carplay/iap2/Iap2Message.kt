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
    // --- Authentication (handled by Cinemo native lib in GM — IDs from MFi spec) ---
    // These are the standard iAP2 auth message IDs. Not visible in decompiled
    // Java (Cinemo handles them in C++) but documented in MFi spec R30+.
    const val REQUEST_AUTH_CERTIFICATE = 0xAA00
    const val AUTH_CERTIFICATE = 0xAA01
    const val REQUEST_AUTH_CHALLENGE_RESPONSE = 0xAA02
    const val AUTH_CHALLENGE_RESPONSE = 0xAA03
    const val AUTH_FAILED = 0xAA04
    const val AUTH_SUCCEEDED = 0xAA05

    // --- Identification (standard iAP2, not in decompiled Java) ---
    const val START_IDENTIFICATION = 0x1D00
    const val IDENTIFICATION_INFORMATION = 0x1D01
    const val IDENTIFICATION_ACCEPTED = 0x1D02
    const val IDENTIFICATION_REJECTED = 0x1D03
    const val CANCEL_IDENTIFICATION = 0x1D05

    // --- CarPlay (CONFIRMED from decompiled CarPlayAvailability/StartSession) ---
    const val CARPLAY_AVAILABILITY = 0x4300     // 17152 — CarPlayAvailability.java
    const val START_CARPLAY = 0x4301            // 17153 — CarPlayStartSession.java
    const val STOP_CARPLAY = 0x4302             // presumed from sequence

    // --- Power (CONFIRMED from decompiled PowerUpdate.java) ---
    const val POWER_UPDATE_RECV = 0xAE00        // 44544
    const val START_POWER_UPDATES = 0xAE01      // 44545
    const val STOP_POWER_UPDATES = 0xAE02       // 44546
    const val POWER_UPDATE_SEND = 0xAE03        // 44547

    // --- Vehicle Status (CONFIRMED from decompiled VehicleStatus.java) ---
    const val START_VEHICLE_STATUS_UPDATES = 0xA100   // 41216
    const val STOP_VEHICLE_STATUS_UPDATES = 0xA101    // 41217
    const val VEHICLE_STATUS_UPDATE = 0xA102          // 41218

    // --- Location (CONFIRMED from decompiled LocationInfo.java) ---
    const val START_LOCATION_INFO = 0xFFFA      // 65530
    const val STOP_LOCATION_INFO = 0xFFFB       // 65531
    const val LOCATION_INFO_UPDATE = 0xFFFC     // 65532

    // --- Communications (CONFIRMED from decompiled CommunicationManager.java) ---
    // Send (accessory → device)
    const val CALL_STATE_UPDATE_SEND = 0x4154   // 16724
    const val LIST_UPDATE_SEND = 0x4155         // 16725
    const val COMMUNICATIONS_UPDATE_SEND = 0x4156 // 16726
    // Receive (device → accessory)
    const val CALL_STATE_UPDATE_RECV = 0x4170   // 16752
    const val LIST_UPDATE_RECV = 0x4171         // 16753
    const val COMMUNICATIONS_UPDATE_RECV = 0x4172 // 16754

    // --- Device Notifications (CONFIRMED from decompiled DeviceNotifications.java) ---
    const val DEVICE_NOTIFICATION_1 = 0x4E09    // 19977
    const val DEVICE_NOTIFICATION_2 = 0x4E0A    // 19978
    const val DEVICE_NOTIFICATION_3 = 0x4E0B    // 19979
    const val DEVICE_NOTIFICATION_4 = 0x4E0C    // 19980

    // --- Bluetooth OOB Pairing (CONFIRMED from decompiled BTOOBPairing.java) ---
    const val OOB_BT_PAIRING_START = 0x0B00     // 2816
    const val OOB_BT_PAIRING_STOP = 0x0B01      // 2817
    const val OOB_BT_PAIRING_LINK_KEY = 0x0B02  // 2818
    const val OOB_BT_PAIRING_3 = 0x0B03         // 2819

    // --- EAP (CONFIRMED from decompiled EAPManager.java) ---
    const val EAP_SESSION_RECV = 0xEA00         // 59904
    const val EAP_SESSION_SEND = 0xEA01         // 59905
    const val EAP_DATA_1 = 0xEA02               // 59906
    const val EAP_DATA_2 = 0xEA03               // 59907

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
        CARPLAY_AVAILABILITY -> "CarPlayAvailability"
        START_CARPLAY -> "StartCarPlay"
        STOP_CARPLAY -> "StopCarPlay"
        START_VEHICLE_STATUS_UPDATES -> "StartVehicleStatusUpdates"
        STOP_VEHICLE_STATUS_UPDATES -> "StopVehicleStatusUpdates"
        VEHICLE_STATUS_UPDATE -> "VehicleStatusUpdate"
        START_LOCATION_INFO -> "StartLocationInfo"
        STOP_LOCATION_INFO -> "StopLocationInfo"
        LOCATION_INFO_UPDATE -> "LocationInfoUpdate"
        START_POWER_UPDATES -> "StartPowerUpdates"
        POWER_UPDATE_RECV -> "PowerUpdate(recv)"
        EAP_SESSION_RECV -> "EAPSession(recv)"
        EAP_SESSION_SEND -> "EAPSession(send)"
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
