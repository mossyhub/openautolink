package com.openautolink.app.cluster

import android.app.Application
import android.graphics.SurfaceTexture
import android.opengl.*
import android.view.Surface
import com.openautolink.app.secondary.*
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import org.robolectric.shadows.*
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class,shadows=[ClusterDirectVideoEglTest.Egl::class,ClusterVideoRendererTest.RecordingGl::class,ClusterVideoRendererTest.RecordingTexture::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ClusterDirectVideoEglTest {
    @Suppress("ACCIDENTAL_OVERRIDE") @Implements(EGL14::class)
    class Egl:ShadowEGL14() {
        companion object {
            private fun handle(value:Long):EGLSurface=EGLSurface::class.java.getDeclaredConstructor(java.lang.Long.TYPE).apply { isAccessible=true }.newInstance(value)
            @JvmStatic @Implementation fun eglCreatePbufferSurface(display:EGLDisplay,config:EGLConfig,attrs:IntArray,offset:Int):EGLSurface {
                calls.add("pbuffer");return handle(77)
            }
            @JvmStatic @Implementation fun eglQuerySurface(display:EGLDisplay,surface:EGLSurface,attribute:Int,out:IntArray,offset:Int):Boolean { out[offset]=if(attribute==EGL14.EGL_WIDTH)1600 else 600;return true }
            @JvmStatic @Implementation fun eglMakeCurrent(display:EGLDisplay,draw:EGLSurface,read:EGLSurface,context:EGLContext):Boolean { calls.add("current");return true }
            @JvmStatic @Implementation fun eglSwapBuffers(display:EGLDisplay,surface:EGLSurface):Boolean {
                calls.add(if(ClusterVideoRendererTest.pendingVideo)"video" else "black")
                return !(failBlack && !ClusterVideoRendererTest.pendingVideo)
            }
            @JvmStatic @Implementation fun eglDestroySurface(display:EGLDisplay,surface:EGLSurface):Boolean { calls.add("destroy-window");return true }
            @JvmStatic @Implementation fun eglDestroyContext(display:EGLDisplay,context:EGLContext)=true
            @JvmStatic @Implementation fun eglTerminate(display:EGLDisplay)=true
        }
    }
    companion object { val calls=CopyOnWriteArrayList<String>();var failBlack=false }
    private fun noHandles() {
        listOf("EGL_NO_DISPLAY" to EGLDisplay::class.java,"EGL_NO_CONTEXT" to EGLContext::class.java,"EGL_NO_SURFACE" to EGLSurface::class.java).forEach { (name,type)->
            ReflectionHelpers.setStaticField(EGL14::class.java,name,type.getDeclaredConstructor(java.lang.Long.TYPE).apply { isAccessible=true }.newInstance(0L))
        }
    }
    @Test fun actualEglPbufferKeepsDecoderAndLatchesHiddenFramesBeforeNewWindowSwap()=exercise(false)
    @Test fun failedBlackSwapRetainsOldWindowUntilPositiveParkingCleanup()=exercise(true)
    private fun exercise(fail:Boolean) {
        noHandles();calls.clear();failBlack=false;ClusterVideoRendererTest.graphicsCalls.clear();ClusterVideoRendererTest.textureListeners.clear()
        ShadowMediaCodec.addDecoder("video/avc",ShadowMediaCodec.CodecConfig(1024,1024) {input,output->output.put(input)})
        val ta=SurfaceTexture(0);val a=Surface(ta);val tb=SurfaceTexture(0);val b=Surface(tb)
        val size=PixelSize(1600,600)
        val mapping=RetainedWindowMapping(SecondaryVideoMapping.androidAuto(SecondaryRenderTarget(SecondaryOutputId("actual"),1,7,size,size.bounds,PixelRect(480,120,1120,480))))
        val d=ClusterEglVideoDriver(a,mapping,true)
        fun codec():Any?=d.javaClass.getDeclaredField("codec").apply { isAccessible=true }.get(d)
        try {
            d.initialize();d.configure(ClusterCodecConfig(mapping.codedSize,byteArrayOf(7),byteArrayOf(8)))
            val decoder=codec();assertNotNull(decoder)
            assertTrue(d.submit(ClusterEncodedFrame(1,byteArrayOf(5),1,true)));assertEquals(1L,d.poll().decoded)
            val listener=ClusterVideoRendererTest.textureListeners.single();listener.second.onFrameAvailable(listener.first)
            assertEquals(1L,d.poll().presented);calls.clear()
            if(fail) {
                failBlack=true
                assertThrows(ClusterGraphicsFailure::class.java) { d.parkWindow() }
                assertFalse(calls.contains("destroy-window"));assertSame(decoder,codec());assertTrue(a.isValid)
                failBlack=false;calls.clear()
            }
            assertTrue(d.parkWindow());assertTrue(calls.indexOf("black")<calls.indexOf("destroy-window"));assertTrue(calls.contains("pbuffer"))
            assertSame(decoder,codec());a.release();ta.release()
            assertTrue(d.submit(ClusterEncodedFrame(1,byteArrayOf(1),2,false)));assertEquals(1L,d.poll().decoded)
            listener.second.onFrameAvailable(listener.first);assertEquals(0L,d.poll().presented)
            calls.clear();assertTrue(d.resumeWindow(b));assertSame(decoder,codec());assertTrue(calls.contains("video"))
            assertEquals(1,ClusterVideoRendererTest.textureListeners.size)
            assertTrue(d.submit(ClusterEncodedFrame(1,byteArrayOf(1),3,false)));d.poll();listener.second.onFrameAvailable(listener.first);assertEquals(1L,d.poll().presented)
        } finally { failBlack=false;assertTrue(d.detach());a.release();ta.release();b.release();tb.release();ShadowMediaCodec.clearCodecs();ShadowMediaCodecList.reset() }
    }
}
