package com.shilapi.xcertplay.media

import android.Manifest
import android.app.Application
import android.media.*
import android.view.Surface
import com.openautolink.carplaylab.media.*
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.*
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real receiver qualification, TCP /info session, its admission snapshot, owned sink and UDP uplink.
 * Only Android capture/codec platform edges are deterministic shadows; no qualification helper mocks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class,manifest=Config.NONE,
    shadows=[CarPlayIndependentMicrophoneReceiverTest.AllRecorder::class,
        AndroidMicrophoneExperimentQualificationTest.ObservedCodec::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayIndependentMicrophoneReceiverTest {
    @Implements(AudioRecord::class)
    class AllRecorder {
        companion object {
            var starts=0
            var minimumCalls=0
            var rejectStereo=false
            @JvmStatic @Implementation fun native_get_min_buff_size(rate: Int, channels: Int, format: Int): Int {
                minimumCalls++
                return if (rejectStereo && channels==2) -2 else 1024
            }
        }
        @Implementation fun native_start(event: Int, session: Int): Int { starts++; return 0 }
        @Implementation fun native_read_in_byte_array(b: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
            if (AndroidMicrophoneExperimentQualificationTest.captureReads.isNotEmpty()) { Thread.sleep(5); return 0 }
            AndroidMicrophoneExperimentQualificationTest.captureReads.add(size)
            val pcm=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            for (i in offset until offset+size step 2) pcm.putShort(i,1000.toShort())
            return size
        }
    }
    private fun authenticate(session: AirPlaySession, store: PairingStore) {
        val controller = AirPlayCrypto.ed25519Generate(); val id = "synthetic-pcm-controller".toByteArray()
        store.save(id.toString(Charsets.UTF_8),controller.publicKey)
        val ephemeral = AirPlayCrypto.x25519Generate()
        val m2 = Tlv8Codec.decode(session.pairVerify.handle(tlv(6 to byteArrayOf(1),3 to ephemeral.publicKey)))
        val remote = m2.getValue(3)
        val shared = AirPlayCrypto.x25519Shared(ephemeral.privateKey,remote)
        val key = AirPlayCrypto.hkdfSha512(shared,"Pair-Verify-Encrypt-Salt".toByteArray(),"Pair-Verify-Encrypt-Info".toByteArray())
        val sig = AirPlayCrypto.ed25519Sign(controller.privateKey,ephemeral.publicKey+id+remote)
        session.pairVerify.handle(tlv(6 to byteArrayOf(3),5 to AirPlayCrypto.chachaSeal(key,
            AirPlayCrypto.nonceLabel("PV-Msg03"),tlv(1 to id,10 to sig))))
        assertTrue(session.finishControlAuthentication())
    }
    private fun tlv(vararg p: Pair<Int,ByteArray>) = Tlv8Codec.encode(p.map { Tlv8Item(it.first,it.second) })
    private fun request(session: AirPlaySession, method: String, path: String, body: Map<String,Any?>? = null): RtspMessage.Response {
        val handle = session.javaClass.getDeclaredMethod("handle",RtspMessage.Request::class.java).apply { isAccessible=true }
        return handle.invoke(session,RtspMessage.Request(method,path,"RTSP/1.0",emptyMap(),
            body?.let { BplistCodec.encode(it) } ?: ByteArray(0))) as RtspMessage.Response
    }
    private fun arm(history: String, live: String) {
        if (history == "PRODUCTION") { CarPlayMicrophoneExperiments.disarm(); return }
        if (history == "AA_STYLE") {
            CarPlayMicrophoneExperiments.arm(CarPlayMicrophoneExperimentProfile(source=MicrophoneSource.VOICE_RECOGNITION, rate=CarPlayMicrophoneCaptureRate.HZ_16000, aaStyle=true))
            return
        }
        val pkg="com.shilapi.xcertplay.media."
        val selection=runCatching { Class.forName(pkg+"CarPlayMicrophoneIndependentSelection") }.getOrNull()
        assertNotNull("Independent receiver-frozen history/live selection must exist",selection)
        val h=Class.forName(pkg+"CarPlayMicrophoneQualificationHistory")
        val l=Class.forName(pkg+"CarPlayMicrophoneSpeechCapture")
        val request=selection!!.getConstructor(h,l).newInstance(h.enumConstants.single { it.toString()==history },l.enumConstants.single { it.toString()==live })
        val profile=CarPlayMicrophoneExperimentProfile()
        val constructor=profile.javaClass.constructors.single { it.parameterCount==7 }
        val frozen=constructor.newInstance(null,profile.rate,profile.channels,profile.selection,profile.codec,request,false) as CarPlayMicrophoneExperimentProfile
        CarPlayMicrophoneExperiments.arm(frozen)
    }
    @Test @Config(sdk=[32,34]) fun omittedManagedAdmissionWithholdsInputWithoutExperimentPreflightOrProbe() {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        AllRecorder.minimumCalls=0
        arm("AA_STYLE","AA_STYLE")
        val surface=mock(Surface::class.java);`when`(surface.isValid).thenReturn(true)
        val receiver=ProjectionReceiver(app,"00:11:22:33:44:55",surface,mock(MfiAuthenticator::class.java),
            display=CarPlayDisplayPreferences(qualifiedVideoSelection=CarPlayVideoSelection(1280,720,30,"avc.exact")),onStatus={})
        try {
            assertEquals("Managed dependencies fail closed at actual probes",0,AllRecorder.minimumCalls)
            val capability=receiver.javaClass.getDeclaredField("microphoneCapability").apply { isAccessible=true }.get(receiver) as MicrophoneCapability
            assertFalse(capability.available)
        } finally { CarPlayMicrophoneExperiments.disarm(); receiver.close(); assertTrue(receiver.awaitClosed(5000)) }
    }
    @Test @Config(sdk=[32,34]) fun productionQualifiedReceiverPreservesAcceptedArmHistoryAndAuthenticatedUplink() = receiver("PRODUCTION","AA_STYLE",9,640)
    @Test @Config(sdk=[32,34]) fun aaStyleQualifiedReceiverConvertsAuthenticatedUplink() = receiver("AA_STYLE","AA_STYLE",9,640)
    @Test fun normalHistory24() = receiver("NORMAL","VR24_MONO",5,960)
    @Test fun normalHistory48() = receiver("NORMAL","VR48_TWO",5,3840)
    @Test fun manualHistory48() = receiver("MANUAL_VR48_TWO","VR48_TWO",9,3840)
    @Test fun impossibleManual24IsRejectedBeforeAnyRecorderOrPairingAllocation() {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        CarPlayMicrophoneExperiments.disarm()
        AllRecorder.minimumCalls=0
        val surface=mock(Surface::class.java);`when`(surface.isValid).thenReturn(true)
        arm("MANUAL_VR48_TWO","VR24_MONO")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                ProjectionReceiver(app,"00:11:22:33:44:55",surface,mock(MfiAuthenticator::class.java),
                    display=CarPlayDisplayPreferences(qualifiedVideoSelection=CarPlayVideoSelection(1280,720,30,"avc.exact")),captureAdmission={true},onStatus={})
            }
            assertEquals(0,AllRecorder.minimumCalls)
            MicrophoneCaptureFence.tryDiagnostic()!!.release()
        } finally { CarPlayMicrophoneExperiments.disarm() }
    }
    @Test fun unsupportedNormal48FailsWithoutOpeningFallbackLiveRecorder() {
        AllRecorder.rejectStereo=true
        try {
            val failure=assertThrows(IllegalArgumentException::class.java) { receiver("NORMAL","VR48_TWO",5,3840) }
            assertEquals("Independent capture not qualified by selected history",failure.message)
            assertEquals(0,AllRecorder.starts)
            MicrophoneCaptureFence.tryDiagnostic()!!.release()
        } finally { AllRecorder.rejectStereo=false;CarPlayMicrophoneExperiments.disarm() }
    }
    @Test fun independentManualEncoderDoesNotReloadNormalRecorderHistory() = encoderDoesNotReloadNormalHistory(false)
    @Test @Config(sdk=[32,34]) fun aaStyleQualifiedOpusUsesConvertedFortyEightKAndRetires() = encoderDoesNotReloadNormalHistory(true)
    private fun encoderDoesNotReloadNormalHistory(aaStyle:Boolean) {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val device=AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_MIC).build()
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(device,"mPort"),"mRole",1)
        shadowOf(app.getSystemService(AudioManager::class.java)).setInputDevices(listOf(device))
        val caps=MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(OpusEncoder.format(64000)).setIsEncoder(true).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("qualified.opus.encoder")
            .setIsEncoder(true).setCapabilities(caps).build())
        // Fresh context invalidates legacy recorder cache, just like a first manual receiver.
        AndroidMicrophoneSupport.initialize(object : android.content.ContextWrapper(app) {
            override fun getApplicationContext(): android.content.Context = this
        })
        val profile=if(aaStyle) CarPlayMicrophoneExperimentProfile(source=MicrophoneSource.VOICE_RECOGNITION,rate=CarPlayMicrophoneCaptureRate.HZ_16000,aaStyle=true) else CarPlayMicrophoneIndependentSelection(
            CarPlayMicrophoneQualificationHistory.MANUAL_VR48_TWO,CarPlayMicrophoneSpeechCapture.VR48_TWO).captureProfile()
        val capability=AndroidMicrophoneSupport.experimentCapability(app,profile)
        assertTrue(0x20000000L in capability.bits("speechrecognition"))
        val convertedPcm=java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        ShadowMediaCodec.addEncoder("qualified.opus.encoder",ShadowMediaCodec.CodecConfig(4096,1275) { input, out ->
            convertedPcm.add(ByteArray(input.remaining()).also { input.get(it) })
            out.put(byteArrayOf(0x98.toByte(),0x33))
        })
        AndroidMicrophoneExperimentQualificationTest.captureReads.clear()
        val lines=java.util.concurrent.CopyOnWriteArrayList<String>()
        val ledger=CaptureAcquisitionLedger(true)
        ledger.bind(java.util.concurrent.Executor { it.run() },lines::add)
        val key=ByteArray(32) { 1 }
        DatagramSocket(0,InetAddress.getLoopbackAddress()).use { udp ->
            udp.soTimeout=3000
            val config=MicrophoneSupport.negotiate(capability,100,"speechrecognition",0x20000000L,
                udp.localPort,480,udp.localAddress,key)!!
            val uplink=MicrophoneUplink(config,qualifiedCapability=capability,captureLedger=ledger,speechRecognitionProfile=profile,captureAdmission=MicrophoneCaptureAdmission {true})
            try {
                CaptureAcquisitionLedger.observe(ledger) {
                    // Worker does not inherit this thread-local: cache boundary is checked below too.
                    uplink.start()
                }
                val packet=DatagramPacket(ByteArray(4096),4096);udp.receive(packet)
                val bytes=packet.data.copyOf(packet.length)
                assertArrayEquals(byteArrayOf(0x98.toByte(),0x33),AirPlayCrypto.chachaOpen(key,
                    ByteArray(4)+bytes.takeLast(8).toByteArray(),bytes.copyOfRange(12,bytes.size-8),bytes.copyOfRange(4,12)))
                assertEquals(1920,convertedPcm.single().size)
                val samples=ByteBuffer.wrap(convertedPcm.single()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                while(samples.hasRemaining()) assertEquals(1000,samples.get().toInt())
                assertNull("Live independent uplink must not initialize normal recorder qualification",ReflectionHelpers.getField<Any?>(AndroidMicrophoneSupport,"configured"))
            } finally { uplink.close(); assertTrue(uplink.isRetired) }
        }
    }
    @Test
    @Config(shadows=[com.shilapi.xcertplay.media.capture.StalledReleaseRecorder::class],
        instrumentedPackages=["com.shilapi.xcertplay.media.capture"])
    fun independentCancellationRetainsPhysicalLeaseUntilReleaseReturns() = heldRelease(false)
    @Test @Config(sdk=[32,34],shadows=[com.shilapi.xcertplay.media.capture.StalledReleaseRecorder::class],instrumentedPackages=["com.shilapi.xcertplay.media.capture"])
    fun aaStyleCancellationRetainsPhysicalLeaseUntilActualRelease() = heldRelease(true)
    private fun heldRelease(aaStyle:Boolean) {
        val fixture=com.shilapi.xcertplay.media.capture.StalledReleaseRecorder.install()
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        AndroidMicrophoneSupport.initialize(app)
        val cap=MicrophoneCapability.of(mapOf("speechrecognition" to setOf(0x40L)))
        val profile=if(aaStyle) CarPlayMicrophoneExperimentProfile(source=MicrophoneSource.VOICE_RECOGNITION,rate=CarPlayMicrophoneCaptureRate.HZ_16000,aaStyle=true) else CarPlayMicrophoneIndependentSelection().captureProfile()
        DatagramSocket(0,InetAddress.getLoopbackAddress()).use { udp ->
            fun config()=MicrophoneSupport.negotiate(cap,100,"speechrecognition",0x40L,
                udp.localPort,480,udp.localAddress,ByteArray(32) { 1 })!!
            val uplink=MicrophoneUplink(config(),qualifiedCapability=cap,speechRecognitionProfile=profile,captureAdmission=MicrophoneCaptureAdmission {true})
            try {
                uplink.start();assertTrue(fixture.reading.await(3,java.util.concurrent.TimeUnit.SECONDS))
                assertFalse(uplink.isRetired); assertNull(MicrophoneCaptureFence.tryDiagnostic())
                uplink.close();assertTrue(fixture.releasing.await(3,java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(AudioRecord.STATE_INITIALIZED,fixture.record!!.state)
                assertEquals(0,fixture.nativeReleases.get())
                assertFalse(uplink.isRetired)
                assertNull(MicrophoneCaptureFence.tryDiagnostic())
                val successor=MicrophoneUplink(config(),qualifiedCapability=cap,speechRecognitionProfile=profile,captureAdmission=MicrophoneCaptureAdmission {true})
                successor.start();assertTrue(successor.isClosed);assertTrue(successor.isRetired)
                fixture.allowRelease.countDown()
                val end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                while(!uplink.isRetired && System.nanoTime()<end) Thread.sleep(5)
                assertTrue(uplink.isRetired)
                assertEquals(0L,fixture.realReleaseCompleted.count)
                assertEquals(1,fixture.nativeReleases.get())
                assertEquals(AudioRecord.STATE_UNINITIALIZED,fixture.record!!.state)
                MicrophoneCaptureFence.tryDiagnostic()!!.release()
            } finally { fixture.allowRelease.countDown();uplink.close();fixture.close() }
        }
    }
    private fun receiver(history: String, live: String, count: Int, captureBytes: Int) {

        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val device=AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_MIC).build()
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(device,"mPort"),"mRole",1)
        shadowOf(app.getSystemService(AudioManager::class.java)).setInputDevices(listOf(device))
        fun addCodec(name: String, encoder: Boolean) {
            val caps=MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                .setMediaFormat(OpusEncoder.format(64000)).setIsEncoder(encoder).build()
            ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName(name)
                .setIsEncoder(encoder).setCapabilities(caps).build())
        }
        addCodec("qualified.opus.encoder",true)
        val actualOutputs=AndroidAudioDecoderSupport.availableBits.toSet()
        assertTrue(actualOutputs.intersect(MicrophoneSupport.opusBits).isEmpty())
        AllRecorder.starts=0
        File(app.noBackupFilesDir,"pairing-probe").deleteRecursively()
        arm(history,live)
        val surface=mock(Surface::class.java);`when`(surface.isValid).thenReturn(true)
        val ledgerLines=java.util.concurrent.CopyOnWriteArrayList<String>()
        val receiver=ProjectionReceiver(app,"00:11:22:33:44:55",surface,mock(MfiAuthenticator::class.java),
            display=CarPlayDisplayPreferences(qualifiedVideoSelection=CarPlayVideoSelection(1280,720,30,"avc.exact")),captureAdmission={true},onStatus=ledgerLines::add)
        assertEquals("Qualification must never start recording",0,AllRecorder.starts)
        fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(owner)
        try {
            assertEquals(if (history=="NORMAL") null else actualOutputs,field(receiver,"outputAudioFormats"))
            val capability=field(receiver,"microphoneCapability") as MicrophoneCapability
            assertEquals("Preserve genuine normal offers; manual intersects actual outputs",if (history=="NORMAL") setOf(0x40L,0x20000000L) else setOf(0x40L),capability.bits("speechrecognition"))
            if (history=="MANUAL_VR48_TWO") assertTrue("Manual VR48 history cannot admit automatic MIC24 default input it never qualified",capability.bits("default").isEmpty())
            if (history in setOf("AA_STYLE","PRODUCTION")) assertTrue((field(receiver,"microphoneExperiment") as CarPlayMicrophoneExperimentProfile).aaStyle)
            else assertNull(field(receiver,"microphoneExperiment"))
            CarPlayMicrophoneExperiments.disarm() // future owners only
            val ip=NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
                .first { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isMulticastAddress && !it.isLinkLocalAddress }
            val peer=ServerSocket().use { probe ->
                probe.bind(InetSocketAddress(InetAddress.getByName("::"),0))
                Socket().use { client ->
                    client.bind(InetSocketAddress(ip,0));client.connect(InetSocketAddress(ip,probe.localPort))
                    probe.accept().use { it.inetAddress }
                }
            }
            receiver.setRuntimeIdentification(Iap2IdentificationConfig(name="test",modelIdentifier="test",manufacturer="test",
                serialNumber="test",firmwareVersion="test",hardwareVersion="test",
                wireless=Iap2WirelessIdentification("00:11:22:33:44:55","test")))
            receiver.start(peer.hostAddress!!)
            Socket().apply { bind(InetSocketAddress(ip,0));connect(InetSocketAddress(ip,receiver.port));soTimeout=5000 }.use { phone ->
                phone.getOutputStream().apply { write("GET /info RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n".toByteArray());flush() }
                val input=phone.getInputStream();val header=ByteArrayOutputStream()
                while (!header.toByteArray().takeLast(4).toByteArray().contentEquals("\r\n\r\n".toByteArray())) {
                    val b=input.read();check(b>=0);header.write(b);check(header.size()<8192)
                }
                val lines=header.toString("US-ASCII").split("\r\n")
                assertTrue(lines.first().contains("200"))
                val length=lines.first { it.startsWith("Content-Length:") }.substringAfter(':').trim().toInt()
                val body=ByteArray(length);var n=0
                while(n<length) { val count=input.read(body,n,length-n);check(count>0);n+=count }
                val info=BplistCodec.decode(body) as Map<*,*>
                val rows=(info["audioFormats"] as List<*>).map { it as Map<*,*> }
                assertEquals(AudioOutputSupport.wirelessRows(actualOutputs).map { it["audioOutputFormats"] },rows.map { it["audioOutputFormats"] })
                val siriRow=rows.single { (it["type"] as? Number)?.toInt()==100 && it["audioType"]=="speechRecognition" }
                assertEquals("Actual /info common admitted LPCM wire, not a fabricated matching offer",0x40L,(siriRow["audioInputFormats"] as Number).toLong())
                val session=(field(receiver,"allSessions") as Set<*>).single() as AirPlaySession
                // Robolectric's accepted Socket exposes a synthetic 192.168.0.10 despite real TCP IO.
                // Correct only this OS boundary to the actual loopback UDP peer; retain session auth/admission.
                val control=spy(field(session,"socket") as Socket)
                `when`(control.remoteSocketAddress).thenReturn(InetSocketAddress(InetAddress.getLoopbackAddress(),12345))
                ReflectionHelpers.setField(session,"socket",control)
                assertEquals(if (history=="NORMAL") null else actualOutputs,field(session,"outputAudioFormats"))
                assertSame(capability,session.offeredMicrophone)
                assertEquals(capability.bits("speechrecognition"),session.offeredMicrophone.bits("speechrecognition"))
                authenticate(session,field(session,"pairings") as PairingStore)
                AndroidMicrophoneExperimentQualificationTest.captureReads.clear()
                DatagramSocket(0,InetAddress.getLoopbackAddress()).use { udp ->
                    udp.soTimeout=3000
                    if (history=="MANUAL_VR48_TWO") {
                        val defaultReply=request(session,"SETUP","/",mapOf("streams" to listOf(mapOf(
                            "type" to 100,"audioType" to "default","audioFormat" to 0x40L,
                            "spf" to 480,"streamConnectionID" to 7654L,"dataPort" to udp.localPort))))
                        assertTrue("Unqualified automatic default input is rejected by real session SETUP",((BplistCodec.decode(defaultReply.body) as Map<*,*>)["streams"] as List<*>).isEmpty())
                        assertEquals(0,AllRecorder.starts)
                    }
                    val rejected=request(session,"SETUP","/",mapOf("streams" to listOf(mapOf(
                        "type" to 100,"audioType" to "speechRecognition","audioFormat" to 0x20000000L,
                        "spf" to 480,"streamConnectionID" to 7654L,"dataPort" to udp.localPort))))
                    assertTrue("Unadvertised wire must not allocate or silently fall back",((BplistCodec.decode(rejected.body) as Map<*,*>)["streams"] as List<*>).isEmpty())
                    assertEquals(0,AllRecorder.starts)
                    val reply=request(session,"SETUP","/",mapOf("streams" to listOf(mapOf(
                        "type" to 100,"audioType" to "speechRecognition","audioFormat" to 0x40L,
                        "spf" to 480,"streamConnectionID" to 7654L,"dataPort" to udp.localPort))))
                    assertEquals(200,reply.status ?: 200)
                    val accepted=((BplistCodec.decode(reply.body) as Map<*,*>)["streams"] as List<*>).single() as Map<*,*>
                    assertTrue("SETUP alone does not allocate a live recorder",AndroidMicrophoneExperimentQualificationTest.captureReads.isEmpty())
                    val outKey=AirPlayCrypto.hkdfSha512(session.sharedSecret!!,"DataStream-Salt7654".toByteArray(),
                        "DataStream-Output-Encryption-Key".toByteArray(),32)
                    val downlink=MicrophonePacketizer.sealPacket(outKey,MicrophoneCounters(),ByteArray(960),480)
                    DatagramSocket().use { send -> send.send(DatagramPacket(downlink,downlink.size,InetAddress.getLoopbackAddress(),(accepted["dataPort"] as Number).toInt())) }
                    val key=MicrophonePacketizer.inputKey(session.sharedSecret!!,7654L)!!
                    val packet=DatagramPacket(ByteArray(4096),4096)
                    try { udp.receive(packet) } catch (e: SocketTimeoutException) {
                        throw AssertionError("captureReads=${AndroidMicrophoneExperimentQualificationTest.captureReads} ip=$ip peer=$peer remote=${session.remoteAddress} lines=$ledgerLines",e)
                    }
                    val bytes=packet.data.copyOf(packet.length)
                    val pcm=AirPlayCrypto.chachaOpen(key,ByteArray(4)+bytes.takeLast(8).toByteArray(),
                        bytes.copyOfRange(12,bytes.size-8),bytes.copyOfRange(4,12))
                    assertEquals(listOf(captureBytes),AndroidMicrophoneExperimentQualificationTest.captureReads.toList())
                    assertEquals(960,pcm.size)
                    val samples=ByteBuffer.wrap(pcm).order(ByteOrder.BIG_ENDIAN).asShortBuffer()
                    while(samples.hasRemaining()) assertEquals(1000,samples.get().toInt())
                }
            }
        } finally { CarPlayMicrophoneExperiments.disarm();receiver.close();assertTrue(receiver.awaitClosed(5000)) }
        MicrophoneCaptureFence.tryDiagnostic()!!.release()
        val creates=ledgerLines.filter { "kind=probe action=create " in it }
        assertEquals("Exact genuine qualification count",count,creates.size)
        val shapes=creates.map {
            listOf("source","rate","channels").joinToString(":") { name -> Regex("$name=([A-Z_0-9]+)").find(it)!!.groupValues[1] }
        }
        assertEquals(if (history=="NORMAL") listOf("MIC:24000:1","MIC:48000:1","VOICE_RECOGNITION:24000:1",
            "VOICE_RECOGNITION:48000:1","VOICE_RECOGNITION:48000:2") else List(9) { if(history in setOf("AA_STYLE","PRODUCTION")) "VOICE_RECOGNITION:16000:1" else "VOICE_RECOGNITION:48000:2" },shapes)
        assertEquals(1,AllRecorder.starts)
        val receiverOrdinal=Regex("receiver=([0-9]+)").find(creates.first())!!.groupValues[1]
        if(history in setOf("AA_STYLE","PRODUCTION")) assertTrue(ledgerLines.any { "stage=microphone-acquisition path=aa-style" in it })
        else assertTrue(ledgerLines.any { it=="media stage=microphone-control receiver=$receiverOrdinal history=${if (history=="NORMAL") "normal" else "manual-vr48-two"} live=${if (live=="VR24_MONO") "vr24-mono" else "vr48-two"}" })
        creates.zipWithNext().forEach { (before,after) ->
            val owner=Regex("owner=([0-9]+)").find(before)!!.groupValues[1]
            assertTrue(ledgerLines.indexOfFirst { "owner=$owner " in it && "action=released " in it } < ledgerLines.indexOf(after))
        }
    }
}
