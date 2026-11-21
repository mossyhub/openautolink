package com.openautolink.app.drivecard

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.openautolink.app.cluster.PixelRect
import com.openautolink.app.secondary.SecondaryHostGeometry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** Renderer-only constructed state; never presented as sampled vehicle telemetry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DriveCardDockHierarchy589Test {
    private data class Word(val value:String,val x:Float,val y:Float,val size:Float,val right:Float)
    private class Recording(bitmap:Bitmap):Canvas(bitmap) {
        val words=mutableListOf<Word>()
        override fun drawText(text:String,x:Float,y:Float,paint:Paint) {
            words+=Word(text,x,y,paint.textSize,x+paint.measureText(text))
            super.drawText(text,x,y,paint)
        }
    }
    @Test fun powerUnitIsGroupedArrivalUsesAvailableDockHeightAndRealFillFades() {
        val image=Bitmap.createBitmap(2000,810,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Recording(image)
            DriveCardRenderer().draw(canvas,SecondaryHostGeometry(2000,810,PixelRect(192,71,1788,660)),
                DriveCardState(availability=DriveCardAvailability.READY,nowKmPerKwh=4.3,
                    powerKw=21.0,powerBasis=DriveCardPowerBasis.BATTERY,
                    trace=listOf(4.3,4.3,4.3),arrival=DriveCardArrival(63,true)))
            val number=canvas.words.single{it.value=="21"}
            val unit=canvas.words.single{it.value=="kW"}
            assertTrue("Power number and unit must form one glanceable group",unit.x-number.right in 0f..12f)
            val arrival=canvas.words.single{it.value.contains("on arrival")}
            assertTrue("Arrival must use available bottom backing rather than tiny fixed footnote",arrival.y>250f)
            assertTrue("Arrival is a readable secondary instrument",arrival.size>=28f)
            val upper=image.getPixel(1750,490);val lower=image.getPixel(1750,529)
            assertTrue("Genuine constant trace fill must fade, not form a solid blue block",Color.blue(upper)>Color.blue(lower)+8)
        } finally {image.recycle()}
    }
}
