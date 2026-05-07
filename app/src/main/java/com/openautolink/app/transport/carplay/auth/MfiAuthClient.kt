package com.openautolink.app.transport.carplay.auth

import com.openautolink.app.diagnostics.OalLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "MfiAuthClient"

/**
 * Client for the MFi auth daemon running on a rooted CPC200.
 *
 * Protocol (little-endian):
 *   Send:    [cmd:1] [len:2 LE] [data:len]
 *   Receive: [status:1] [len:2 LE] [data:len]
 *
 * Commands:
 *   0x01 = PING
 *   0x02 = GET_CERT — read Apple MFi certificate from chip
 *   0x03 = SIGN_CHALLENGE — sign authentication challenge
 *   0x04 = GET_INFO — read chip version info
 */
class MfiAuthClient(
    private val host: String,
    private val port: Int = 5290,
) {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    /** Connect to the MFi auth daemon. */
    suspend fun connect(timeoutMs: Int = 5000) = withContext(Dispatchers.IO) {
        disconnect()
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
        OalLog.i(TAG, "Connected to MFi daemon at $host:$port")
    }

    /** Disconnect from the daemon. */
    fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
    }

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    /** Ping the daemon. Returns true if it responds. */
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        try {
            val (status, data) = sendCommand(CMD_PING)
            status == STATUS_OK && String(data) == "OK"
        } catch (e: Exception) {
            OalLog.e(TAG, "Ping failed: ${e.message}")
            false
        }
    }

    /** Get chip info: device version, firmware, auth protocol, error code, self-test. */
    suspend fun getInfo(): MfiChipInfo? = withContext(Dispatchers.IO) {
        try {
            val (status, data) = sendCommand(CMD_GET_INFO)
            if (status != STATUS_OK || data.size < 6) return@withContext null
            MfiChipInfo(
                deviceVersion = data[0].toInt() and 0xFF,
                firmwareVersion = data[1].toInt() and 0xFF,
                authProtoMajor = data[2].toInt() and 0xFF,
                authProtoMinor = data[3].toInt() and 0xFF,
                errorCode = data[4].toInt() and 0xFF,
                selfTestStatus = data[5].toInt() and 0xFF,
            )
        } catch (e: Exception) {
            OalLog.e(TAG, "GetInfo failed: ${e.message}")
            null
        }
    }

    /**
     * Read the Apple MFi certificate from the chip.
     * This is sent to the iPhone during authentication.
     */
    suspend fun getCertificate(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val (status, data) = sendCommand(CMD_GET_CERT)
            if (status != STATUS_OK || data.isEmpty()) {
                OalLog.e(TAG, "GetCert failed: status=$status, len=${data.size}")
                return@withContext null
            }
            OalLog.i(TAG, "Certificate: ${data.size} bytes")
            data
        } catch (e: Exception) {
            OalLog.e(TAG, "GetCert exception: ${e.message}")
            null
        }
    }

    /**
     * Sign an authentication challenge using the MFi chip.
     * The challenge comes from the iPhone, the signed response proves
     * we are a legitimate MFi accessory.
     */
    suspend fun signChallenge(challenge: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val (status, data) = sendCommand(CMD_SIGN_CHALLENGE, challenge)
            if (status != STATUS_OK || data.isEmpty()) {
                OalLog.e(TAG, "Sign failed: status=$status, len=${data.size}")
                return@withContext null
            }
            OalLog.i(TAG, "Challenge signed: ${challenge.size}B in → ${data.size}B out")
            data
        } catch (e: Exception) {
            OalLog.e(TAG, "Sign exception: ${e.message}")
            null
        }
    }

    // --- Protocol implementation ---

    private fun sendCommand(cmd: Int, data: ByteArray = ByteArray(0)): Pair<Int, ByteArray> {
        val out = output ?: throw IllegalStateException("Not connected")
        val inp = input ?: throw IllegalStateException("Not connected")

        // Send: [cmd:1][len:2 LE][data]
        val hdr = ByteArray(3)
        hdr[0] = cmd.toByte()
        hdr[1] = (data.size and 0xFF).toByte()
        hdr[2] = (data.size shr 8 and 0xFF).toByte()
        out.write(hdr)
        if (data.isNotEmpty()) out.write(data)
        out.flush()

        // Receive: [status:1][len:2 LE][data]
        val respHdr = readExact(inp, 3)
        val status = respHdr[0].toInt() and 0xFF
        val respLen = (respHdr[1].toInt() and 0xFF) or ((respHdr[2].toInt() and 0xFF) shl 8)
        val respData = if (respLen > 0) readExact(inp, respLen) else ByteArray(0)

        return status to respData
    }

    private fun readExact(stream: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var total = 0
        while (total < n) {
            val r = stream.read(buf, total, n - total)
            if (r <= 0) throw java.io.IOException("Connection closed (read $total of $n)")
            total += r
        }
        return buf
    }

    companion object {
        private const val CMD_PING = 0x01
        private const val CMD_GET_CERT = 0x02
        private const val CMD_SIGN_CHALLENGE = 0x03
        private const val CMD_GET_INFO = 0x04

        private const val STATUS_OK = 0x00
    }
}

data class MfiChipInfo(
    val deviceVersion: Int,
    val firmwareVersion: Int,
    val authProtoMajor: Int,
    val authProtoMinor: Int,
    val errorCode: Int,
    val selfTestStatus: Int,
) {
    override fun toString(): String =
        "MfiChip(v$deviceVersion fw=$firmwareVersion proto=$authProtoMajor.$authProtoMinor err=0x${errorCode.toString(16)} selfTest=0x${selfTestStatus.toString(16)})"
}
