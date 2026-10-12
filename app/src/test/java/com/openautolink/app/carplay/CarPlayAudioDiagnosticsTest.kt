package com.openautolink.app.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayAudioDiagnosticsTest {
    // Reflection makes the first RED a behavior assertion even before the pure helper exists.
    private fun sanitize(session: Long, message: String): String? {
        val type = try { Class.forName("com.openautolink.app.carplay.CarPlayAudioDiagnostics") }
            catch (_: ClassNotFoundException) { return null }
        return type.getMethod("sanitize", java.lang.Long.TYPE, String::class.java)
            .invoke(type.getField("INSTANCE").get(null), session, message) as? String
    }

    @Test fun acceptedReplyExportsOnlyStreamTypeNotPortsOrStreamIdentifiers() {
        assertEquals("CARPLAY_AUDIO session=7 BENCH_DIAG streamAccepted type=100",
            sanitize(7, "BENCH_DIAG streamReply type=100 port=54321 streamId=null"))
        assertEquals("CARPLAY_AUDIO session=7 BENCH_DIAG streamAccepted type=130",
            sanitize(7, "BENCH_DIAG streamReply type=130 port=54321 streamId=1"))
        assertNull(sanitize(7, "BENCH_DIAG streamReply type=100 port=secret streamId=1"))
        assertNull(sanitize(7, "BENCH_DIAG streamReply type=100 port=54321 streamId=private-key"))
        assertNull(sanitize(7, "BENCH_DIAG streamReply type=100 port=54321 streamId=1 IP=192.168.1.1"))
        assertEquals("CARPLAY_AUDIO session=7 BENCH_DIAG handleFailureType=IllegalArgumentException at=85",
            sanitize(7, "BENCH_DIAG handleFailureType=IllegalArgumentException at=85"))
        assertNull(sanitize(7, "BENCH_DIAG handleFailureType=secretSSID at=85"))
    }

    @Test fun exactMarkerSchemasRetainOnlyFixedEnumsAndNumericFields() {
        val allowed = listOf(
            "BENCH_DIAG audioOfferRow index=0 type=100 audioType=speechRecognition output=536870912 input=absent",
            "BENCH_DIAG audioOfferRows=9 codecBits=8388608,536870912",
            "BENCH_DIAG modeResource=2 entity=1 permanent=null",
            "BENCH_DIAG streamOfferCount=1 acceptedCount=0",
            "BENCH_DIAG responseStatus=200",
            "BENCH_DIAG requestMethod=SETUP bytes=324",
            "BENCH_DIAG incomingCommand=modesChanged",
            "BENCH_DIAG videoOutput codec=H264 advertised=1920x1080 coded=1920x1088 visible=1920x1080",
            "BENCH_DIAG displayOffer codec=H264 width=1920 height=1080 fps=30 fallback=false",
            "BENCH_DIAG controlPeerEOF", "BENCH_DIAG eventPeerEOF",
            "BENCH_DIAG microphoneCapability=false",
            "BENCH_DIAG recordTiming synced=true matchedResponses=1",
            "BENCH_DIAG startupRequestUiSent=false",
            "media stage=audio-decoder-configured codec=OPUS",
            "media stage=audio-output-failed codec=AAC_LC",
            "media stage=audiotrack-written bytes=1920; human audibility unverified",
            "media stage=audio-focus-denied", "media stage=audio-owner-limit",
            "media stage=audio-retirement-pending", "media stage=audio-queue-overflow",
            "media stage=audio-decoder-backpressure", "media stage=audio-write-backpressure",
            "media stage=session-ended", "media stage=record-active; rendering unverified"
        )
        allowed.forEach { assertEquals(it, "CARPLAY_AUDIO session=7 $it", sanitize(7, it)) }
    }

    @Test fun maliciousFreeTextAndSchemaExtensionsAreRejectedRatherThanTruncatedIntoValidity() {
        val valid = "BENCH_DIAG streamType=100 audioFormat=536870912 hasConnectionId=true hasSeed=false"
        val malicious = listOf(
            valid + " password=secret", valid + "\nSSID=private", valid + "\r", valid + "\u0000",
            "BENCH_DIAG incomingCommand=192.168.1.1", "BENCH_DIAG incomingCommand=secretSSID",
            "BENCH_DIAG failureType=SecretPassphrase at=1",
            "BENCH_DIAG sessionCloseCaller=PrivateIdentity.method:1",
            "BENCH_DIAG streamTransport dataPort=192.168.1.1",
            "BENCH_DIAG audioOfferRow index=0 type=100 audioType=secretSSID output=1 input=0",
            "BENCH_DIAG modeResource=2 entity=private permanent=0",
            valid.replace("536870912", "1e9"), valid.replace("536870912", "1234567890123456789012345"),
            valid + " ".repeat(500), "pair key=secret", "BENCH_DIAG " + "x".repeat(10000)
        )
        malicious.forEach { assertNull(it, sanitize(7, it)) }
        assertNull(sanitize(0, valid)); assertNull(sanitize(-1, valid))
        assertTrue(sanitize(Long.MAX_VALUE, valid)!!.length <= CarPlayAudioDiagnostics.MAX_LINE_LENGTH)
    }

    @Test fun connectionStagesSurviveProductionExportWithoutIdentifiers() {
        val stages = listOf(
            "carplay-metadata received=1 media=true guidance=false hostPublication=false",
            "carplay-metadata hostPublication=true media=true guidance=true",
            "carplay-metadata publication=failed",
            "CarPlay connection stage=permissions result=started",
            "CarPlay connection stage=receiver-identity result=failed type=IllegalArgumentException reason=SETUP",
            "CarPlay network stage=address-selected family=6 mode=ap; ingress unverified",
            "CarPlay network stage=listener-bound family=6 admission=trusted-controller; ingress unverified",
            "CarPlay RFCOMM connecting · timeout 20s",
            "CarPlay RFCOMM connected · authentication pending",
            "CarPlay phone identification accepted",
            "CarPlay receiver MFi authentication accepted · phone AirPlay trust pending",
            "CarPlay endpoint listening · awaiting real phone / render evidence",
            "CarPlay endpoint reply id=19981 result=sent",
            "CarPlay network stage=control-accepted authentication=pending",
            "CarPlay network stage=authentication accepted=true",
            "control discovery started", "control resolved family=IPv4 port=54321",
            "control connecting attempt=1 port=54321", "control request sent attempt=1",
            "control response attempt=1 status=200; no projection proof",
            "media stage=runtime-identification-accepted", "media stage=runtime-authentication-accepted",
            "media stage=video-first-frame-confirmed count=1",
            "BENCH_DIAG videoOutput codec=H265 advertised=2560x1440 coded=2560x1440 visible=2560x1440"
        )
        stages.forEach { assertNotNull(it, sanitize(7, it)) }
        stages.forEach { assertNull(sanitize(7, it + " password=secret")) }
        assertNull(sanitize(7, "CarPlay connection stage=secret result=started"))
    }

    @Test fun streamQualifiedContinuityAndNavigationSchemasSurviveWithoutPrivateSuffixes() {
        val markers = listOf(
            "media stage=receive kind=audio type=102 packets=4 bytes=100 missing=2 gaps=1 late=1 maxGap=2 readMs=300 arrivalMs=310 processMs=2 longReads=1 ended=true",
            "media stage=audio-setup stream=1 type=102 audioType=media codec=AAC_LC rate=48000 clock=48000 channels=2 spf=1024",
            "media stage=audio-setup stream=1 type=100 audioType=speechrecognition codec=OPUS rate=48000 clock=24000 channels=1 spf=0",
            "media stage=audio-track stream=1 type=102 audioType=media codec=AAC_LC rate=48000 channels=2 capacity=4800",
            "media stage=audio-stats stream=1 type=102 audioType=media codec=AAC_LC packets=4 queue=0 queueDrops=1 decoderDrops=2 written=4096 zero=1 partial=2 errors=0 droppedBytes=12 underruns=1 route=2 ended=true",
            "carplay-nav kind=route parsed=1 failed=0 result=parsed state=active current=nonempty cached=true publishable=true transition=start reason=none",
            "carplay-nav kind=maneuver parsed=1 failed=1 result=failed state=unknown current=absent cached=false publishable=false transition=none reason=none",
            "carplay-nav kind=lifecycle parsed=1 failed=0 result=cleared state=unknown current=absent cached=false publishable=false transition=clear reason=stale",
            "carplay-nav publication=returned guidance=true transition=start"
        )
        markers.forEach { marker ->
            assertNotNull(marker, sanitize(9, marker))
            listOf(" secret=private", "\n", "\u0000").forEach { assertNull(sanitize(9, marker + it)) }
        }
        assertNull(sanitize(9, markers[0].replace("type=102", "type=999")))
        assertNull(sanitize(9, markers[1].replace("codec=AAC_LC", "codec=private")))
        assertNull(sanitize(9, markers[5].replace("state=active", "state=private")))
    }

    @Test fun selectedNumericSetupFieldsSurviveWithLocalSessionQualification() {
        val message = "BENCH_DIAG streamType=100 audioFormat=536870912 hasConnectionId=true hasSeed=false"
        assertEquals("CARPLAY_AUDIO session=7 $message", sanitize(7, message))
    }
}
