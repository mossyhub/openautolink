package com.openautolink.app.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayMicrophoneConfigurationExportTest {
    private val config="media stage=microphone-config scope=own requested=VOICE_COMMUNICATION rate=24000 recorder=VOICE_COMMUNICATION mode=communication config=present client=VOICE_COMMUNICATION active=VOICE_RECOGNITION clientRate=24000 deviceRate=48000 clientChannels=mono deviceChannels=mono clientEncoding=pcm16 deviceEncoding=pcm16 route=bus"
    private val effects="media stage=microphone-effects scope=own source=VOICE_COMMUNICATION rate=24000 chain=capture result=ok examined=2 truncated=false aec=true ns=false agc=true other=0"

    @Test fun finiteConfigurationAndEnabledEffectsCrossExactExporterSchemas() {
        val receipts=listOf(config,effects,
            config.replace("VOICE_COMMUNICATION","VOICE_RECOGNITION").replace("rate=24000","rate=16000").replace("clientRate=24000","clientRate=16000").replace("mode=communication","mode=normal"),
            effects.replace("VOICE_COMMUNICATION","VOICE_RECOGNITION").replace("rate=24000","rate=16000"),
            config.replace("config=present","config=absent").replace("client=VOICE_COMMUNICATION","client=absent").replace("active=VOICE_RECOGNITION","active=absent").replace("clientRate=24000","clientRate=absent").replace("deviceRate=48000","deviceRate=absent").replace("=mono","=absent").replace("=pcm16","=absent").replace("route=bus","route=absent"),
            effects.replace("result=ok","result=unavailable").replace("examined=2","examined=0").replace("aec=true ns=false agc=true","aec=unknown ns=unknown agc=unknown"),
            effects.replace("chain=capture","chain=client").replace("result=ok","result=truncated").replace("examined=2","examined=16").replace("truncated=false","truncated=true").replace("ns=false","ns=unknown"))
        val lines=mutableListOf<String>()
        val reporter=CarPlayAudioDiagnosticReporter(8,System::nanoTime,lines::add)
        receipts.forEach { receipt ->
            assertEquals("CARPLAY_AUDIO session=8 $receipt",CarPlayAudioDiagnostics.sanitize(8,receipt))
            reporter.record(receipt)
        }
        assertEquals(receipts.map { "CARPLAY_AUDIO session=8 $it" },lines)
    }

    @Test fun privateFieldsUnsupportedEnumsAndUnboundedEffectCountsFailClosed() {
        listOf(config,effects,config.replace("rate=24000","rate=16000"),effects.replace("rate=24000","rate=16000")).forEach { receipt ->
            listOf(" deviceId=1"," address=Call_In_Mic"," name=private"," package=private"," uuid=private"," samples=private","\n","\u0000").forEach {
                assertNull(CarPlayAudioDiagnostics.sanitize(1,receipt+it))
            }
        }
        listOf(config.replace("mode=communication","mode=77"),config.replace("route=bus","route=Call_In_Mic"),
            config.replace("active=VOICE_RECOGNITION","active=999"),config.replace("deviceRate=48000","deviceRate=12345"),
            config.replace("requested=VOICE_COMMUNICATION","requested=private"),config.replace("config=present","config=maybe"),
            effects.replace("examined=2","examined=17"),effects.replace("other=0","other=17"),
            effects.replace("chain=capture","chain=devicewide"),effects.replace("aec=true","aec=available"),
            effects.replace("source=VOICE_COMMUNICATION","source=private"),effects.replace("result=ok","result=enabled")
        ).forEach { assertNull(it,CarPlayAudioDiagnostics.sanitize(1,it)) }
    }
}
