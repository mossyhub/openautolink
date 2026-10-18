package com.openautolink.app.carplay

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class CarPlayConnectionFailureTest {
    @Test fun manualTimeoutSuggestsCheckingWifiAndCurrentIpWithoutClaimingIpChanged() {
        val failure=CarPlayConnectionFailure(CarPlayFailureKind.RUNTIME_STARTUP_TIMEOUT,true)
        assertTrue(failure.userGuidance.contains("current iPhone IP"))
        assertTrue(failure.userGuidance.contains("Wi-Fi"))
        assertFalse(failure.userGuidance.contains("IP changed"))
        assertFalse(CarPlayConnectionFailure(CarPlayFailureKind.RUNTIME_STARTUP_TIMEOUT,false).userGuidance.contains("current iPhone IP"))
        assertFalse(CarPlayConnectionFailure(CarPlayFailureKind.RUNTIME_AUTHENTICATION,true).userGuidance.contains("current iPhone IP"))
        assertTrue(CarPlayConnectionFailure(CarPlayFailureKind.LISTENER_BIND,true).userGuidance.contains("listener"))
    }
    @Test fun manualFallbackSharesScopedEnrollmentWithoutWaitingForAutomaticWifiProvisioning() {
        val relative="app/src/main/java/com/openautolink/app/carplay/OalCarPlayRuntime.kt"
        val runtime=listOf(Path.of(".."),Path.of(".")).map {it.resolve(relative)}.first { Files.exists(it) }.toFile().readText()
        assertTrue("manual joined-AP choice must not await auto Wi-Fi request",runtime.contains("if (network.manualPeer == null)"))
        assertTrue(runtime.contains("explicit-manual-peer"))
        assertTrue(runtime.contains("projection.authorizeControllerEnrollment(120_000)"))
        assertTrue(runtime.contains("CarPlayConnectionFailure(CarPlayFailureKind.RUNTIME_STARTUP_TIMEOUT"))
        assertTrue(runtime.contains("projection.runtimeFailureStage"))
    }
}
