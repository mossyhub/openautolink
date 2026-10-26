// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.AcousticEchoCanceler

/** The AA acquisition configuration, not its protocol/frame sender. No routing or mode writes. */
internal object AndroidAutoCapturePath {
    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun recorder():AudioRecord {
        val minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        require(minimum>0)
        return AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,
            AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(minimum*2,1024))
    }
    /** Call only AFTER startRecording, matching AA. Null/unavailable/failed is not enabled. */
    class Effects(session:Int) {
        private val held=mutableListOf<AudioEffect>()
        private fun attach(available:()->Boolean,create:()->AudioEffect?):String = try {
            if(!available()) "unavailable" else {
                val effect=create()
                if(effect==null) "unavailable" else {
                    held.add(effect)
                    effect.enabled=true
                    if(effect.enabled) "enabled" else "disabled"
                }
            }
        } catch(_:Exception) { "failed" }
        val ns=attach(NoiseSuppressor::isAvailable) { NoiseSuppressor.create(session) }
        val agc=attach(AutomaticGainControl::isAvailable) { AutomaticGainControl.create(session) }
        val aec=attach(AcousticEchoCanceler::isAvailable) { AcousticEchoCanceler.create(session) }
        fun release():Boolean {
            var success=true
            held.forEach { if(runCatching { it.release() }.isFailure) success=false }
            held.clear()
            return success
        }
    }
}
