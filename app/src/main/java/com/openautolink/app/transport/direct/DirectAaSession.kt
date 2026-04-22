package com.openautolink.app.transport.direct

import com.openautolink.app.audio.AudioFrame
import com.openautolink.app.diagnostics.OalLog
import com.openautolink.app.proto.Control
import com.openautolink.app.proto.Media
import com.openautolink.app.transport.AudioPurpose
import com.openautolink.app.transport.ConnectionState
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.video.VideoFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * Direct AA session â€” speaks AA wire protocol directly to the phone.
 *
 * Lifecycle:
 * 1. start() â†’ opens TCP server on port 5288, registers NSD
 * 2. Phone connects â†’ version exchange â†’ SSL handshake â†’ service discovery
 * 3. Phone opens channels â†’ video/audio/sensor streaming
 * 4. stop() â†’ closes everything
 *
 * This replaces the bridge: instead of OAL protocol over TCP to an SBC,
 * we speak the raw AA wire protocol directly to the phone.
 */
class DirectAaSession(
    private val scope: CoroutineScope,
    private val context: Context,
) {
    companion object {
        private const val TAG = "DirectAaSession"
        private const val PORT = 5288
        private const val NSD_SERVICE_TYPE = "_aawireless._tcp."
        private const val NSD_SERVICE_NAME = "OpenAutoLink"
        private const val MAX_UNACKED = 30  // Flow control window for wireless
    }

    // Public state
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _controlMessages = MutableSharedFlow<ControlMessage>(extraBufferCapacity = 64)
    val controlMessages: Flow<ControlMessage> = _controlMessages.asSharedFlow()

    private val _videoFrames = MutableSharedFlow<VideoFrame>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val videoFrames: Flow<VideoFrame> = _videoFrames.asSharedFlow()

    private val _audioFrames = MutableSharedFlow<AudioFrame>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val audioFrames: Flow<AudioFrame> = _audioFrames.asSharedFlow()

    // Internal state
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var serverJob: Job? = null
    private var readJob: Job? = null
    private var nsdManager: NsdManager? = null
    private var nsdRegistered = false

    private val codec = AaWireCodec()
    private val sslEngine = AaSslEngine()
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var sslActive = false

    private var videoAssembler: AaVideoAssembler? = null
    private var unackedFrames = 0

    // Video config from settings
    var videoConfig = DirectServiceDiscovery.VideoConfig()

    /**
     * Start listening for incoming phone connections.
     */
    fun start() {
        if (serverJob?.isActive == true) return
        _connectionState.value = ConnectionState.DISCONNECTED

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(PORT).apply { reuseAddress = true }
                registerNsd()
                OalLog.i(TAG, "Listening on port $PORT")
                _controlMessages.emit(ControlMessage.Hello(
                    name = "DirectMode", version = 1, capabilities = listOf("direct"),
                    videoPort = 0, audioPort = 0,
                ))

                while (true) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    val client = serverSocket?.accept() ?: break
                    OalLog.i(TAG, "Phone connected from ${client.inetAddress?.hostAddress}")
                    handleConnection(client)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (serverSocket != null && !serverSocket!!.isClosed) {
                    OalLog.e(TAG, "Server error: ${e.message}")
                }
            } finally {
                unregisterNsd()
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    /**
     * Stop the session and close all connections.
     */
    fun stop() {
        readJob?.cancel()
        readJob = null
        serverJob?.cancel()
        serverJob = null
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        clientSocket = null
        serverSocket = null
        sslEngine.release()
        sslActive = false
        unregisterNsd()
        _connectionState.value = ConnectionState.DISCONNECTED
        OalLog.i(TAG, "Stopped")
    }

    /**
     * Send an AA message to the phone.
     */
    suspend fun sendMessage(msg: AaMessage) = withContext(Dispatchers.IO) {
        val out = outputStream ?: return@withContext
        try {
            if (sslActive) {
                // AA post-handshake: 4-byte header is NOT encrypted,
                // only the body (type + payload) is encrypted.
                val bodyLen = AaMessage.TYPE_SIZE + msg.payloadLength
                val plainBody = ByteArray(bodyLen)
                plainBody[0] = (msg.type shr 8).toByte()
                plainBody[1] = (msg.type and 0xFF).toByte()
                if (msg.payloadLength > 0) {
                    System.arraycopy(msg.payload, msg.payloadOffset, plainBody, 2, msg.payloadLength)
                }
                val encBody = sslEngine.encrypt(plainBody) ?: return@withContext
                // Write 4-byte header with encrypted body length
                val header = ByteArray(4)
                header[0] = msg.channel.toByte()
                header[1] = msg.flags.toByte()
                header[2] = (encBody.size shr 8).toByte()
                header[3] = (encBody.size and 0xFF).toByte()
                synchronized(out) {
                    out.write(header)
                    out.write(encBody)
                    out.flush()
                }
            } else {
                synchronized(out) { codec.encode(msg, out) }
            }
        } catch (e: Exception) {
            OalLog.e(TAG, "Send error: ${e.message}")
        }
    }

    // â”€â”€ Connection handling â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private suspend fun handleConnection(socket: Socket) {
        clientSocket = socket
        inputStream = socket.getInputStream()
        outputStream = socket.getOutputStream()
        sslActive = false

        try {
            _connectionState.value = ConnectionState.CONNECTING

            // 1. Version exchange
            OalLog.i(TAG, "Starting version exchange")
            codec.writeVersionRequest(outputStream!!)
            val (major, minor) = codec.readVersionResponse(inputStream!!)
            OalLog.i(TAG, "Phone version: $major.$minor")

            // 2. SSL handshake
            OalLog.i(TAG, "Starting SSL handshake")
            if (!sslEngine.performHandshake(inputStream!!, outputStream!!)) {
                OalLog.e(TAG, "SSL handshake failed")
                _controlMessages.emit(ControlMessage.Error(7, "SSL handshake failed"))
                return
            }
            sslActive = true
            OalLog.i(TAG, "SSL handshake complete")

            // 3. Send AUTH_COMPLETE
            sendMessage(AaMessage.raw(AaChannel.CONTROL, AaMsgType.AUTH_COMPLETE,
                byteArrayOf(0x08, 0x00))) // status = OK

            // 4. Wait for ServiceDiscoveryRequest, respond with our capabilities
            _connectionState.value = ConnectionState.CONNECTED

            // 5. Start read loop
            videoAssembler = AaVideoAssembler(
                bufferSize = if (videoConfig.codec == "H.265") AaVideoAssembler.H265_BUFFER_SIZE
                             else AaVideoAssembler.DEFAULT_BUFFER_SIZE,
                onFrameCorrupted = { scope.launch { sendVideoFocusNotification() } },
            )

            readLoop()

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OalLog.e(TAG, "Connection error: ${e.message}")
        } finally {
            _controlMessages.emit(ControlMessage.PhoneDisconnected("connection_closed"))
            try { socket.close() } catch (_: Exception) {}
            clientSocket = null
            inputStream = null
            outputStream = null
            sslActive = false
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private suspend fun readLoop() {
        val input = inputStream ?: return

        while (true) {
            // Step 1: Read 4-byte AA header (NOT encrypted)
            val rawHeader = ByteArray(4)
            if (readFully(input, rawHeader, 4) < 0) {
                OalLog.i(TAG, "Phone disconnected (EOF)")
                return
            }


            val channel = rawHeader[0].toInt() and 0xFF
            val flags = rawHeader[1].toInt() and 0xFF
            val encLen = ((rawHeader[2].toInt() and 0xFF) shl 8) or (rawHeader[3].toInt() and 0xFF)

            if (encLen < 0 || encLen > 4 * 1024 * 1024) {
                OalLog.e(TAG, "Invalid enc_len: $encLen on ${AaChannel.name(channel)}")
                return
            }

            // For fragmented video (flag 0x09), consume 4-byte total-size prefix
            if (flags == 0x09) {
                val fragSizeBuf = ByteArray(4)
                if (readFully(input, fragSizeBuf, 4) < 0) return
            }

            // Step 2: Read enc_len bytes of encrypted body
            val encBody = ByteArray(encLen)
            if (encLen > 0 && readFully(input, encBody, encLen) < 0) {
                OalLog.i(TAG, "Phone disconnected during body read")
                return
            }

            // Step 3: Decrypt if SSL is active
            val plainBody: ByteArray = if (sslActive && encLen > 0) {
                sslEngine.decrypt(encBody) ?: run {
                    OalLog.e(TAG, "SSL decrypt failed on ${AaChannel.name(channel)}")
                    return
                }
            } else {
                encBody
            }

            if (plainBody.size < 2) continue

            // Step 4: Parse type + payload from decrypted plaintext
            val type = ((plainBody[0].toInt() and 0xFF) shl 8) or (plainBody[1].toInt() and 0xFF)
            val msg = AaMessage(channel, flags, type, plainBody, 2, plainBody.size - 2)

            handleMessage(msg)
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, length: Int): Int {
        var offset = 0
        while (offset < length) {
            val n = input.read(buf, offset, length - offset)
            if (n < 0) return -1
            offset += n
        }
        return offset
    }

    // â”€â”€ Message dispatch â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private suspend fun handleMessage(msg: AaMessage) {
        when (msg.channel) {
            AaChannel.CONTROL -> handleControl(msg)
            AaChannel.VIDEO -> handleVideo(msg)
            AaChannel.AUDIO_MEDIA, AaChannel.AUDIO_SPEECH, AaChannel.AUDIO_SYSTEM -> handleAudio(msg)
            AaChannel.NAVIGATION -> handleNavigation(msg)
            AaChannel.MEDIA_PLAYBACK -> handleMediaPlayback(msg)
            AaChannel.PHONE_STATUS -> handlePhoneStatus(msg)
            else -> {
                // Channel open requests come as control-type messages on any channel
                if (AaMsgType.isControl(msg.type)) {
                    handleChannelControl(msg)
                }
            }
        }
    }

    private suspend fun handleControl(msg: AaMessage) {
        when (msg.type) {
            AaMsgType.SERVICE_DISCOVERY_REQUEST -> {
                OalLog.i(TAG, "ServiceDiscoveryRequest received")
                val response = DirectServiceDiscovery.build(videoConfig)
                sendMessage(AaMessage.fromProto(AaChannel.CONTROL, AaMsgType.SERVICE_DISCOVERY_RESPONSE, response))
                OalLog.i(TAG, "ServiceDiscoveryResponse sent (${response.servicesCount} services)")
            }

            AaMsgType.PING_REQUEST -> {
                // Respond to ping with same timestamp
                val payload = if (msg.payloadLength > 0) {
                    msg.payload.copyOfRange(msg.payloadOffset, msg.payloadOffset + msg.payloadLength)
                } else ByteArray(0)
                sendMessage(AaMessage.raw(AaChannel.CONTROL, AaMsgType.PING_RESPONSE, payload))
            }

            AaMsgType.BYEBYE_REQUEST -> {
                OalLog.i(TAG, "ByeByeRequest received")
                sendMessage(AaMessage.raw(AaChannel.CONTROL, AaMsgType.BYEBYE_RESPONSE, ByteArray(0)))
                _controlMessages.emit(ControlMessage.PhoneDisconnected("byebye"))
            }

            AaMsgType.AUDIO_FOCUS_REQUEST -> {
                // Parse the request to determine what type of focus is being requested
                try {
                    val data = msg.payload.copyOfRange(msg.payloadOffset, msg.payloadOffset + msg.payloadLength)
                    val request = Control.AudioFocusRequestNotification.parseFrom(data)
                    OalLog.d(TAG, "AudioFocusRequest: type=${request.request}")
                } catch (_: Exception) {}
                // Always grant focus — the car has its own audio focus management via CarAudioManager
                val notification = Control.AudioFocusNotification.newBuilder()
                    .setFocusState(Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN)
                    .build()
                sendMessage(AaMessage.fromProto(AaChannel.CONTROL, AaMsgType.AUDIO_FOCUS_NOTIFICATION, notification))
            }

            AaMsgType.NAV_FOCUS_REQUEST -> {
                // Grant nav focus
                sendMessage(AaMessage.raw(AaChannel.CONTROL, AaMsgType.NAV_FOCUS_NOTIFICATION,
                    byteArrayOf(0x08, 0x02))) // type = 2 (focused)
            }

            AaMsgType.VOICE_SESSION_NOTIFICATION -> {
                // Parse voice session state and emit for UI
                try {
                    val data = msg.payload.copyOfRange(msg.payloadOffset, msg.payloadOffset + msg.payloadLength)
                    // Voice session proto: field 1 = status (1=started, 2=finished)
                    val active = data.isNotEmpty() && data.size >= 2 && data[1].toInt() == 1
                    OalLog.i(TAG, "Voice session: ${if (active) "started" else "finished"}")
                    _controlMessages.emit(ControlMessage.VoiceSession(active))
                } catch (_: Exception) {
                    OalLog.i(TAG, "Voice session notification (unparsed)")
                }
            }
        }
    }

    private suspend fun handleChannelControl(msg: AaMessage) {
        when (msg.type) {
            AaMsgType.CHANNEL_OPEN_REQUEST -> {
                OalLog.i(TAG, "ChannelOpenRequest for ${AaChannel.name(msg.channel)}")
                // Respond with channel open success
                val response = Control.ChannelOpenResponse.newBuilder()
                    .setStatus(com.openautolink.app.proto.Common.MessageStatus.STATUS_SUCCESS)
                    .build()
                sendMessage(AaMessage.fromProto(msg.channel, AaMsgType.CHANNEL_OPEN_RESPONSE, response))

                // If video channel opened, send VideoFocusNotification
                if (msg.channel == AaChannel.VIDEO) {
                    sendVideoFocusNotification()
                    _controlMessages.emit(ControlMessage.PhoneConnected("phone", "wireless"))
                }
            }

            AaMsgType.MEDIA_SETUP -> {
                // Media setup â€” respond with config
                OalLog.i(TAG, "MediaSetup on ${AaChannel.name(msg.channel)}")
                val configResponse = byteArrayOf(0x08, 0x00) // status = OK
                sendMessage(AaMessage.raw(msg.channel, AaMsgType.MEDIA_CONFIG, configResponse))
            }

            AaMsgType.MEDIA_START -> {
                OalLog.i(TAG, "MediaStart on ${AaChannel.name(msg.channel)}")
                // Emit audio start for audio channels
                if (AaChannel.isAudio(msg.channel)) {
                    val purpose = when (msg.channel) {
                        AaChannel.AUDIO_MEDIA -> AudioPurpose.MEDIA
                        AaChannel.AUDIO_SPEECH -> AudioPurpose.NAVIGATION
                        AaChannel.AUDIO_SYSTEM -> AudioPurpose.ALERT
                        else -> AudioPurpose.MEDIA
                    }
                    val sampleRate = if (msg.channel == AaChannel.AUDIO_MEDIA) 48000 else 16000
                    val channels = if (msg.channel == AaChannel.AUDIO_MEDIA) 2 else 1
                    _controlMessages.emit(ControlMessage.AudioStart(purpose, sampleRate, channels))
                }
            }

            AaMsgType.MEDIA_STOP -> {
                OalLog.i(TAG, "MediaStop on ${AaChannel.name(msg.channel)}")
                if (AaChannel.isAudio(msg.channel)) {
                    val purpose = when (msg.channel) {
                        AaChannel.AUDIO_MEDIA -> AudioPurpose.MEDIA
                        AaChannel.AUDIO_SPEECH -> AudioPurpose.NAVIGATION
                        AaChannel.AUDIO_SYSTEM -> AudioPurpose.ALERT
                        else -> AudioPurpose.MEDIA
                    }
                    _controlMessages.emit(ControlMessage.AudioStop(purpose))
                }
            }

            AaMsgType.MIC_REQUEST -> {
                OalLog.i(TAG, "MicRequest on ${AaChannel.name(msg.channel)}")
                _controlMessages.emit(ControlMessage.MicStart(16000))
            }

            AaMsgType.VIDEO_FOCUS_REQUEST -> {
                OalLog.i(TAG, "VideoFocusRequest")
                sendVideoFocusNotification()
            }
        }
    }

    private suspend fun handleVideo(msg: AaMessage) {
        when (msg.type) {
            AaMsgType.MEDIA_DATA -> {
                val frame = videoAssembler?.process(msg)
                if (frame != null) {
                    _videoFrames.emit(frame)
                    // Send media ack
                    unackedFrames++
                    if (unackedFrames >= MAX_UNACKED / 2) {
                        val ack = Media.Ack.newBuilder()
                            .setSessionId(1)
                            .setAck(unackedFrames)
                            .build()
                        sendMessage(AaMessage.fromProto(AaChannel.VIDEO, AaMsgType.MEDIA_ACK, ack))
                        unackedFrames = 0
                    }
                }
            }

            AaMsgType.MEDIA_CODEC_CONFIG -> {
                OalLog.i(TAG, "Video codec config received (${msg.payloadLength}B)")
                // Forward codec config as a video frame with flag for decoder init
                val data = if (msg.payloadOffset == 0 && msg.payloadLength == msg.payload.size) {
                    msg.payload
                } else {
                    msg.payload.copyOfRange(msg.payloadOffset, msg.payloadOffset + msg.payloadLength)
                }
                _videoFrames.emit(VideoFrame(
                    width = 0, height = 0,
                    ptsMs = 0L,
                    flags = VideoFrame.FLAG_CODEC_CONFIG,
                    data = data,
                ))
            }

            else -> handleChannelControl(msg)
        }
    }

    private suspend fun handleAudio(msg: AaMessage) {
        if (msg.type == AaMsgType.MEDIA_DATA) {
            val purpose = when (msg.channel) {
                AaChannel.AUDIO_MEDIA -> AudioPurpose.MEDIA
                AaChannel.AUDIO_SPEECH -> AudioPurpose.NAVIGATION
                AaChannel.AUDIO_SYSTEM -> AudioPurpose.ALERT
                else -> AudioPurpose.MEDIA
            }
            val pcm = if (msg.payloadOffset == 0 && msg.payloadLength == msg.payload.size) {
                msg.payload
            } else {
                msg.payload.copyOfRange(msg.payloadOffset, msg.payloadOffset + msg.payloadLength)
            }
            _audioFrames.emit(AudioFrame(
                direction = AudioFrame.DIRECTION_PLAYBACK,
                purpose = purpose,
                sampleRate = if (msg.channel == AaChannel.AUDIO_MEDIA) 48000 else 16000,
                channels = if (msg.channel == AaChannel.AUDIO_MEDIA) 2 else 1,
                data = pcm,
            ))

            // Ack audio
            val ack = Media.Ack.newBuilder().setSessionId(1).setAck(1).build()
            sendMessage(AaMessage.fromProto(msg.channel, AaMsgType.MEDIA_ACK, ack))
        } else {
            handleChannelControl(msg)
        }
    }

    // Track last turn detail for combining with distance events
    private var lastTurnDetail: com.openautolink.app.proto.NavigationStatus.NextTurnDetail? = null

    private suspend fun handleNavigation(msg: AaMessage) {
        val navDetailType = 0x8004  // NEXTTURNDETAILS
        val navDistType = 0x8005    // NEXTTURNDISTANCEANDTIME

        when (msg.type) {
            navDetailType -> {
                try {
                    val data = msg.payload.copyOfRange(msg.payloadOffset, msg.payloadOffset + msg.payloadLength)
                    val detail = com.openautolink.app.proto.NavigationStatus.NextTurnDetail.parseFrom(data)
                    lastTurnDetail = detail
                    val maneuver = mapNextEvent(detail.nextturn)
                    _controlMessages.emit(ControlMessage.NavState(
                        maneuver = maneuver,
                        distanceMeters = null,
                        road = detail.road,
                        etaSeconds = null,
                        roundaboutExitNumber = if (detail.hasTrunnumer()) detail.trunnumer.toInt() else null,
                        roundaboutExitAngle = if (detail.hasTurnangel()) detail.turnangel.toInt() else null,
                    ))
                    OalLog.d(TAG, "Nav: turn=$maneuver road=${detail.road}")
                } catch (e: Exception) {
                    OalLog.e(TAG, "Nav detail parse error: ${e.message}")
                }
            }

            navDistType -> {
                try {
                    val data = msg.payload.copyOfRange(msg.payloadOffset, msg.payloadOffset + msg.payloadLength)
                    val event = com.openautolink.app.proto.NavigationStatus.NextTurnDistanceEvent.parseFrom(data)
                    val detail = lastTurnDetail
                    _controlMessages.emit(ControlMessage.NavState(
                        maneuver = detail?.let { mapNextEvent(it.nextturn) },
                        distanceMeters = if (event.hasDistance()) event.distance.toInt() else null,
                        road = detail?.road,
                        etaSeconds = if (event.hasTime()) event.time.toInt() else null,
                    ))
                } catch (e: Exception) {
                    OalLog.e(TAG, "Nav distance parse error: ${e.message}")
                }
            }

            else -> handleChannelControl(msg)
        }
    }

    private fun mapNextEvent(event: com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent): String {
        return when (event) {
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.DEPARTE -> "depart"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.NAME_CHANGE -> "name_change"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.SLIGHT_TURN -> "slight_turn"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.TURN -> "turn"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.SHARP_TURN -> "sharp_turn"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.UTURN -> "u_turn"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.ONRAMPE -> "on_ramp"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.OFFRAMP -> "off_ramp"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.FORME -> "fork"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.MERGE -> "merge"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_ENTER -> "roundabout_enter"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_EXIT -> "roundabout_exit"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_ENTER_AND_EXIT -> "roundabout_enter_exit"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.STRAIGHTE -> "straight"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.FERRY_BOAT -> "ferry_boat"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.FERRY_TRAINE -> "ferry_train"
            com.openautolink.app.proto.NavigationStatus.NextTurnDetail.NextEvent.DESTINATION -> "destination"
            else -> "unknown"
        }
    }

    private suspend fun handleMediaPlayback(msg: AaMessage) {
        when (msg.type) {
            0x8001, 0x8003 -> {
                try {
                    val data = msg.payload.copyOfRange(msg.payloadOffset, msg.payloadOffset + msg.payloadLength)
                    val metadata = com.openautolink.app.proto.MediaPlayback.MediaMetaData.parseFrom(data)
                    val title = metadata.song.takeIf { it.isNotEmpty() }
                    val artist = metadata.artist.takeIf { it.isNotEmpty() }
                    val album = metadata.album.takeIf { it.isNotEmpty() }
                    OalLog.d(TAG, "Media: $title - $artist")
                    emitMediaMetadata(title, artist, album)
                } catch (e: Exception) {
                    OalLog.e(TAG, "MediaPlayback parse error: ${e.message}")
                }
            }
            else -> handleChannelControl(msg)
        }
    }

    /** Isolated method to avoid Kotlin type inference issues with sealed class + protobuf. */
    private suspend fun emitMediaMetadata(title: String?, artist: String?, album: String?) {
        _controlMessages.emit(ControlMessage.MediaMetadata(
            title = title,
            artist = artist,
            album = album,
            durationMs = null,
            positionMs = null,
            playing = null
        ))
    }

    private suspend fun handlePhoneStatus(msg: AaMessage) {
        if (msg.type == AaMsgType.MEDIA_DATA || msg.type == AaMsgType.MEDIA_START) {
            try {
                val data = msg.payload.copyOfRange(msg.payloadOffset, msg.payloadOffset + msg.payloadLength)
                val status = Control.ServiceDiscoveryResponse.parseFrom(data)
                // Phone status is embedded in the PhoneStatusService on the control proto
                // For now, just log and try to extract signal/calls from the raw data
                OalLog.d(TAG, "PhoneStatus received (${data.size}B)")
                // TODO: parse PhoneStatus_Call and signal_strength when proto is available
            } catch (_: Exception) {
                OalLog.d(TAG, "PhoneStatus (${msg.payloadLength}B)")
            }
        } else {
            handleChannelControl(msg)
        }
    }

    private suspend fun sendVideoFocusNotification() {
        val focus = Media.VideoFocusNotification.newBuilder()
            .setMode(Media.VideoFocusMode.VIDEO_FOCUS_NATIVE)
            .setUnsolicited(false)
            .build()
        sendMessage(AaMessage.fromProto(AaChannel.VIDEO, AaMsgType.VIDEO_FOCUS_NOTIFICATION, focus))
    }

    // â”€â”€ NSD (Network Service Discovery) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Send mic audio PCM data to the phone on channel 7 (MIC).
     */
    suspend fun sendMicAudio(pcmData: ByteArray) {
        if (!sslActive) return
        sendMessage(AaMessage.raw(AaChannel.MIC, AaMsgType.MEDIA_DATA, pcmData))
    }

    private fun registerNsd() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = NSD_SERVICE_NAME
                serviceType = NSD_SERVICE_TYPE
                port = PORT
            }
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdListener)
            OalLog.i(TAG, "NSD registered: $NSD_SERVICE_NAME on port $PORT")
        } catch (e: Exception) {
            OalLog.e(TAG, "NSD registration failed: ${e.message}")
        }
    }

    private fun unregisterNsd() {
        if (nsdRegistered) {
            try {
                nsdManager?.unregisterService(nsdListener)
            } catch (_: Exception) {}
            nsdRegistered = false
        }
    }

    private val nsdListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            nsdRegistered = true
            OalLog.i(TAG, "NSD service registered: ${serviceInfo.serviceName}")
        }
        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            OalLog.e(TAG, "NSD registration failed: error $errorCode")
        }
        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            nsdRegistered = false
        }
        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
    }
}
