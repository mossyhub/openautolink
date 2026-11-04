package com.openautolink.app.cluster

import android.app.Application
import android.graphics.SurfaceTexture
import android.view.Surface
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],manifest=Config.NONE,application=Application::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class ClusterBackgroundBridgeTest {
    @Test fun frozenAndroidAutoBackgroundSurvivesActualBridgeBoundsCallback()=run(false)
    @Test fun frozenCarPlayBackgroundSurvivesActualBridgeBoundsCallback()=run(true)
    private fun run(cp:Boolean) {
        val p=ClusterDisplayProfiles.AEGEAN_ORANGE_239;val stable=PixelRect(0,135,2000,810)
        val h=ClusterHostGeometry(2,p.panel,stable,stable)
        val q=requireNotNull(ClusterDisplayProfiles.qualify(p.vehicle,listOf(ClusterDisplayBinding(p.role,2)),h))
        val mapping=if(cp) ClusterVideoGeometry.carPlay(q) else ClusterVideoGeometry.androidAuto(q)
        val created=AtomicInteger();val submitted=AtomicInteger();val clears=AtomicInteger()
        val factory=ClusterRenderDriverFactory {_,_->created.incrementAndGet();object:ClusterRenderDriver {
            override fun configure(config:ClusterCodecConfig) {}
            override fun submit(frame:ClusterEncodedFrame):Boolean {submitted.incrementAndGet();return true}
            override fun poll()=ClusterRenderProgress(0,0)
            override fun stopDecoder() {}
            override fun clearOutput() {clears.incrementAndGet()}
            override fun detach()=true
        }}
        val output=ClusterProjectionOutput(mapping,factory);val lease=ClusterOutputLease(42,2)
        val texture=SurfaceTexture(0);val surface=Surface(texture)
        try {
            assertTrue(output.bindLease(lease));assertTrue(output.attach(lease,surface,h).get(3,TimeUnit.SECONDS))
            val before=clears.get()
            assertTrue(output.updateBounds(lease,surface,h.copy(visible=p.panel.bounds)).get(3,TimeUnit.SECONDS))
            assertTrue(output.status.value.hostAttached);assertSame(mapping,output.mapping)
            assertEquals(1,created.get());assertEquals(before,clears.get())
            val stream=requireNotNull(output.beginStream(lease,Any()) {true})
            assertTrue(output.annexB(lease,stream,byteArrayOf(0,0,0,1,7,1,0,0,0,1,8,1,0,0,0,1,5,1),1))
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            while(submitted.get()==0 && System.nanoTime()<end) Thread.sleep(5)
            assertEquals(1,submitted.get())
            assertFalse(output.updateBounds(lease,surface,h.copy(visible=p.panel.bounds,stable=p.panel.bounds)).get(3,TimeUnit.SECONDS))
            assertFalse(output.status.value.hostAttached)
        } finally {assertTrue(output.retire(lease).get(3,TimeUnit.SECONDS));surface.release();texture.release()}
    }
}
