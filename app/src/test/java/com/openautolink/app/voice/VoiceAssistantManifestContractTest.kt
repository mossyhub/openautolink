package com.openautolink.app.voice

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAssistantManifestContractTest {
    @Test fun oalIsAnAssistantCandidateUntilSelectedInCarSettings() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val descriptor = File("src/main/res/xml/oal_voice_interaction_service.xml")

        assertTrue(manifest.contains(".voice.OalVoiceInteractionService"))
        assertTrue(manifest.contains("android.service.voice.VoiceInteractionService"))
        assertTrue(manifest.contains("android.permission.BIND_VOICE_INTERACTION"))
        assertFalse(manifest.contains("<uses-permission android:name=\"android.permission.BIND_VOICE_INTERACTION\""))
        assertTrue(manifest.contains("android.settings.VOICE_INPUT_SETTINGS"))
        assertTrue(manifest.contains("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"))
        val recognition = manifest.substringAfter("android:name=\".voice.OalRecognitionService\"")
            .substringBefore("</service>")
        assertTrue("RecognitionService must require the system-only bind permission",
            recognition.contains("android:permission=\"android.permission.BIND_RECOGNITION_SERVICE\""))
        assertTrue(descriptor.isFile)
        assertTrue(descriptor.readText().contains("android:supportsAssist=\"true\""))
    }
}
