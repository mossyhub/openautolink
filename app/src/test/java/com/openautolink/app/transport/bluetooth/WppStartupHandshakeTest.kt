package com.openautolink.app.transport.bluetooth

import android.bluetooth.BluetoothSocket
import android.content.Context
import com.openautolink.app.diagnostics.OalLog
import com.openautolink.app.proto.Wireless
import io.mockk.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Actual production protobuf transaction, only the Bluetooth socket/platform is fake. */
@OptIn(ExperimentalCoroutinesApi::class)
class WppStartupHandshakeTest {
    @Test fun credentialHandoffStartsDiscoveryOnlyAfterExactWireBytesAndRetiresWithControl() = runTest {
        fixture(this, failedInput = false) { f ->
            val worker = launch { f.handshake() }
            runCurrent()
            assertEquals(listOf("ready"), f.events)
            assertFalse(worker.isCompleted)
            verify(exactly = 0) { f.socket.close() }
            assertArrayEquals(f.output.toByteArray(), f.handoffBytes)
            val input = DataInputStream(ByteArrayInputStream(checkNotNull(f.handoffBytes)))
            fun next(type: Int): ByteArray {
                val size = input.readUnsignedShort()
                assertEquals(type, input.readUnsignedShort())
                return ByteArray(size).also { input.readFully(it) }
            }
            val wpp = Wireless.WifiProjectionProtocolInfo.newBuilder()
                .setIpAddress("127.0.0.1").setPort(5280).build()
            assertArrayEquals(Wireless.WifiVersionRequest.newBuilder()
                .setVersionMajor(4).setVersionMinor(1).setWppInfo(wpp).build().toByteArray(), next(4))
            assertArrayEquals(Wireless.WifiStartRequest.newBuilder()
                .setIpAddress("127.0.0.1").setPort(5280).setStatus(0).setWppInfo(wpp)
                .setAccessPointInfo(Wireless.AccessPointInfo.newBuilder()
                    .setSsid("fixture-ap").setKey("fixture-key").setBssid("02:11:22:33:44:55")
                    .setWifiSecurityMode(Wireless.SecurityMode.WPA2_PERSONAL)
                    .addAllSupportedWifiChannels(listOf(5180)).build()).build().toByteArray(), next(1))
            assertArrayEquals(Wireless.WifiInfoResponse.newBuilder()
                .setSsid("fixture-ap").setKey("fixture-key").setBssid("02:11:22:33:44:55")
                .setSecurityMode(Wireless.SecurityMode.WPA2_PERSONAL)
                .setAccessPointType(Wireless.AccessPointType.STATIC).build().toByteArray(), next(3))
            assertEquals(-1, input.read())
            assertFalse(AaWirelessBtControl.handshakeInFlight)
            worker.cancelAndJoin()
            assertEquals(listOf("ready", "closed"), f.events)
            verify(exactly = 1) { f.socket.close() }
        }
    }

    @Test fun failedExchangeRevokesExactEndpointWithoutStartingDiscovery() = runTest {
        fixture(this, failedInput = true) { f ->
            f.handshake()
            assertEquals(listOf("closed"), f.events)
            verify(exactly = 1) { f.socket.close() }
        }
    }

    private suspend fun fixture(scope: TestScope, failedInput: Boolean, body: suspend (Fixture) -> Unit) {
        mockkObject(OalLog)
        every { OalLog.i(any(), any()) } just Runs
        every { OalLog.w(any(), any()) } just Runs
        val owner = AaWirelessBtControl.installSessionOwner("wpp", "fixture-interface")
        val lease = checkNotNull(AaWirelessBtControl.beginHandshake())
        val f = Fixture(scope, failedInput, lease)
        try { body(f) }
        finally {
            f.server.stop()
            lease.finish()
            AaWirelessBtControl.clearSessionOwner(owner)
            unmockkObject(OalLog)
        }
    }

    private class Fixture(scope: TestScope, failedInput: Boolean, val lease: AaWirelessBtControl.HandshakeLease) {
        val server = AaWirelessBtServer(mockk<Context>(), scope)
        val events = mutableListOf<String>()
        var handoffBytes: ByteArray? = null
        val output = ByteArrayOutputStream()
        val replies = ByteArrayOutputStream().also {
            if (!failedInput) DataOutputStream(it).apply {
                val version = Wireless.WifiVersionResponse.newBuilder().setVersionMajor(1).setVersionMinor(0).build().toByteArray()
                writeShort(version.size); writeShort(5); write(version)
                writeShort(0); writeShort(2)
            }
        }.toByteArray()
        val socket = mockk<BluetoothSocket> {
            every { inputStream } returns ByteArrayInputStream(replies)
            every { outputStream } returns output
            every { isConnected } returns true
            every { close() } just Runs
        }
        init {
            server.javaClass.getDeclaredField("scope").apply { isAccessible = true }.set(server, scope.backgroundScope)
            server.javaClass.getDeclaredField("running").apply { isAccessible = true }.setBoolean(server, true)
            server.updateCredentials(AaWirelessBtServer.WifiCredentials(
                "fixture-ap", "fixture-key", "02:11:22:33:44:55", "10.2.110.66", 5277, listOf(5180)))
            server.setEndpointResolver { _, _ -> AaWirelessBtServer.Endpoint.PhoneLoopback(5280,
                onAssociationReady = {
                    assertTrue("handoff must follow flushed security-info bytes", output.size() > 0)
                    handoffBytes = output.toByteArray()
                    events += "ready"
                }, onControlClosed = { events += "closed" }) }
        }
        suspend fun handshake(): Unit = suspendCoroutine { continuation ->
            val method = server.javaClass.declaredMethods.single { it.name == "handleHandshake" }
                .apply { isAccessible = true }
            val result = method.invoke(server, socket, "AA:BB:CC:DD:EE:FF", "Phone", lease, continuation)
            if (result !== COROUTINE_SUSPENDED) continuation.resume(Unit)
        }
    }
}
