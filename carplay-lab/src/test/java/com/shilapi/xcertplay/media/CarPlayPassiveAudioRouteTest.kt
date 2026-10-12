package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.view.Surface
import com.openautolink.carplaylab.media.CarPlayDisplayPreferences
import com.openautolink.carplaylab.media.CarPlayDisplaySelection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32], manifest=Config.NONE)
class CarPlayPassiveAudioRouteTest {
    @Test fun actualAlternateTrackSnapshotHasNavigationSpeechAndUnavailableRoute() {
        val lines = CopyOnWriteArrayList<String>()
        val observed = CountDownLatch(1)
        val surface = mock(Surface::class.java); `when`(surface.isValid).thenReturn(true)
        val sink = AndroidMediaSink(RuntimeEnvironment.getApplication(),surface,
            display=CarPlayDisplaySelection(CarPlayDisplayPreferences(),"avc.exact",false),
            onStatus={ lines.add(it); if(it.startsWith("media stage=audio-route")) observed.countDown() })
        val format = requireNotNull(AudioOutputSupport.negotiate(0x4000L,101,"compatibility"))
        try {
            sink.onAudioStarted(com.shilapi.xcertplay.airplay.AudioStreamId(101,"compatibility"),format,0)
            assertTrue(observed.await(7,TimeUnit.SECONDS))
            assertTrue(lines.contains("media stage=audio-owners scope=own owners=1 tracks=1"))
            assertTrue(lines.contains("media stage=audio-route stream=1 type=101 audioType=compatibility codec=LPCM scope=own usage=12 content=1 device=null"))
        } finally { sink.close(); assertTrue(sink.awaitRetired(3000)) }
    }
    @Test fun sinkWithholdsExactRetirementUntilStalledPublicQueryReturns() {
        val manager = mock(AudioManager::class.java)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val lines = CopyOnWriteArrayList<String>()
        `when`(manager.activePlaybackConfigurations).thenAnswer {
            entered.countDown()
            while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
            emptyList<AudioPlaybackConfiguration>()
        }
        val app = spy(RuntimeEnvironment.getApplication() as Context)
        doReturn(app).`when`(app).applicationContext
        doReturn(manager).`when`(app).getSystemService(AudioManager::class.java)
        val surface = mock(Surface::class.java); `when`(surface.isValid).thenReturn(true)
        val sink = AndroidMediaSink(app,surface,
            display=CarPlayDisplaySelection(CarPlayDisplayPreferences(),"avc.exact",false),onStatus=lines::add)
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            sink.close()
            assertFalse("replacement must wait for the exact diagnostic worker",sink.awaitRetired(50))
            val size = lines.size
            release.countDown()
            assertTrue(sink.awaitRetired(3000))
            assertEquals("no late old-owner snapshot may reach replacement",size,lines.size)
        } finally { release.countDown(); sink.close(); assertTrue(sink.awaitRetired(3000)) }
    }
    @Test fun stalledPublicQueryCannotHideOwnZeroOrPublishAfterRetirement() {
        val manager = mock(AudioManager::class.java)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ownObserved = CountDownLatch(1)
        val lines = CopyOnWriteArrayList<String>()
        `when`(manager.activePlaybackConfigurations).thenAnswer {
            assertEquals("carplay-audio-route-diagnostics",Thread.currentThread().name)
            entered.countDown()
            while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
            emptyList<AudioPlaybackConfiguration>()
        }
        val diagnostics = PassiveAudioRouteDiagnostics(manager,
            { listOf("media stage=audio-owners scope=own owners=0 tracks=0") },
            { lines.add(it); if(it.contains("audio-owners")) ownObserved.countDown() })
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            assertTrue("own zero must publish before a potentially stalled public Binder query",ownObserved.await(100,TimeUnit.MILLISECONDS))
            diagnostics.close()
            assertFalse("interruption is not retirement acknowledgement",diagnostics.isRetired)
            val count = lines.size
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!diagnostics.isRetired && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(diagnostics.isRetired)
            assertEquals("late old query must not publish",count,lines.size)
            assertFalse(lines.any { it.contains("device-playback") })
        } finally { diagnostics.close(); release.countDown() }
    }
    @Test fun failedPublicQueryIsExplicitUnavailableNotZeroPlayers() {
        val manager = mock(AudioManager::class.java)
        `when`(manager.activePlaybackConfigurations).thenThrow(SecurityException("private owner"))
        val observed = CountDownLatch(1)
        val lines = CopyOnWriteArrayList<String>()
        val diagnostics = PassiveAudioRouteDiagnostics(manager,
            { listOf("media stage=audio-owners scope=own owners=0 tracks=0") },
            { lines.add(it); if(it.contains("result=unavailable")) observed.countDown() })
        try {
            assertTrue(observed.await(2,TimeUnit.SECONDS))
            assertTrue(lines.contains("media stage=device-playback-summary scope=device owner=unknown result=unavailable observed=0 emitted=0 truncated=false"))
            assertFalse(lines.any { it.contains("private owner") })
        } finally { diagnostics.close() }
    }
    @Test fun zeroOwnOwnersDoesNotHideDeviceWideUnknownMediaOwner() {
        val lines = CopyOnWriteArrayList<String>()
        val observed = CountDownLatch(1)
        val manager = mock(AudioManager::class.java)
        val playback = mock(AudioPlaybackConfiguration::class.java)
        val device = mock(android.media.AudioDeviceInfo::class.java)
        `when`(device.type).thenReturn(android.media.AudioDeviceInfo.TYPE_BUS)
        `when`(playback.audioDeviceInfo).thenReturn(device)
        `when`(playback.audioAttributes).thenReturn(AudioAttributes.Builder().setUsage(1).setContentType(2).build())
        `when`(manager.activePlaybackConfigurations).thenReturn(List(12) { playback })
        val app = spy(RuntimeEnvironment.getApplication() as Context)
        doReturn(app).`when`(app).applicationContext
        doReturn(manager).`when`(app).getSystemService(AudioManager::class.java)
        val surface = mock(Surface::class.java); `when`(surface.isValid).thenReturn(true)
        val sink = AndroidMediaSink(app,surface,
            display=CarPlayDisplaySelection(CarPlayDisplayPreferences(),"avc.exact",false),
            onStatus={ lines.add(it); if (it.startsWith("media stage=device-playback index=7")) observed.countDown() })
        try {
            assertTrue("passive route snapshot must run without an OAL audio SETUP",observed.await(3,TimeUnit.SECONDS))
            assertTrue(lines.contains("media stage=audio-owners scope=own owners=0 tracks=0"))
            assertTrue(lines.contains("media stage=device-playback-summary scope=device owner=unknown result=ok observed=12 emitted=8 truncated=true"))
            assertEquals(8,lines.count { it.startsWith("media stage=device-playback index=") })
            assertTrue(lines.contains("media stage=device-playback index=0 scope=device owner=unknown usage=1 content=2 device=21"))
        } finally { sink.close(); assertTrue(sink.awaitRetired(3000)) }
        val afterClose = lines.size
        Thread.sleep(100)
        assertEquals("retired snapshot worker must not publish",afterClose,lines.size)
        verify(manager,atLeastOnce()).getActivePlaybackConfigurations()
        verify(device,never()).getAddress()
        verify(device,never()).getProductName()
        verify(device,never()).getId()
    }
}
