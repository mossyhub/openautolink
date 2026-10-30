package com.openautolink.app.carplay

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PhonePlaybackIngressTest {
    @Test fun exactLeasePolicyReceiptsAreFiniteAndRejectFreeText() {
        val lease=CarPlayHostMediaLease(CarPlayHostMediaMode.NORMAL)
        val lines=mutableListOf<String>();lease.receipt=lines::add
        val method=lease.javaClass.declaredMethods.firstOrNull { it.name.startsWith("recordPhoneResumePolicy") }
        assertNotNull("existing bounded host-media ZIP sink must retain finite policy events without optional logging",method)
        method!!.isAccessible=true
        method.invoke(lease,"media stage=media-resume-policy reason=phone-resume result=accepted title=private-canary")
        assertTrue(lines.isEmpty())
        repeat(2000) {method.invoke(lease,"media stage=media-resume-policy reason=phone-resume result=accepted")}
        assertEquals(33,lines.size)
        assertEquals(1,lines.count { "result=limit" in it })
        assertTrue(lines.all {CarPlayAudioDiagnostics.sanitize(1,it)!=null})
        assertTrue(lines.none { "private-canary" in it })
    }
    @Test fun heldPresentationCannotCoalesceAwayExplicitPausePlayOrStampCapture() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val events=mutableListOf<Boolean?>();var captures=0
        val host=object:CarPlayMetadataHost {
            override fun start()=true
            override fun publish(media:com.openautolink.carplaylab.metadata.CarPlayMediaMetadata?,navigation:com.openautolink.carplaylab.metadata.CarPlayNavigationMetadata?,artwork:ByteArray?) { entered.countDown();release.await(3,TimeUnit.SECONDS) }
            override fun close() {}
        }
        val link=OalCarPlayMetadataLink({0L},{},host)
        try {
            val hook=link.javaClass.declaredFields.firstOrNull { it.name=="capturePlaybackObservation" }
            assertNotNull("link must capture authority before real intake, outside presentation worker",hook)
            hook!!.isAccessible=true
            hook.set(link, { captures++; { state:Boolean? -> events.add(state); Unit } })
            link.authenticate();assertTrue(entered.await(3,TimeUnit.SECONDS))
            for(state in listOf(1,2,1)) link.handle(Iap2Messages.buildRaw(0x5001) { group(1) { u8(0,state) } })
            link.handle(Iap2Messages.buildRaw(0x5001) { group(0) { string(1,"Title") } })
            assertEquals(listOf(true,false,true),events)
            assertEquals(4,captures)
        } finally { release.countDown();link.close() }
    }
    @Test fun processSelectionControllerIsAvailableDefaultOn() {
        val type=runCatching { Class.forName("com.openautolink.app.carplay.PhonePlaybackResumeRecovery") }.getOrNull()
        assertNotNull("process-only selection must be wired independently of a screen",type)
        val instance=type!!.getField("INSTANCE").get(null)
        assertEquals(true,type.getMethod("isEnabled").invoke(instance))
    }
}
