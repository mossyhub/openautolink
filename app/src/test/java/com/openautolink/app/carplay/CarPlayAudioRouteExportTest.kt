package com.openautolink.app.carplay

import android.view.Surface
import com.openautolink.carplaylab.media.CarPlayDisplayPreferences
import com.openautolink.carplaylab.media.CarPlayDisplaySelection
import com.shilapi.xcertplay.media.AndroidMediaSink
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.graphics.SurfaceTexture
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk=[32], manifest=Config.NONE)
class CarPlayAudioRouteExportTest {
    @Test fun actualSinkZeroOwnerSnapshotSurvivesReporterAndProductionArchive() {
        val captured = CopyOnWriteArrayList<String>()
        val observed = CountDownLatch(1)
        val routeObserved = CountDownLatch(1)
        val reporter = CarPlayAudioDiagnosticReporter(517,System::nanoTime) {
            captured.add(it)
            if (it.contains("device-playback-summary")) observed.countDown()
            if (it.contains("stage=audio-route")) routeObserved.countDown()
        }
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val sink = AndroidMediaSink(RuntimeEnvironment.getApplication(),surface,
            display=CarPlayDisplaySelection(CarPlayDisplayPreferences(),"avc.exact",false),onStatus=reporter::record)
        try {
            assertTrue("real zero-owner and device-wide snapshots must cross sanitizer",observed.await(3,TimeUnit.SECONDS))
            assertTrue(captured.any { it == "CARPLAY_AUDIO session=517 media stage=audio-owners scope=own owners=0 tracks=0" })
            val format = requireNotNull(com.shilapi.xcertplay.media.AudioOutputSupport.negotiate(0x4000L,101,"compatibility"))
            sink.onAudioStarted(com.shilapi.xcertplay.airplay.AudioStreamId(101,"compatibility"),format,0)
            assertTrue("actual track attributes/route must cross export",routeObserved.await(7,TimeUnit.SECONDS))
            assertTrue(captured.any { it.contains("stage=audio-route") && it.contains("scope=own usage=12 content=1 device=null") })
            val folder = java.nio.file.Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")),"route-export").toFile()
            try {
                val contents = captured.joinToString("\n",postfix="\n")
                val log = java.io.File(folder,"oal_route.log").apply { writeText(contents) }
                val plan = com.openautolink.app.diagnostics.LogUploadArchive.select(listOf(log),log.lastModified())
                com.openautolink.app.diagnostics.LogUploadArchive.create(plan,folder).use { archive ->
                    java.util.zip.ZipFile(archive.file).use { zip ->
                        assertEquals(contents,zip.getInputStream(zip.getEntry(log.name)).bufferedReader().readText())
                    }
                }
            } finally { folder.deleteRecursively() }
        } finally { sink.close(); assertTrue(sink.awaitRetired(3000)); surface.release(); texture.release() }
    }
    @Test fun exactRouteSchemasRejectIdentitiesUnboundedNumbersAndPrivateSuffixes() {
        val valid = listOf(
            "media stage=audio-owners scope=own owners=0 tracks=0",
            "media stage=audio-owners scope=own owners=8 tracks=8",
            "media stage=audio-route stream=1 type=101 audioType=compatibility codec=LPCM scope=own usage=12 content=1 device=21",
            "media stage=device-playback-summary scope=device owner=unknown result=ok observed=12 emitted=8 truncated=true",
            "media stage=device-playback-summary scope=device owner=unknown result=unavailable observed=0 emitted=0 truncated=false",
            "media stage=device-playback index=0 scope=device owner=unknown usage=1 content=2 device=null"
        )
        valid.forEach {
            assertNotNull(it,CarPlayAudioDiagnostics.sanitize(517,it))
            for (suffix in listOf(" uid=12"," pid=12"," address=private"," name=private","\n"))
                assertNull(CarPlayAudioDiagnostics.sanitize(517,it+suffix))
        }
        for (invalid in listOf(
            valid[0].replace("owners=0","owners=9"),
            valid[3].replace("emitted=8","emitted=9"),
            valid[5].replace("owner=unknown","owner=phone"),
            valid[5].replace("usage=1","usage=-1"),
            valid[5].replace("index=0","index=8"),
            valid[5].replace("device=null","device=12345")
        )) assertNull(invalid,CarPlayAudioDiagnostics.sanitize(517,invalid))
    }
}
