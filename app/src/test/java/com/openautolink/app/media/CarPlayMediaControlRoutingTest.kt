package com.openautolink.app.media

import android.content.Context
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import com.openautolink.app.carplay.MetadataHostOwnership
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],manifest=Config.NONE,application=android.app.Application::class)
class CarPlayMediaControlRoutingTest {
    @After fun cleanup() { unmockkAll() }
    private fun manager(): Pair<OalMediaSessionManager,MediaSessionCompat.Callback> {
        val app=RuntimeEnvironment.getApplication()
        val callback=slot<MediaSessionCompat.Callback>()
        mockkConstructor(MediaSessionCompat::class)
        every { anyConstructed<MediaSessionCompat>().setCallback(capture(callback), any<android.os.Handler>()) } just Runs
        every { anyConstructed<MediaSessionCompat>().setPlaybackState(any()) } just Runs
        every { anyConstructed<MediaSessionCompat>().setMetadata(any()) } just Runs
        every { anyConstructed<MediaSessionCompat>().setActive(any()) } just Runs
        val manager=OalMediaSessionManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible=true }.newInstance(app)
        manager.initialize()
        return manager to callback.captured
    }
    @Test fun productionHostMapsCommandsToConsumerHidAndRestoresAaOnClose() {
        val (manager,callback)=manager()
        val app=RuntimeEnvironment.getApplication()
        val prefs=mockk<com.openautolink.app.data.AppPreferences>()
        mockkObject(com.openautolink.app.data.AppPreferences.Companion)
        every { com.openautolink.app.data.AppPreferences.getInstance(any()) } returns prefs
        every { prefs.clusterNavigation } returns kotlinx.coroutines.flow.flowOf(false)
        every { prefs.distanceUnits } returns kotlinx.coroutines.flow.flowOf("auto")
        mockkObject(OalMediaSessionManager.Companion)
        every { OalMediaSessionManager.getInstance(any()) } returns manager
        val aa=mockk<OalMediaSessionManager.MediaControlCallback>(relaxed=true)
        manager.mediaControlCallback=aa
        val sent=mutableListOf<Int>()
        val constructor=com.openautolink.app.carplay.OalCarPlayMetadataHost::class.java.declaredConstructors.find { it.parameterCount==2 }
        assertNotNull("metadata host must install a real command transport",constructor)
        val sender: (Int)->Boolean = { sent.add(it);true }
        val host=constructor!!.apply { isAccessible=true }.newInstance(app,sender) as com.openautolink.app.carplay.CarPlayMetadataHost
        try {
            assertTrue(host.start())
            callback.onPlay();callback.onPause();callback.onStop();callback.onSkipToNext();callback.onSkipToPrevious()
            assertEquals(listOf(1,2,2,4,5),sent)
            verify(exactly=0) { aa.onCommand(any()) }
            host.close();host.close();callback.onStop()
            verify(exactly=1) { aa.onCommand(GmMediaControlPolicy.Command.STOP) }
        } finally { host.close();manager.release() }
    }
    @Test fun exactCarPlayCommandsAndTeardownRestoreRetainedAndroidAutoCallback() {
        val (manager,callback)=manager()
        val aa=mockk<OalMediaSessionManager.MediaControlCallback>(relaxed=true)
        val cp=mockk<OalMediaSessionManager.MediaControlCallback>(relaxed=true)
        manager.mediaControlCallback=aa
        val owner=Any(); val replacement=Any()
        assertTrue(MetadataHostOwnership.process.claim(owner))
        try {
            manager.installCarPlayControls(owner,cp)
            callback.onPlay(); callback.onPause(); callback.onStop(); callback.onSkipToNext(); callback.onSkipToPrevious()
            verifySequence { cp.onCommand(GmMediaControlPolicy.Command.PLAY); cp.onCommand(GmMediaControlPolicy.Command.PAUSE); cp.onCommand(GmMediaControlPolicy.Command.STOP); cp.onCommand(GmMediaControlPolicy.Command.NEXT); cp.onCommand(GmMediaControlPolicy.Command.PREVIOUS) }
            verify(exactly=0) { aa.onCommand(any()) }
            manager.retireCarPlayControls(replacement)
            callback.onStop()
            verify(exactly=2) { cp.onCommand(GmMediaControlPolicy.Command.STOP) }
            MetadataHostOwnership.process.retire(owner) { manager.retireCarPlayControls(owner) }
            callback.onStop()
            verify(exactly=1) { aa.onCommand(GmMediaControlPolicy.Command.STOP) }
        } finally { MetadataHostOwnership.process.retire(owner) {}; manager.release() }
    }
    @Test fun originalCommandsAreOwnerQualifiedAndRetainedInActualUploadArchive() {
        val (manager, callback) = manager()
        val owner = Any()
        val lines = mutableListOf<String>()
        val reporter = com.openautolink.app.carplay.CarPlayAudioDiagnosticReporter(7, System::nanoTime, lines::add)
        val received = mutableListOf<GmMediaControlPolicy.Command>()
        val control = object : OalMediaSessionManager.MediaControlCallback {
            override fun onCommand(command: GmMediaControlPolicy.Command) { received.add(command) }
        }
        assertTrue(MetadataHostOwnership.process.claim(owner))
        try {
            val install = manager.javaClass.declaredMethods.find { it.name == "installCarPlayControls\$app_debug" && it.parameterCount == 3 }
                ?: manager.javaClass.declaredMethods.find { it.name.startsWith("installCarPlayControls") && !it.name.endsWith("\$default") && it.parameterCount == 3 }
            assertNotNull("optional exact-owner diagnostic observer is missing", install)
            val observer: (String) -> Unit = reporter::record
            install!!.apply { isAccessible = true }.invoke(manager, owner, control, observer)
            callback.onPlay(); callback.onPause(); callback.onStop(); callback.onStop()
            assertEquals(listOf(GmMediaControlPolicy.Command.PLAY, GmMediaControlPolicy.Command.PAUSE, GmMediaControlPolicy.Command.STOP, GmMediaControlPolicy.Command.STOP), received)
            val expected = listOf("PLAY", "PAUSE", "STOP", "STOP").map {
                "CARPLAY_AUDIO session=7 media stage=media-command-ingress command=$it controller=unknown"
            }
            val boundary=Regex(" sequence=([0-9]+) elapsedMs=([0-9]+)$")
            assertTrue("source-boundary sequence and elapsedMs are required",lines.all { boundary.containsMatchIn(it) })
            assertEquals(expected,lines.map { it.replace(boundary, "") })
            assertEquals(listOf(1L,2L,3L,4L),lines.map { boundary.find(it)!!.groupValues[1].toLong() })
            val elapsed=lines.map { boundary.find(it)!!.groupValues[2].toLong() }
            assertTrue(elapsed.zipWithNext().all { (a,b) -> b>=a })
            assertTrue("two immediate identical native STOPs must both survive export",elapsed.last()-elapsed[elapsed.lastIndex-1]<1000)
            val snapshot=lines.toList()
            MetadataHostOwnership.process.retire(owner) {}
            callback.onStop()
            assertEquals("retired owners cannot emit diagnostics or controls", snapshot, lines)
            assertEquals(4, received.size)
            val folder = java.nio.file.Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")), "command-export").toFile()
            try {
                val log = java.io.File(folder, "oal_audio.log").apply { writeText(lines.joinToString("\n", postfix="\n")) }
                val plan = com.openautolink.app.diagnostics.LogUploadArchive.select(listOf(log), log.lastModified())
                com.openautolink.app.diagnostics.LogUploadArchive.create(plan, folder).use { archive ->
                    java.util.zip.ZipFile(archive.file).use { zip ->
                        assertEquals(log.readText(), zip.getInputStream(zip.getEntry(log.name)).bufferedReader().readText())
                    }
                }
            } finally { folder.deleteRecursively() }
        } finally { MetadataHostOwnership.process.retire(owner) {}; manager.release() }
    }
    @Test fun frameworkControllerPackagesReduceToFiniteClassesWithoutSuppressingControls() {
        val (manager, callback) = manager()
        val owner = Any(); val receipts = mutableListOf<String>(); var controls = 0
        assertTrue(MetadataHostOwnership.process.claim(owner))
        try {
            manager.installCarPlayControls(owner, object : OalMediaSessionManager.MediaControlCallback {
                override fun onCommand(command: GmMediaControlPolicy.Command) { controls++ }
            }, receipts::add)
            listOf("com.android.bluetooth" to "bluetooth", "com.android.car" to "car-service", "com.android.systemui" to "system-ui", "com.gm.rhmi" to "oem", "com.gm.gmaudio.server" to "oem", "com.gm.private.address" to "other").forEach { (pkg, category) ->
                every { anyConstructed<MediaSessionCompat>().currentControllerInfo } returns androidx.media.MediaSessionManager.RemoteUserInfo(pkg, 123, 456)
                callback.onStop()
                assertTrue(receipts.last().matches(Regex("media stage=media-command-ingress command=STOP controller=$category sequence=[0-9]+ elapsedMs=[0-9]+")))
            }
            manager.installCarPlayControls(owner, object : OalMediaSessionManager.MediaControlCallback {
                override fun onCommand(command: GmMediaControlPolicy.Command) { controls++ }
            }, receipts::add)
            callback.onStop()
            assertTrue("reinstalling same owner must retain source sequence",receipts.last().contains(" sequence=7 "))
            manager.installCarPlayControls(owner, object : OalMediaSessionManager.MediaControlCallback {
                override fun onCommand(command: GmMediaControlPolicy.Command) { controls++ }
            }) { throw IllegalStateException("observer private error") }
            callback.onStop()
            assertEquals(8, controls)
            assertTrue(receipts.all { !it.contains("com.") && !it.contains(" uid=") && !it.contains(" pid=") })
        } finally { MetadataHostOwnership.process.retire(owner) {}; manager.release() }
    }
    @Test fun carPlayOwnedStopNeverFallsBackToAndroidAuto() {
        val (manager,callback)=manager()
        val aa=mockk<OalMediaSessionManager.MediaControlCallback>(relaxed=true)
        manager.mediaControlCallback=aa
        val owner=Any()
        assertTrue(MetadataHostOwnership.process.claim(owner))
        try {
            callback.onStop()
            verify(exactly=0) { aa.onCommand(any()) }
        } finally { MetadataHostOwnership.process.retire(owner) {}; manager.release() }
    }
}
