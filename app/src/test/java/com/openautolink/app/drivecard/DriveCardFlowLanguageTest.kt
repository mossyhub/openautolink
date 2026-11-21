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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DriveCardFlowLanguageTest {
    private class Words(b:Bitmap):Canvas(b) {
        val words=mutableListOf<String>()
        override fun drawText(t:String,x:Float,y:Float,p:Paint){words+=t;super.drawText(t,x,y,p)}
    }
    private fun verify(power:Double,basis:DriveCardPowerBasis,number:String,label:String) {
        val b=Bitmap.createBitmap(2000,810,Bitmap.Config.ARGB_8888)
        try {
            val c=Words(b)
            DriveCardRenderer().draw(c,SecondaryHostGeometry(2000,810,PixelRect(0,0,2000,810)),
                DriveCardState(powerKw=power,powerBasis=basis))
            assertFalse("Direction belongs to gauge/color, not literal words: ${c.words}",
                c.words.any{it in setOf("Inflow","Use","Return","Neutral")})
            assertTrue("Preserve actual signed kW",c.words.contains(number)&&c.words.contains("kW"))
            assertTrue("Motor and battery flow remain distinct quantities",c.words.contains(label))
        }finally{b.recycle()}
    }
    @Test fun batteryInflowHasNoDirectionWord()=verify(-23.0,DriveCardPowerBasis.BATTERY,"−23","Battery")
    @Test fun batteryUseHasNoDirectionWord()=verify(21.0,DriveCardPowerBasis.BATTERY,"21","Battery")
    @Test fun zeroHasNoDirectionWord()=verify(0.0,DriveCardPowerBasis.BATTERY,"0","Battery")
    @Test fun motorReturnHasNoDirectionWord()=verify(-8.0,DriveCardPowerBasis.MOTOR,"−8.0","Motor")
}
