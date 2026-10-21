package com.shilapi.xcertplay.media

import android.Manifest
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
class MicrophoneDiagnosticInputCensusTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun device(address: String = "PRIVATE_OTHER", source: Boolean = true): AudioDeviceInfo =
        mock(AudioDeviceInfo::class.java).also {
            `when`(it.isSource).thenReturn(source)
            `when`(it.address).thenReturn(address)
            `when`(it.sampleRates).thenReturn(intArrayOf())
            `when`(it.channelMasks).thenReturn(intArrayOf())
            `when`(it.channelIndexMasks).thenReturn(intArrayOf())
        }
    private fun install(devices: List<AudioDeviceInfo>) {
        shadowOf(app.getSystemService(AudioManager::class.java)).setInputDevices(devices)
    }
    private fun others(n: Int) = List(n) { device() }
    private val shape = MicrophoneDiagnosticShape(6, 24000, 1, 16, 0)

    @Test fun duplicateBeyondSixteenIsMultipleEvenWithManyMatches() {
        install(listOf(device("Call_In_Mic")) + others(16) + List(100) { device("Call_In_Mic") })
        assertEquals(MicrophoneDiagnosticMatch.MULTIPLE, MicrophoneDiagnosticAndroid(app).inventory().match)
    }

    @Test fun completeCensusWithoutExactSourceIsAbsent() {
        val sink = device("Call_In_Mic", false)
        install(others(19) + sink + device("Call_In_Mic ") + device("call_in_mic"))
        assertEquals(MicrophoneDiagnosticMatch.ABSENT, MicrophoneDiagnosticAndroid(app).inventory().match)
        verify(sink, never()).address
    }

    @Test fun entry256CanBeUniqueWhileExportRemainsSixteen() {
        val prefix = others(255)
        val exact = device("Call_In_Mic")
        install(prefix + exact)
        val inventory = MicrophoneDiagnosticAndroid(app).inventory()
        assertEquals(MicrophoneDiagnosticMatch.UNIQUE, inventory.match)
        assertEquals(16, inventory.inputs.size)
        for (d in prefix.drop(16) + exact) {
            verify(d, never()).sampleRates
            verify(d, never()).channelMasks
            verify(d, never()).channelIndexMasks
        }
        for (d in prefix + exact) {
            verify(d, never()).id
            verify(d, never()).productName
        }
        assertFalse(inventory.toString().contains("Call_In_Mic"))
        assertFalse(inventory.toString().contains("PRIVATE"))
    }

    @Test fun entry257MakesEveryPartialMultiplicityTruncatedWithoutReadingTail() {
        for (matches in 0..2) {
            val tail = device("Call_In_Mic")
            val first = List(matches) { device("Call_In_Mic") } + others(256 - matches)
            install(first + tail)
            val inventory = MicrophoneDiagnosticAndroid(app).inventory()
            assertEquals(MicrophoneDiagnosticMatch.TRUNCATED, inventory.match)
            assertEquals(16, inventory.inputs.size)
            verify(tail, never()).isSource
            verify(tail, never()).address
        }
    }

    @Test fun identityPropertyExceptionsBeyondSixteenFailClosedAndEraseCandidate() {
        for (sourceFails in listOf(false, true)) {
            val record = mock(AudioRecord::class.java)
            shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
            val p = MicrophoneDiagnosticAndroid(app) { record }
            val exact = device("Call_In_Mic")
            install(listOf(exact))
            assertEquals(MicrophoneDiagnosticMatch.UNIQUE, p.inventory().match)
            val broken = device()
            if (sourceFails) `when`(broken.isSource).thenThrow(SecurityException("PRIVATE_EXCEPTION"))
            else `when`(broken.address).thenThrow(SecurityException("PRIVATE_EXCEPTION"))
            install(listOf(exact) + others(15) + broken)
            val result = p.inventory()
            assertEquals(MicrophoneDiagnosticMatch.UNAVAILABLE, result.match)
            assertTrue(result.inputs.isEmpty())
            assertFalse(result.toString().contains("PRIVATE"))
            val owner = p.open(shape)
            try { assertFalse(owner.preferNamedInput()) } finally { owner.release() }
            verify(record, never()).setPreferredDevice(any())
        }
    }

    @Test fun capabilityPropertyExceptionAlsoFailsClosed() {
        val exact = device("Call_In_Mic")
        `when`(exact.channelIndexMasks).thenThrow(IllegalStateException("PRIVATE_EXCEPTION"))
        install(listOf(exact))
        val result = MicrophoneDiagnosticAndroid(app).inventory()
        assertEquals(MicrophoneDiagnosticMatch.UNAVAILABLE, result.match)
        assertTrue(result.inputs.isEmpty())
    }

    @Test fun passiveInspectResolvesBeyondSixteenWithZeroRecorderAllocations() {
        install(others(16) + device("Call_In_Mic"))
        val allocations = AtomicInteger()
        val p = MicrophoneDiagnosticAndroid(app) { allocations.incrementAndGet(); error("must not allocate") }
        val done = CountDownLatch(1)
        val owner = MicrophoneDiagnostic(p, { true }, {
            if (it.phase == MicrophoneDiagnosticPhase.COMPLETE) done.countDown()
        }, MicrophoneDiagnosticTiming(30, 300, 1000, 10))
        try {
            assertTrue(owner.inspect())
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(0, allocations.get())
            assertEquals(MicrophoneDiagnosticMatch.UNIQUE, owner.state.result!!.inventory!!.match)
            assertTrue(owner.state.result!!.arms.isEmpty())
        } finally { owner.cancel() }
        MicrophoneCaptureFence.tryNormal()!!.release()
    }

    @Test fun realAdapterPrefersExactBeyondSixteenOnlyOnExplicitRequestAndClearsOnIncomplete() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val exact = device("Call_In_Mic")
        val record = mock(AudioRecord::class.java)
        `when`(record.setPreferredDevice(exact)).thenReturn(true)
        val p = MicrophoneDiagnosticAndroid(app) { record }
        install(others(16) + exact)
        assertEquals(MicrophoneDiagnosticMatch.UNIQUE, p.inventory().match)
        val owner = p.open(shape)
        try {
            verify(record, never()).setPreferredDevice(any())
            assertTrue(owner.preferNamedInput())
            verify(record).setPreferredDevice(exact)
        } finally { owner.release() }
        clearInvocations(record)
        for (devices in listOf(others(257), listOf(exact) + others(16) + device("Call_In_Mic"), others(20))) {
            install(devices)
            assertNotEquals(MicrophoneDiagnosticMatch.UNIQUE, p.inventory().match)
            val next = p.open(shape)
            try { assertFalse(next.preferNamedInput()) } finally { next.release() }
            verify(record, never()).setPreferredDevice(any())
        }
    }
}
