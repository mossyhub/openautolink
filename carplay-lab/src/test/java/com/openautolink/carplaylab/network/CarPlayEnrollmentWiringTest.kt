package com.openautolink.carplaylab.network

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Test

/** Integration guards supplement the behavioral identity/admission/crypto tests. */
class CarPlayEnrollmentWiringTest {
    private fun source(relative: String): String = listOf(Path.of(".."), Path.of("."))
        .map { it.resolve(relative) }.first { Files.exists(it) }.toFile().readText()

    @Test fun receiverUsesGeneratedIdentityInsteadOfRequiringCopiedState() {
        val receiver = source("carplay-lab/src/main/java/com/openautolink/carplaylab/media/ProjectionReceiver.kt")
        assertFalse(receiver.contains("pairing-probe/state.bin"))
        assertTrue(receiver.contains("PairingPersistence.openReceiver"))
    }

    @Test fun runtimeUsesTrustedOnlyAdmissionWithoutPreexistingStatePrerequisite() {
        val runtime = source("app/src/main/java/com/openautolink/app/carplay/OalCarPlayRuntime.kt")
        assertFalse(runtime.contains("pairing-probe/state.bin"))
        assertTrue(runtime.contains("trustedOnly = true"))
        assertTrue(runtime.contains("projection.startSharedIpv4(network)")) // Exact admission, proven IPv4 transport.
        val receiver = source("carplay-lab/src/main/java/com/openautolink/carplaylab/media/ProjectionReceiver.kt")
        assertTrue(receiver.contains("admission.allowsPairSetup(owner)"))
        assertTrue(receiver.contains("trustedControllers, trustedOnly"))
        assertTrue(receiver.contains("session.controllerId in trustedControllers"))
        assertTrue(receiver.contains("enrollmentWindowMillis = firstEnrollmentWindowMillis"))
    }

    @Test fun runtimeAuthorizesBoundedConsentQualifiedEnrollmentWithoutCallingMfiPhoneTrust() {
        val runtime = source("app/src/main/java/com/openautolink/app/carplay/OalCarPlayRuntime.kt")
        assertTrue(runtime.contains("projection.requiresControllerEnrollment"))
        assertFalse(runtime.contains("CarPlay enrollment blocked: Bluetooth-to-AirPlay controller binding unavailable"))
        assertTrue(runtime.contains("projection.authorizeFirstEnrollment(120_000)"))
        assertTrue(runtime.contains("WirelessEnrollmentConsentGate"))
        assertTrue(runtime.indexOf("projection.authorizeFirstEnrollment") > runtime.indexOf("Iap2MfiAuthenticationClient(auth).run"))
        assertTrue(runtime.contains("selected-bootstrap-request; first-client race remains possible"))
        assertFalse(runtime.contains("CarPlay phone authentication accepted"))
        assertTrue(runtime.contains("CarPlay receiver MFi authentication accepted"))
    }

    @Test fun cancellationFencesPendingAdmissionBeforeResourceRetirement() {
        val runtime = source("app/src/main/java/com/openautolink/app/carplay/OalCarPlayRuntime.kt")
        assertTrue(runtime.contains("receiver?.revokePendingAdmission()"))
        val receiver = source("carplay-lab/src/main/java/com/openautolink/carplaylab/media/ProjectionReceiver.kt")
        assertTrue(receiver.contains("fun revokePendingAdmission()"))
        assertTrue(receiver.contains("peerAdmission?.retire()"))
        assertTrue(receiver.contains("admission.persistEnrollment(session, id, key, persistence.pairings)"))
        assertTrue(receiver.contains("!admissionRevoked.get() && session in sessions"))
    }
}
