package com.openautolink.app.media

import android.app.Application
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.support.v4.media.session.MediaSessionCompat
import android.view.Surface
import com.openautolink.app.carplay.*
import com.openautolink.carplaylab.media.ProjectionReceiver
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Actual installed callback -> host -> receiver -> sink -> encrypted event writer.
 * Synthetic authenticated-owner fixture, not a PairVerify or physical-phone claim. */
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[32,34],manifest=Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PhoneMediaNativeIntegrationTest {
    private fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply { isAccessible=true }
    @After fun cleanup() { OalMediaSessionManager.instanceOrNull()?.release();unmockkAll() }
    @Test fun liveItemNativePlayAndStopReachEncryptedWriterAcrossEveryPublicationMode() {
        val app=RuntimeEnvironment.getApplication()
        val preferences=mockk<com.openautolink.app.data.AppPreferences>()
        mockkObject(com.openautolink.app.data.AppPreferences.Companion)
        every { com.openautolink.app.data.AppPreferences.getInstance(any()) } returns preferences
        every { preferences.clusterNavigation } returns kotlinx.coroutines.flow.flowOf(false)
        every { preferences.distanceUnits } returns kotlinx.coroutines.flow.flowOf("auto")
        val manager=OalMediaSessionManager.getInstance(app);manager.initialize()
        val token=manager.getSessionToken()
        val compat=field(manager,"mediaSession").get(manager) as MediaSessionCompat
        val impl=field(compat,"mImpl").get(compat)
        val callback=impl.javaClass.methods.first {it.name=="getCallback"}.apply {isAccessible=true}.invoke(impl) as MediaSessionCompat.Callback
        val caps=MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,1280,720))
            .setIsEncoder(false).setColorFormats(intArrayOf(2130708361))
            .setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel().apply {profile=MediaCodecInfo.CodecProfileLevel.AVCProfileHigh;level=MediaCodecInfo.CodecProfileLevel.AVCLevel52})).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("media.avc").setIsEncoder(false).setCapabilities(caps).build())
        for(mode in CarPlayHostMediaMode.entries) {
            val surface=mockk<Surface>();every { surface.isValid } returns true
            val receiver=ProjectionReceiver(app,"02:00:00:00:00:01",surface,mockk<MfiAuthenticator>(relaxed=true),wired=true,onStatus={})
            val session=AirPlaySession(Socket(),AirPlayConfig("test","id","mac","950.7.1",AirPlayDisplayConfig(800,480)),AirPlayIdentity.generate(),PairingStore(),null,object:AirPlaySessionListener {},object:AirPlayMediaHandler {})
            val lease=CarPlayHostMediaLease(mode);manager.beginCarPlayHostMedia(lease)
            val host=OalCarPlayMetadataHost(app,attemptContext=CarPlayAttemptContext(lease)) { receiver.mediaCommand(session,it) }
            try {
                ServerSocket(0).use { server -> Socket("127.0.0.1",server.localPort).use { phone -> server.accept().use { event ->
                    val key=ByteArray(32) {(it+1).toByte()};val peer=ControlCipher(key,key)
                    field(session,"eventSocket").set(session,event);field(session,"eventCipher").set(session,ControlCipher(key,key))
                    (field(session,"authenticated").get(session) as java.util.concurrent.atomic.AtomicBoolean).set(true)
                    @Suppress("UNCHECKED_CAST") val sessions=field(receiver,"sessions").get(receiver) as MutableSet<AirPlaySession>
                    sessions.add(session);field(receiver,"active").set(receiver,session);field(receiver,"runtimeOwner").set(receiver,session);field(receiver,"runtimeReady").set(receiver,true)
                    val input=field(receiver,"input").get(receiver) as ExecutorService
                    assertTrue(host.start());assertFalse("metadata claim is not runtime readiness",manager.browseState().ready)
                    host.mediaAvailability(true)
                    assertTrue(manager.browseState().ready)
                    assertEquals("oal_phone_current",manager.browseChildren("oal_phone_audio").single().mediaId)
                    assertEquals(token,manager.getSessionToken())
                    shadowOf(app.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                    phone.soTimeout=1500
                    fun readIndex(expected:Int) {
                        val stream=phone.getInputStream();val length=stream.readNBytes(2);assertEquals(2,length.size)
                        val n=(length[0].toInt() and 255) or ((length[1].toInt() and 255) shl 8)
                        val bytes=peer.decrypt(length+stream.readNBytes(n+16)).data
                        val offset=bytes.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n")+4
                        val command=BplistCodec.decode(bytes.copyOfRange(offset,bytes.size)) as Map<*,*>
                        assertEquals("hidSendReport",command["type"])
                        assertArrayEquals(byteArrayOf(expected.toByte()),command["hidReport"] as ByteArray)
                    }
                    callback.onPlayFromMediaId("invalid",null);callback.onPlayFromMediaId(null,null)
                    callback.onPlayFromMediaId("oal_phone_current",null);input.submit {}.get(3,TimeUnit.SECONDS)
                    readIndex(1);readIndex(0)
                    callback.onPause();callback.onStop();callback.onSkipToNext();callback.onSkipToPrevious();input.submit {}.get(3,TimeUnit.SECONDS)
                    for(index in listOf(2,2,4,5)) {readIndex(index);readIndex(0)}
                    host.publish(com.openautolink.carplaylab.metadata.CarPlayMediaMetadata(playing=true),null,null)
                    assertEquals(true,manager.diagnosticSnapshot().phonePlaying)
                    assertEquals(mode!=CarPlayHostMediaMode.INACTIVE,manager.diagnosticSnapshot().sessionActive)
                    host.mediaAvailability(false);callback.onPlayFromMediaId("oal_phone_current",null);input.submit {}.get(3,TimeUnit.SECONDS)
                    assertTrue(manager.browseChildren("oal_phone_audio").isEmpty())
                    phone.soTimeout=100
                    assertTrue(runCatching {phone.getInputStream().read()}.exceptionOrNull() is java.net.SocketTimeoutException)
                } } }
            } finally {host.close();receiver.close();assertTrue(receiver.awaitClosed(3000));session.close();manager.finishCarPlayHostMedia(lease)}
        }
    }
}
