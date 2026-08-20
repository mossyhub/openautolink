package com.openautolink.app.transport.aasdk

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionDialStateTest {

    @Test
    fun startTcpTargetProtectsLiveSessionFromSameIpRedial() {
        val state = CompanionDialState()

        state.recordStartTcpTarget("10.19.238.82")

        assertTrue(state.shouldIgnoreRedial("10.19.238.82", hasLiveTransport = true))
    }

    @Test
    fun differentPhoneCanReplaceLiveSession() {
        val state = CompanionDialState()
        state.recordStartTcpTarget("10.19.238.82")

        assertFalse(state.shouldIgnoreRedial("10.19.238.109", hasLiveTransport = true))
    }

    @Test
    fun deadSessionDoesNotBlockRetryToSamePhone() {
        val state = CompanionDialState()
        state.recordStartTcpTarget("10.19.238.82")

        assertFalse(state.shouldIgnoreRedial("10.19.238.82", hasLiveTransport = false))
    }

    @Test
    fun activeRetryForDifferentPhoneDoesNotBlockNewPhone() {
        val state = CompanionDialState()
        state.recordStartTcpTarget("10.19.238.184")

        assertFalse(state.shouldIgnoreActiveDial("10.19.238.82", connectorIsActive = true))
    }

    @Test
    fun activeRetryForSamePhoneRemainsSingleFlight() {
        val state = CompanionDialState()
        state.recordStartTcpTarget("10.19.238.82")

        assertTrue(state.shouldIgnoreActiveDial("10.19.238.82", connectorIsActive = true))
    }

    @Test
    fun dialCompanionUsesTargetAwareActiveConnectorGuard() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt",
        ).readText()
        val body = source.substringAfter("fun dialCompanion(ip: String)")
            .substringBefore("private fun startTcp")

        assertTrue(body.contains("companionDialState.shouldIgnoreActiveDial(ip, existingIsActive)"))
        val startTcpBody = source.substringAfter("private fun startTcp")
            .substringBefore("private fun startUsb")
        val lockIndex = startTcpBody.indexOf("synchronized(connectionStartLock)")
        val ownerCheckIndex = startTcpBody.indexOf("if (_tcpConnector !== connector)")
        val nativeStartIndex = startTcpBody.indexOf("handleConnection(tcpSocket)")
        assertTrue(lockIndex >= 0)
        assertTrue(ownerCheckIndex > lockIndex)
        assertTrue(nativeStartIndex > ownerCheckIndex)
        val stopBody = source.substringAfter("fun stop()")
            .substringBefore("fun forceReconnect")
        assertTrue(stopBody.contains("synchronized(connectionStartLock)"))
        val forceReconnectBody = source.substringAfter("fun forceReconnect")
            .substringBefore("fun sendTouchEvent")
        assertTrue(forceReconnectBody.contains("synchronized(connectionStartLock)"))
    }

    @Test
    fun cancelledConnectorCannotPublishLateSocket() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/transport/hotspot/TcpConnector.kt",
        ).readText()
        val afterConnect = source.substringAfter("socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)")
            .substringBefore("onSocketReady(socket)")

        assertTrue(afterConnect.contains("if (!isRunning)"))
        assertTrue(afterConnect.contains("socket.close()"))
    }

    private fun projectFile(relativePath: String): File {
        val workingDir = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(workingDir) { it.parentFile }
            .map { root -> File(root, relativePath) }
            .first { it.isFile }
    }
}
