package com.openautolink.carplaylab.media

import android.app.Application
import android.view.Surface
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.*
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],manifest=Config.NONE,application=Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayClusterHevcReceiverTest {
    private val hvcc get()=java.util.Base64.getDecoder().decode("AQFgAAAAkAAAAAAAePAA/P34+AAADwOgAAEAGEABDAH//wFgAAADAJAAAAMAAAMAeJWYCaEAAQAsQgEBAWAAAAMAkAAAAwAAAwB4oAPogDMfJllZpJMrwFoCAAADAAIAAAMAPBCiAAEAB0QBwXK0YkA=")
    private fun field(owner:Any,name:String):Any=owner.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(owner)
    @Test fun encodedHevcBypassesAvcOnlyLabDecoderAndRejectsBadReplacementLocally() = exercise(true)
    @Test fun deniedGlobalHevcNeverReachesOptionalConsumerOrTearsDownMain() = exercise(false)
    @Test fun insufficientAvcInstanceBudgetWithholdsOnlyOptionalOutputAndKeepsMainHevc() = exercise(true,false)
    private fun exercise(hevc:Boolean,avc:Boolean=true) {
        val events=mutableListOf<String>()
        val listener=object:CarPlayClusterVideoListener {
            override fun onStreamActive(streamEpoch:Long,active:Boolean) { events+="$streamEpoch:$active" }
            override fun onCodecConfig(streamEpoch:Long,codecData:ByteArray) { events+="config";assertArrayEquals(hvcc,codecData) }
            override fun onFrame(streamEpoch:Long,naluBytes:ByteArray) { events+="frame" }
        }
        val cluster=CarPlayClusterVideoConfiguration.create(AirPlayInsets(),AirPlayInsets(),CarPlayVideoSelection(2000,810,30,"cluster.avc","cluster.hevc"),2000,810)
        val receiver=ProjectionReceiver(RuntimeEnvironment.getApplication(),"02:00:00:00:00:01",mock(Surface::class.java),mock(MfiAuthenticator::class.java),
            display=CarPlayDisplayPreferences(preferHevc=true,qualifiedVideoSelection=CarPlayVideoSelection(1280,720,30,"main.avc","main.hevc")),wired=true,
            clusterVideoConfiguration=cluster,clusterVideoListener=listener,videoDecoderBudgetProbe=VideoDecoderBudgetProbe { codec,_,_ -> if(codec==VideoCodec.H264) avc else hevc },onStatus={})
        try {
            val config=receiver.javaClass.getDeclaredMethod("sessionConfig").apply { isAccessible=true }.invoke(receiver) as AirPlayConfig
            assertEquals(hevc,config.hevc)
            assertEquals(hevc,receiver.displayConfiguration.preferHevc)
            val media=field(receiver,"mediaSink") as MediaSink
            if(!avc) {
                assertNull(config.cluster)
                media.onVideoCodec(110,VideoCodec.H265)
                return
            }
            media.onScreenStreamActive(111,true)
            media.onVideoCodec(111,VideoCodec.H265)
            media.onVideoConfig(111,hvcc);media.onVideoFrame(111,byteArrayOf(0,0,0,1,0x26,1))
            if(hevc) {
                assertEquals(listOf("1:true","config","frame"),events)
                media.onVideoConfig(111,hvcc.apply { this[12]=123 })
                media.onVideoFrame(111,byteArrayOf(0,0,0,1,0x26,1))
                assertEquals(listOf("1:true","config","frame","1:false"),events)
            } else assertEquals(listOf("1:true","1:false"),events)
            media.onVideoCodec(110,VideoCodec.H264) // Main is still usable.
            val secondary=field(field(receiver,"sink"),"clusterVideo")
            val latest=field(secondary,"latest") as java.util.concurrent.atomic.AtomicReference<*>
            assertNull("Encoded output must never initialize the lab's second decoder",latest.get())
        } finally { receiver.close();assertTrue(receiver.awaitClosed(5000)) }
    }
}
