package com.openautolink.app.carplay

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.AudioRecord
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MicrophoneInfo
import android.util.Pair
import android.view.Surface
import com.openautolink.app.diagnostics.*
import com.openautolink.carplaylab.media.*
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.*
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import io.mockk.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.util.ReflectionHelpers
import java.io.*
import java.net.*
import java.util.concurrent.*
import java.util.zip.ZipInputStream

/** Real production receiver/sink/uplink/atomic store/manual uploader; OS and HTTP boundaries only. */
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[32],manifest=Config.NONE,
    instrumentedPackages=["com.shilapi.xcertplay.media"],shadows=[CarPlayCaptureProductionIntegrationTest.Recorder::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayCaptureProductionIntegrationTest {
    @Implements(AudioRecord::class)
    class Recorder {
        companion object { @JvmStatic @Implementation fun native_get_min_buff_size(r:Int,c:Int,f:Int)=1024 }
        private var first = true
        @Implementation fun native_start(e:Int,s:Int)=0
        @Implementation fun native_read_in_byte_array(b:ByteArray,o:Int,s:Int,blocking:Boolean):Int {
            if(first) {first=false;b.fill(0,o,o+s);return s}
            reading.countDown(); Thread.sleep(30000); return 0
        }
        @Implementation fun getActiveMicrophones():List<MicrophoneInfo> = mapping
        @Implementation fun native_release() {
            if(reading.count==0L) {
                releasing.countDown()
                while(true) { try { release.await(); break } catch(_:InterruptedException) {} }
            }
        }
    }
    @Implements(android.util.AtomicFile::class)
    class FailingCaptureAtomicStore {
        @RealObject lateinit var actual:android.util.AtomicFile
        @Implementation fun startWrite():FileOutputStream {
            if(actual.baseFile.name.startsWith("carplay_capture_run_")) {
                atomicAttempts.incrementAndGet();throw IOException("injected OS atomic-open failure")
            }
            return org.robolectric.util.reflector.Reflector.reflector(AtomicWrites::class.java,actual).startWrite()
        }
    }
    @org.robolectric.util.reflector.ForType(android.util.AtomicFile::class)
    interface AtomicWrites { @org.robolectric.util.reflector.Direct fun startWrite():FileOutputStream }
    companion object {
        val atomicAttempts=java.util.concurrent.atomic.AtomicInteger()
        var reading=CountDownLatch(1);var releasing=CountDownLatch(1);var release=CountDownLatch(1)
        var mapping=emptyList<MicrophoneInfo>()
    }
    private fun field(target:Any,name:String):Any = target.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(target)
    private fun productionSink(context:Context, notify:(String)->Unit):(CaptureTerminalSummary)->Boolean {
        val type=runCatching {Class.forName("com.openautolink.app.carplay.CarPlayCaptureTerminalSinkKt")}.getOrNull()
        assertNotNull("Production app must provide its exact receiver-frozen durable terminal closure",type)
        @Suppress("UNCHECKED_CAST")
        return type!!.getDeclaredMethod("captureTerminalSink",Context::class.java,kotlin.jvm.functions.Function1::class.java)
            .invoke(null,context,notify) as (CaptureTerminalSummary)->Boolean
    }
    private fun receiver(context:Context,status:(String)->Unit,sink:((CaptureTerminalSummary)->Boolean)?=null):ProjectionReceiver {
        val ctor=ProjectionReceiver::class.java.declaredConstructors.singleOrNull {it.parameterCount==13}
        assertNotNull("Production receiver must accept a typed durable terminal sink",ctor)
        val surface=mockk<Surface>();every {surface.isValid} returns true
        return ctor!!.newInstance(context,"02:00:00:00:00:01",surface,mockk<MfiAuthenticator>(relaxed=true),
            CarPlayDisplayPreferences(qualifiedVideoSelection=CarPlayVideoSelection(1280,720,30,"test.avc")),
            false,null,null,true,false,sink,null,status) as ProjectionReceiver
    }
    private fun logs(context:Context)=File(context.getExternalFilesDir(null),"openautolink/logs").apply {mkdirs()}
    private fun terminals(context:Context)=logs(context).listFiles().orEmpty().filter {it.name.startsWith("carplay_capture_run_")}
    private fun waitFor(message:String,condition:()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(!condition() && System.nanoTime()<end) Thread.sleep(5)
        assertTrue(message,condition())
    }
    @Test fun bothProductionRuntimeReportersRetainLateEvidenceButNeverNotifyCanceledUi() {
        val app=RuntimeEnvironment.getApplication();val surface=mockk<Surface>();val ui=CopyOnWriteArrayList<String>()
        val runtimes=listOf<CarPlayRuntime>(
            OalCarPlayRuntime(app,CarPlayRuntimeConfig("","","",false,"","",0,CarPlayDisplayPreferences()),surface,ui::add),
            OalUsbCarPlayRuntime(app,mockk<android.hardware.usb.UsbDevice>(relaxed=true),CarPlayDisplayPreferences(),surface,ui::add))
        try { runtimes.forEach { runtime ->
            (field(runtime,"canceled") as java.util.concurrent.atomic.AtomicBoolean).set(true)
            val method=runtime.javaClass.declaredMethods.singleOrNull {it.name=="receiverStatus"}
                ?: runtime.javaClass.declaredMethods.single {it.name=="status"}
            method.isAccessible=true
            val before=org.robolectric.shadows.ShadowLog.getLogsForTag("OalCarPlayAudio").size
            method.invoke(runtime,"media stage=microphone-retention receiver=1 final=true pending=0 owners=0 lost=1")
            assertEquals("Canceled ${runtime.javaClass.simpleName} must retain finite historical evidence",before+1,
                org.robolectric.shadows.ShadowLog.getLogsForTag("OalCarPlayAudio").size)
            assertTrue(ui.isEmpty())
        } } finally {
            runtimes.forEach {runtime ->
                (field(runtime,"canceled") as java.util.concurrent.atomic.AtomicBoolean).set(false);runtime.close()
            }
        }
    }
    @Test fun finalRetirementRetriesActualFailedStoreExactlyThreeTimesBeforeShutdownAndExportsLoss() {
        val app=RuntimeEnvironment.getApplication();File(app.noBackupFilesDir,"pairing-probe").deleteRecursively()
        val unavailable=object:android.content.ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun getExternalFilesDir(type:String?):File?=null
        }
        val notifications=CopyOnWriteArrayList<String>();val statusesSeen=CopyOnWriteArrayList<String>()
        val persist=productionSink(unavailable,notifications::add)
        val attempts=java.util.concurrent.atomic.AtomicInteger();val threads=CopyOnWriteArrayList<String>()
        lateinit var owner:ProjectionReceiver
        owner=receiver(app,statusesSeen::add) {summary ->
            assertFalse("Final durability attempts must precede shutdown",(field(owner,"statuses") as ThreadPoolExecutor).isShutdown)
            attempts.incrementAndGet();threads.add(Thread.currentThread().name);persist(summary)
        }
        val statuses=field(owner,"statuses") as ThreadPoolExecutor
        val blocked=CountDownLatch(1);val unblock=CountDownLatch(1)
        try {
            statuses.execute {blocked.countDown();unblock.await()};assertTrue(blocked.await(3,TimeUnit.SECONDS))
            val ledger=field(owner,"captureLedger") as CaptureAcquisitionLedger
            val pcm=Pcm16Window(1).snapshot()
            ledger.terminals.complete(CaptureTerminalSummary(ledger.receiverOrdinal,1,CaptureTerminalSummary.History.NORMAL,
                CaptureTerminalSummary.Profile.DEFAULT,MicrophoneSource.MIC,24000,1,24000,CaptureTerminalSummary.Selection.FIRST,
                CaptureAcquisitionLedger.Termination.CANCELLED,0,0,0,pcm,pcm))
            owner.close()
            // Pending terminal finalizer must be queued while passive worker is blocked.
            waitFor("retirement finalizer queued") { (field(owner,"statusRetirementQueued") as java.util.concurrent.atomic.AtomicBoolean).get() }
            assertFalse(statuses.isShutdown);unblock.countDown();assertTrue(owner.awaitClosed(10000))
            assertEquals(3,attempts.get());assertTrue(threads.all {it=="lab-media-status"})
            assertTrue(notifications.isEmpty());assertEquals(0,ledger.terminals.pending());assertEquals(1L,ledger.terminals.lost())
            assertTrue(statusesSeen.any {it=="media stage=microphone-retention receiver=${ledger.receiverOrdinal} final=true pending=0 owners=0 lost=1"})
            owner.close();assertTrue(owner.awaitClosed(0));assertEquals(3,attempts.get())
        } finally {unblock.countDown();owner.close();owner.awaitClosed(10000)}
    }
    @Test fun stalledActualCaptureRetainsExactReceiverAndPersistsBeforeUiAfterDetailExhaustionThenUploadsIdenticalBytes() = stalledCaptureExport(false)
    @Test @Config(sdk=[32,34]) fun aaStyleActualReleaseAndFourPartTerminalSurviveUiAbsenceAndManualZip() = stalledCaptureExport(true)
    private fun stalledCaptureExport(aaStyle:Boolean) {
        reading=CountDownLatch(1);releasing=CountDownLatch(1);release=CountDownLatch(1)
        val app=RuntimeEnvironment.getApplication();shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val microphone=mockk<MicrophoneInfo>()
        every {microphone.channelMapping} returns listOf(Pair(0,MicrophoneInfo.CHANNEL_MAPPING_DIRECT))
        mapping=listOf(microphone)
        val device=AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_MIC).build()
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(device,"mPort"),"mRole",1)
        shadowOf(app.getSystemService(AudioManager::class.java)).setInputDevices(listOf(device))
        File(app.noBackupFilesDir,"pairing-probe").deleteRecursively();logs(app).listFiles().orEmpty().forEach {it.delete()}
        CarPlayMicrophoneExperiments.disarm()
        if(aaStyle) CarPlayMicrophoneExperiments.arm(CarPlayMicrophoneExperimentProfile(source=MicrophoneSource.VOICE_RECOGNITION,rate=CarPlayMicrophoneCaptureRate.HZ_16000,aaStyle=true))
        val raw=CopyOnWriteArrayList<String>();val exported=CopyOnWriteArrayList<String>();val threads=CopyOnWriteArrayList<String>()
        val reporter=CarPlayAudioDiagnosticReporter(539,{0L},exported::add)
        repeat(64) {reporter.record("media stage=audio-queue-overflow")}
        assertEquals(64,exported.size)
        val notify:(String)->Unit={ line ->
            raw.add(line);reporter.record(line)
            if(line.startsWith("media stage=microphone-terminal ")) {
                assertEquals("UI must follow actual atomic persistence",1,terminals(app).size)
                assertTrue(terminals(app).single().readText().contains(line));threads.add(Thread.currentThread().name)
            }
        }
        val oldLogger=DiagnosticLog.fileLogWriter
        DiagnosticLog.fileLogWriter=null
        val owner=receiver(app,notify,productionSink(app,notify))
        CarPlayMicrophoneExperiments.disarm() // UI gone / future Off must not mutate frozen owner
        val ledger=field(owner,"captureLedger") as CaptureAcquisitionLedger
        val statuses=field(owner,"statuses") as ThreadPoolExecutor
        val sink=field(owner,"sink") as AndroidMediaSink
        val cap=field(owner,"microphoneCapability") as MicrophoneCapability
        val session=mockk<AirPlaySession>(relaxed=true)
        val id=MicrophoneStreamId(session,100,"telephony",1)
        DatagramSocket(0,InetAddress.getLoopbackAddress()).use { peer ->
            val config=MicrophoneSupport.negotiate(cap,100,"telephony",0x40L,peer.localPort,480,peer.localAddress,ByteArray(32){1})!!
            try {
                sink.onMicrophoneStarted(id,config);assertTrue(reading.await(3,TimeUnit.SECONDS))
                waitFor("Actual uplink policy callback must export all three owned route stages") {
                    listOf("microphone-route", "microphone-mapping", "microphone-channel").all {stage ->
                        exported.any {"stage=microphone-owned " in it && "stage=$stage " in it}
                    }
                }
                repeat(300) {ledger.root.event(CaptureAcquisitionLedger.Action.INPUTS)}
                statuses.submit {}.get(3,TimeUnit.SECONDS)
                assertTrue(raw.any {"action=limit" in it})
                owner.close();assertTrue(releasing.await(3,TimeUnit.SECONDS))
                assertFalse(owner.awaitClosed(30));assertFalse(statuses.isShutdown)
                assertNull(MicrophoneCaptureFence.tryDiagnostic());assertTrue(terminals(app).isEmpty())
                waitFor("pending owner must export on passive worker") {exported.any {"state=release-pending" in it}}
                assertTrue(raw.none {it.startsWith("media stage=microphone-terminal ")})
                release.countDown();assertTrue(owner.awaitClosed(10000))
                assertEquals(1,terminals(app).size);assertEquals(if(aaStyle) 4 else 3,threads.size)
                assertTrue(threads.all {it=="lab-media-status"})
                val bytes=terminals(app).single().readBytes()
                assertTrue(bytes.toString(Charsets.UTF_8).contains("receiver=${ledger.receiverOrdinal} "))
                assertTrue(bytes.toString(Charsets.UTF_8).contains("signal=zeros"))
                if(aaStyle) {
                    val acquisition=bytes.toString(Charsets.UTF_8).lineSequence().single { "part=acquisition" in it }
                    assertTrue(acquisition.contains("captureRate=16000 captureChannels=1 wireRate=24000"))
                    assertNotNull(CarPlayAudioDiagnostics.sanitize(1,acquisition))
                    assertNull(CarPlayAudioDiagnostics.sanitize(1,acquisition+" private suffix"))
                }
                val routeLog=File(logs(app),"oal_actual_owner_routes.log").apply {
                    writeText(exported.filter {"stage=microphone-owned " in it}.joinToString("\n",postfix="\n"))
                }
                val body=ByteArrayOutputStream()
                val http=object:HttpURLConnection(URL("https://example.invalid/upload")) {
                    override fun connect() {};override fun disconnect() {};override fun usingProxy()=false
                    override fun getOutputStream()=body;override fun getResponseCode()=200
                }
                assertTrue(LogUploader(app,{http},{true}).upload("https://example.invalid/upload","fixture","fixture") is UploadResult.Success)
                val members=mutableMapOf<String,ByteArray>()
                ZipInputStream(body.toByteArray().inputStream()).use {zip->while(true) {val entry=zip.nextEntry?:break;members[entry.name]=zip.readBytes()}}
                assertArrayEquals(bytes,members[terminals(app).single().name])
                assertArrayEquals(routeLog.readBytes(),members[routeLog.name])
                owner.close();assertTrue(owner.awaitClosed(0));assertEquals(1,terminals(app).size)
                MicrophoneCaptureFence.tryDiagnostic()!!.release()
            } finally {release.countDown();owner.close();owner.awaitClosed(10000);DiagnosticLog.fileLogWriter=oldLogger;CarPlayMicrophoneExperiments.disarm()}
        }
    }
    @Test @Config(shadows=[Recorder::class,FailingCaptureAtomicStore::class])
    fun saturatedReporterRetainsActualFailedStoreFinalCensusInManualZip() {
        atomicAttempts.set(0)
        reading=CountDownLatch(1);releasing=CountDownLatch(1);release=CountDownLatch(1);mapping=emptyList()
        val app=RuntimeEnvironment.getApplication();shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val device=AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_MIC).build()
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(device,"mPort"),"mRole",1)
        shadowOf(app.getSystemService(AudioManager::class.java)).setInputDevices(listOf(device))
        File(app.noBackupFilesDir,"pairing-probe").deleteRecursively()
        logs(app).listFiles().orEmpty().forEach {it.delete()};CarPlayMicrophoneExperiments.disarm()
        val exported=CopyOnWriteArrayList<String>();val notifications=CopyOnWriteArrayList<String>()
        val log=File(logs(app),"oal_final_retention.log")
        val reporter=CarPlayAudioDiagnosticReporter(539,{0L}) {line -> exported.add(line);log.appendText(line+"\n")}
        repeat(64) {reporter.record("media stage=audio-queue-overflow")}
        assertEquals(64,exported.size)
        val persist=productionSink(app,notifications::add);val attempts=java.util.concurrent.atomic.AtomicInteger()
        val owner=receiver(app,reporter::record) {summary -> attempts.incrementAndGet();persist(summary)}
        val ledger=field(owner,"captureLedger") as CaptureAcquisitionLedger
        val sink=field(owner,"sink") as AndroidMediaSink
        val cap=field(owner,"microphoneCapability") as MicrophoneCapability
        val session=mockk<AirPlaySession>(relaxed=true);val id=MicrophoneStreamId(session,100,"telephony",1)
        DatagramSocket(0,InetAddress.getLoopbackAddress()).use {peer ->
            val config=MicrophoneSupport.negotiate(cap,100,"telephony",0x40L,peer.localPort,480,peer.localAddress,ByteArray(32){1})!!
            try {
                sink.onMicrophoneStarted(id,config);assertTrue(reading.await(3,TimeUnit.SECONDS))
                owner.close();assertTrue(releasing.await(3,TimeUnit.SECONDS))
                assertFalse(owner.awaitClosed(30));assertNull(MicrophoneCaptureFence.tryDiagnostic())
                assertEquals(0,attempts.get());assertTrue(terminals(app).isEmpty())
                release.countDown();assertTrue(owner.awaitClosed(10000))
                assertEquals(3,attempts.get());assertEquals("Actual AtomicFile open must fail each bounded store attempt",3,atomicAttempts.get())
                assertEquals(1L,ledger.terminals.lost())
                assertEquals(0,ledger.terminals.pending());assertTrue(notifications.isEmpty());assertTrue(terminals(app).isEmpty())
                val finals=exported.filter {"stage=microphone-retention " in it && " final=true " in it && it.endsWith("pending=0 owners=0 lost=1")}
                assertEquals("Actual failed-store final census must survive saturated real reporter and shutdown",1,finals.size)
                assertTrue(finals.single().contains("receiver=${ledger.receiverOrdinal} "))
                val body=ByteArrayOutputStream();val http=object:HttpURLConnection(URL("https://example.invalid/upload")) {
                    override fun connect() {};override fun disconnect() {};override fun usingProxy()=false
                    override fun getOutputStream()=body;override fun getResponseCode()=200
                }
                assertTrue(LogUploader(app,{http},{true}).upload("https://example.invalid/upload","fixture","fixture") is UploadResult.Success)
                val members=mutableMapOf<String,ByteArray>()
                ZipInputStream(body.toByteArray().inputStream()).use {z->while(true){val e=z.nextEntry?:break;members[e.name]=z.readBytes()}}
                assertArrayEquals(log.readBytes(),members[log.name]);assertTrue(members[log.name]!!.toString(Charsets.UTF_8).contains(finals.single()))
                val before=exported.toList();owner.close();assertTrue(owner.awaitClosed(0))
                assertEquals(before,exported.toList());assertEquals(3,attempts.get())
                MicrophoneCaptureFence.tryDiagnostic()!!.release()
            } finally {release.countDown();owner.close();owner.awaitClosed(10000)}
        }
    }
    @Test fun actualReceiverPolicyWrapperExportsAllThreeFiniteRouteStagesIntoManualArchive() {
        val app=RuntimeEnvironment.getApplication();File(app.noBackupFilesDir,"pairing-probe").deleteRecursively()
        val exported=CopyOnWriteArrayList<String>();val reporter=CarPlayAudioDiagnosticReporter(539,System::nanoTime,exported::add)
        val owner=receiver(app,reporter::record)
        try {
            val ledger=field(owner,"captureLedger") as CaptureAcquisitionLedger
            val observation=ledger.owner(CaptureAcquisitionLedger.Kind.LIVE,MicrophoneSource.MIC,24000,1)
            val receipt=observation.javaClass.declaredMethods.single {it.name.startsWith("receipt")}.apply {isAccessible=true}
            val markers=listOf("media stage=microphone-route scope=own routed=1 active=1 equality=same stability=unknown truncated=false",
                "media stage=microphone-mapping scope=own result=ok examined=1 truncated=false",
                "media stage=microphone-channel scope=own row=0 channel=0 mapping=direct")
            markers.forEach {receipt.invoke(observation,it)}
            (field(owner,"statuses") as ThreadPoolExecutor).submit {}.get(3,TimeUnit.SECONDS)
            val owned=exported.filter {"stage=microphone-owned " in it}
            assertEquals("Actual ledger must retain receiver/owner route, mapping and channel wrappers",3,owned.size)
            markers.forEach {marker->assertTrue(owned.any {it.endsWith(marker) && "receiver=${ledger.receiverOrdinal} " in it && "owner=${observation.ordinal} " in it})}
            val log=File(logs(app),"oal_correlated_routes.log").apply {writeText(owned.joinToString("\n",postfix="\n"))}
            val body=ByteArrayOutputStream();val http=object:HttpURLConnection(URL("https://example.invalid/upload")) {
                override fun connect() {};override fun disconnect() {};override fun usingProxy()=false
                override fun getOutputStream()=body;override fun getResponseCode()=200
            }
            assertTrue(LogUploader(app,{http},{true}).upload("https://example.invalid/upload","fixture","fixture") is UploadResult.Success)
            val members=mutableMapOf<String,ByteArray>();ZipInputStream(body.toByteArray().inputStream()).use {z->while(true){val e=z.nextEntry?:break;members[e.name]=z.readBytes()}}
            assertArrayEquals(log.readBytes(),members[log.name])
        } finally {owner.close();assertTrue(owner.awaitClosed(10000))}
    }
}
