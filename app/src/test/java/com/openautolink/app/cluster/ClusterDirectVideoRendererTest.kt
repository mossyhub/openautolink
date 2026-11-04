package com.openautolink.app.cluster

import android.app.Application
import android.graphics.SurfaceTexture
import android.view.Surface
import com.openautolink.app.secondary.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32], application=Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ClusterDirectVideoRendererTest {
    private class Driver: ClusterRenderDriver {
        val configs=AtomicInteger(); val frames=AtomicInteger(); val parks=AtomicInteger(); val resumes=AtomicInteger()
        var parkSuccess=true
        override fun configure(config: ClusterCodecConfig) { configs.incrementAndGet() }
        override fun submit(frame: ClusterEncodedFrame):Boolean { frames.incrementAndGet(); return true }
        override fun poll()=ClusterRenderProgress(0,0)
        override fun stopDecoder()=Unit
        override fun clearOutput()=Unit
        override fun detach()=true
        override fun parkWindow():Boolean { parks.incrementAndGet(); return parkSuccess }
        override fun resumeWindow(surface:Surface):Boolean { resumes.incrementAndGet(); return true }
    }
    @Test fun pageAwayRetainsDecoderAndPredictionChainAcrossNewSurfaceWithoutNewIdr() {
        val d=Driver(); val r=ClusterVideoRenderer(ClusterRenderDriverFactory { _,_->d })
        val ta=SurfaceTexture(0); val a=Surface(ta); val tb=SurfaceTexture(0); val b=Surface(tb)
        val target=SecondaryRenderTarget(SecondaryOutputId("actual-window"),1,7,PixelSize(1600,600),PixelRect(0,0,1600,600),PixelRect(480,120,1120,480))
        val mapping=SecondaryVideoMapping.androidAuto(target)
        try {
            val first=r.attach(a,mapping,1); assertTrue(first.ready.get(3,TimeUnit.SECONDS))
            assertTrue(r.configure(1,ClusterCodecConfig(mapping.codedSize,byteArrayOf(7),byteArrayOf(8))))
            assertTrue(r.offer(ClusterEncodedFrame(1,byteArrayOf(5),1,true))); assertTrue(r.barrier().get(3,TimeUnit.SECONDS))
            assertEquals(1,d.frames.get()); assertTrue(r.park(first).get(3,TimeUnit.SECONDS))
            assertTrue(r.offer(ClusterEncodedFrame(1,byteArrayOf(1),2,false))); assertTrue(r.barrier().get(3,TimeUnit.SECONDS))
            val next=r.attach(b,mapping,2); assertTrue(next.ready.get(3,TimeUnit.SECONDS))
            assertTrue(r.offer(ClusterEncodedFrame(1,byteArrayOf(1),3,false))); assertTrue(r.barrier().get(3,TimeUnit.SECONDS))
            assertEquals(3,d.frames.get()); assertEquals(1,d.configs.get()); assertEquals(1,d.resumes.get())
            assertTrue(r.park(first).get(3,TimeUnit.SECONDS)); assertEquals(1,d.parks.get())
        } finally { assertTrue(r.retire().get(3,TimeUnit.SECONDS)); a.release();ta.release();b.release();tb.release() }
    }
}
