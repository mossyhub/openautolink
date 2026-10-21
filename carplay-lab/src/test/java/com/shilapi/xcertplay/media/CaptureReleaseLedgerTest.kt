package com.shilapi.xcertplay.media

import android.Manifest
import android.app.Application
import android.media.*
import com.shilapi.xcertplay.airplay.*
import java.net.*
import java.util.concurrent.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class,manifest=Config.NONE,shadows=[CaptureReleaseLedgerTest.StalledRecorder::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CaptureReleaseLedgerTest {
    @Implements(AudioRecord::class)
    class StalledRecorder {
        companion object {
            @JvmStatic @Implementation fun native_get_min_buff_size(rate:Int,channels:Int,format:Int)=1024
        }
        @Implementation fun native_start(event:Int,session:Int)=0
        @Implementation fun native_read_in_byte_array(b:ByteArray,offset:Int,size:Int,blocking:Boolean):Int {
            reading.countDown(); Thread.sleep(30000);return 0
        }
        @Implementation fun native_release() {
            releasing.countDown()
            while (true) { try { allowRelease.await();break } catch (_:InterruptedException) {} }
        }
    }
    companion object {
        var reading=CountDownLatch(1)
        var releasing=CountDownLatch(1)
        var allowRelease=CountDownLatch(1)
    }
    @Test fun cancellationKeepsFenceAndNeverAcknowledgesStalledActualReleaseOrRelabelsLateOwner() {
        reading=CountDownLatch(1);releasing=CountDownLatch(1);allowRelease=CountDownLatch(1)
        val app=RuntimeEnvironment.getApplication();shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        AndroidMicrophoneSupport.initialize(app)
        val lines=CopyOnWriteArrayList<String>();val tasks=LinkedBlockingQueue<Runnable>()
        val ledger=CaptureAcquisitionLedger(false);ledger.bind(Executor { tasks.add(it) },lines::add)
        fun flush() { while(true) (tasks.poll() ?: return).run() }
        val constructor=MicrophoneUplink::class.java.declaredConstructors.firstOrNull {
            it.parameterTypes.size==6 && it.parameterTypes[4]==CaptureAcquisitionLedger::class.java &&
                it.parameterTypes.last()==CarPlayMicrophoneExperimentProfile::class.java }
        assertNotNull("actual uplink must retain the receiver's exact acquisition ledger",constructor)
        val cap=MicrophoneCapability.of(mapOf("telephony" to setOf(0x40L)))
        DatagramSocket(0,InetAddress.getLoopbackAddress()).use { peer ->
            val config=MicrophoneSupport.negotiate(cap,100,"telephony",0x40L,peer.localPort,480,peer.localAddress,ByteArray(32){1})!!
            val report: (String) -> Unit = {}
            val uplink=constructor!!.apply { isAccessible=true }.newInstance(config,report,null,cap,ledger,null) as MicrophoneUplink
            try {
                uplink.start();assertTrue(reading.await(3,TimeUnit.SECONDS))
                uplink.close();assertTrue(releasing.await(3,TimeUnit.SECONDS));flush()
                assertFalse(uplink.isRetired);assertNull(MicrophoneCaptureFence.tryDiagnostic())
                assertTrue(lines.any { "kind=live action=end " in it })
                assertTrue(lines.any { "kind=live action=release-begin " in it })
                assertFalse(lines.any { "kind=live action=released " in it })
                assertFalse(lines.any { "kind=live action=retired " in it })
                val successor=CaptureAcquisitionLedger(true);val newer=mutableListOf<String>()
                successor.bind(Executor { tasks.add(it) },newer::add)
                allowRelease.countDown()
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
                while(!uplink.isRetired && System.nanoTime()<deadline) Thread.sleep(5)
                assertTrue(uplink.isRetired);flush()
                assertEquals(1,lines.count { "kind=live action=released result=ok" in it })
                assertEquals(1,lines.count { "kind=live action=retired result=ok" in it })
                assertFalse(newer.any { "kind=live" in it })
                assertTrue(lines.any { "stage=microphone-signal" in it && "blocks=0 " in it && "phase=released" in it })
            } finally { allowRelease.countDown();uplink.close() }
        }
        MicrophoneCaptureFence.tryDiagnostic()!!.release()
    }
}
