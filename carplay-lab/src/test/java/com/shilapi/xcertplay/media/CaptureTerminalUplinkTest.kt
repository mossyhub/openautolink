package com.shilapi.xcertplay.media

import android.Manifest
import android.app.Application
import android.media.AudioRecord
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.capture.StalledReleaseRecorder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.net.*
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class,manifest=Config.NONE,
    instrumentedPackages=["com.shilapi.xcertplay.media.capture"],
    shadows=[StalledReleaseRecorder::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CaptureTerminalUplinkTest {
    @Test fun actualUplinkEnqueuesExactlyOneImmutableTerminalOnlyAfterStalledReleaseCompletes() {
        val fixture=StalledReleaseRecorder.install()
        val reading=fixture.reading; val releasing=fixture.releasing; val release=fixture.allowRelease
        val app=RuntimeEnvironment.getApplication(); shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        AndroidMicrophoneSupport.initialize(app)
        val ledger=CaptureAcquisitionLedger(false)
        repeat(300) { ledger.root.event(CaptureAcquisitionLedger.Action.INPUTS) }
        val terminals=mutableListOf<CaptureTerminalSummary>()
        ledger.bindTerminals(Executor { }) { terminals.add(it); true }
        val cap=MicrophoneCapability.of(mapOf("telephony" to setOf(0x40L)))
        DatagramSocket(0,InetAddress.getLoopbackAddress()).use { peer ->
            val config=MicrophoneSupport.negotiate(cap,100,"telephony",0x40L,peer.localPort,480,peer.localAddress,ByteArray(32){1})!!
            val uplink=MicrophoneUplink(config,{},null,cap,ledger,captureAdmission=MicrophoneCaptureAdmission {true})
            try {
                uplink.start(); assertTrue(reading.await(3,TimeUnit.SECONDS))
                assertFalse(uplink.isRetired); assertNull(MicrophoneCaptureFence.tryDiagnostic())
                uplink.close(); assertTrue(releasing.await(3,TimeUnit.SECONDS))
                assertEquals(AudioRecord.STATE_INITIALIZED,fixture.record!!.state)
                assertEquals(0,fixture.nativeReleases.get())
                assertEquals(1L,fixture.realReleaseCompleted.count)
                assertFalse(uplink.isRetired); assertNull(MicrophoneCaptureFence.tryDiagnostic())
                assertTrue(ledger.terminals.flush()); assertTrue(terminals.isEmpty())
                val successor=CaptureAcquisitionLedger(true)
                release.countDown()
                val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
                while(!uplink.isRetired && System.nanoTime()<end) Thread.sleep(5)
                assertTrue(uplink.isRetired); assertTrue(ledger.terminals.flush())
                assertEquals(0L,fixture.realReleaseCompleted.count)
                assertEquals(1,fixture.nativeReleases.get())
                assertEquals(AudioRecord.STATE_UNINITIALIZED,fixture.record!!.state)
                assertEquals(1,terminals.size); assertEquals(ledger.receiverOrdinal,terminals.single().receiver)
                assertEquals(0,successor.terminals.pending()); assertEquals(0L,terminals.single().raw.observedFrames)
                assertTrue(terminals.single().lines().first().contains("signal=no-frames"))
                assertTrue(ledger.terminals.flush()); assertEquals(1,terminals.size)
            } finally { release.countDown(); uplink.close(); fixture.close() }
        }
        MicrophoneCaptureFence.tryDiagnostic()!!.release()
    }
}
