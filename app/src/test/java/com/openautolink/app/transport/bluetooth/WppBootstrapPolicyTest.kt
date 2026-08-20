package com.openautolink.app.transport.bluetooth

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WppBootstrapPolicyTest {

    @Test
    fun `reserved proxy dials immediately while WPP handshake runs`() {
        assertEquals(
            LateCompanionAction.DIAL_COMPANION,
            WppBootstrapPolicy.onCompanionReachable(
                bootstrapLoopbackPending = true,
                usesReservedProxyPort = true,
                handshakeInFlight = true,
                sessionStreaming = false,
            ),
        )
    }

    @Test
    fun `legacy dynamic proxy queues readvertise until handshake completes`() {
        assertEquals(
            LateCompanionAction.QUEUE_READVERTISE,
            WppBootstrapPolicy.onCompanionReachable(
                bootstrapLoopbackPending = true,
                usesReservedProxyPort = false,
                handshakeInFlight = true,
                sessionStreaming = false,
            ),
        )
    }

    @Test
    fun `legacy dynamic proxy readvertises after handshake`() {
        assertEquals(
            LateCompanionAction.READVERTISE,
            WppBootstrapPolicy.onCompanionReachable(
                bootstrapLoopbackPending = true,
                usesReservedProxyPort = false,
                handshakeInFlight = false,
                sessionStreaming = false,
            ),
        )
    }

    @Test
    fun `late companion cannot replace a streaming session`() {
        assertEquals(
            LateCompanionAction.IGNORE,
            WppBootstrapPolicy.onCompanionReachable(
                bootstrapLoopbackPending = true,
                usesReservedProxyPort = true,
                handshakeInFlight = false,
                sessionStreaming = true,
            ),
        )
    }

    @Test
    fun `duplicate discovery is ignored after bootstrap ownership is consumed`() {
        assertEquals(
            LateCompanionAction.IGNORE,
            WppBootstrapPolicy.onCompanionReachable(
                bootstrapLoopbackPending = false,
                usesReservedProxyPort = true,
                handshakeInFlight = false,
                sessionStreaming = false,
            ),
        )
    }

    @Test
    fun `bootstrap discovery deadline fits inside companion waiter`() {
        assertTrue(WppBootstrapPolicy.DISCOVERY_DEADLINE_MS < 30_000L)
    }

    @Test
    fun `AP bootstrap advertises stable loopback instead of the blocked car address`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtControl.kt",
        ).readText()

        assertTrue(source.contains("val attempt = BootstrapAttempt("))
        assertTrue(source.contains("phoneBtAddress = phoneBtAddress"))
        assertTrue(source.contains("phoneBtName = phoneBtName"))
        assertTrue(
            source.contains(
                "AaWirelessBtServer.Endpoint.PhoneLoopback(OalProtocol.WPP_PROXY_PORT)",
            ),
        )
    }

    @Test
    fun `attempt owned scanner runs even with no cached candidates`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtControl.kt",
        ).readText()
        val scanner = source.substringAfter("private fun startBootstrapCompanionDiscovery(")
            .substringBefore("private val backgroundProbeRunning")

        assertTrue(scanner.contains("bootstrapAttempt.get() !== attempt"))
        assertTrue(scanner.contains("val remainingMs = (deadline - System.currentTimeMillis())"))
        assertTrue(scanner.contains("findCompanionOnAnySubnet("))
        assertTrue(scanner.contains("phoneBtAddress = attempt.phoneBtAddress"))
        assertTrue(scanner.contains("phoneBtName = attempt.phoneBtName"))
        assertTrue(scanner.contains("WppBootstrapPolicy.DISCOVERY_DEADLINE_MS"))
        assertTrue(scanner.contains("reportedPhoneId = probe.phoneId"))
    }

    @Test
    fun `bootstrap ownership is consumed atomically before dialing`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtControl.kt",
        ).readText()

        assertTrue(source.contains("java.util.concurrent.atomic.AtomicReference<BootstrapAttempt?>"))
        assertTrue(source.contains("bootstrapAttempt.compareAndSet(attempt, null)"))
        assertTrue(source.contains("onCompanionSelected?.invoke(host)"))
    }

    @Test
    fun `each Bluetooth handshake uses an idempotent ownership lease`() {
        val server = projectFile(
            "app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtServer.kt",
        ).readText()
        val control = projectFile(
            "app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtControl.kt",
        ).readText()

        assertTrue(server.contains("val handshakeLease = AaWirelessBtControl.beginHandshake()"))
        assertTrue(server.contains("if (handshakeLease == null)"))
        assertTrue(server.contains("val handshakeJob = scope.launch"))
        assertTrue(server.contains("handshakeJob.invokeOnCompletion"))
        assertEquals(2, "handshakeLease.finish()".toRegex().findAll(server).count())
        assertTrue(control.contains("finished.compareAndSet(false, true)"))
        assertTrue(control.contains("private var handshakeEpoch = 0L"))
        assertTrue(control.contains("HandshakeLease(handshakeEpoch)"))
        assertTrue(control.contains("if (leaseEpoch != handshakeEpoch) return"))
        assertTrue(control.contains("handshakeEpoch++"))
        assertTrue(control.contains("activeHandshakeCount.incrementAndGet()"))
    }

    @Test
    fun `legacy compatibility queue retains exact attempt ownership`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtControl.kt",
        ).readText()

        assertTrue(source.contains("synchronized(handshakeStateLock)"))
        assertTrue(source.contains("handshakeAdmissionBlocked = true"))
        assertTrue(source.contains("handshakeAdmissionToken == admissionToken"))
        assertTrue(source.contains("readvertise(admissionToken)"))
        assertTrue(source.contains("handshakeAdmissionBlocked = false"))
        assertTrue(source.contains("if (handshakeAdmissionBlocked) return null"))
        assertTrue(source.contains("PendingLegacyReadvertise(host, proxyPort, attempt)"))
        val queueBranch = source.substringAfter("LateCompanionAction.QUEUE_READVERTISE ->")
            .substringBefore("LateCompanionAction.READVERTISE ->")
        assertTrue(queueBranch.contains("flushPendingReadvertise()"))
        assertTrue(source.contains("if (activeHandshakeCount.get() > 0 || handshakeAdmissionBlocked) return"))
        assertTrue(source.contains("activeHandshakeCount.incrementAndGet()"))
        assertTrue(
            source.contains(
                "bootstrapAttempt.compareAndSet(candidate.attempt, null)",
            ),
        )
    }

    @Test
    fun `discovery preserves the companion reported proxy port`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/transport/PhoneDiscovery.kt",
        ).readText()

        assertTrue(source.contains("reportedProxyPort = ident.wppProxyPort"))
        assertTrue(source.contains("phoneId = ident.phoneId"))
        assertTrue(source.contains("reportedPhoneId = phoneId"))
    }

    private fun projectFile(path: String): File {
        var dir = File(System.getProperty("user.dir") ?: error("user.dir unavailable"))
        repeat(8) {
            val candidate = File(dir, path)
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: error("Project root not found for: $path")
        }
        error("Project file not found: $path")
    }
}
