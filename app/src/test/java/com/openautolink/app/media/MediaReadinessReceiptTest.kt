package com.openautolink.app.media

import android.app.Application
import android.graphics.Bitmap
import com.openautolink.app.carplay.*
import com.openautolink.app.diagnostics.*
import com.openautolink.carplaylab.metadata.CarPlayMediaMetadata
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[32,34],manifest=Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaReadinessReceiptTest {
    @After fun cleanup() { OalMediaSessionManager.instanceOrNull()?.release() }
    @Test fun realReadinessChildrenAndArtworkOutcomesSurviveManualZipWithoutLoggingOrUi() {
        val app=RuntimeEnvironment.getApplication()
        val manager=OalMediaSessionManager.getInstance(app);manager.initialize()
        val lease=CarPlayHostMediaLease(CarPlayHostMediaMode.NORMAL);manager.beginCarPlayHostMedia(lease)
        val owner=Any();assertTrue(MetadataHostOwnership.process.claim(owner))
        manager.installCarPlayControls(owner,object:OalMediaSessionManager.MediaControlCallback { override fun onCommand(command:GmMediaControlPolicy.Command) {} })
        val dir=File(app.getExternalFilesDir(null),"openautolink/logs")
        val receipt=File(dir,"media_readiness.log")
        MediaDiagnosticStore.prepareForManualUpload(app)
        val baseline=if(receipt.isFile) receipt.readText() else ""
        fun count(text:String,key:String)=Regex("(?m)^$key=([0-9]+)$").find(text)?.groupValues?.get(1)?.toInt() ?: 0
        try {
            assertTrue(manager.browseChildren(OalMediaBrowserService.PHONE_FOLDER_ID).isEmpty())
            manager.setCarPlayMediaReady(owner,true)
            assertEquals(1,manager.browseChildren(OalMediaBrowserService.PHONE_FOLDER_ID).size)
            manager.browseChildren("private.canary.parent")
            val png=ByteArrayOutputStream().also { Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
            manager.updateCarPlay(owner,CarPlayMediaMetadata(title="private.canary.title"),png,true)
            manager.updateCarPlay(owner,CarPlayMediaMetadata(),byteArrayOf(1,2,3),true)
            manager.recordDiagnosticReceipt(lease,"carplay-artwork result=accepted size=small")
            manager.recordDiagnosticReceipt(lease,"carplay-artwork result=accepted size=small private.canary")
            val service=org.robolectric.Robolectric.buildService(OalMediaBrowserService::class.java).create()
            try { service.get().onGetRoot("private.canary.package",123,null) } finally { service.destroy() }
            manager.recordDiagnosticReceipt(CarPlayHostMediaLease(CarPlayHostMediaMode.NORMAL),"carplay-artwork result=reference size=large")
            repeat(1000) { manager.browseChildren("private.canary.parent") }
            assertEquals(MediaStoreStatus.AVAILABLE,MediaDiagnosticStore.prepareForManualUpload(app))
            assertTrue("manual ZIP must retain finite browse/artwork evidence with file logging off",receipt.isFile)
            val text=receipt.readText()
            assertTrue(text.contains("phase=READY"));assertTrue(text.contains("controls=true runtime=true lease=true applied=true"))
            for(key in listOf("root","children_phone_ready","children_phone_empty","decode_ok","decode_failed","transfer_accepted"))
                assertEquals(key,1,count(text,key)-count(baseline,key))
            assertEquals(0,count(text,"transfer_reference")-count(baseline,"transfer_reference"))
            assertFalse(text.contains("private.canary"));assertTrue(receipt.length()<4096)
            LogUploadArchive.create(LogUploadArchive.select(listOf(receipt),receipt.lastModified()),app.cacheDir).use { archive ->
                java.util.zip.ZipFile(archive.file).use { zip -> assertEquals(text,zip.getInputStream(zip.getEntry(receipt.name)).bufferedReader().readText()) }
            }
        } finally {
            MetadataHostOwnership.process.retire(owner) {manager.retireCarPlayControls(owner)}
            manager.finishCarPlayHostMedia(lease)
        }
    }
}
