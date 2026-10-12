package com.openautolink.carplaylab.network

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Test

/** Wiring guards supplement, never substitute for real socket/crypto tests. */
class CarPlayNetworkWiringTest {
    private fun source(relative: String): String {
        val roots = listOf(Path.of(".."), Path.of("."))
        return roots.map { it.resolve(relative) }.first { Files.exists(it) }.toFile().readText()
    }
    @Test fun runtimeAndReceiverWireScopedPolicyInsteadOfIpv4Precondition() {
        val runtime = source("app/src/main/java/com/openautolink/app/carplay/OalCarPlayRuntime.kt")
        assertFalse(runtime.contains("IPv4 candidate only"))
        assertTrue(runtime.contains("CarPlayNetworkPolicy.select"))
        assertTrue(runtime.contains("stage=address-selection-failed"))
        assertTrue(source("carplay-lab/src/main/java/com/openautolink/carplaylab/media/ProjectionReceiver.kt").contains("stage=admission-unavailable"))
        assertTrue(runtime.contains("projection.start(network)"))
        assertTrue(runtime.contains("network.advertisedAddresses"))
        assertTrue(runtime.contains("network.manualPeer"))
        assertTrue(runtime.contains("config.apSecurity"))
        val receiver = source("carplay-lab/src/main/java/com/openautolink/carplaylab/media/ProjectionReceiver.kt")
        assertTrue(receiver.contains("CarPlayPeerAdmission"))
        assertTrue(receiver.contains("override fun onControlAuthenticated"))
        assertTrue(receiver.contains("pairSetupAdmission = if (trustedOnly && admission != null)"))
        assertTrue(receiver.contains("admission.allowsPairSetup(owner)"))
        assertTrue(receiver.contains("network?.local"))
        val engine = source("carplay-lab/src/main/java/com/shilapi/xcertplay/airplay/CarPlayMediaEngine.kt")
        assertTrue(engine.contains("socketBinding = session.socketBinding"))
        val activation = source("carplay-lab/src/main/java/com/openautolink/carplaylab/pairing/PhoneActivation.kt")
        assertTrue(activation.contains("socket.bind(InetSocketAddress"))
        assertTrue(activation.contains("socketBinding?.local"))
        val mic = source("carplay-lab/src/main/java/com/shilapi/xcertplay/media/MicrophoneUplink.kt")
        assertTrue(mic.contains("config.socketBinding"))
        val settings = source("app/src/main/java/com/openautolink/app/data/CarPlaySettings.kt")
        for (field in listOf("networkFamily", "apSecurity", "apFrequencyMhz", "localAddress")) assertTrue(settings.contains("val $field:"))
    }
}
