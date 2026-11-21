package com.openautolink.app.drivecard

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import com.openautolink.app.cluster.PixelRect
import com.openautolink.app.secondary.SecondaryHostGeometry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DriveCardRendererTest {
    @Test fun panelFitsPhysicalCanvasAndLeavesOemGaugesAndWarningStripsTransparent() {
        for ((w,h) in listOf(2000 to 810, 1280 to 480)) {
            val safe = PixelRect((w*.279).toInt(), (h*.086).toInt(), (w*.894).toInt(), (h*.815).toInt())
            val bitmap = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
            val geometry = SecondaryHostGeometry(w,h,safe)
            val state = DriveCardState(nowKmPerKwh = 5.2, recentKmPerKwh = 4.8, powerKw = 18.0,
                trace = listOf(3.0,4.0,3.8,5.2,4.9,6.1), arrival = DriveCardArrival(63,true))
            DriveCardRenderer().draw(Canvas(bitmap), geometry, state)
            var painted=0
            for(y in 0 until h) for(x in 0 until w) {
                val alpha=bitmap.getPixel(x,y) ushr 24
                // Content bounds are not backing bounds; the dock continues inside the
                // already-owned window. These are app-region checks, not an OEM-mask claim.
                if(alpha!=0) { painted++;assertTrue(x >= w*.5 && x < w && y >= h*.25 && y < h) }
                if(x < w/2) assertEquals("OEM left gauge must not be painted",0,alpha)
            }
            assertTrue(painted>1000)
            assertTrue("Only a bounded panel, not an opaque screen",painted<w*h/5)
            val output=System.getProperty("drivecard.render.dir").orEmpty()
            if(output.isNotEmpty()) {
                File(output).mkdirs()
                File(output,"drivecard-${w}x${h}-sdk${android.os.Build.VERSION.SDK_INT}.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            }
            if(output.isNotEmpty()) {
                // Neutral synthetic app-canvas backdrop makes the actual alpha/black slope visible.
                // This is a host fixture, NOT a map, vehicle photo or actual vehicle telemetry.
                val preview=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                val canvas=Canvas(preview)
                canvas.drawColor(android.graphics.Color.rgb(45,65,80))
                canvas.drawBitmap(bitmap,0f,0f,null)
                File(output,"canvas-${w}x${h}-sdk${android.os.Build.VERSION.SDK_INT}.png").outputStream().use {preview.compress(Bitmap.CompressFormat.PNG,100,it)}
                preview.recycle()
            }
            bitmap.recycle()
        }
    }
    @Test fun phoneSafeRegionCannotAuthorizePaintingOemGaugeSide() {
        val image=Bitmap.createBitmap(2000,810,Bitmap.Config.ARGB_8888)
        DriveCardRenderer().draw(Canvas(image),SecondaryHostGeometry(2000,810,PixelRect(0,0,800,700)),DriveCardState(recentKmPerKwh=5.0))
        for(y in 0 until 810) for(x in 0 until 1000) assertEquals("Directions area cannot move card over left instruments",0,image.getPixel(x,y))
        image.recycle()
    }
    @Test fun emptyOrTinySafeAreaNeverOverdrawsBounds() {
        val image=Bitmap.createBitmap(100,50,Bitmap.Config.ARGB_8888)
        DriveCardRenderer().draw(Canvas(image),SecondaryHostGeometry(100,50,PixelRect(90,40,100,50)),DriveCardState())
        for(y in 0 until 50) for(x in 0 until 90) assertEquals(0,image.getPixel(x,y))
        image.recycle()
    }
}
