package com.openautolink.app.ui.settings

import android.app.Application
import android.graphics.*
import com.openautolink.app.cluster.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class LiveGridWatermarkTest {
    private val model=LiveGridGeometry(PixelSize(2000,810),PixelRect(98,65,1904,745),
        PixelRect(98,65,1904,745),PixelRect(622,165,1374,669),PixelRect(0,135,2000,810))
    private fun draw(clipped:Boolean=false,active:Boolean=true):Bitmap {
        val view=LiveTransparentGridView(RuntimeEnvironment.getApplication());view.layout(0,0,2000,810);view.geometry=model;view.active=active
        val bitmap=Bitmap.createBitmap(2000,810,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap)
        if(clipped)canvas.clipRect(400,100,1500,650)
        view.draw(canvas)
        System.getProperty("watermark.render.dir")?.let { dir ->
            File(dir).mkdirs();File(dir,"physical-grid-${RuntimeEnvironment.getApiLevel()}-${if(clipped)"clipped" else if(active)"active" else "stopped"}.png")
                .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        }
        return bitmap
    }
    private fun watermark(bitmap:Bitmap):Pair<Rect,Int> {
        var left=2000;var top=810;var right=0;var bottom=0;var maxAlpha=0;var blue=0
        for(y in 0 until bitmap.height)for(x in 0 until bitmap.width) {
            val p=bitmap.getPixel(x,y);val a=Color.alpha(p)
            // Cyan grid strokes have B-G24 or0, green safe outlines have B<G. Isolate actual store blue/A art.
            val storeBlue=Color.blue(p)>Color.green(p)+25 && Color.blue(p)>Color.red(p)+15
            val storeWhite=Color.red(p)>230 && Color.green(p)>230 && Color.blue(p)>230
            // Fully covered store pixels have the declared alpha64. Low-alpha cyan stroke fringes
            // can unpremultiply to blue-looking RGB and are not part of the logo's bounds.
            if(a == 64 && (storeBlue || storeWhite)) {
                left=minOf(left,x);top=minOf(top,y);right=maxOf(right,x+1);bottom=maxOf(bottom,y+1);maxAlpha=maxOf(maxAlpha,a)
                if(storeBlue)blue++
            }
        }
        assertTrue("Actual direct grid needs the colored canonical store blue-chain logo, not an empty/monochrome/opaque tile",blue>100)
        assertEquals("Watermark opacity is bounded to25%",64,maxAlpha)
        return Rect(left,top,right,bottom) to blue
    }
    @Test fun actualDirectGridCentersLargeCanonicalStoreArtWithTransparentBackdrop() {
        val bitmap=draw();val (bounds,_)=watermark(bitmap)
        assertEquals(1001f,bounds.exactCenterX(),5f);assertEquals(405f,bounds.exactCenterY(),5f)
        assertTrue(bounds.width()>450 && bounds.height()>350)
        assertTrue("Preserve store mark aspect, do not stretch it",bounds.width().toFloat()/bounds.height() in 1.15f..1.5f)
        assertEquals(0,bitmap.getPixel(10,10));assertEquals(0,bitmap.getPixel(1950,780))
        assertEquals("No rectangular logo backdrop",0,Color.alpha(bitmap.getPixel(bounds.left+5,bounds.top+5)))
        assertNotEquals("Cyan grid survives above the watermark",0,bitmap.getPixel(98+1806/10,200))
        val root=generateSequence(File(System.getProperty("user.dir"))){it.parentFile}.first { File(it,"app/src/main/res").exists() }
        val original=File(root,"app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png").readBytes()
        val alias=File(root,"app/src/main/res/drawable-nodpi/oal_grid_store_art.png").readBytes()
        assertArrayEquals("Grid resource reuses exact canonical store bytes, not recreated artwork or Ring0 BETA tile",original,alias)
    }
    @Test fun actualDrawableClipCentersWatermarkInVisibleGridIntersection() {
        val bitmap=draw(clipped=true);val (bounds,_)=watermark(bitmap)
        assertEquals(950f,bounds.exactCenterX(),5f);assertEquals(375f,bounds.exactCenterY(),5f)
        assertTrue(bounds.left>=400 && bounds.top>=100 && bounds.right<=1500 && bounds.bottom<=650)
        assertEquals(0,bitmap.getPixel(399,375));assertEquals(0,bitmap.getPixel(1501,375))
    }
    @Test fun stoppedGridLeavesLogoAndGridCompletelyTransparent() {
        val bitmap=draw(active=false);val pixels=IntArray(bitmap.width*bitmap.height)
        bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        assertTrue(pixels.all { it==0 })
    }
}
