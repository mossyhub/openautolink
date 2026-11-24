package com.openautolink.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.openautolink.app.diagnostics.DiagnosticLog

/** AAOS assistant candidate. The system only binds this after the driver selects OAL in Settings. */
class OalVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        DiagnosticLog.i("voice", "OAL selected as the car digital assistant; projection voice routing enabled")
    }

    override fun onShutdown() {
        DiagnosticLog.i("voice", "OAL digital assistant role released")
        super.onShutdown()
    }
}

/** One session for the system assistant gesture; projection owns the actual voice UI. */
class OalVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = OalVoiceInteractionSession(this)
}

class OalVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        ProjectionVoiceAssistantRouter.request("assistant-session flags=0x${Integer.toHexString(showFlags)}")
        hide()
        finish()
    }
}

/**
 * Required companion for a VoiceInteractionService. Speech recognition remains on the connected
 * phone; a direct SpeechRecognizer request is refused rather than claiming local recognition.
 */
class OalRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        DiagnosticLog.w("voice", "SpeechRecognizer refused: recognition belongs to the active phone projection")
        runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
    }

    override fun onCancel(listener: Callback) {
        runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
    }

    override fun onStopListening(listener: Callback) {
        runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
    }
}
