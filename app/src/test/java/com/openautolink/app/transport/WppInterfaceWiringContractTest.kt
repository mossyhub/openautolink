package com.openautolink.app.transport

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WppInterfaceWiringContractTest {

    @Test
    fun `WPP address resolution has no cross-interface fallback`() {
        val control = source(
            "app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtControl.kt",
        )

        assertTrue(control.contains("WppInterfacePolicy.selectedIpv4("))
        assertFalse(control.contains("findCompanionOnAnySubnet("))
        assertFalse(control.contains("private fun allLocalIpv4()"))
        assertFalse(control.contains("fun tier(name: String)"))
    }

    @Test
    fun `WPP discovery rejects peers outside selected interface subnet`() {
        val control = source(
            "app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtControl.kt",
        )

        assertTrue(control.contains("WppInterfacePolicy.isPeerOnSelectedSubnet("))
        assertTrue(control.contains("Rejecting companion at ${'$'}host — outside selected WPP interface"))
    }

    @Test
    fun `WPP idle sweep is forced to configured interface`() {
        val projection = source(
            "app/src/main/java/com/openautolink/app/ui/projection/ProjectionViewModel.kt",
        )
        val discovery = source(
            "app/src/main/java/com/openautolink/app/transport/PhoneDiscovery.kt",
        )

        val manager = source(
            "app/src/main/java/com/openautolink/app/session/SessionManager.kt",
        )

        assertTrue(projection.contains("phoneDiscovery.startSweep(forcedInterfaceName = wppInterface)"))
        assertTrue(projection.contains("phoneDiscovery.udpBroadcastOnInterface(wppInterface"))
        assertFalse(projection.contains("preferences.wppApInterface,\n            ) { transport, interfaceName ->"))
        assertTrue(manager.contains("wppInterfaceName.takeIf { directTransport == AppPreferences.DIRECT_TRANSPORT_WPP }"))

        assertTrue(discovery.contains("private var interfaceConstraint"))
        assertTrue(discovery.contains("val forced = interfaceConstraint ?: forcedInterfaceName"))
        assertTrue(discovery.contains("if (host != null && !isHostAllowed(host))"))
    }

    @Test
    fun `WPP TCP sweep binds every probe to selected source address`() {
        val discovery = source(
            "app/src/main/java/com/openautolink/app/transport/PhoneDiscovery.kt",
        )

        assertTrue(discovery.contains("probeHost(ip, sourceIpv4 = plan.localIpv4)"))
        assertTrue(discovery.contains("socket.bind(InetSocketAddress(sourceIpv4, 0))"))
    }

    @Test
    fun `WPP UDP discovery binds selected source address`() {
        val discovery = source(
            "app/src/main/java/com/openautolink/app/transport/PhoneDiscovery.kt",
        )

        assertTrue(discovery.contains("DatagramSocket(null)"))
        assertTrue(discovery.contains("if (sourceIpv4 != null) InetSocketAddress(sourceIpv4, 0)"))
        assertTrue(discovery.contains("WppInterfacePolicy.isPeerOnSelectedSubnet(sourceIpv4, replyHost)"))
    }

    @Test
    fun `inbound WPP server binds and admits only selected interface subnet`() {
        val server = source(
            "app/src/main/java/com/openautolink/app/transport/hotspot/WppTcpServer.kt",
        )
        val aasdk = source(
            "app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt",
        )

        assertTrue(server.contains("private val bindInterfaceName: String"))
        assertTrue(server.contains("WppInterfacePolicy.liveIpv4(bindInterfaceName)"))
        assertTrue(server.contains("bind(InetSocketAddress(bindAddress, port), BACKLOG)"))
        assertTrue(server.contains("WppInterfacePolicy.isPeerOnSelectedSubnet(bindAddress, remoteHost)"))
        assertTrue(server.contains("private enum class LifecycleState { NEW, RUNNING, STOPPED }"))
        assertTrue(server.contains("if (lifecycleState != LifecycleState.NEW || !admissionCheck())"))
        assertTrue(server.contains("if (lifecycleState != LifecycleState.RUNNING || !admissionCheck())"))
        assertTrue(server.contains("runCatching { candidate.close() }"))
        assertTrue(aasdk.contains("bindInterfaceName = wppInterfaceName"))
        assertTrue(aasdk.contains("WppInterfacePolicy.liveIpv4(wppInterfaceName)"))
        assertTrue(source("app/src/main/java/com/openautolink/app/session/SessionManager.kt")
            .contains("session.wppInterfaceName = wppInterfaceName"))
    }

    @Test
    fun `WPP admission waits for the selected-interface listener to bind`() {
        val server = source(
            "app/src/main/java/com/openautolink/app/transport/hotspot/WppTcpServer.kt",
        )
        val aasdk = source(
            "app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt",
        )
        val manager = source(
            "app/src/main/java/com/openautolink/app/session/SessionManager.kt",
        )
        val startBlock = manager.substringAfter("private suspend fun startSession(")
            .substringBefore("private fun prepareNativeSessionStart")

        assertTrue(server.contains("suspend fun awaitBound(): Boolean"))
        assertTrue(server.contains("bindOutcome.complete(true)"))
        assertTrue(aasdk.contains("suspend fun awaitWppTransportReady(): Boolean"))
        val start = startBlock.indexOf("session.start()")
        val await = startBlock.indexOf("session.awaitWppTransportReady()", startIndex = start)
        val admit = startBlock.indexOf("installWirelessSessionAdmissionIfCurrent(", startIndex = start)
        assertTrue(start >= 0)
        assertTrue(await > start)
        assertTrue(admit > await)
    }

    @Test
    fun `changing WPP interface cancels a stale sweep before installing constraint`() {
        val discovery = source(
            "app/src/main/java/com/openautolink/app/transport/PhoneDiscovery.kt",
        )
        val block = discovery.substringAfter("fun setInterfaceConstraint(interfaceName: String?)")
            .substringBefore("private fun isHostAllowed")

        val stop = block.indexOf("stopSweep()")
        val install = block.indexOf("interfaceConstraint = normalized")
        assertTrue(stop >= 0)
        assertTrue(install > stop)
    }

    @Test
    fun `stop and reconnect cancel pending WPP bind before lifecycle mutex`() {
        val manager = source(
            "app/src/main/java/com/openautolink/app/session/SessionManager.kt",
        )
        val stopBlock = manager.substringAfter("suspend fun stop()")
            .substringBefore("private suspend fun stopWhileLifecycleLocked")
        val reconnectBlock = manager.substringAfter("suspend fun reconnect(")
            .substringBefore("private suspend fun doReconnectAfterCancel")

        val stopCancel = stopBlock.indexOf("interruptPendingTransportStart(\"stop\")")
        val stopLock = stopBlock.indexOf("startMutex.lock()")
        val reconnectCancel = reconnectBlock.lastIndexOf("interruptPendingTransportStart(\"reconnect\")")
        val reconnectLock = reconnectBlock.indexOf("startMutex.lock()")
        val handshakeGuard = reconnectBlock.indexOf("AaWirelessBtControl.handshakeInFlight")
        assertTrue(stopCancel >= 0)
        assertTrue(stopLock > stopCancel)
        assertTrue(reconnectCancel >= 0)
        assertTrue(reconnectCancel > handshakeGuard)
        assertTrue(reconnectLock > reconnectCancel)
        assertTrue(manager.contains("interruptPendingTransportStart(\"stop\")"))
        assertTrue(manager.contains("installWirelessSessionAdmissionIfCurrent("))
        assertTrue(manager.contains("currentTransportStartGeneration() != transportStartGeneration"))
    }

    @Test
    fun `WPP aborts instead of starting an unbound connector`() {
        val aasdk = source(
            "app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt",
        )
        val block = aasdk.substringAfter("if (transportMode == \"wpp\")")
            .substringBefore("// An explicit dial target wins")

        assertTrue(block.contains("if (selectedSourceIpv4 == null)"))
        assertTrue(block.contains("return@synchronized"))
        assertTrue(block.indexOf("return@synchronized") < block.indexOf("connector.sourceIpv4"))
    }

    @Test
    fun `WPP TCP connector binds selected source address`() {
        val connector = source(
            "app/src/main/java/com/openautolink/app/transport/hotspot/TcpConnector.kt",
        )
        val aasdk = source(
            "app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt",
        )

        assertTrue(connector.contains("socket.bind(InetSocketAddress(sourceIpv4, 0))"))
        assertTrue(connector.contains("Connected to companion at ${'$'}host:${'$'}port from ${'$'}sourceIpv4"))
        assertTrue(aasdk.contains("connector.sourceIpv4 = selectedSourceIpv4"))
    }

    @Test
    fun `save and reconnect owns the synchronously selected WPP interface`() {
        val viewModel = source(
            "app/src/main/java/com/openautolink/app/ui/settings/SettingsViewModel.kt",
        )
        val saveBlock = viewModel.substringAfter("fun saveAndReconnect()")

        val snapshot = saveBlock.indexOf("val wppInterfaceName = _wppApInterfaceOverride.value")
        val persist = saveBlock.indexOf("preferences.setWppApInterface(wppInterfaceName)")
        val reconnect = saveBlock.indexOf("sm.reconnect(")
        assertTrue(snapshot >= 0)
        assertTrue(persist > snapshot)
        assertTrue(reconnect > persist)
        assertFalse(saveBlock.contains(".setInterfaceConstraint(wppInterfaceName)"))
        val manager = source("app/src/main/java/com/openautolink/app/session/SessionManager.kt")
        val sessionBlock = manager.substringAfter("private suspend fun startSession(")
            .substringBefore("// Map resolution string")
        assertTrue(sessionBlock.contains(".setInterfaceConstraint("))
        assertTrue(sessionBlock.contains("wppInterfaceName.takeIf"))
    }

    @Test
    fun `WPP settings use a live interface selector instead of free text`() {
        val settings = source(
            "app/src/main/java/com/openautolink/app/ui/settings/SettingsScreen.kt",
        )
        val wppBlock = settings
            .substringAfter("if (uiState.directTransport == AppPreferences.DIRECT_TRANSPORT_WPP)")
            .substringBefore("SectionHeader(\"Connection Mode\")")

        assertTrue(wppBlock.contains("WPP network interface"))
        assertTrue(wppBlock.contains("listCarHotspotInterfaces()"))
        assertFalse(wppBlock.contains("onValueChange = { viewModel.updateWppApInterface(it) }"))
    }

    @Test
    fun `blank persisted WPP interface resolves and saves as GM default`() {
        val preferences = source(
            "app/src/main/java/com/openautolink/app/data/AppPreferences.kt",
        )
        val readBlock = preferences.substringAfter("val wppApInterface: Flow<String>")
            .substringBefore("val directTransport")
        val writeBlock = preferences.substringAfter("suspend fun setWppApInterface(name: String)")
            .substringBefore("suspend fun setDirectTransport")

        assertTrue(readBlock.contains("takeIf { it.isNotEmpty() }"))
        assertTrue(readBlock.contains("?: DEFAULT_WPP_AP_INTERFACE"))
        assertTrue(writeBlock.contains("ifEmpty { DEFAULT_WPP_AP_INTERFACE }"))
    }

    private fun source(relativePath: String): String = projectFile(relativePath).readText()

    private fun projectFile(relativePath: String): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            val candidate = File(dir, relativePath)
            if (candidate.exists()) return candidate
            val parent = dir.parentFile ?: return File(relativePath)
            dir = parent
        }
        return File(relativePath)
    }
}
