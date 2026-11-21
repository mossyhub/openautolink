package com.openautolink.app.drivecard

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.openautolink.app.cluster.PixelRect
import com.openautolink.app.secondary.SecondaryHostGeometry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32,34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DriveCardMetersRenderTest {
    private fun render(s: DriveCardState, name: String): Bitmap {
        val b = Bitmap.createBitmap(2000,810,Bitmap.Config.ARGB_8888)
        DriveCardRenderer().draw(Canvas(b), SecondaryHostGeometry(2000,810,PixelRect(0,0,2000,810)), s)
        val out = System.getProperty("drivecard.render.dir").orEmpty()
        if(out.isNotBlank()) { File(out).mkdirs(); File(out,"$name-sdk${android.os.Build.VERSION.SDK_INT}.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) } }
        return b
    }
    private fun count(b: Bitmap, accept: (Int,Int,Int)->Boolean): Int {
        var n=0
        for(y in 0 until b.height) for(x in 0 until b.width) { val c=b.getPixel(x,y); if(Color.alpha(c)>240 && accept(Color.red(c),Color.green(c),Color.blue(c)))n++ }
        return n
    }
    @Test fun negativeBatteryFlowHasAVisibleGreenGaugeAndReadout() {
        val b=render(DriveCardState(powerKw=-23.0,powerBasis=DriveCardPowerBasis.BATTERY),"inflow")
        try { assertTrue("Genuine inflow must be green, not only pale-blue text",count(b){r,g,bl->r<130 && g>160 && bl<165}>30) }finally{b.recycle()}
    }
    @Test fun lowCriticalAndNormalCurrentLevelsHaveDistinctMeterColors() {
        for((name,wh) in listOf("critical" to 7000f,"low" to 14000f,"normal" to 50000f)) {
            val m=DriveCardModel();m.update(DriveCardBatteryHistory589Test.sample(wh=wh).copy(evChargeRateW=null),DriveCardBatteryHistory589Test.NOW)
            val b=render(m.snapshot(DriveCardBatteryHistory589Test.NOW),"soc-$name")
            try {
                val pixels=when(name){"critical"->count(b){r,g,bl->r>180&&g<130&&bl<130};"low"->count(b){r,g,bl->r>180&&g>160&&bl<130};else->count(b){r,g,bl->r<130&&g>160&&bl<165}}
                assertTrue("$name current SOC needs its own colored bar",pixels>20)
            }finally{b.recycle()}
        }
    }
}
