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
@Config(sdk=[32],application=Application::class,manifest=Config.NONE,
    instrumentedPackages=["com.shilapi.xcertplay.media.capture"],
    shadows=[com.shilapi.xcertplay.media.capture.StalledReleaseRecorder::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CaptureReleaseLedgerTest {
    @Test fun cancellationKeepsFenceAndNeverAcknowledgesStalledActualReleaseOrRelabelsLateOwner() {
        val app=RuntimeEnvironment.getApplication();shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        AndroidMicrophoneSupport.initialize(app)
        val lines=CopyOnWriteArrayList<String>();val tasks=LinkedBlockingQueue<Runnable>()
        val ledger=CaptureAcquisitionLedger(false);ledger.bind(Executor { tasks.add(it) },lines::add)
        fun flush() { while(true) (tasks.poll() ?: return).run() }
        val constructor=MicrophoneUplink::class.java.declaredConstructors.firstOrNull {
            it.parameterTypes.size==8 && it.parameterTypes[4]==CaptureAcquisitionLedger::class.java &&
                it.parameterTypes[5]==CarPlayMicrophoneExperimentProfile::class.java &&
                it.parameterTypes[6]==MicrophoneCaptureAdmission::class.java &&
                it.parameterTypes[7]==Boolean::class.javaPrimitiveType }
        assertNotNull("actual uplink must retain the receiver's exact acquisition ledger",constructor)
        val cap=MicrophoneCapability.of(mapOf("telephony" to setOf(0x40L)))
        DatagramSocket(0,InetAddress.getLoopbackAddress()).use { peer ->
            val config=MicrophoneSupport.negotiate(cap,100,"telephony",0x40L,peer.localPort,480,peer.localAddress,ByteArray(32){1})!!
            val report: (String) -> Unit = {}
            var owner: MicrophoneUplink? = null
            val fixture=com.shilapi.xcertplay.media.capture.StalledReleaseRecorder.install()
            try {
                val reading=fixture.reading; val releasing=fixture.releasing; val allowRelease=fixture.allowRelease
                val uplink=constructor!!.apply { isAccessible=true }.newInstance(
                    config,report,null,cap,ledger,null,MicrophoneCaptureAdmission { true },false
                ) as MicrophoneUplink
                owner=uplink
                uplink.start();assertTrue(reading.await(3,TimeUnit.SECONDS))
                assertFalse(uplink.isRetired); assertNull(MicrophoneCaptureFence.tryDiagnostic())
                uplink.close();assertTrue(releasing.await(3,TimeUnit.SECONDS));flush()
                assertEquals(AudioRecord.STATE_INITIALIZED,fixture.record!!.state)
                assertEquals(0,fixture.nativeReleases.get())
                assertEquals(1L,fixture.realReleaseCompleted.count)
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
                assertEquals(0L,fixture.realReleaseCompleted.count)
                assertEquals(1,fixture.nativeReleases.get())
                assertEquals(AudioRecord.STATE_UNINITIALIZED,fixture.record!!.state)
                assertEquals(1,lines.count { "kind=live action=released result=ok" in it })
                assertEquals(1,lines.count { "kind=live action=retired result=ok" in it })
                assertFalse(newer.any { "kind=live" in it })
                assertTrue(lines.any { "stage=microphone-signal" in it && "blocks=0 " in it && "phase=released" in it })
            } finally {
                fixture.allowRelease.countDown()
                try { owner?.close() } finally { fixture.close() }
            }
        }
        MicrophoneCaptureFence.tryDiagnostic()!!.release()
    }
}
