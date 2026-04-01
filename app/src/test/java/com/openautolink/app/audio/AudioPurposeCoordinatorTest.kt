package com.openautolink.app.audio

import com.openautolink.app.transport.AudioPurpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioPurposeCoordinatorTest {

    private lateinit var coordinator: AudioPurposeCoordinator

    @Before
    fun setUp() {
        coordinator = AudioPurposeCoordinator()
    }

    // --- Call State Transitions ---

    @Test
    fun `initial call state is IDLE`() {
        assertEquals(CallState.IDLE, coordinator.callState.value)
    }

    @Test
    fun `alert starts transitions to RINGING`() {
        coordinator.onPurposeStarted(AudioPurpose.ALERT)
        assertEquals(CallState.RINGING, coordinator.callState.value)
    }

    @Test
    fun `phone call starts transitions to IN_CALL`() {
        coordinator.onPurposeStarted(AudioPurpose.PHONE_CALL)
        assertEquals(CallState.IN_CALL, coordinator.callState.value)
    }

    @Test
    fun `ring then answer transitions RINGING to IN_CALL`() {
        coordinator.onPurposeStarted(AudioPurpose.ALERT)
        assertEquals(CallState.RINGING, coordinator.callState.value)

        coordinator.onPurposeStarted(AudioPurpose.PHONE_CALL)
        assertEquals(CallState.IN_CALL, coordinator.callState.value)
    }

    @Test
    fun `call end transitions IN_CALL to IDLE`() {
        coordinator.onPurposeStarted(AudioPurpose.PHONE_CALL)
        coordinator.onPurposeStopped(AudioPurpose.PHONE_CALL)
        assertEquals(CallState.IDLE, coordinator.callState.value)
    }

    @Test
    fun `ring rejected transitions RINGING to IDLE`() {
        coordinator.onPurposeStarted(AudioPurpose.ALERT)
        assertEquals(CallState.RINGING, coordinator.callState.value)

        coordinator.onPurposeStopped(AudioPurpose.ALERT)
        assertEquals(CallState.IDLE, coordinator.callState.value)
    }

    @Test
    fun `full call lifecycle ring answer hangup`() {
        // Ring
        coordinator.onPurposeStarted(AudioPurpose.ALERT)
        assertEquals(CallState.RINGING, coordinator.callState.value)

        // Answer (alert stops, call starts)
        coordinator.onPurposeStarted(AudioPurpose.PHONE_CALL)
        coordinator.onPurposeStopped(AudioPurpose.ALERT)
        assertEquals(CallState.IN_CALL, coordinator.callState.value)

        // Hang up
        coordinator.onPurposeStopped(AudioPurpose.PHONE_CALL)
        assertEquals(CallState.IDLE, coordinator.callState.value)
    }

    @Test
    fun `call while alert still active stays IN_CALL`() {
        coordinator.onPurposeStarted(AudioPurpose.ALERT)
        coordinator.onPurposeStarted(AudioPurpose.PHONE_CALL)
        // Alert still active but call overrides
        assertEquals(CallState.IN_CALL, coordinator.callState.value)
    }

    // --- Volume Ducking ---

    @Test
    fun `media alone gets normal volume`() {
        val actions = coordinator.onPurposeStarted(AudioPurpose.MEDIA)
        val mediaAction = actions.find { it.purpose == AudioPurpose.MEDIA }
        assertEquals(AudioPurposeCoordinator.NORMAL_VOLUME, mediaAction?.volume)
    }

    @Test
    fun `media ducked when nav starts`() {
        coordinator.onPurposeStarted(AudioPurpose.MEDIA)

        val actions = coordinator.onPurposeStarted(AudioPurpose.NAVIGATION)
        val mediaAction = actions.find { it.purpose == AudioPurpose.MEDIA }
        assertEquals(AudioPurposeCoordinator.DUCK_VOLUME_NAV, mediaAction?.volume)
    }

    @Test
    fun `media restored when nav stops`() {
        coordinator.onPurposeStarted(AudioPurpose.MEDIA)
        coordinator.onPurposeStarted(AudioPurpose.NAVIGATION)

        val actions = coordinator.onPurposeStopped(AudioPurpose.NAVIGATION)
        val mediaAction = actions.find { it.purpose == AudioPurpose.MEDIA }
        assertEquals(AudioPurposeCoordinator.NORMAL_VOLUME, mediaAction?.volume)
    }

    @Test
    fun `media ducked deeply during phone call`() {
        coordinator.onPurposeStarted(AudioPurpose.MEDIA)

        val actions = coordinator.onPurposeStarted(AudioPurpose.PHONE_CALL)
        val mediaAction = actions.find { it.purpose == AudioPurpose.MEDIA }
        assertEquals(AudioPurposeCoordinator.DUCK_VOLUME_CALL, mediaAction?.volume)
    }

    @Test
    fun `media ducked during alert ring`() {
        coordinator.onPurposeStarted(AudioPurpose.MEDIA)

        val actions = coordinator.onPurposeStarted(AudioPurpose.ALERT)
        val mediaAction = actions.find { it.purpose == AudioPurpose.MEDIA }
        assertEquals(AudioPurposeCoordinator.DUCK_VOLUME_ALERT, mediaAction?.volume)
    }

    @Test
    fun `media ducked most during assistant`() {
        coordinator.onPurposeStarted(AudioPurpose.MEDIA)

        val actions = coordinator.onPurposeStarted(AudioPurpose.ASSISTANT)
        val mediaAction = actions.find { it.purpose == AudioPurpose.MEDIA }
        assertEquals(AudioPurposeCoordinator.DUCK_VOLUME_ASSISTANT, mediaAction?.volume)
    }

    @Test
    fun `navigation ducked during phone call`() {
        coordinator.onPurposeStarted(AudioPurpose.NAVIGATION)

        val actions = coordinator.onPurposeStarted(AudioPurpose.PHONE_CALL)
        val navAction = actions.find { it.purpose == AudioPurpose.NAVIGATION }
        assertEquals(AudioPurposeCoordinator.DUCK_VOLUME_NAV, navAction?.volume)
    }

    @Test
    fun `phone call always gets normal volume`() {
        coordinator.onPurposeStarted(AudioPurpose.MEDIA)
        coordinator.onPurposeStarted(AudioPurpose.NAVIGATION)

        val actions = coordinator.onPurposeStarted(AudioPurpose.PHONE_CALL)
        val callAction = actions.find { it.purpose == AudioPurpose.PHONE_CALL }
        assertEquals(AudioPurposeCoordinator.NORMAL_VOLUME, callAction?.volume)
    }

    @Test
    fun `media restored after call ends`() {
        coordinator.onPurposeStarted(AudioPurpose.MEDIA)
        coordinator.onPurposeStarted(AudioPurpose.PHONE_CALL)

        val actions = coordinator.onPurposeStopped(AudioPurpose.PHONE_CALL)
        val mediaAction = actions.find { it.purpose == AudioPurpose.MEDIA }
        assertEquals(AudioPurposeCoordinator.NORMAL_VOLUME, mediaAction?.volume)
    }

    @Test
    fun `call then nav ducking uses call volume for media`() {
        coordinator.onPurposeStarted(AudioPurpose.MEDIA)
        coordinator.onPurposeStarted(AudioPurpose.PHONE_CALL)

        // Nav starts during call — media still ducked at call level (deeper)
        val actions = coordinator.onPurposeStarted(AudioPurpose.NAVIGATION)
        val mediaAction = actions.find { it.purpose == AudioPurpose.MEDIA }
        assertEquals(AudioPurposeCoordinator.DUCK_VOLUME_CALL, mediaAction?.volume)
    }

    // --- Reset ---

    @Test
    fun `reset clears all state`() {
        coordinator.onPurposeStarted(AudioPurpose.MEDIA)
        coordinator.onPurposeStarted(AudioPurpose.PHONE_CALL)
        assertEquals(CallState.IN_CALL, coordinator.callState.value)

        coordinator.reset()

        assertEquals(CallState.IDLE, coordinator.callState.value)
        assertTrue(coordinator.activePurposes().isEmpty())
    }

    // --- No actions for inactive purposes ---

    @Test
    fun `stopping inactive purpose returns empty or no actions for it`() {
        val actions = coordinator.onPurposeStopped(AudioPurpose.MEDIA)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `actions only include active purposes`() {
        coordinator.onPurposeStarted(AudioPurpose.MEDIA)
        val actions = coordinator.onPurposeStarted(AudioPurpose.NAVIGATION)

        val purposes = actions.map { it.purpose }.toSet()
        assertEquals(setOf(AudioPurpose.MEDIA, AudioPurpose.NAVIGATION), purposes)
    }
}
