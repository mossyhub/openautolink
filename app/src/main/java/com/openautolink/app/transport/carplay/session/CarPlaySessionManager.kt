package com.openautolink.app.transport.carplay.session

import com.openautolink.app.diagnostics.OalLog
import com.openautolink.app.transport.carplay.auth.MfiAuthClient
import com.openautolink.app.transport.carplay.iap2.*
import com.openautolink.app.transport.carplay.usb.IPhoneUsbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private const val TAG = "CarPlaySession"

enum class CarPlaySessionState {
    IDLE,
    USB_CONNECTING,
    LINK_SETUP,          // iAP2 SYN handshake
    AUTHENTICATING,      // MFi challenge-response via CPC200
    IDENTIFYING,         // Send accessory identification
    CARPLAY_STARTING,    // StartCarPlay message
    STREAMING,           // Active CarPlay session
    STOPPING,
    ERROR,
}

/**
 * CarPlay session manager — orchestrates the full connection flow.
 *
 * State machine:
 *   IDLE → USB_CONNECTING → LINK_SETUP → AUTHENTICATING →
 *   IDENTIFYING → CARPLAY_STARTING → STREAMING → STOPPING → IDLE
 *
 * Dependencies:
 *   - IPhoneUsbTransport: USB bulk read/write to iPhone
 *   - MfiAuthClient: TCP connection to CPC200 MFi signing daemon
 *   - Iap2FrameCodec: link-layer frame encode/decode
 *   - Iap2Message: control message serialization
 */
class CarPlaySessionManager(
    private val usbTransport: IPhoneUsbTransport,
    private val mfiAuth: MfiAuthClient,
) {
    private val _state = MutableStateFlow(CarPlaySessionState.IDLE)
    val state: StateFlow<CarPlaySessionState> = _state.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    // Link-layer state
    private var localSeq = 0
    private var remoteSeq = 0
    private var maxPayloadSize = IAP2_DEFAULT_MAX_PAYLOAD
    private val readBuffer = ByteArray(4096)

    // Cached auth data
    private var mfiCertificate: ByteArray? = null

    /**
     * Run the full CarPlay session. Call this from a coroutine on IO dispatcher.
     * Returns when the session ends (normally or on error).
     */
    suspend fun runSession() = withContext(Dispatchers.IO) {
        try {
            _state.value = CarPlaySessionState.USB_CONNECTING
            status("Opening USB connection to iPhone...")

            // Step 1: Open USB
            if (!usbTransport.open()) {
                error("Failed to open USB connection")
                return@withContext
            }
            OalLog.i(TAG, "USB transport open")

            // Step 2: Connect to MFi auth daemon
            status("Connecting to MFi auth daemon...")
            mfiAuth.connect()
            if (!mfiAuth.ping()) {
                error("MFi auth daemon not responding")
                return@withContext
            }
            OalLog.i(TAG, "MFi auth daemon connected")

            // Pre-fetch certificate (saves round-trip during auth)
            mfiCertificate = mfiAuth.getCertificate()
            if (mfiCertificate == null) {
                error("Failed to read MFi certificate")
                return@withContext
            }
            OalLog.i(TAG, "MFi certificate: ${mfiCertificate!!.size} bytes")

            // Step 3: iAP2 link-layer handshake
            _state.value = CarPlaySessionState.LINK_SETUP
            status("iAP2 link handshake (SYN)...")
            if (!performLinkHandshake()) {
                error("iAP2 link handshake failed")
                return@withContext
            }
            OalLog.i(TAG, "iAP2 link layer established")

            // Step 4: Wait for auth request from iPhone
            _state.value = CarPlaySessionState.AUTHENTICATING
            status("Waiting for iPhone auth challenge...")
            if (!handleAuthentication()) {
                error("Authentication failed")
                return@withContext
            }
            OalLog.i(TAG, "Authentication succeeded")

            // Step 5: Send identification
            _state.value = CarPlaySessionState.IDENTIFYING
            status("Sending identification...")
            if (!sendIdentification()) {
                error("Identification rejected")
                return@withContext
            }
            OalLog.i(TAG, "Identification accepted")

            // Step 6: Start CarPlay
            _state.value = CarPlaySessionState.CARPLAY_STARTING
            status("Starting CarPlay session...")
            if (!startCarPlay()) {
                error("CarPlay start failed")
                return@withContext
            }
            OalLog.i(TAG, "CarPlay session started!")

            // Step 7: Enter streaming loop
            _state.value = CarPlaySessionState.STREAMING
            status("CarPlay streaming active")
            streamingLoop()

        } catch (e: Exception) {
            OalLog.e(TAG, "Session error: ${e.message}")
            error("Session error: ${e.message}")
        } finally {
            _state.value = CarPlaySessionState.STOPPING
            usbTransport.close()
            mfiAuth.disconnect()
            _state.value = CarPlaySessionState.IDLE
            status("Session ended")
        }
    }

    // --- Link Layer Handshake ---

    private fun performLinkHandshake(): Boolean {
        localSeq = 0

        // Send SYN with our link parameters
        val synPayload = Iap2SynPayload(
            version = 1,
            maxOutstanding = 1,
            maxPacketLength = IAP2_DEFAULT_MAX_PAYLOAD,
            retransmitTimeoutMs = 1000,
            cumAckTimeoutMs = 50,
            maxRetransmissions = 30,
            maxCumAcks = 3,
            sessions = listOf(0), // Control session only for now
        )

        val synFrame = Iap2Frame(
            frameType = Iap2FrameType.SYN,
            seqNum = localSeq,
            ackNum = 0,
            sessionId = 0,
            payload = synPayload.encode(),
        )
        sendFrame(synFrame)
        OalLog.i(TAG, "Sent SYN")

        // Wait for SYN-ACK (SYN with ACK flag)
        val response = receiveFrame(timeoutMs = 5000) ?: run {
            OalLog.e(TAG, "No SYN-ACK received")
            return false
        }

        if (response.frameType != Iap2FrameType.SYN) {
            OalLog.e(TAG, "Expected SYN-ACK, got ${Iap2FrameType.name(response.frameType)}")
            return false
        }

        OalLog.i(TAG, "Received SYN-ACK (seq=${response.seqNum} ack=${response.ackNum})")

        // Parse peer's link params
        if (response.payload.isNotEmpty()) {
            val peerParams = Iap2SynPayload.decode(response.payload)
            maxPayloadSize = minOf(maxPayloadSize, peerParams.maxPacketLength)
            OalLog.i(TAG, "Peer params: maxPayload=$maxPayloadSize maxOutstanding=${peerParams.maxOutstanding}")
        }

        remoteSeq = response.seqNum

        // Send ACK
        localSeq = (localSeq + 1) and 0xFF
        val ackFrame = Iap2Frame(
            frameType = Iap2FrameType.ACK,
            seqNum = localSeq,
            ackNum = (remoteSeq + 1) and 0xFF,
            sessionId = 0,
            payload = ByteArray(0),
        )
        sendFrame(ackFrame)
        OalLog.i(TAG, "Sent ACK — link established")

        return true
    }

    // --- Authentication ---

    private suspend fun handleAuthentication(): Boolean {
        // iPhone sends RequestAuthCertificate
        val certReq = receiveMessage(timeoutMs = 10000)
        if (certReq == null || certReq.messageId != Iap2MessageId.REQUEST_AUTH_CERTIFICATE) {
            OalLog.e(TAG, "Expected RequestAuthCertificate, got ${certReq?.let { Iap2MessageId.name(it.messageId) }}")
            return false
        }
        OalLog.i(TAG, "Received RequestAuthCertificate")

        // Send our certificate (from CPC200)
        val certMsg = Iap2Message(
            messageId = Iap2MessageId.AUTH_CERTIFICATE,
            params = listOf(Iap2Param.bytes(Iap2ParamId.AUTH_CERTIFICATE_DATA, mfiCertificate!!)),
        )
        sendMessage(certMsg)
        OalLog.i(TAG, "Sent AuthCertificate (${mfiCertificate!!.size} bytes)")

        // iPhone sends RequestAuthChallengeResponse with challenge data
        val challengeReq = receiveMessage(timeoutMs = 10000)
        if (challengeReq == null || challengeReq.messageId != Iap2MessageId.REQUEST_AUTH_CHALLENGE_RESPONSE) {
            OalLog.e(TAG, "Expected RequestAuthChallengeResponse, got ${challengeReq?.let { Iap2MessageId.name(it.messageId) }}")
            return false
        }

        val challenge = challengeReq.params.firstOrNull()?.data
        if (challenge == null || challenge.isEmpty()) {
            OalLog.e(TAG, "Empty challenge data")
            return false
        }
        OalLog.i(TAG, "Received auth challenge: ${challenge.size} bytes")

        // Sign the challenge via CPC200 MFi chip
        status("Signing challenge via CPC200...")
        val signature = mfiAuth.signChallenge(challenge)
        if (signature == null) {
            OalLog.e(TAG, "MFi signing failed")
            return false
        }
        OalLog.i(TAG, "Challenge signed: ${signature.size} bytes")

        // Send signed response
        val responseMsg = Iap2Message(
            messageId = Iap2MessageId.AUTH_CHALLENGE_RESPONSE,
            params = listOf(Iap2Param.bytes(Iap2ParamId.AUTH_RESPONSE_DATA, signature)),
        )
        sendMessage(responseMsg)
        OalLog.i(TAG, "Sent AuthChallengeResponse")

        // Wait for AuthSucceeded or AuthFailed
        val authResult = receiveMessage(timeoutMs = 10000)
        if (authResult == null) {
            OalLog.e(TAG, "No auth result received")
            return false
        }

        return when (authResult.messageId) {
            Iap2MessageId.AUTH_SUCCEEDED -> {
                OalLog.i(TAG, "*** AUTH SUCCEEDED ***")
                true
            }
            Iap2MessageId.AUTH_FAILED -> {
                OalLog.e(TAG, "*** AUTH FAILED ***")
                false
            }
            else -> {
                OalLog.e(TAG, "Unexpected auth response: ${Iap2MessageId.name(authResult.messageId)}")
                false
            }
        }
    }

    // --- Identification ---

    private fun sendIdentification(): Boolean {
        // Build identification message with our accessory info
        val identMsg = Iap2Message(
            messageId = Iap2MessageId.IDENTIFICATION_INFORMATION,
            params = listOf(
                Iap2Param.string(Iap2ParamId.NAME, "OpenAutoLink CarPlay"),
                Iap2Param.string(Iap2ParamId.MODEL_IDENTIFIER, "OAL-CP-1.0"),
                Iap2Param.string(Iap2ParamId.MANUFACTURER, "OpenAutoLink"),
                Iap2Param.string(Iap2ParamId.SERIAL_NUMBER, "OAL-0001"),
                Iap2Param.string(Iap2ParamId.FIRMWARE_VERSION, "1.0.0"),
                Iap2Param.string(Iap2ParamId.HARDWARE_VERSION, "1.0"),
                Iap2Param.uint8(Iap2ParamId.POWER_PROVIDING_CAPABILITY, 0), // No power
                Iap2Param.uint16(Iap2ParamId.MAX_CURRENT_DRAWN_FROM_DEVICE, 0),
                Iap2Param.string(Iap2ParamId.CURRENT_LANGUAGE, "en"),
                Iap2Param.string(Iap2ParamId.SUPPORTED_LANGUAGE, "en"),
                // Declare CarPlay support via supported messages
                // TODO: Add message groups for CarPlay, vehicle status, location, etc.
            ),
        )
        sendMessage(identMsg)
        OalLog.i(TAG, "Sent IdentificationInformation")

        // Wait for IdentificationAccepted or IdentificationRejected
        val result = receiveMessage(timeoutMs = 10000)
        if (result == null) {
            OalLog.e(TAG, "No identification result")
            return false
        }

        return when (result.messageId) {
            Iap2MessageId.IDENTIFICATION_ACCEPTED -> {
                OalLog.i(TAG, "*** IDENTIFICATION ACCEPTED ***")
                true
            }
            Iap2MessageId.IDENTIFICATION_REJECTED -> {
                OalLog.e(TAG, "*** IDENTIFICATION REJECTED ***")
                // TODO: parse rejection reason
                false
            }
            else -> {
                OalLog.e(TAG, "Unexpected ident response: ${Iap2MessageId.name(result.messageId)}")
                false
            }
        }
    }

    // --- Start CarPlay ---

    private fun startCarPlay(): Boolean {
        // TODO: Build proper StartCarPlay message with:
        // - public key (pairing identity)
        // - listening port
        // - screen resolution
        // - wired attributes with IP address (NCM interface)
        val startMsg = Iap2Message(
            messageId = Iap2MessageId.START_CARPLAY,
            params = listOf(
                // Placeholder — these need to match what the iPhone expects
                Iap2Param.uint16(Iap2ParamId.CARPLAY_PORT, 7000),
            ),
        )
        sendMessage(startMsg)
        OalLog.i(TAG, "Sent StartCarPlay")

        // Wait for response
        val result = receiveMessage(timeoutMs = 10000)
        if (result == null) {
            OalLog.e(TAG, "No StartCarPlay response")
            return false
        }

        OalLog.i(TAG, "StartCarPlay response: ${Iap2MessageId.name(result.messageId)}")
        // TODO: handle the response properly
        return true
    }

    // --- Streaming Loop ---

    private fun streamingLoop() {
        OalLog.i(TAG, "Entering streaming loop...")
        // TODO: At this point, the CarPlay data channel should be active.
        // For wired: data flows over USB NCM interface (IP networking)
        // We need to:
        // 1. Set up NCM USB networking
        // 2. Receive AirPlay video/audio streams
        // 3. Feed H.264 to MediaCodec
        // 4. Feed audio to AudioTrack
        // 5. Forward touch events back via iAP2

        // For now, just keep reading iAP2 control messages
        while (_state.value == CarPlaySessionState.STREAMING) {
            val msg = receiveMessage(timeoutMs = 1000)
            if (msg != null) {
                OalLog.i(TAG, "Control message: ${Iap2MessageId.name(msg.messageId)}")
                handleControlMessage(msg)
            }
        }
    }

    private fun handleControlMessage(msg: Iap2Message) {
        when (msg.messageId) {
            Iap2MessageId.STOP_CARPLAY -> {
                OalLog.i(TAG, "iPhone requested StopCarPlay")
                _state.value = CarPlaySessionState.STOPPING
            }
            Iap2MessageId.START_VEHICLE_STATUS_UPDATES -> {
                OalLog.i(TAG, "iPhone wants vehicle status updates")
                // TODO: Start forwarding VHAL data
            }
            Iap2MessageId.START_LOCATION_INFO -> {
                OalLog.i(TAG, "iPhone wants location info")
                // TODO: Start forwarding GPS
            }
            Iap2MessageId.DEVICE_NOTIFICATION_1 -> {
                OalLog.i(TAG, "Device info update received")
            }
            else -> {
                OalLog.i(TAG, "Unhandled message: ${Iap2MessageId.name(msg.messageId)}")
            }
        }
    }

    // --- Transport helpers ---

    private fun sendFrame(frame: Iap2Frame) {
        val bytes = Iap2FrameCodec.encode(frame)
        val written = usbTransport.write(bytes)
        if (written < 0) {
            OalLog.e(TAG, "Frame write failed: $written")
        }
    }

    private fun sendMessage(msg: Iap2Message) {
        val msgBytes = msg.encode()
        localSeq = (localSeq + 1) and 0xFF
        val frame = Iap2Frame(
            frameType = Iap2FrameType.DATA,
            seqNum = localSeq,
            ackNum = (remoteSeq + 1) and 0xFF,
            sessionId = IAP2_CONTROL_SESSION.toInt(),
            payload = msgBytes,
        )
        sendFrame(frame)
    }

    private fun receiveFrame(timeoutMs: Int = 5000): Iap2Frame? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val n = usbTransport.read(readBuffer, minOf(1000, (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(100)))
            if (n > 0) {
                val result = Iap2FrameCodec.decode(readBuffer, 0, n)
                if (result != null) {
                    // ACK the received frame
                    remoteSeq = result.frame.seqNum
                    return result.frame
                }
            }
        }
        return null
    }

    private fun receiveMessage(timeoutMs: Int = 5000): Iap2Message? {
        val frame = receiveFrame(timeoutMs) ?: return null

        // Auto-ACK data frames
        if (frame.frameType == Iap2FrameType.DATA && frame.payload.isNotEmpty()) {
            val ackFrame = Iap2Frame(
                frameType = Iap2FrameType.ACK,
                seqNum = localSeq,
                ackNum = (frame.seqNum + 1) and 0xFF,
                sessionId = 0,
                payload = ByteArray(0),
            )
            sendFrame(ackFrame)
        }

        if (frame.payload.isEmpty()) return null
        return Iap2Message.decode(frame.payload)
    }

    private fun status(msg: String) {
        _statusMessage.value = msg
        OalLog.i(TAG, msg)
    }

    private fun error(msg: String) {
        _state.value = CarPlaySessionState.ERROR
        _statusMessage.value = "ERROR: $msg"
        OalLog.e(TAG, msg)
    }
}
