package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class CarPlayMicrophoneExperimentTest {
    @Test fun expiresOnlyFutureSnapshotsAndDisarmDoesNotMutateCapturedProfile() {
        var now = 100L
        val registry = CarPlayMicrophoneExperimentRegistry { now }
        val profile = CarPlayMicrophoneExperimentProfile(source = MicrophoneSource.VOICE_RECOGNITION)
        registry.arm(profile)
        val frozen = registry.snapshot()
        assertSame(profile, frozen)
        assertTrue(registry.status().armed)
        assertEquals(900000L, registry.status().remainingMillis)
        now += 899999
        assertSame(profile, registry.snapshot())
        now++
        assertNull(registry.snapshot())
        assertFalse(registry.status().armed)
        assertSame(profile, frozen)
        registry.arm(profile)
        registry.disarm()
        assertNull(registry.snapshot())
        assertSame(profile, frozen)
    }

    @Test fun refusesMonoChannelSelectionRatherThanNormalizing() {
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayMicrophoneExperimentProfile(selection = CarPlayMicrophoneChannelSelection.SECOND)
        }
    }
    @Test fun memoryOnlyExperimentStartsDisabled() {
        val registry = runCatching { Class.forName("com.shilapi.xcertplay.media.CarPlayMicrophoneExperiments") }.getOrNull()
        assertNotNull("bounded memory-only live microphone registry is missing", registry)
        val instance = registry!!.getField("INSTANCE").get(null)
        registry.getMethod("disarm").invoke(instance)
        assertNull(registry.getMethod("snapshot").invoke(instance))
    }
}
