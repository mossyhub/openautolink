package com.openautolink.app.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.net.Network
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext

/**
 * TCP control channel — JSON line reader/writer on port 5288.
 * Each message is a single JSON object followed by \n.
 */
class TcpControlChannel {

    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private val writeMutex = Mutex()

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    suspend fun connect(host: String, port: Int, timeoutMs: Int = 5000, network: Network? = null) {
        withContext(Dispatchers.IO) {
            val s = Socket()
            network?.bindSocket(s)
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.tcpNoDelay = true
            s.keepAlive = true
            socket = s
            writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
        }
    }

    fun receiveMessages(): Flow<ControlMessage> = flow {
        val reader = BufferedReader(
            InputStreamReader(
                socket?.getInputStream() ?: return@flow,
                Charsets.UTF_8
            )
        )

        while (coroutineContext.isActive) {
            val line = reader.readLine() ?: break // EOF — connection closed
            val message = ControlMessageSerializer.deserialize(line)
            if (message != null) {
                emit(message)
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun send(message: ControlMessage) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val w = writer ?: return@withContext
                val line = ControlMessageSerializer.serialize(message)
                w.write(line)
                w.newLine()
                w.flush()
            }
        }
    }

    fun close() {
        try { writer?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        writer = null
        socket = null
    }
}
