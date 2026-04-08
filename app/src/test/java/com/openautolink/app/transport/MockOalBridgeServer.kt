package com.openautolink.app.transport

import com.openautolink.app.audio.AudioFrame
import com.openautolink.app.video.VideoFrame
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-process OAL bridge mock for JVM integration tests.
 * Spins up TCP servers on ephemeral ports, speaks OAL protocol.
 *
 * Usage:
 *   val server = MockOalBridgeServer()
 *   server.start()
 *   // ... connect TcpControlChannel to server.controlPort, etc.
 *   server.stop()
 */
class MockOalBridgeServer {

    private var controlServer: ServerSocket? = null
    private var videoServer: ServerSocket? = null
    private var audioServer: ServerSocket? = null

    val controlPort: Int get() = controlServer?.localPort ?: 0
    val videoPort: Int get() = videoServer?.localPort ?: 0
    val audioPort: Int get() = audioServer?.localPort ?: 0

    private val running = AtomicBoolean(false)
    private val threads = mutableListOf<Thread>()

    // Connected client sockets
    private var controlClient: Socket? = null
    private var controlOut: OutputStream? = null
    private var videoClient: Socket? = null
    private var videoOut: DataOutputStream? = null
    private var audioClient: Socket? = null
    private var audioOut: DataOutputStream? = null

    // Latches for connection events
    val controlConnected = CountDownLatch(1)
    val videoConnected = CountDownLatch(1)
    val audioConnected = CountDownLatch(1)

    // Received messages from app
    val receivedControlLines = CopyOnWriteArrayList<String>()
    val receivedControlMessages = CopyOnWriteArrayList<ControlMessage>()

    // Received audio frames from app (mic direction=1)
    val receivedAudioFrames = CopyOnWriteArrayList<AudioFrame>()

    fun start() {
        startOnPorts(0, 0, 0)  // Ephemeral ports
    }

    fun startOnPorts(controlPort: Int, videoPort: Int, audioPort: Int) {
        // Retry port binding — OS may not have released ports from previous server yet
        var lastError: Exception? = null
        for (attempt in 1..10) {
            try {
                controlServer = ServerSocket(controlPort).apply { reuseAddress = true }
                videoServer = ServerSocket(videoPort).apply { reuseAddress = true }
                audioServer = ServerSocket(audioPort).apply { reuseAddress = true }
                lastError = null
                break
            } catch (e: java.net.BindException) {
                lastError = e
                controlServer?.close(); videoServer?.close(); audioServer?.close()
                Thread.sleep(200)
            }
        }
        if (lastError != null) throw lastError!!
        running.set(true)

        // Accept control connection
        threads += Thread({
            try {
                val s = controlServer!!.accept()
                s.tcpNoDelay = true
                controlClient = s
                controlOut = s.getOutputStream()
                controlConnected.countDown()

                // Read lines from control client
                val reader = s.getInputStream().bufferedReader()
                while (running.get() && !s.isClosed) {
                    val line = reader.readLine() ?: break
                    receivedControlLines.add(line)
                    val msg = ControlMessageSerializer.deserialize(line)
                    if (msg != null) receivedControlMessages.add(msg)
                }
            } catch (_: Exception) {}
        }, "mock-control-accept").also { it.isDaemon = true; it.start() }

        // Accept video connection
        threads += Thread({
            try {
                val s = videoServer!!.accept()
                s.tcpNoDelay = true
                videoClient = s
                videoOut = DataOutputStream(s.getOutputStream())
                videoConnected.countDown()
            } catch (_: Exception) {}
        }, "mock-video-accept").also { it.isDaemon = true; it.start() }

        // Accept audio connection
        threads += Thread({
            try {
                val s = audioServer!!.accept()
                s.tcpNoDelay = true
                audioClient = s
                audioOut = DataOutputStream(s.getOutputStream())
                audioConnected.countDown()

                // Read mic audio frames from app (direction=1)
                val input = DataInputStream(s.getInputStream().buffered(65536))
                while (running.get() && !s.isClosed) {
                    val hdr = ByteArray(8)
                    input.readFully(hdr)
                    val direction = hdr[0].toInt() and 0xFF
                    val purposeByte = hdr[1].toInt() and 0xFF
                    val sampleRate = (hdr[2].toInt() and 0xFF) or ((hdr[3].toInt() and 0xFF) shl 8)
                    val channels = hdr[4].toInt() and 0xFF
                    val payloadLength = (hdr[5].toInt() and 0xFF) or
                            ((hdr[6].toInt() and 0xFF) shl 8) or
                            ((hdr[7].toInt() and 0xFF) shl 16)
                    val payload = if (payloadLength > 0) ByteArray(payloadLength).also { input.readFully(it) } else ByteArray(0)
                    val purpose = AudioFrame.purposeFromByte(purposeByte) ?: continue
                    receivedAudioFrames.add(AudioFrame(direction, purpose, sampleRate, channels, payload))
                }
            } catch (_: Exception) {}
        }, "mock-audio-accept").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running.set(false)
        controlClient?.close()
        videoClient?.close()
        audioClient?.close()
        controlServer?.close()
        videoServer?.close()
        audioServer?.close()
        threads.forEach { it.interrupt() }
    }

    // ── Send control messages (bridge → app) ────────────────────────

    fun sendControlJson(json: String) {
        controlOut?.let { out ->
            out.write("$json\n".toByteArray())
            out.flush()
        }
    }

    fun sendHello(
        capabilities: List<String> = listOf("h264"),
        name: String = "MockBridge"
    ) {
        val json = """{"type":"hello","version":1,"name":"$name","capabilities":${
            capabilities.joinToString(",", "[", "]") { "\"$it\"" }
        },"video_port":$videoPort,"audio_port":$audioPort}"""
        sendControlJson(json)
    }

    fun sendPhoneConnected(phoneName: String = "TestPhone", phoneType: String = "android") {
        sendControlJson("""{"type":"phone_connected","phone_name":"$phoneName","phone_type":"$phoneType"}""")
    }

    fun sendPhoneDisconnected(reason: String = "test") {
        sendControlJson("""{"type":"phone_disconnected","reason":"$reason"}""")
    }

    fun sendAudioStart(
        purpose: String = "media",
        sampleRate: Int = 48000,
        channels: Int = 2
    ) {
        sendControlJson("""{"type":"audio_start","purpose":"$purpose","sample_rate":$sampleRate,"channels":$channels}""")
    }

    fun sendAudioStop(purpose: String = "media") {
        sendControlJson("""{"type":"audio_stop","purpose":"$purpose"}""")
    }

    fun sendNavState(
        maneuver: String = "turn_right",
        distanceMeters: Int = 150,
        road: String = "Main St",
        etaSeconds: Int = 420
    ) {
        sendControlJson("""{"type":"nav_state","maneuver":"$maneuver","distance_meters":$distanceMeters,"road":"$road","eta_seconds":$etaSeconds}""")
    }

    fun sendMediaMetadata(
        title: String = "Song",
        artist: String = "Artist",
        album: String = "Album",
        durationMs: Long = 240000,
        positionMs: Long = 0,
        playing: Boolean = true
    ) {
        sendControlJson("""{"type":"media_metadata","title":"$title","artist":"$artist","album":"$album","duration_ms":$durationMs,"position_ms":$positionMs,"playing":$playing}""")
    }

    fun sendConfigEcho(
        videoCodec: String = "h264",
        videoWidth: Int = 1920,
        videoHeight: Int = 1080,
        videoFps: Int = 30
    ) {
        sendControlJson("""{"type":"config_echo","video_codec":"$videoCodec","video_width":"$videoWidth","video_height":"$videoHeight","video_fps":"$videoFps","aa_resolution":"1080p"}""")
    }

    fun sendError(code: Int = 100, message: String = "test error") {
        sendControlJson("""{"type":"error","code":$code,"message":"$message"}""")
    }

    fun sendStats(videoFrames: Long = 0, audioFrames: Long = 0, uptime: Long = 0) {
        sendControlJson("""{"type":"stats","video_frames_sent":$videoFrames,"audio_frames_sent":$audioFrames,"uptime_seconds":$uptime}""")
    }

    // ── Send video frames (bridge → app) ────────────────────────────

    fun sendVideoFrame(
        width: Int = 1920,
        height: Int = 1080,
        ptsMs: Long = 0,
        flags: Int = 0,
        payload: ByteArray = ByteArray(0)
    ) {
        val out = videoOut ?: return
        val header = ByteArray(16)
        writeU32LE(header, 0, payload.size.toLong())
        writeU16LE(header, 4, width)
        writeU16LE(header, 6, height)
        writeU32LE(header, 8, ptsMs)
        writeU16LE(header, 12, flags)
        writeU16LE(header, 14, 0) // reserved
        synchronized(out) {
            out.write(header)
            if (payload.isNotEmpty()) out.write(payload)
            out.flush()
        }
    }

    fun sendCodecConfig(width: Int = 1920, height: Int = 1080, spsppsPps: ByteArray = MINIMAL_SPS_PPS) {
        sendVideoFrame(width, height, 0, VideoFrame.FLAG_CODEC_CONFIG, spsppsPps)
    }

    fun sendKeyframe(width: Int = 1920, height: Int = 1080, ptsMs: Long = 0, payload: ByteArray = MINIMAL_IDR) {
        sendVideoFrame(width, height, ptsMs, VideoFrame.FLAG_KEYFRAME, payload)
    }

    fun sendPFrame(width: Int = 1920, height: Int = 1080, ptsMs: Long = 33, payload: ByteArray = MINIMAL_PFRAME) {
        sendVideoFrame(width, height, ptsMs, 0, payload)
    }

    // ── Send audio frames (bridge → app) ─────────────────────────────

    fun sendAudioFrame(
        purpose: AudioPurpose = AudioPurpose.MEDIA,
        sampleRate: Int = 48000,
        channels: Int = 2,
        pcm: ByteArray = ByteArray(3840) // 20ms @ 48kHz stereo 16-bit
    ) {
        val out = audioOut ?: return
        val header = ByteArray(8)
        header[0] = AudioFrame.DIRECTION_PLAYBACK.toByte()
        header[1] = AudioFrame.purposeToByte(purpose).toByte()
        writeU16LE(header, 2, sampleRate)
        header[4] = channels.toByte()
        writeU24LE(header, 5, pcm.size)
        synchronized(out) {
            out.write(header)
            if (pcm.isNotEmpty()) out.write(pcm)
            out.flush()
        }
    }

    // ── Wait helpers ─────────────────────────────────────────────────

    fun awaitControlConnected(timeoutMs: Long = 3000): Boolean =
        controlConnected.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun awaitVideoConnected(timeoutMs: Long = 3000): Boolean =
        videoConnected.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun awaitAudioConnected(timeoutMs: Long = 3000): Boolean =
        audioConnected.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun awaitReceivedAudioFrame(timeoutMs: Long = 3000): AudioFrame? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val found = receivedAudioFrames.firstOrNull()
            if (found != null) return found
            Thread.sleep(50)
        }
        return null
    }

    fun awaitReceivedMessage(
        type: Class<out ControlMessage>,
        timeoutMs: Long = 3000
    ): ControlMessage? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val found = receivedControlMessages.firstOrNull { type.isInstance(it) }
            if (found != null) return found
            Thread.sleep(50)
        }
        return null
    }

    // ── Byte helpers ─────────────────────────────────────────────────

    private fun writeU16LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeU32LE(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeU24LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
    }

    companion object {
        // Minimal H.264 SPS+PPS for testing (Annex B format, baseline profile)
        val MINIMAL_SPS_PPS = byteArrayOf(
            // SPS: start code + NAL type 7
            0x00, 0x00, 0x00, 0x01, 0x67,
            0x42, 0x00, 0x0A, 0xF8.toByte(), 0x41, 0xA2.toByte(),
            // PPS: start code + NAL type 8
            0x00, 0x00, 0x00, 0x01, 0x68,
            0xCE.toByte(), 0x38, 0x80.toByte()
        )

        // Minimal IDR slice (just enough to be recognized as keyframe)
        val MINIMAL_IDR = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, 0x65, // start code + NAL type 5 (IDR)
            0x88.toByte(), 0x80.toByte(), 0x40, 0x00
        )

        // Minimal P-frame slice
        val MINIMAL_PFRAME = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, 0x41, // start code + NAL type 1 (non-IDR)
            0x9A.toByte(), 0x00, 0x04
        )
    }
}
