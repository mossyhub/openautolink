package com.openautolink.app.cluster

import android.app.Application
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.*
import android.view.Surface
import com.openautolink.app.carplay.OalCarPlayClusterOutput
import com.openautolink.app.secondary.*
import com.openautolink.carplaylab.media.CarPlayClusterVideoConfiguration
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.media.CarPlayVideoSelection
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodec
import org.robolectric.shadows.ShadowMediaCodecList
import org.robolectric.util.ReflectionHelpers

/** Actual adapter -> queue -> EGL driver -> named MediaCodec factory/configure/submit/poll.
 * OS codec copies input bytes; this is not physical HEVC decoding or simultaneous decode proof. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk=[32],manifest=Config.NONE,application=Application::class,
    shadows=[ClusterVideoRendererTest.RecordingEgl::class,ClusterVideoRendererTest.RecordingGl::class,
        ClusterVideoRendererTest.RecordingTexture::class,CarPlayClusterHevcDecodeTest.RecordingHevcCodec::class],
    instrumentedPackages=["com.openautolink.app.cluster"])
class CarPlayClusterHevcDecodeTest {
    companion object { val instances=java.util.concurrent.CopyOnWriteArrayList<MediaCodec>() }
    @org.robolectric.annotation.Implements(MediaCodec::class)
    class RecordingHevcCodec : ShadowMediaCodec() {
        companion object {
            @JvmStatic @org.robolectric.annotation.Implementation fun createByCodecName(name:String):MediaCodec {
                ClusterQualifiedDecoderTest.factories += "name:$name"
                return ReflectionHelpers.callConstructor(MediaCodec::class.java,
                    ReflectionHelpers.ClassParameter.from(String::class.java,name),
                    ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!,false),
                    ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!,false)).also { instances += it }
            }
        }
    }
    private val hvcc get() = java.util.Base64.getDecoder().decode("AQFgAAAAkAAAAAAAePAA/P34+AAADwOgAAEAGEABDAH//wFgAAADAJAAAAMAAAMAeJWYCaEAAQAsQgEBAWAAAAMAkAAAAwAAAwB4oAPogDMfJllZpJMrwFoCAAADAAIAAAMAPBCiAAEAB0QBwXK0YkA=")
    @Test fun malformedUnofferedAndDifferentVisibleHevcConfigurationsAreRejected() {
        val selected=CarPlayVideoSelection(2000,810,30,"cluster.avc","cluster.hevc")
        assertThrows(IllegalArgumentException::class.java) { ClusterCodecConfig.carPlay(hvcc,selected.copy(hevcDecoderName=null)) }
        assertThrows(IllegalArgumentException::class.java) { ClusterCodecConfig.carPlay(hvcc,selected.copy(height=808)) }
        assertThrows(IllegalArgumentException::class.java) { ClusterCodecConfig.carPlay(hvcc.copyOf(hvcc.size-1),selected) }
        assertThrows(IllegalArgumentException::class.java) { ClusterCodecConfig.carPlay(hvcc.apply { this[12]=123 },selected) }
        val valid=ClusterCodecConfig.carPlay(hvcc,selected)
        assertEquals(PixelSize(2000,816),valid.decoderCodedSize)
        val map=SecondaryVideoMapping(SecondaryRenderTarget(SecondaryOutputId("test.geometry"),1,7,
            PixelSize(2000,810),PixelRect(0,0,2000,810),PixelRect(100,10,1900,800)),
            PixelSize(2000,810),PixelRect(0,0,2000,810),30)
        val full=ClusterGlPlan.create(map,PixelRect(0,0,2000,816),valid.decoderCodedSize)
        assertEquals(1f-810f/816f,full.textureCoordinates[1],0.00001f)
        assertEquals(100,map.phoneSafeInsets.left);assertEquals(10,map.phoneSafeInsets.top)
    }
    @Test fun inBandHevcParameterSetsCannotReplaceTheNegotiatedCanvasOrProfile() {
        val target=SecondaryRenderTarget(SecondaryOutputId("test.inline"),1,7,PixelSize(2000,810),PixelRect(0,0,2000,810),PixelRect(0,0,2000,810))
        val map=SecondaryVideoMapping(target,PixelSize(2000,810),PixelRect(0,0,2000,810),30)
        val bridge=ClusterProjectionOutput(map);val lease=ClusterOutputLease(55,66);assertTrue(bridge.bindLease(lease))
        try {
            val source=bridge.beginStream(lease,Any()) { true }!!
            val config=ClusterCodecConfig.carPlay(hvcc,CarPlayVideoSelection(2000,810,30,"cluster.avc","cluster.hevc"))
            assertTrue(bridge.configure(lease,source,config))
            assertTrue(bridge.annexB(lease,source,config.sps,1))
            assertFalse(bridge.annexB(lease,source,byteArrayOf(0,0,0,1,0x42,1,0x55),2))
        } finally { assertTrue(bridge.retire(lease).get(3,TimeUnit.SECONDS)) }
    }
    @Test fun productionRuntimeListenerUsesNegotiatedHevcRatherThanItsAvcBootstrapName() {
        val descriptor=SecondaryDisplayDescriptor(SecondaryOutputId("test.runtime"),7,1,"test",2000,810,160,
            SecondaryRoute.PUBLIC_ACTIVITY,SecondaryReason.NONE,"Test output")
        val inventory=kotlinx.coroutines.flow.MutableStateFlow(SecondaryInventorySnapshot(1,listOf(descriptor)))
        val configured=java.util.concurrent.CompletableFuture<ClusterCodecConfig>()
        val delivered=java.util.concurrent.CountDownLatch(1)
        val driver=object:ClusterRenderDriver {
            override fun configure(config:ClusterCodecConfig) { configured.complete(config) }
            override fun submit(frame:ClusterEncodedFrame):Boolean { delivered.countDown();return true }
            override fun poll()=ClusterRenderProgress(0,0)
            override fun stopDecoder()=Unit
            override fun clearOutput()=Unit
            override fun detach()=true
        }
        val runtime=SecondaryOutputRuntime(org.robolectric.RuntimeEnvironment.getApplication(),inventory,
            { _,_ -> error("unused factory") },{ _,_ -> "cluster.avc" })
        val owner=runtime.RuntimeOutput(descriptor,0,1)
        val target=SecondaryRenderTarget(descriptor.id,1,7,PixelSize(2000,810),PixelRect(0,0,2000,810),PixelRect(0,0,2000,810))
        val map=SecondaryVideoMapping(target,PixelSize(2000,810),PixelRect(0,0,2000,810),30)
        val bridge=ClusterProjectionOutput(map,ClusterRenderDriverFactory { _,_ -> driver },"cluster.avc")
        owner.bridge=bridge;assertTrue(bridge.bindLease(owner.lease))
        owner.configuration=CarPlayClusterVideoConfiguration.create(AirPlayInsets(),AirPlayInsets(),CarPlayVideoSelection(2000,810,30,"cluster.avc","cluster.hevc"),2000,810)
        val texture=SurfaceTexture(0);val surface=Surface(texture)
        try {
            assertTrue(bridge.attach(owner.lease,surface,target).get(3,TimeUnit.SECONDS))
            val listener=runtime.cpListener(owner)
            listener.onStreamActive(1,true);listener.onCodecConfig(1,hvcc)
            listener.onFrame(1,byteArrayOf(0,0,0,1,0x26,1,0x55))
            assertTrue("Actual runtime listener did not deliver HEVC",delivered.await(3,TimeUnit.SECONDS))
            assertEquals("cluster.hevc",configured.get(1,TimeUnit.SECONDS).decoderName)
            assertEquals(PixelSize(2000,816),configured.get().decoderCodedSize)
            listener.onCodecConfig(1,hvcc.apply { this[12]=123 })
            assertNull("Bad replacement must revoke the old config, not reuse its CSD",owner.stream)
            listener.onFrame(1,byteArrayOf(0,0,0,1,0x26,1,0x55))
            assertEquals(1L,bridge.status.value.received)
        } finally {
            assertTrue(bridge.retire(owner.lease).get(3,TimeUnit.SECONDS))
            assertTrue(runtime.close().get(3,TimeUnit.SECONDS));surface.release();texture.release()
        }
    }
    @Test fun productionAttachSelectsMeasuredCanvasAndKeepsHevcOffWithoutExplicitExperiment() = measuredAttach(false)
    @Test fun productionAttachCanExplicitlySelectPaddedQualifiedHevc() = measuredAttach(true)
    private fun measuredAttach(experimental:Boolean) {
        val descriptor=SecondaryDisplayDescriptor(SecondaryOutputId("test.measured"),7,1,"test",2000,810,160,
            SecondaryRoute.PUBLIC_ACTIVITY,SecondaryReason.NONE,"Test output")
        val inventory=kotlinx.coroutines.flow.MutableStateFlow(SecondaryInventorySnapshot(1,listOf(descriptor)))
        val requests=java.util.concurrent.CopyOnWriteArrayList<com.shilapi.xcertplay.media.VideoDecoderRequest>()
        val driver=object:ClusterRenderDriver {
            override fun configure(config:ClusterCodecConfig)=Unit
            override fun submit(frame:ClusterEncodedFrame)=true
            override fun poll()=ClusterRenderProgress(0,0)
            override fun stopDecoder()=Unit
            override fun clearOutput()=Unit
            override fun detach()=true
        }
        val runtime=SecondaryOutputRuntime(org.robolectric.RuntimeEnvironment.getApplication(),inventory,
            { map,name -> ClusterProjectionOutput(map,ClusterRenderDriverFactory { _,_ -> driver },name) },{ _,_ -> "cluster.avc" },
            { com.shilapi.xcertplay.media.VideoDecoderProbe { request -> requests+=request
                if(request.codec==com.shilapi.xcertplay.airplay.VideoCodec.H264) "cluster.avc" else "cluster.hevc" } })
        val owner=runtime.RuntimeOutput(descriptor,0,1).apply { carPlay=true;experimentalHevc=experimental }
        val texture=SurfaceTexture(0);val surface=Surface(texture)
        try {
            val receipt=SecondaryHostReceipt(surface,SecondaryHostGeometry(2000,810,PixelRect(100,50,1900,760)),SecondaryHostOwner(descriptor,1))
            assertTrue(owner.attach(receipt).get(3,TimeUnit.SECONDS))
            val config=requireNotNull(owner.configuration)
            assertEquals(2000,config.display.widthPixels);assertEquals(810,config.display.heightPixels)
            assertEquals(experimental,config.videoSelection.hevcOffered)
            assertEquals(PixelSize(2000,810),owner.bridge!!.mapping.codedSize)
            assertEquals(AirPlayInsets(50,50,100,100),config.display.safeArea)
            assertEquals(experimental,requests.any { it.codec==com.shilapi.xcertplay.airplay.VideoCodec.H265 && it.height==816 })
        } finally {
            owner.bridge?.let { assertTrue(it.retire(owner.lease).get(3,TimeUnit.SECONDS)) }
            assertTrue(runtime.close().get(3,TimeUnit.SECONDS));surface.release();texture.release()
        }
    }
    @Test fun incomingHevcReachesNamedDecoderWithPaddedCodedHeightAndRetiresExactOwner() = actualDecode(true)
    @Test fun actualSpsCoded816IsRejectedByDecoderSupportingOnlyVisible810WithoutTypeFallback() = actualDecode(false)
    private fun actualDecode(supportsPadded:Boolean) {
        for ((name,type) in listOf("EGL_NO_DISPLAY" to EGLDisplay::class.java,"EGL_NO_CONTEXT" to EGLContext::class.java,"EGL_NO_SURFACE" to EGLSurface::class.java)) {
            ReflectionHelpers.setStaticField(EGL14::class.java,name,type.getDeclaredConstructor(java.lang.Long.TYPE).apply { isAccessible=true }.newInstance(0L))
        }
        ClusterVideoRendererTest.failBlackSwap=false; ClusterVideoRendererTest.pendingVideo=false
        ClusterVideoRendererTest.textureListeners.clear();ClusterQualifiedDecoderTest.factories.clear();instances.clear()
        ShadowMediaCodecList.reset()
        val caps=MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC,2000,if(supportsPadded) 816 else 810)).setIsEncoder(false)
            .setColorFormats(intArrayOf(2130708361)).setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel().apply {
                profile=MediaCodecInfo.CodecProfileLevel.HEVCProfileMain;level=MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5 })).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("cluster.hevc").setIsEncoder(false).setCapabilities(caps).build())
        ShadowMediaCodec.addDecoder("cluster.hevc",ShadowMediaCodec.CodecConfig(65536,65536) { input,output -> output.put(input) })
        val target=SecondaryRenderTarget(SecondaryOutputId("test.hevc"),1,7,PixelSize(2000,810),PixelRect(0,0,2000,810),PixelRect(100,10,1900,800))
        val map=SecondaryVideoMapping(target,PixelSize(2000,810),PixelRect(0,0,2000,810),30)
        val bridge=ClusterProjectionOutput(map,"cluster.avc");val lease=ClusterOutputLease(30,40);assertTrue(bridge.bindLease(lease))
        val texture=SurfaceTexture(0);val surface=Surface(texture)
        try {
            val config=CarPlayClusterVideoConfiguration.create(AirPlayInsets(),AirPlayInsets(10,10,100,100),CarPlayVideoSelection(2000,810,30,"cluster.avc","cluster.hevc"),2000,810)
            val adapter=OalCarPlayClusterOutput(ClusterOutputSelection(mode=ClusterDisplayMode.MAP_VIDEO),config,null,bridge,lease)
            assertTrue(bridge.attach(lease,surface,target).get(3,TimeUnit.SECONDS))
            adapter.onStreamActive(1,true);adapter.onCodecConfig(0,hvcc);adapter.onCodecConfig(1,hvcc)
            adapter.onFrame(1,byteArrayOf(0,0,0,1,2,1,0x55)) // delta cannot initialize a GOP
            adapter.onFrame(1,byteArrayOf(0,0,0,1,0x26,0,0x55)) // invalid temporal_id
            adapter.onFrame(1,byteArrayOf(0,0,0,1,0x26,1,0x55))
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            while(bridge.status.value.decoded==0L && bridge.status.value.error==null && System.nanoTime()<deadline) Thread.sleep(5)
            if(!supportsPadded) {
                assertNotNull("Actual padded format must fail locally",bridge.status.value.error)
                assertEquals(0L,bridge.status.value.decoded)
                assertEquals(listOf("name:cluster.hevc"),ClusterQualifiedDecoderTest.factories.toList())
                return
            }
            assertEquals("adapter/codec path: ${bridge.status.value}",1L,bridge.status.value.decoded)
            assertEquals(1L,bridge.status.value.keyframes)
            assertEquals(listOf("name:cluster.hevc"),ClusterQualifiedDecoderTest.factories.toList())
            val actual=instances.single().inputFormat
            assertEquals(MediaFormat.MIMETYPE_VIDEO_HEVC,actual.getString(MediaFormat.KEY_MIME))
            assertEquals(2000,actual.getInteger(MediaFormat.KEY_WIDTH));assertEquals(816,actual.getInteger(MediaFormat.KEY_HEIGHT))
            assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,actual.getInteger(MediaFormat.KEY_PROFILE))
            assertNotNull(actual.getByteBuffer("csd-0"));assertFalse(actual.containsKey("csd-1"))
            adapter.onStreamActive(1,false)
            adapter.onFrame(1,byteArrayOf(0,0,0,1,0x26,1))
            assertEquals(2L,bridge.status.value.received)
        } finally {
            assertTrue(bridge.retire(lease).get(3,TimeUnit.SECONDS))
            assertTrue(surface.isValid);surface.release();texture.release();ShadowMediaCodec.clearCodecs();ShadowMediaCodecList.reset()
        }
    }
}
