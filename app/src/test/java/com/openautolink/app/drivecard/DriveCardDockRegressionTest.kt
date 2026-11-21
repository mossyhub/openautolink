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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DriveCardDockRegressionTest {
    @Test fun directionsAreaCannotMoveOrShrinkTheCard() {
        val a=Bitmap.createBitmap(2000,810,Bitmap.Config.ARGB_8888)
        val b=Bitmap.createBitmap(2000,810,Bitmap.Config.ARGB_8888)
        val state=DriveCardState(nowKmPerKwh=5.2,recentKmPerKwh=4.8)
        DriveCardRenderer().draw(Canvas(a),SecondaryHostGeometry(2000,810,PixelRect(558,70,1694,668)),state)
        DriveCardRenderer().draw(Canvas(b),SecondaryHostGeometry(2000,810,PixelRect(191,70,1788,660)),state)
        assertTrue("Card belongs to full output, not phone Directions area",a.sameAs(b))
        assertTrue("Dock must reach the right-side edge envelope",a.getPixel(1850,640) ushr 24 != 0)
        for(y in 0 until 810) for(x in 0 until 1000) assertEquals(0,a.getPixel(x,y))
    }
}
