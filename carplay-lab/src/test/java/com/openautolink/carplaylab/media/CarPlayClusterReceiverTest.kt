// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.*
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32], manifest=Config.NONE, application=android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayClusterReceiverTest {
    @Test fun receiverExposesExactLeaseAndEncodedCommonRendererSeams() {
        assertNotNull("secondary attaches need exact sink leases",ProjectionReceiver::class.java.methods.firstOrNull { it.name == "attachClusterSurface" })
        assertNotNull("secondary detach must accept the exact lease",ProjectionReceiver::class.java.methods.firstOrNull { it.name == "detachClusterSurface" })
        assertNotNull("common EGL needs an encoded111 listener rather than host stretch",
            runCatching { Class.forName("com.openautolink.carplaylab.media.CarPlayClusterVideoListener") }.getOrNull())
    }
    private fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(owner)
    private fun config(receiver: ProjectionReceiver) = receiver.javaClass.getDeclaredMethod("sessionConfig").apply { isAccessible=true }.invoke(receiver) as AirPlayConfig
    private val saved = CarPlayDisplayPreferences(preferHevc=true,
        qualifiedVideoSelection=CarPlayVideoSelection(1280,720,30,"main.avc","main.hevc"))
    private fun cluster() = CarPlayClusterVideoConfiguration.create(AirPlayInsets(10,10,20,20),
        AirPlayInsets(40,40,360,360),CarPlayVideoSelection(1280,480,30,"cluster.avc"))
    private fun receiver(cluster: CarPlayClusterVideoConfiguration?, listener: CarPlayClusterVideoListener? = null): ProjectionReceiver {
        val app = RuntimeEnvironment.getApplication()
        java.io.File(app.noBackupFilesDir,"pairing-probe").deleteRecursively()
        return ProjectionReceiver(app,"02:00:00:00:00:01",mock(Surface::class.java),
            mock(MfiAuthenticator::class.java),display=saved,wired=true,
            clusterVideoConfiguration=cluster,clusterVideoListener=listener,onStatus={})
    }
    @Test fun receiverBindsTheActualOfferAndSinkToTheSameAvcPilotReceipt() {
        val cluster = cluster()
        val receiver = receiver(cluster)
        try {
            val config = config(receiver)
            assertNotNull("qualified cluster must reach the actual receiver session config",config.cluster)
            val receipt = field(receiver,"videoSelection") as CarPlayVideoSelection
            assertEquals(cluster.display,config.cluster)
            assertTrue(receipt.matches(config))
            assertFalse(config.hevc)
            assertFalse(receiver.displayConfiguration.preferHevc)
            assertFalse(receiver.displayConfiguration.qualifiedVideoSelection!!.hevcOffered)
            assertTrue(saved.preferHevc)
            assertTrue(saved.qualifiedVideoSelection!!.hevcOffered)
            val sink = field(receiver,"sink") as AndroidMediaSink
            assertSame(cluster.videoSelection,field(field(sink,"clusterVideo"),"selection"))
            sink.onVideoCodec(110,VideoCodec.H264)
            sink.onVideoCodec(111,VideoCodec.H264)
            assertThrows(IllegalArgumentException::class.java) { sink.onVideoCodec(110,VideoCodec.H265) }
            assertThrows(IllegalArgumentException::class.java) { sink.onVideoCodec(111,VideoCodec.H265) }
        } finally { receiver.close(); assertTrue(receiver.awaitClosed(5000)) }
    }
    @Test fun directSurfaceAttachReturnsTheExactSinkLeaseAndStaleDetachCannotClearSuccessor() {
        val receiver = receiver(cluster())
        val surface = mock(Surface::class.java)
        `when`(surface.isValid).thenReturn(true)
        try {
            val first = checkNotNull(receiver.attachClusterSurface(surface))
            val second = checkNotNull(receiver.attachClusterSurface(surface))
            assertNotSame(first,second)
            val secondary = field(field(receiver,"sink"),"clusterVideo")
            assertSame(secondary,second.owner)
            val presentation = field(secondary,"presentation") as VideoPresentation
            assertSame(second.receipt,presentation.snapshot())
            receiver.detachClusterSurface(first)
            assertSame(second.receipt,presentation.snapshot())
            receiver.detachClusterSurface(second)
            assertNull(presentation.snapshot().surface)
            receiver.revokePendingAdmission()
            assertNull(receiver.attachClusterSurface(surface))
        } finally { receiver.close(); assertTrue(receiver.awaitClosed(5000)) }
        verify(surface,never()).release()
    }
    @Test fun encodedListenerGetsIndependentEpochsSnapshotsAndRetirementWithoutSecondDecode() {
        val events = mutableListOf<String>()
        val snapshots = mutableListOf<ByteArray>()
        val receiver = receiver(cluster(),object : CarPlayClusterVideoListener {
            override fun onStreamActive(streamEpoch: Long, active: Boolean) { events += "$streamEpoch:$active" }
            override fun onCodecConfig(streamEpoch: Long, codecData: ByteArray) { events += "$streamEpoch:config"; snapshots += codecData }
            override fun onFrame(streamEpoch: Long, naluBytes: ByteArray) { events += "$streamEpoch:frame"; snapshots += naluBytes }
        })
        try {
            assertNull(receiver.attachClusterSurface(mock(Surface::class.java)))
            val media = field(receiver,"mediaSink") as MediaSink
            val bytes = byteArrayOf(1,2)
            media.onScreenStreamActive(111,true)
            media.onVideoConfig(111,bytes); media.onVideoFrame(111,bytes)
            bytes[0] = 9
            assertEquals(1,snapshots[0][0].toInt()); assertEquals(1,snapshots[1][0].toInt())
            media.onScreenStreamActive(111,true)
            media.onVideoFrame(111,bytes)
            receiver.revokePendingAdmission()
            media.onVideoFrame(111,bytes); media.onVideoConfig(111,bytes); media.onScreenStreamActive(111,true)
            assertEquals(listOf("1:true","1:config","1:frame","1:false","2:true","2:frame","2:false"),events)
            val secondary = field(field(receiver,"sink"),"clusterVideo")
            assertNull(secondary.javaClass.getDeclaredField("latest").apply { isAccessible=true }
                .get(secondary).let { it as java.util.concurrent.atomic.AtomicReference<*> }.get())
        } finally { receiver.close(); assertTrue(receiver.awaitClosed(5000)) }
    }
    @Test fun optionalListenerFailureCannotPoisonMainOrLaterSecondaryEpoch() {
        val events = mutableListOf<String>()
        val receiver = receiver(cluster(),object : CarPlayClusterVideoListener {
            override fun onStreamActive(streamEpoch: Long, active: Boolean) { events += "$streamEpoch:$active" }
            override fun onCodecConfig(streamEpoch: Long, codecData: ByteArray) { throw IllegalStateException("fixture") }
            override fun onFrame(streamEpoch: Long, naluBytes: ByteArray) { events += "$streamEpoch:frame" }
        })
        try {
            val media = field(receiver,"mediaSink") as MediaSink
            media.onScreenStreamActive(111,true); media.onVideoConfig(111,byteArrayOf(1)); media.onVideoFrame(111,byteArrayOf(1))
            media.onVideoCodec(110,VideoCodec.H264)
            media.onScreenStreamActive(111,true); media.onVideoFrame(111,byteArrayOf(1))
            assertEquals(listOf("1:true","1:false","2:true","2:frame"),events)
        } finally { receiver.close(); assertTrue(receiver.awaitClosed(5000)) }
    }
    @Test fun actualBinaryInfoAndSetupAdmitTwoAvcStreamsWithoutGlobalHevc() {
        val receiver = receiver(cluster())
        try {
            val config = config(receiver)
            val receipt = field(receiver,"videoSelection") as CarPlayVideoSelection
            val engine = field(receiver,"engine") as CarPlayMediaEngine
            java.net.ServerSocket(0).use { server -> java.net.Socket("127.0.0.1",server.localPort).use { phone ->
                server.accept().use { control ->
                    val session = AirPlaySession(control,config,AirPlayIdentity.generate(),PairingStore(),null,
                        object:AirPlaySessionListener {},engine,videoSelection=receipt,availableAudioFormats=emptySet())
                    try {
                        val verify = field(session,"pairVerify")
                        verify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible=true }.set(verify,ByteArray(32))
                        // PairVerify crypto has independent tests; inject its exact verified boundary here.
                        verify.javaClass.getDeclaredField("verified").apply { isAccessible=true }.setBoolean(verify,true)
                        val handle = session.javaClass.getDeclaredMethod("handle",RtspMessage.Request::class.java).apply { isAccessible=true }
                        fun request(method: String, path: String, body: Map<String,Any?>? = null): Map<*,*> {
                            val result = handle.invoke(session,RtspMessage.Request(method,path,"RTSP/1.0",emptyMap(),
                                body?.let { BplistCodec.encode(it) } ?: ByteArray(0))) as RtspMessage.Response
                            assertEquals(200,result.status ?: 200)
                            return BplistCodec.decode(result.body) as Map<*,*>
                        }
                        val info = request("POST","/info")
                        assertFalse(info.containsKey("hevcInfo"))
                        val displays = (info["displays"] as List<*>).map { it as Map<*,*> }
                        assertEquals(listOf(110L,111L),displays.map { it["type"] })
                        assertEquals(listOf(AirPlayInfoPlist.MAIN_UUID,AirPlayInfoPlist.ALT_UUID),displays.map { it["uuid"] })
                        val alt = displays[1]
                        assertEquals(1280L,alt["widthPixels"]); assertEquals(480L,alt["heightPixels"]); assertEquals(30L,alt["maxFPS"])
                        assertEquals(0L,alt["features"]); assertEquals(0L,alt["primaryInputDevice"])
                        assertEquals("maps:/car/instrumentcluster",alt["initialURL"])
                        val area = (alt["viewAreas"] as List<*>).single() as Map<*,*>
                        assertEquals(1240L,area["widthPixels"]); assertEquals(460L,area["heightPixels"])
                        assertEquals(20L,area["originXPixels"]); assertEquals(10L,area["originYPixels"])
                        val safe = area["safeArea"] as Map<*,*>
                        assertEquals(560L,safe["widthPixels"]); assertEquals(400L,safe["heightPixels"])
                        assertEquals(360L,safe["originXPixels"]); assertEquals(40L,safe["originYPixels"])
                        assertEquals(false,safe["drawUIOutsideSafeArea"])
                        val hids = info["hidDevices"] as List<*>
                        assertEquals(4,hids.size)
                        assertFalse(hids.any { (it as Map<*,*>).values.contains(AirPlayInfoPlist.ALT_UUID) })
                        val initial = request("SETUP","/stream",mapOf("features" to listOf("hevc")))
                        assertFalse((initial["enabledFeatures"] as List<*>).contains("hevc"))
                        assertFalse(session.acceptsVideoCodec(VideoCodec.H265))
                        val streamReply = request("SETUP","/stream",mapOf("streams" to listOf(
                            mapOf("type" to 110,"streamConnectionID" to 10L),mapOf("type" to 111,"streamConnectionID" to 11L))))
                        val streams = (streamReply["streams"] as List<*>).map { it as Map<*,*> }
                        assertEquals(listOf(110L,111L),streams.map { it["type"] })
                        val ports = streams.map { (it["dataPort"] as Number).toInt() }
                        assertTrue(ports.all { it > 0 }); assertEquals(2,ports.distinct().size)
                    } finally { session.close() }
                }
            } }
        } finally { receiver.close(); assertTrue(receiver.awaitClosed(5000)) }
    }
    @Test fun disabledReceiverRetainsMainHevcAndExactLegacyConfig() {
        val receiver = receiver(null)
        try {
            val config = config(receiver)
            assertNull(config.cluster)
            assertTrue(config.hevc)
            assertTrue(receiver.displayConfiguration.preferHevc)
            assertEquals(saved,receiver.displayConfiguration)
            assertNull(receiver.clusterVideoPilotStatus)
            val legacy = benchReceiverConfig("02:00:00:00:00:01",0,MicrophoneCapability.NONE,saved)
                .copy(hevc=true,oemLabel="OpenAutoLink",icons=config.icons)
            assertEquals(legacy,config)
            assertArrayEquals(BplistCodec.encode(AirPlayInfoPlist.build(legacy,emptySet(),videoSelection=saved.qualifiedVideoSelection)),
                BplistCodec.encode(AirPlayInfoPlist.build(config,emptySet(),videoSelection=field(receiver,"videoSelection") as CarPlayVideoSelection)))
            assertNull(receiver.attachClusterSurface(mock(Surface::class.java)))
            val sink = field(receiver,"sink") as AndroidMediaSink
            assertThrows(IllegalArgumentException::class.java) { sink.onVideoCodec(111,VideoCodec.H264) }
        } finally { receiver.close(); assertTrue(receiver.awaitClosed(5000)) }
    }
}
