package com.shilapi.xcertplay.media

import android.Manifest
import android.app.Application
import android.media.AudioRecord
import com.shilapi.xcertplay.airplay.*
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
@Config(sdk=[32],application=Application::class,manifest=Config.NONE,shadows=[CaptureTerminalUplinkTest.Recorder::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CaptureTerminalUplinkTest {
    @Implements(AudioRecord::class)
    class Recorder {
        companion object { @JvmStatic @Implementation fun native_get_min_buff_size(r:Int,c:Int,f:Int)=1024 }
        @Implementation fun native_start(e:Int,s:Int)=0
        @Implementation fun native_read_in_byte_array(b:ByteArray,o:Int,s:Int,blocking:Boolean):Int {
            reading.countDown(); Thread.sleep(30000); return 0
        }
        @Implementation fun native_release() {
            releasing.countDown()
            while(true) { try { release.await(); break } catch(_:InterruptedException) {} }
        }
    }
    companion object {
        var reading=CountDownLatch(1); var releasing=CountDownLatch(1); var release=CountDownLatch(1)
    }
    @Test fun actualUplinkEnqueuesExactlyOneImmutableTerminalOnlyAfterStalledReleaseCompletes() {
        reading=CountDownLatch(1); releasing=CountDownLatch(1); release=CountDownLatch(1)
        val app=RuntimeEnvironment.getApplication(); shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        AndroidMicrophoneSupport.initialize(app)
        val ledger=CaptureAcquisitionLedger(false)
        repeat(300) { ledger.root.event(CaptureAcquisitionLedger.Action.INPUTS) }
        val terminals=mutableListOf<CaptureTerminalSummary>()
        ledger.bindTerminals(Executor { }) { terminals.add(it); true }
        val cap=MicrophoneCapability.of(mapOf("telephony" to setOf(0x40L)))
        DatagramSocket(0,InetAddress.getLoopbackAddress()).use { peer ->
            val config=MicrophoneSupport.negotiate(cap,100,"telephony",0x40L,peer.localPort,480,peer.localAddress,ByteArray(32){1})!!
            val uplink=MicrophoneUplink(config,{},null,cap,ledger)
            try {
                uplink.start(); assertTrue(reading.await(3,TimeUnit.SECONDS))
                uplink.close(); assertTrue(releasing.await(3,TimeUnit.SECONDS))
                assertFalse(uplink.isRetired); assertNull(MicrophoneCaptureFence.tryDiagnostic())
                assertTrue(ledger.terminals.flush()); assertTrue(terminals.isEmpty())
                val successor=CaptureAcquisitionLedger(true)
                release.countDown()
                val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
                while(!uplink.isRetired && System.nanoTime()<end) Thread.sleep(5)
                assertTrue(uplink.isRetired); assertTrue(ledger.terminals.flush())
                assertEquals(1,terminals.size); assertEquals(ledger.receiverOrdinal,terminals.single().receiver)
                assertEquals(0,successor.terminals.pending()); assertEquals(0L,terminals.single().raw.observedFrames)
                assertTrue(terminals.single().lines().first().contains("signal=no-frames"))
                assertTrue(ledger.terminals.flush()); assertEquals(1,terminals.size)
            } finally { release.countDown(); uplink.close() }
        }
        MicrophoneCaptureFence.tryDiagnostic()!!.release()
    }
}
