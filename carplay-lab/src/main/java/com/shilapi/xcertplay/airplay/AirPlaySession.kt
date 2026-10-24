// SPDX-License-Identifier: GPL-3.0-only
// Adapted from DiPlay e2fd8eadf5a6b705666f292055e72aa93387533d; see MEDIA-PROVENANCE.md.
package com.shilapi.xcertplay.airplay

import android.util.Log
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

data class AirPlayDeviceInfo(
    val name: String,
    val deviceId: String,
    val wifiMac: String,
    val model: String,
)

/** Session lifecycle and command callbacks for the driver/UI layer. */
interface AirPlaySessionListener {
    /** Real stored-key PairVerify proof; false revokes control before any encrypted media SETUP. */
    fun onControlAuthenticated(session: AirPlaySession): Boolean = true
    fun onTcpAccepted(event: AirPlayTcpAccepted) {}
    fun onSessionActive(session: AirPlaySession) {}
    fun onSessionEnded(session: AirPlaySession) {}
    fun onVideoFrameRendered(session: AirPlaySession) {}
    fun onTransportError(message: String) {}
    fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {}
    fun onHostUiRequested(session: AirPlaySession) {}
    fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {}
    /** Video in car: a playback message on a remote control session (X-Apple-StreamID). */
    fun onRemoteControlMessage(session: AirPlaySession, streamId: Long, message: Map<String, Any?>) {}
    /** Video in car: requestUI "videoplayback:", the iPhone asks the car to show its video player. */
    fun onVideoPlaybackUiRequested(session: AirPlaySession) {}
    fun onDebugLog(message: String) {}
}

/** Stream transport seam; media decode/render is supplied by a later layer. */
interface AirPlayMediaHandler {
    fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int? = null
    fun onAudio(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Map<String, Any?>? = null
    fun onDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? = null
    /** CarPlay's main buffered audio (type 103): the SETUP response, or null to decline it. */
    fun onBufferedAudio(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? = null
    /**
     * SETRATE, SETRATEANCHORTIME, GETANCHOR and FLUSHBUFFERED for the buffered stream: the response
     * plist (the anchor), or null for an empty 200.
     */
    fun onBufferedAudioControl(session: AirPlaySession, method: String, body: Map<String, Any?>): Map<String, Any?>? = null
    fun onFeedback(session: AirPlaySession): Map<String, Any?>? = null
    fun onTeardown(session: AirPlaySession, type: Int) {}
    fun onSessionClosed(session: AirPlaySession) {}
    fun setIapTunnelHandler(handler: ((BlockingDuplexByteStream) -> Boolean)?) {}
    fun onSetupResponseSent(session: AirPlaySession) {}
}

/**
 * One CarPlay AirPlay control connection.
 *
 * It owns the RTSP framing, pairing/auth/info routing, the encrypted event channel used for HID
 * input, stream SETUP/TEARDOWN routing, the NTP timing exchange, and the keep-alive socket.
 */
class AirPlaySession @JvmOverloads constructor(
    private val socket: Socket,
    private val config: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    private val mfi: MfiAuthenticator?,
    private val listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
    /** False mirrors pinned DiPlay; true retains the alternate post-RECORD requestUI policy. */
    requestUiOnStartup: Boolean = false,
    val socketBinding: com.openautolink.carplaylab.network.CarPlaySocketBinding? = null,
    private val allowPairSetup: Boolean = true,
    private val saveController: ((String, ByteArray) -> Boolean)? = null,
    private val pairSetupAdmission: ((AirPlaySession) -> Boolean)? = null,
    private val videoSelection: com.shilapi.xcertplay.media.CarPlayVideoSelection? = null,
    microphoneCapability: com.shilapi.xcertplay.media.MicrophoneCapability =
        if (config.microphone) com.shilapi.xcertplay.media.AndroidMicrophoneSupport.capability
        else com.shilapi.xcertplay.media.MicrophoneCapability.NONE,
    availableAudioFormats: Set<Long>? = null,
) : Closeable {
    private val outputAudioFormats = availableAudioFormats?.toSet()
    // Freeze the exact source/rate qualification used by this session's /info and SETUP.
    internal val offeredMicrophone = if (config.microphone && !config.lpcmOnly && !config.disableAudioOutput)
        microphoneCapability else com.shilapi.xcertplay.media.MicrophoneCapability.NONE
    internal val pairSetup = PairSetup(identity, pairings, saveController)
    internal val pairVerify = PairVerify(identity, pairings)
    internal var cipher: ControlCipher? = null
    internal var encBuf = ByteArray(0)
    internal var deviceBtMac = ""
    internal val activeStreams = linkedSetOf<Int>()
    @Volatile private var hevcNegotiated = false
    /** Actual in-band codec may still be AVC after an optional HEVC negotiation. */
    fun acceptsVideoCodec(codec: VideoCodec): Boolean = !isClosed &&
        (codec == VideoCodec.H264 || hevcNegotiated)

    /** Counts the iPhone's cluster stream setups; 0 while no cluster stream is up. */
    @Volatile var clusterStream = 0
        private set
    private var clusterStreamSetups = 0
    @Volatile private var mainScreenToken: Any? = null

    private val closed = AtomicBoolean(false)
    private val cleanupStarted = AtomicBoolean(false)
    private val authenticated = AtomicBoolean(false)
    val controlAuthenticated: Boolean get() = authenticated.get()
    internal val isClosed: Boolean get() = closed.get()
    private val notified = AtomicBoolean(false)
    private val eventTransportLock = Any()
    @Volatile private var eventServer: ServerSocket? = null
    @Volatile private var eventSocket: Socket? = null
    @Volatile private var eventCipher: ControlCipher? = null
    private var eventCseq = 0
    private var pendingNightMode: Boolean? = null
    private val firstTouchSendLogged = AtomicBoolean(false)
    private val touchSendFailureLogged = AtomicBoolean(false)
    private val ntp = NtpClock(expectedPeer = socket.inetAddress, socketBinding = socketBinding)
    private var keepAliveSocket: DatagramSocket? = null
    private var keepAliveThread: Thread? = null
    private val eventWriteLock = Any()
    private val startup = CarPlayStartup(eventWriteLock, requestUiOnStartup = requestUiOnStartup) { command ->
        val sent = !closed.get() && sendCommand(command)
        listener.onDebugLog("BENCH_DIAG startupRequestUiSent=$sent")
        sent
    }
    private val eventThreads = CopyOnWriteArrayList<Thread>()

    val host: String = socket.inetAddress?.hostAddress ?: ""
    val localAddress: InetAddress? = socket.localAddress
    private val peerAddress: InetAddress? = socket.inetAddress
    internal val remoteAddress: InetAddress?
        get() = (socket.remoteSocketAddress as? InetSocketAddress)?.address
    val controllerId: String? get() = pairVerify.verifiedControllerId
    val sharedSecret: ByteArray? get() = pairVerify.shared?.copyOf()
    val videoInCar: Boolean get() = config.videoInCar
    private val videoPlaybackAvailability = VideoPlaybackAvailability { allowed ->
        sendCommand(
            linkedMapOf(
                "type" to "setVideoPlaybackAllowed",
                "params" to linkedMapOf("videoPlaybackAllowed" to allowed),
            ),
        )
    }

    fun syncedNtp(): BigInteger = ntp.syncedNtp()

    internal fun logDebug(message: String) { if (message.startsWith("BENCH_DIAG ")) listener.onDebugLog(message) }

    internal fun videoFrameRendered() {
        if (!closed.get()) listener.onVideoFrameRendered(this)
    }

    internal fun logTrace(message: String) = Unit

    @Volatile private var controlThread: Thread? = null
    fun start() {
        controlThread = Thread(::runControl, "airplay-control").apply {
            isDaemon = true
            start()
        }
    }
    /** Only call off the control thread, after close; retains state-lock ownership through storage writes. */
    fun awaitControlStopped() {
        val worker = controlThread ?: return
        if (worker !== Thread.currentThread()) worker.join()
    }

    /** Transport cancellation never owns the cipher/writer lock or invokes media/OS callbacks. */
    fun cancelPendingIo() {
        closed.set(true)
        startup.close()
        safeClose(socket)
        val retiring = synchronized(eventTransportLock) {
            val resources = eventServer to eventSocket
            eventServer = null; eventSocket = null
            resources
        }
        safeClose(retiring.first)
        safeClose(retiring.second)
    }

    override fun close() {
        cancelPendingIo()
        if (!cleanupStarted.compareAndSet(false, true)) return
        listener.onDebugLog("BENCH_DIAG sessionCloseCaller=" + Thread.currentThread().stackTrace.drop(2).take(4).joinToString("|") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" })
        startup.close()
        mainScreenToken = null
        clearClusterContent()
        safeClose(socket)
        try {
            media.onSessionClosed(this)
        } catch (error: Exception) {
            Unit
        }
        teardown()
        if (notified.compareAndSet(false, true)) listener.onSessionEnded(this)
    }

    /** Stable identity of the usable primary screen; replaced on SETUP, absent after teardown/close. */
    internal fun mainScreenSessionToken(): Any? = if (closed.get()) null else mainScreenToken

    /**
     * Asks the iPhone to draw CarPlay's cluster UI on the alt screen (showUI with the display's URL,
     * then a keyframe) or to stop drawing it (stopUI). The stream stays up either way; CarKit handles
     * both as car-initiated commands for a screen UUID.
     */
    fun setClusterUiShown(shown: Boolean): Boolean {
        synchronized(clusterCommandLock) {
            if (closed.get() || clusterStream <= 0) return false
            val content = synchronized(clusterContentLock) { clusterContent } ?: return false
            if (!shown) {
                val sent = sendCommand(mapOf("type" to "stopUI",
                    "params" to mapOf("uuid" to AirPlayInfoPlist.ALT_UUID)))
                return synchronized(clusterContentLock) {
                    val current = clusterContent ?: return false
                    if (closed.get() || current.stream != content.stream) {
                        if (sent) clusterContent = current.copy(displayed = null, shown = false, token = Any())
                        return false
                    }
                    if (sent) clusterContent = current.copy(shown = false)
                    sent
                }
            }
            return showClusterUrl(content, content.requested ?: return false)
        }
    }

    private data class ClusterContent(val stream: Int, val requested: String?, val displayed: String?,
        val shown: Boolean = true, val token: Any = Any())
    private val clusterContentLock = Any()
    private val clusterCommandLock = Any()
    @Volatile private var clusterContent: ClusterContent? = null
    private var requestedClusterUrl = config.cluster?.initialUrl // guarded by clusterContentLock

    /** Last delivered content for this stream. Pending selections never grant wheel zoom eligibility. */
    fun clusterUrl(): String? = clusterContentRoute()?.second

    /** One immutable snapshot keeps content and generation checks consistent without locking keys. */
    internal fun clusterContentRoute(): Triple<Int, String?, Any>? {
        val content = clusterContent ?: return null
        if (closed.get() || content.stream != clusterStream) return null
        return Triple(content.stream, content.displayed, content.token)
    }

    /**
     * Switches the dashboard to another of the iPhone's `altScreenURLs` without reconnecting: `showUI`
     * with that URL and a keyframe (on a Tang this switches map, turn card and both at once). With [send]
     * false (the map is paused) the URL is only kept for the next `showUI`. False without a cluster stream.
     */
    fun setClusterUrl(url: String, send: Boolean): Boolean {
        return synchronized(clusterCommandLock) {
            val content = synchronized(clusterContentLock) {
                val current = clusterContent ?: return false
                if (closed.get() || current.stream != clusterStream || config.cluster == null) return false
                requestedClusterUrl = url
                current.copy(requested = url).also { clusterContent = it }
            }
            if (!send) true else showClusterUrl(content, url)
        }
    }

    /** Seeds a retained controller selection even when this phone has not set up its cluster yet. */
    internal fun restoreClusterUrl(url: String, send: Boolean): Boolean = synchronized(clusterCommandLock) {
        val content = synchronized(clusterContentLock) {
            if (closed.get() || config.cluster == null) return false
            requestedClusterUrl = url
            clusterContent?.copy(requested = url)?.also { clusterContent = it }
        }
        if (content == null || !send || (content.shown && content.displayed == url)) true
        else showClusterUrl(content, url)
    }

    private fun showClusterUrl(content: ClusterContent, url: String): Boolean {
        if (closed.get() || clusterStream != content.stream) return false
        val uuid = AirPlayInfoPlist.ALT_UUID
        val uiSent = sendCommand(mapOf("type" to "showUI", "params" to mapOf("uuid" to uuid, "url" to url)))
        val sent = uiSent && clusterStream == content.stream && !closed.get() &&
            sendCommand(mapOf("type" to "forceKeyFrame", "params" to mapOf("uuid" to uuid)))
        return synchronized(clusterContentLock) {
            val current = clusterContent ?: return false
            if (closed.get() || current.stream != content.stream || clusterStream != content.stream) {
                // A command already in flight can outlast re-SETUP. Its displayed result is unknown.
                if (uiSent) clusterContent = current.copy(displayed = null, token = Any())
                return false
            }
            if (uiSent) clusterContent = current.copy(displayed = if (sent) url else null,
                shown = true, token = Any())
            sent
        }
    }

    private fun beginClusterContent() = synchronized(clusterContentLock) {
        val stream = ++clusterStreamSetups
        val initial = config.cluster?.initialUrl
        clusterContent = ClusterContent(stream, requestedClusterUrl, initial)
        clusterStream = stream
    }

    private fun clearClusterContent() = synchronized(clusterContentLock) {
        clusterStream = 0
        clusterContent = null
    }

    // Re-SETUP starts at initialURL. Reapply the saved live selection only after SETUP was answered,
    // or the event channel becomes ready, without claiming it was displayed before delivery.
    private fun reapplyClusterContent() {
        synchronized(clusterCommandLock) {
            val content = clusterContent ?: return
            val requested = content.requested ?: return
            if (content.shown && requested != content.displayed) showClusterUrl(content, requested)
        }
    }

    /**
     * Zooms CarPlay's dashboard map one step, as a car's own zoom controls do: the car command
     * changeMapZoomLevel for the cluster screen's UUID. Seen with Apple Maps on a Tang: zoomDirection 0
     * zooms in, 1 zooms out. Sent only while the cluster stream is up.
     */
    fun changeMapZoomLevel(zoomIn: Boolean): Boolean = clusterStream > 0 && sendCommand(
        linkedMapOf(
            "type" to "changeMapZoomLevel",
            "params" to linkedMapOf(
                "uuid" to AirPlayInfoPlist.ALT_UUID,
                "zoomDirection" to if (zoomIn) ZOOM_DIRECTION_IN else ZOOM_DIRECTION_OUT,
            ),
        ),
    )

    /** Moves CarPlay on the main screen to one of its declared view areas, without reconnecting. */
    fun showViewArea(index: Int): Boolean =
        sendCommand(AirPlayInfoPlist.viewAreaCommand(index, config.main.viewAreas?.size ?: 1))

    fun sendCommand(command: Map<String, Any?>): Boolean = synchronized(eventWriteLock) {
        sendCommandLocked(command)
    }

    /**
     * A message on a video in car remote control session, framed like the iPhone's own: POST /command
     * with X-Apple-StreamID and {params: {data: bplist(message)}}.
     */
    fun sendRemoteControlMessage(streamId: Long, message: Map<String, Any?>): Boolean = synchronized(eventWriteLock) {
        sendCommandLocked(
            linkedMapOf("params" to linkedMapOf("data" to BplistCodec.encode(message))),
            "X-Apple-StreamID: $streamId\r\n",
        )
    }

    /**
     * Video in car: retains whether video may play now and sends the latest value once SETUP has
     * enabled video and the event channel is ready. An iPhone without the feature gets no command.
     */
    internal fun setVideoPlaybackAllowed(allowed: Boolean): VideoPlaybackDelivery =
        videoPlaybackAvailability.setDesired(allowed)

    private fun sendCommandLocked(command: Map<String, Any?>, extraHeaders: String = ""): Boolean {
        if (closed.get()) return false
        val socket = eventSocket ?: return false
        val cipher = eventCipher ?: return false
        eventCseq++
        val body = BplistCodec.encode(command)
        val head = "POST /command RTSP/1.0\r\n" + extraHeaders +
            "Content-Type: $PLIST_CONTENT_TYPE\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "CSeq: $eventCseq\r\n\r\n"
        Unit
        return try {
            val bytes = cipher.encrypt(head.toByteArray(Charsets.US_ASCII) + body)
            val output = socket.getOutputStream()
            output.write(bytes)
            output.flush()
            true
        } catch (error: Exception) {
            listener.onDebugLog("BENCH_DIAG commandWriteFailureType=${error.javaClass.simpleName}")
            close()
            false
        }
    }

    fun sendTouch(contacts: List<AirPlayContact>): Boolean {
        val scaled = contacts.map {
            it.copy(x = it.x * config.main.widthPixels, y = it.y * config.main.heightPixels)
        }
        val report = AirPlayHid.touchReport(scaled)
        val sendStartNs = System.nanoTime()
        val sent = sendHidReport(AirPlayHid.TOUCH_HID_UID, report)
        Unit
        if (sent && firstTouchSendLogged.compareAndSet(false, true)) {
            val first = scaled.firstOrNull()
            Unit
        } else if (!sent && touchSendFailureLogged.compareAndSet(false, true)) {
            Unit
        }
        return sent
    }

    fun sendKnob(state: AirPlayKnobState, momentary: Boolean = true) {
        sendHidReport(AirPlayHid.KNOB_HID_UID, AirPlayHid.knobReport(state))
        if (momentary) sendHidReport(AirPlayHid.KNOB_HID_UID, AirPlayHid.knobReport(AirPlayKnobState()))
    }

    fun sendKnobSelect(down: Boolean) =
        sendHidReport(AirPlayHid.KNOB_HID_UID, AirPlayHid.knobReport(AirPlayKnobState(select = down)))

    /** One momentary consumer press and release; never interleave another input owner. */
    fun sendMedia(index: Int): Boolean = synchronized(eventWriteLock) {
        if (closed.get() || index !in 1..6) return@synchronized false
        sendHidReport(AirPlayHid.MEDIA_HID_UID, AirPlayHid.mediaReport(index)) &&
            sendHidReport(AirPlayHid.MEDIA_HID_UID, AirPlayHid.mediaReport(0))
    }

    /** Blocking: callers must use a bounded off-main worker. No retry/migration to another owner. */
    fun sendPhoneControl(control: PhoneControl, current: () -> Boolean): PhoneControlOutcome = synchronized(eventWriteLock) {
        if (!encryptedEventReady || !runCatching(current).getOrDefault(false))
            return@synchronized PhoneControlOutcome.WITHHELD
        val originalSocket = eventSocket
        val originalCipher = eventCipher
        val press = sendHidReport(AirPlayHid.TELEPHONY_HID_UID, AirPlayHid.telephonyReport(control.selector))
        // Best effort release on the ORIGINAL usable channel, even if the first write failed.
        // A stale command must never be replayed. A failed write is not proof that no press escaped.
        val usable = !closed.get() && eventSocket === originalSocket && eventCipher === originalCipher &&
            originalSocket?.isClosed == false
        val release = if (usable) sendHidReport(AirPlayHid.TELEPHONY_HID_UID, AirPlayHid.telephonyReport(0)) else null
        when {
            press && release == true -> PhoneControlOutcome.WRITTEN
            press && release == false -> PhoneControlOutcome.PRESS_WRITTEN_RELEASE_FAILED
            press -> PhoneControlOutcome.PRESS_WRITTEN_RELEASE_UNAVAILABLE
            release == true -> PhoneControlOutcome.PRESS_FAILED_RELEASE_WRITTEN
            release == false -> PhoneControlOutcome.PRESS_FAILED_RELEASE_FAILED
            else -> PhoneControlOutcome.PRESS_FAILED_RELEASE_UNAVAILABLE
        }
    }

    fun invokeSiri() {
        sendCommand(linkedMapOf("type" to "requestSiri", "params" to linkedMapOf("siriAction" to 2)))
        sendCommand(linkedMapOf("type" to "requestSiri", "params" to linkedMapOf("siriAction" to 3)))
    }

    fun sendIapMessage(data: ByteArray, timeoutMillis: Long = 0L): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        val command = linkedMapOf<String, Any?>(
            "type" to "iAPSendMessage",
            "params" to linkedMapOf("data" to data),
        )
        if (timeoutMillis == 0L) return sendCommand(command)

        val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        while (true) {
            val sent = synchronized(eventWriteLock) {
                if (eventSocket == null || eventCipher == null) {
                    null
                } else {
                    sendCommandLocked(command)
                }
            }
            if (sent != null) return sent
            if (closed.get()) return false

            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) return false
            val sleepMillis = minOf(
                EVENT_READY_POLL_MILLIS,
                (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND,
            ).coerceAtLeast(1)
            try {
                Thread.sleep(sleepMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    /** Transport truth, not RECORD/render/status readiness. */
    val encryptedEventReady: Boolean get() =
        controlAuthenticated && !closed.get() && eventSocket?.isClosed == false && eventCipher != null
    /** Atomic readiness admission: never retain a stale vehicle sample before event readiness. */
    fun setNightModeIfEventReady(night: Boolean): Boolean = synchronized(eventWriteLock) {
        if (!encryptedEventReady) false else setNightMode(night)
    }

    fun setNightMode(night: Boolean): Boolean = synchronized(eventWriteLock) {
        pendingNightMode = night
        sendPendingNightModeLocked()
    }

    /** No retained unlock: the source owner requalifies/replays at readiness.
     * Firmware normalizes params.limitedUI and scopes params.uuid to a display.
     * Secondary screens never inherit the main screen's Park unlock.
     */
    fun setLimitedUIIfEventReady(limited: Boolean, canUnlock: () -> Boolean): Boolean = synchronized(eventWriteLock) {
        if (!config.drivingRestrictions || !encryptedEventReady) return@synchronized false
        // Requalify AFTER acquiring the socket's writer lock: a queued unlock may have expired,
        // lost its source/owner, or changed gear while another command held the writer.
        val safeLimited = limited || !runCatching(canUnlock).getOrDefault(false)
        fun send(uuid: String, value: Boolean) = sendCommandLocked(linkedMapOf(
            "type" to "setLimitedUI", "params" to linkedMapOf("uuid" to uuid, "limitedUI" to value)))
        val main = send(AirPlayInfoPlist.MAIN_UUID, safeLimited)
        val secondary = if (config.cluster != null) send(AirPlayInfoPlist.ALT_UUID, true) else true
        // A clamped unlock is not an acknowledged unlock; the bridge must re-evaluate its source.
        main && secondary && safeLimited == limited
    }

    private fun sendPendingNightModeLocked(): Boolean {
        val night = pendingNightMode ?: return true
        val sent = sendCommandLocked(
            linkedMapOf("type" to "setNightMode", "params" to linkedMapOf("nightMode" to night)),
        )
        if (sent) pendingNightMode = null
        return sent
    }

    private fun sendHidReport(uid: Int, report: ByteArray): Boolean =
        sendCommand(
            linkedMapOf(
                "type" to "hidSendReport",
                "uuid" to uid.toString(16),
                "hidReport" to report,
            ),
        )

    private fun runControl() {
        socket.tcpNoDelay = true
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        var accumulated = ByteArray(0)
        val buffer = ByteArray(READ_CHUNK_BYTES)
        var closeReason = "session closed"
        try {
            while (!closed.get()) {
                val count = input.read(buffer)
                if (count < 0) {
                    listener.onDebugLog("BENCH_DIAG controlPeerEOF")
                    closeReason = "peer EOF"
                    break
                }
                var plaintext = buffer.copyOf(count)
                val activeCipher = cipher
                if (activeCipher != null) {
                    require(encBuf.size + plaintext.size <= MAX_CONTROL_BYTES)
                    encBuf += plaintext
                    val decrypted = try {
                        activeCipher.decrypt(encBuf)
                    } catch (error: Exception) {
                        closeReason = "control decrypt failed: ${error.message ?: error.javaClass.simpleName}"
                        Unit
                        break
                    }
                    encBuf = decrypted.rest
                    plaintext = decrypted.data
                }
                require(accumulated.size + plaintext.size <= MAX_CONTROL_BYTES)
                accumulated += plaintext
                val parsed = RtspMessage.parseMessages(accumulated)
                accumulated = parsed.rest
                for (request in parsed.messages) {
                    val cseq = request.headers["cseq"] ?: "-"
                    val path = request.path.lowercase()
                    val showInDebugOverlay =
                        !path.endsWith("/feedback") &&
                            !(request.method == "POST" && path.endsWith("/command"))
                    Unit
                    if (showInDebugOverlay) Unit
                    Unit
                    listener.onDebugLog("BENCH_DIAG requestMethod=${request.method.filter { it in 'A'..'Z' }.take(20)} bytes=${request.body.size}")
                    val response = try {
                        handle(request)
                    } catch (error: Exception) {
                        listener.onDebugLog("BENCH_DIAG handleFailureType=${error.javaClass.simpleName} at=${error.stackTrace.firstOrNull()?.lineNumber}")
                        RtspMessage.Response(status = 500)
                    }
                    Unit
                    if (showInDebugOverlay) Unit
                    listener.onDebugLog("BENCH_DIAG responseStatus=${response.status ?: 200}")
                    if (request.method == "SETUP") {
                        val streamsReply = runCatching { asMap(BplistCodec.decode(response.body))?.get("streams") as? List<*> }.getOrNull()
                        streamsReply?.forEach { entry ->
                            val replyMap = asMap(entry) ?: return@forEach
                            listener.onDebugLog("BENCH_DIAG streamReply type=${(replyMap["type"] as? Number)?.toLong()} port=${(replyMap["dataPort"] as? Number)?.toLong()} streamId=${(replyMap["streamID"] as? Number)?.toLong()}")
                        }
                    }
                    val wire = RtspMessage.buildResponse(request, response)
                    Unit
                    output.write(cipher?.encrypt(wire) ?: wire)
                    if (request.method == "RECORD" && (response.status ?: 200) == 200) {
                        output.flush()
                        startup.recordReplied()
                    }
                    if (cipher == null && pairVerify.controlKeys != null) {
                        check(finishControlAuthentication()) { "Authenticated controller admission declined" }
                    }
                }
                output.flush()
                notifySetupResponseSent()
            }
        } catch (error: Exception) {
            closeReason = "control I/O failed: ${error.message ?: error.javaClass.simpleName}"
            if (!closed.get()) listener.onDebugLog("BENCH_DIAG eventReadFailureType=${error.javaClass.simpleName}")
        } finally {
            Unit
            close()
        }
    }

    internal fun finishControlAuthentication(): Boolean {
        if (authenticated.get()) return !closed.get()
        if (closed.get() || !pairVerify.isVerified) return false
        val keys = pairVerify.controlKeys ?: return false
        if (!listener.onControlAuthenticated(this)) { close(); return false }
        cipher = ControlCipher(keys.readKey, keys.writeKey)
        authenticated.set(true)
        return true
    }

    private fun handle(request: RtspMessage.Request): RtspMessage.Response {
        if (request.method in setOf("SETUP", "RECORD", "TEARDOWN") &&
            (!pairVerify.isVerified || socketBinding != null && !controlAuthenticated))
            return RtspMessage.Response(status = 403)
        when (request.method) {
            "SETUP" -> return handleSetup(request)
            "RECORD" -> {
                val timingReady = ntp.isSynced
                listener.onDebugLog("BENCH_DIAG recordTiming synced=$timingReady matchedResponses=${ntp.matchedResponses}")
                listener.onSessionActive(this)
                return RtspMessage.Response(status = 200)
            }
            "TEARDOWN" -> return handleTeardown(request)
        }

        if (config.bufferedAudioOutputEnabled && request.method in BUFFERED_AUDIO_METHODS) {
            val body = if (request.body.isEmpty() && request.method == "GETANCHOR") emptyMap() else {
                runCatching { asMap(BplistCodec.decode(request.body)) }.getOrNull()
                    ?: return RtspMessage.Response(status = 400)
            }
            val response = media.onBufferedAudioControl(this, request.method, body)
            if (request.method != "GETANCHOR") Unit
            return if (response == null) RtspMessage.Response(status = 200) else RtspMessage.Response(
                headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
                body = BplistCodec.encode(response),
            )
        }

        val path = request.path.lowercase()
        if (path.endsWith("/pair-setup") && (!allowPairSetup || pairSetupAdmission?.invoke(this) == false))
            return RtspMessage.Response(status = 403)
        return when {
            path.endsWith("/pair-setup") -> RtspMessage.Response(
                headers = mapOf("Content-Type" to PAIRING_CONTENT_TYPE),
                body = pairSetup.handle(request.body),
            )
            path.endsWith("/pair-verify") -> RtspMessage.Response(
                headers = mapOf("Content-Type" to PAIRING_CONTENT_TYPE),
                body = pairVerify.handle(request.body),
            )
            path.endsWith("/auth-setup") -> {
                val body = mfi?.let { MfiSapAuthSetup.handle(request.body, it) }
                if (body == null) RtspMessage.Response(status = 400)
                else RtspMessage.Response(headers = mapOf("Content-Type" to OCTET_CONTENT_TYPE), body = body)
            }
            path.endsWith("/info") -> {
                val info = AirPlayInfoPlist.build(config,
                    availableAudioFormats = outputAudioFormats ?: if (config.lpcmOnly || config.disableAudioOutput) emptySet()
                        else com.shilapi.xcertplay.media.AndroidAudioDecoderSupport.availableBits,
                    microphoneCapability = offeredMicrophone, videoSelection = videoSelection)
                val audioRows = info["audioFormats"] as? List<*> ?: emptyList<Any>()
                AudioOfferDiagnostics.lines(info).forEach(listener::onDebugLog)
                listener.onDebugLog("BENCH_DIAG audioOfferRows=${audioRows.size} codecBits=${(outputAudioFormats ?: com.shilapi.xcertplay.media.AndroidAudioDecoderSupport.availableBits).sorted().joinToString(",")}")
                if (request.body.isNotEmpty()) {
                    Unit
                    Unit
                }
                Unit
                Unit
                Unit
                RtspMessage.Response(
                    headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
                    body = BplistCodec.encode(info),
                )
            }
            request.method == "POST" && path.endsWith("/command") -> handleCommand(request)
            request.method == "POST" && path.endsWith("/feedback") -> {
                val body = media.onFeedback(this)
                if (body == null) {
                    RtspMessage.Response(status = 200)
                } else {
                    RtspMessage.Response(
                        headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
                        body = BplistCodec.encode(body),
                    )
                }
            }
            else -> RtspMessage.Response(status = 200)
        }
    }

    private fun notifySetupResponseSent() {
        try {
            media.onSetupResponseSent(this)
        } catch (error: Exception) {
            Unit
        }
        reapplyClusterContent()
    }

    private fun debugLog(message: String, uiVisible: Boolean = true) {
        Unit
        if (!uiVisible) return
        try {
            Unit
        } catch (error: Exception) {
            Unit
        }
    }

    private fun trace(message: String) {
        try {
            Unit
        } catch (error: Exception) {
            Unit
        }
    }

    private fun handleSetup(request: RtspMessage.Request): RtspMessage.Response {
        val dict = try {
            asMap(BplistCodec.decode(request.body)) ?: return RtspMessage.Response(status = 400)
        } catch (error: Exception) {
            Unit
            return RtspMessage.Response(status = 400)
        }
        Unit
        val streams = dict["streams"] as? List<*>
        if (streams != null) {
            val responseStreams = handleStreams(streams)
            Unit
            val body = BplistCodec.encode(linkedMapOf("streams" to responseStreams))
            Unit
            return RtspMessage.Response(headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE), body = body)
        }

        val name = string(dict["name"])
        val deviceId = string(dict["deviceID"])
        val wifiMac = string(dict["macAddress"]).lowercase()
        val model = string(dict["model"])
        Unit
        if (deviceId.isNotEmpty()) deviceBtMac = deviceId
        if (name.isNotEmpty() || deviceId.isNotEmpty() || wifiMac.isNotEmpty()) {
            listener.onDeviceInfo(this, AirPlayDeviceInfo(name, deviceId, wifiMac, model))
        }

        val peerTimingPort = long(dict["timingPort"])?.toInt() ?: 0
        val response = linkedMapOf<String, Any?>(
            "timingPort" to openTiming(peerTimingPort),
            "eventPort" to openEvent(),
        )
        if (dict["keepAliveLowPower"] == true || dict["keepAliveLowPower"] == 1L) {
            response["keepAlivePort"] = openKeepAlive()
        }
        val features = setupEnabledFeatures(config, dict["features"] as? List<*>, videoSelection)
        hevcNegotiated = "hevc" in features
        val videoPlaybackEnabled = VideoInCar.FEATURE in features
        val videoDelivery = videoPlaybackAvailability.setFeatureEnabled(videoPlaybackEnabled)
        Unit
        response["enabledFeatures"] = features
        return RtspMessage.Response(
            headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
            body = BplistCodec.encode(response),
        )
    }

    private fun handleStreams(streams: List<*>): List<Any?> {
        val result = arrayListOf<Any?>()
        for (entry in streams) {
            val stream = asMap(entry) ?: continue
            val type = long(stream["type"])?.toInt() ?: continue
            listener.onDebugLog("BENCH_DIAG streamType=$type audioFormat=${(stream["audioFormat"] as? Number)?.toLong()} hasConnectionId=${stream.containsKey("streamConnectionID")} hasSeed=${stream.containsKey("seed")}")
            val transport = listOf("dataTransportType", "transportType", "protocol", "streamID", "dataPort", "controlPort").joinToString(" ") { key ->
                val value = (stream[key] as? Number)?.toLong()
                "$key=${value ?: "absent-or-nonnumeric"}"
            }
            listener.onDebugLog("BENCH_DIAG streamTransport $transport")
            val controlType = (stream["controlType"] as? Number)?.toLong()
            val dedicated = stream["wantsDedicatedSocket"] as? Boolean
            val asIs = stream["sendMessageAsIs"] as? Boolean
            val channelValid = (stream["channelID"] as? String)?.isNotEmpty() == true
            val clientValid = (stream["clientUUID"] as? String)?.isNotEmpty() == true
            listener.onDebugLog("BENCH_DIAG rcsOptions controlType=$controlType dedicated=$dedicated asIs=$asIs channelValid=$channelValid clientValid=$clientValid")
            when (type) {
                STREAM_TYPE_MAIN_SCREEN, STREAM_TYPE_ALT_SCREEN -> {
                    val port = media.onScreen(this, type, stream)
                    Unit
                    if (port != null) {
                        activeStreams.add(type)
                        if (type == STREAM_TYPE_ALT_SCREEN) beginClusterContent()
                        else mainScreenToken = Any()
                        result.add(linkedMapOf("type" to type, "dataPort" to port))
                    }
                }
                STREAM_TYPE_MAIN_AUDIO, STREAM_TYPE_ALT_AUDIO, STREAM_TYPE_MAIN_HIGH_AUDIO -> {
                    val streamResponse = media.onAudio(this, type, stream)
                    Unit
                    if (streamResponse != null) {
                        activeStreams.add(type)
                        result.add(streamResponse)
                    }
                }
                STREAM_TYPE_MAIN_BUFFERED_AUDIO -> {
                    val streamResponse = if (config.bufferedAudioOutputEnabled) media.onBufferedAudio(this, stream) else null
                    Unit
                    if (streamResponse != null) {
                        activeStreams.add(type)
                        result.add(streamResponse)
                    }
                }
                STREAM_TYPE_DATA -> {
                    val streamResponse = media.onDataStream(this, stream)
                    Unit
                    if (streamResponse != null) {
                        activeStreams.add(type)
                        result.add(streamResponse)
                    }
                }
                else -> Unit
            }
        }
        listener.onDebugLog("BENCH_DIAG streamOfferCount=${streams.size} acceptedCount=${result.size}")
        return result
    }

    private fun handleCommand(request: RtspMessage.Request): RtspMessage.Response {
        val body = try {
            asMap(BplistCodec.decode(request.body)) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap<String, Any?>()
        }
        val type = string(body["type"])
        val params = asMap(body["params"]) ?: emptyMap()
        Unit
        val streamId = request.headers["x-apple-streamid"]?.toLongOrNull()
        val data = params["data"] as? ByteArray
        if (streamId != null && data != null) {
            runCatching { asMap(BplistCodec.decode(data)) }.getOrNull()
                ?.let { listener.onRemoteControlMessage(this, streamId, it) }
            return RtspMessage.Response(status = 200)
        }
        if (type == "requestUI" && string(params["url"]) == VideoInCar.UI_URL) {
            listener.onVideoPlaybackUiRequested(this)
        } else if (type == "requestUI" && controlAuthenticated &&
            (params["url"] == null || params["url"] == "")) {
            // Generic return only. Never launch arbitrary phone-provided URL intents.
            listener.onHostUiRequested(this)
        }
        listener.onCommand(this, type, params)
        return RtspMessage.Response(status = 200)
    }

    private fun handleTeardown(request: RtspMessage.Request): RtspMessage.Response {
        val decodedBody = try {
            BplistCodec.decode(request.body)
        } catch (_: Exception) {
            null
        }
        val types = teardownStreamTypes(decodedBody)
        val reason = (asMap(decodedBody)?.get("reason") as? Number)?.toLong()
        listener.onDebugLog("BENCH_DIAG teardownTypes=${types?.sorted()?.joinToString(",") ?: "all"} reasonCode=${reason ?: "absent"}")

        Unit
        Unit

        // Restore wheel input before releasing media resources, which may take time to close.
        if (types == null || STREAM_TYPE_MAIN_SCREEN in types) mainScreenToken = null
        if (types == null || STREAM_TYPE_ALT_SCREEN in types) clearClusterContent()
        if (types == null) {
            activeStreams.toList().forEach { media.onTeardown(this, it) }
            activeStreams.clear()
        } else {
            types.forEach { type -> if (activeStreams.remove(type)) media.onTeardown(this, type) }
        }
        if (STREAM_TYPE_ALT_SCREEN !in activeStreams) clearClusterContent()
        return RtspMessage.Response(status = 200)
    }

    private fun openTiming(peerPort: Int): Int {
        val port = ntp.listen()
        if (peerPort > 0) peerAddress?.let { ntp.start(it, peerPort) }
        return port
    }

    private fun openKeepAlive(): Int {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(socketBinding?.local ?: InetAddress.getByName("::"), 0))
        keepAliveSocket = socket
        keepAliveThread = Thread({ runKeepAlive(socket) }, "airplay-keepalive").apply {
            isDaemon = true
            start()
        }
        return socket.localPort
    }

    private fun runKeepAlive(socket: DatagramSocket) {
        val buffer = ByteArray(512)
        while (!closed.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                if (socketBinding?.let { !it.allowsPeer(packet.address) } ?: (peerAddress != packet.address)) continue
            } catch (_: Exception) {
                if (closed.get()) return
            }
        }
    }

    private fun openEvent(): Int {
        val server = ServerSocket(0, 50, socketBinding?.local ?: InetAddress.getByName("::"))
        synchronized(eventTransportLock) {
            if (closed.get()) { safeClose(server); error("Session closed") }
            eventServer = server
        }
        spawnEvent("airplay-event-accept") { acceptEvent(server) }
        return server.localPort
    }

    private fun teardown() {
        videoPlaybackAvailability.setEventReady(false)
        videoPlaybackAvailability.setFeatureEnabled(false)
        ntp.close()
        safeClose(keepAliveSocket)
        keepAliveSocket = null
        keepAliveThread?.interrupt()
        keepAliveThread = null
        cancelPendingIo()
        eventCipher = null
        eventThreads.forEach { it.interrupt() }
        eventThreads.clear()
    }

    private fun acceptEvent(server: ServerSocket) {
        try {
            var socket = server.accept()
            while ((socketBinding?.let { !it.allowsPeer(socket.inetAddress) || !it.allowsLocal(socket.localAddress) }
                ?: (peerAddress != socket.inetAddress)) && !closed.get()) {
                safeClose(socket)
                socket = server.accept()
            }
            if (closed.get()) { safeClose(socket); return }
            socket.setSoLinger(true, 0)
            // Avoid Nagle coalescing on this interactive event connection: small HID/event writes
            // need not wait for earlier unacknowledged data.
            socket.tcpNoDelay = true
            Unit
            listener.onDebugLog("BENCH_DIAG eventConnected")
            synchronized(eventTransportLock) {
                if (closed.get()) { safeClose(socket); return }
                eventSocket = socket
            }
            val shared = pairVerify.shared
            if (shared == null) {
                Unit
                safeClose(socket)
                close()
                return
            }
            val writeKey = AirPlayCrypto.hkdfSha512(
                shared,
                "Events-Salt".asciiBytes(),
                "Events-Write-Encryption-Key".asciiBytes(),
                32,
            )
            val readKey = AirPlayCrypto.hkdfSha512(
                shared,
                "Events-Salt".asciiBytes(),
                "Events-Read-Encryption-Key".asciiBytes(),
                32,
            )
            synchronized(eventWriteLock) {
                if (closed.get()) return
                eventCipher = ControlCipher(readKey, writeKey)
                startup.eventReady()
                sendPendingNightModeLocked()
            }
            val videoDelivery = videoPlaybackAvailability.setEventReady(true)
            Unit
            reapplyClusterContent()
            runEventRead(socket)
        } catch (error: Exception) {
            if (!closed.get()) {
                Unit
                close()
            }
        }
    }

    private fun runEventRead(socket: Socket) {
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            var encrypted = ByteArray(0)
            var plaintext = ByteArray(0)
            val buffer = ByteArray(READ_CHUNK_BYTES)
            while (!closed.get()) {
                val count = input.read(buffer)
                if (count < 0) { listener.onDebugLog("BENCH_DIAG eventPeerEOF"); break }
                val cipher = eventCipher ?: break
                require(encrypted.size + count <= MAX_CONTROL_BYTES)
                encrypted += buffer.copyOf(count)
                val decrypted = try {
                    cipher.decrypt(encrypted)
                } catch (error: Exception) {
                    listener.onDebugLog("BENCH_DIAG eventDecryptFailureType=${error.javaClass.simpleName}")
                    break
                }
                encrypted = decrypted.rest
                require(plaintext.size + decrypted.data.size <= MAX_CONTROL_BYTES)
                plaintext += decrypted.data
                val parsed = RtspMessage.parseMessages(plaintext)
                plaintext = parsed.rest
                for (message in parsed.messages) {
                    if (message.method.startsWith("RTSP/") || message.method.startsWith("HTTP/")) continue
                    Unit
                    val response = RtspMessage.buildResponse(message, RtspMessage.Response(status = 200))
                    Unit
                    Unit
                    synchronized(eventWriteLock) {
                        output.write(cipher.encrypt(response))
                        output.flush()
                    }
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) listener.onDebugLog("BENCH_DIAG eventReadFailureType=${error.javaClass.simpleName}")
        } finally {
            Unit
            videoPlaybackAvailability.setEventReady(false)
            if (eventSocket === socket) eventSocket = null
            eventCipher = null
            safeClose(socket)
            if (!closed.get()) close()
        }
    }

    private fun spawnEvent(name: String, body: () -> Unit) {
        val thread = Thread(body, name).apply { isDaemon = true }
        eventThreads.add(thread)
        thread.start()
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val PLIST_CONTENT_TYPE = "application/x-apple-binary-plist"
        const val PAIRING_CONTENT_TYPE = "application/pairing+tlv8"
        const val OCTET_CONTENT_TYPE = "application/octet-stream"

        const val STREAM_TYPE_MAIN_SCREEN = 110
        const val STREAM_TYPE_ALT_SCREEN = 111
        const val STREAM_TYPE_MAIN_AUDIO = 100
        const val STREAM_TYPE_ALT_AUDIO = 101
        const val STREAM_TYPE_MAIN_HIGH_AUDIO = 102
        const val STREAM_TYPE_DATA = 130
        const val STREAM_TYPE_MAIN_BUFFERED_AUDIO = 103
        private val BUFFERED_AUDIO_METHODS = setOf("SETRATE", "SETRATEANCHORTIME", "GETANCHOR", "FLUSHBUFFERED")

        const val MAX_CONTROL_BYTES = 1024 * 1024
        const val READ_CHUNK_BYTES = 16 * 1024
        const val ZOOM_DIRECTION_IN = 0
        const val ZOOM_DIRECTION_OUT = 1
        const val EVENT_READY_POLL_MILLIS = 25L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

/** The features SETUP enables; video in car only when configured and the iPhone [proposed] it. */
/** The session feature the iPhone proposes for main buffered audio. */
internal const val MAIN_BUFFERED_FEATURE = "mainBuffered"

internal fun setupEnabledFeatures(config: AirPlayConfig, proposed: List<*>?,
    videoSelection: com.shilapi.xcertplay.media.CarPlayVideoSelection? = null): List<String> {
    val features = mutableListOf<String>()
    // HEVC is an accessory capability, enabled before the phone reads /info.
    // Unlike opt-in video-in-car features, pinned receivers do not require
    // the phone to echo "hevc" in this initial SETUP request.
    if (config.hevc && videoSelection?.matches(config) == true && videoSelection.hevcOffered)
        features.add("hevc")
    features.add("iAPChannel")
    features.add("viewAreas")
    if (config.cluster != null) features.add("altScreen")
    if (config.videoInCar && proposed.orEmpty().contains(VideoInCar.FEATURE)) features.add(VideoInCar.FEATURE)
    if (config.bufferedAudioOutputEnabled && proposed.orEmpty().contains(MAIN_BUFFERED_FEATURE)) features.add(MAIN_BUFFERED_FEATURE)
    return features
}

/**
 * The stream types a TEARDOWN body closes, or null when it names none (the whole session). Several
 * type-130 streams can be open, the iAP tunnel (streamID 1) and video in car's data streams; tearing
 * down one of the latter must not close the iAP tunnel.
 */
internal fun teardownStreamTypes(body: Any?): List<Int>? {
    val entries = ((body as? Map<*, *>)?.get("streams") as? List<*>).orEmpty().mapNotNull { entry ->
        val stream = entry as? Map<*, *> ?: return@mapNotNull null
        val type = (stream["type"] as? Number)?.toInt() ?: return@mapNotNull null
        type to (stream["streamID"] as? Number)?.toLong()
    }
    if (entries.isEmpty()) return null
    return entries.filterNot { (type, streamId) -> type == 130 && streamId != null && streamId != 1L }.map { it.first }
}

internal fun safeClose(closeable: Closeable?) {
    try {
        closeable?.close()
    } catch (_: Exception) {
        // Best-effort close.
    }
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun asMap(value: Any?): Map<String, Any?>? {
    val map = value as? Map<*, *> ?: return null
    val result = LinkedHashMap<String, Any?>(map.size)
    for ((key, entry) in map) result[key.toString()] = entry
    return result
}

private fun string(value: Any?): String = value as? String ?: ""

private fun long(value: Any?): Long? = (value as? Number)?.toLong()
