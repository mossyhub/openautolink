package com.openautolink.app.drivecard

import android.app.Application
import android.graphics.*
import com.openautolink.app.cluster.PixelRect
import com.openautolink.app.secondary.SecondaryHostGeometry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DriveCardMetersPreviewTest {
    private data class Line(val x1:Float,val y1:Float,val x2:Float,val y2:Float,val color:Int)
    private class Recording(b:Bitmap):Canvas(b) {
        val lines=mutableListOf<Line>();val words=mutableListOf<String>()
        override fun drawLine(x1:Float,y1:Float,x2:Float,y2:Float,p:Paint) {
            lines+=Line(x1,y1,x2,y2,p.color);super.drawLine(x1,y1,x2,y2,p)
        }
        override fun drawText(t:String,x:Float,y:Float,p:Paint){words+=t;super.drawText(t,x,y,p)}
    }
    private val trace=listOf(3.2,3.5,3.3,4.0,5.1,4.4,3.8,4.0,6.6,7.2,5.0,4.4,4.7,5.9)
    private fun state()=DriveCardState(nowKmPerKwh=5.2,recentKmPerKwh=4.3,powerKw=21.0,trace=trace,
        arrival=DriveCardArrival(63,true),availability=DriveCardAvailability.READY,
        powerBasis=DriveCardPowerBasis.BATTERY,batteryPercent=71,batteryApproximate=true)
    private fun geometry(w:Int=2000,h:Int=810)=SecondaryHostGeometry(w,h,PixelRect(0,0,w,h))
    @Test fun signedFlowActuallyFollowsAngledEdgeAndTwoBarsHaveDistinctMeaning() {
        for (power in listOf(-23.0,21.0)) {
            val b=Bitmap.createBitmap(2000,810,Bitmap.Config.ARGB_8888)
            try {
                val c=Recording(b);DriveCardRenderer().draw(c,geometry(),state().copy(powerKw=power))
                val color=if(power<0) Color.rgb(79,218,139) else Color.rgb(255,167,40)
                val l=c.lines.single { it.color==color }
                assertTrue("Flow follows slant rather than a second horizontal SOC bar",l.x1!=l.x2&&l.y1!=l.y2)
                assertTrue("Inflow grows up; use grows down",if(power<0)l.y2<l.y1 else l.y2>l.y1)
                assertTrue(c.words.contains("Battery now"));assertTrue(c.words.contains("≈71%"))
                assertTrue(c.words.contains("≈63% on arrival"));assertTrue(c.words.contains("recent 4.3"))
            }finally{b.recycle()}
        }
    }
    @Test fun sourceBoundWideAndCompactBeforeAfterExamples() {
        val base=state()
        val cases=listOf("use" to base,"inflow" to base.copy(nowKmPerKwh=null,powerKw=-23.0),
            "low" to base.copy(batteryPercent=19,arrival=DriveCardArrival(8,true)),
            "critical" to base.copy(batteryPercent=7,arrival=DriveCardArrival(2,true)),
            "empty" to DriveCardState(batteryPercent=0,availability=DriveCardAvailability.LEARNING),
            "full-charging" to DriveCardState(batteryPercent=100,availability=DriveCardAvailability.CHARGING),
            "unavailable" to DriveCardState(),"gaps" to base.copy(trace=listOf(3.0,4.0,Double.NaN,5.0,4.8)))
        for((w,h) in listOf(2000 to 810,1280 to 480))for((name,s)in cases)for(before in listOf(true,false)) {
            val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
            try {
                val c=Canvas(b);c.drawColor(Color.rgb(45,65,80))
                if(before)DriveCardBaselineRenderer().draw(c,geometry(w,h),s,imperial=true)
                else DriveCardRenderer().draw(c,geometry(w,h),s,imperial=true)
                val out=System.getProperty("drivecard.render.dir").orEmpty()
                if(out.isNotBlank()) {
                    val phase=if(before)"before" else "after"
                    File(out).mkdirs();val stem="$phase-$name-${w}x$h-sdk${android.os.Build.VERSION.SDK_INT}"
                    File(out,"$stem.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}
                    val crop=Bitmap.createBitmap(b,w/2,h/4,w-w/2,h-h/4)
                    try{File(out,"$stem-card.png").outputStream().use{crop.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{crop.recycle()}
                }
                assertEquals("Saved outer material/dock stays black",Color.BLACK,b.getPixel(w-5,h-5))
            }finally{b.recycle()}
        }
    }
}
