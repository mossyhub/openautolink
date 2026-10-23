package com.openautolink.app.media

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmMediaControlWiringContractTest {

    @Test
    fun `GM media controls are built in with no user or native enable gate`() {
        val files = listOf(
            "app/src/main/java/com/openautolink/app/data/AppPreferences.kt",
            "app/src/main/java/com/openautolink/app/ui/settings/SettingsViewModel.kt",
            "app/src/main/java/com/openautolink/app/ui/settings/SettingsScreen.kt",
            "app/src/main/java/com/openautolink/app/media/OalMediaSessionManager.kt",
            "app/src/main/java/com/openautolink/app/session/SessionManager.kt",
            "app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt",
            "app/src/main/java/com/openautolink/app/transport/aasdk/AasdkNative.kt",
            "app/src/main/cpp/aasdk_jni.cpp",
            "app/src/main/cpp/jni_session.cpp",
            "app/src/main/cpp/jni_session.h",
        ).associateWith(::source)

        val removedTokens = listOf(
            "EXPERIMENTAL_GM_MEDIA_CONTROLS",
            "experimentalGmMediaControls",
            "experimentalControlsEnabled",
            "setExperimentalControlsEnabled",
            "desiredExperimentalMediaControlsEnabled",
            "setExperimentalMediaControlsEnabled",
            "experimentalMediaControlsEnabled",
            "Experimental GM media controls",
            "experimentalGmMediaControlsToggle",
        )
        files.forEach { (path, text) ->
            removedTokens.forEach { token ->
                assertFalse("$token must be absent from $path", text.contains(token))
            }
        }
    }

    @Test
    fun `MediaSession controls and both GM mute channels always reach the current AA session`() {
        val media = source("app/src/main/java/com/openautolink/app/media/OalMediaSessionManager.kt")
        val manager = source("app/src/main/java/com/openautolink/app/session/SessionManager.kt")
        val aasdk = source("app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt")
        val nativeApi = source("app/src/main/java/com/openautolink/app/transport/aasdk/AasdkNative.kt")
        val jni = source("app/src/main/cpp/aasdk_jni.cpp")
        val nativeImpl = source("app/src/main/cpp/jni_session.cpp")

        assertTrue(media.contains("override fun onStop()"))
        assertTrue(media.contains("GmMediaControlPolicy.Command.STOP"))
        assertTrue(media.contains("PlaybackStateCompat.ACTION_STOP"))
        val dispatch = media.substringAfter("private fun dispatchMediaCommand(")
            .substringBefore("internal fun controllerClass(")
        assertTrue(dispatch.contains("MetadataHostOwnership.process.legacy"))
        assertTrue(dispatch.contains("mediaControlCallback.takeIf { hostMediaLease == null }"))
        assertTrue(dispatch.contains("callback?.onCommand(command)"))

        assertTrue(manager.contains("installGmMediaControls()"))
        assertTrue(manager.contains("registerMuteStateReceiver()"))
        assertTrue(manager.contains("MASTER_MUTE_CHANGED_ACTION"))
        assertTrue(manager.contains("STREAM_MUTE_CHANGED_ACTION"))
        assertTrue(manager.contains("EXTRA_MASTER_VOLUME_MUTED"))
        assertTrue(manager.contains("manager.isStreamMute(AudioManager.STREAM_MUSIC)"))
        assertTrue(manager.contains("routeMediaSessionCommand"))
        assertTrue(manager.contains("syncGmMuteState"))
        val prepareNativeStart = manager
            .substringAfter("private fun prepareNativeSessionStart")
            .substringBefore("private fun startStreamingServicesLocked")
        assertTrue(
            prepareNativeStart.contains(
                "synchronized(gmMediaControlLock) { lastDeliveredHuMuted = null }",
            ),
        )
        val receiverBlock = manager
            .substringAfter("private fun registerMuteStateReceiver()")
            .substringBefore("private fun syncGmMuteState")
        assertTrue(receiverBlock.contains("scope.launch { syncGmMuteState"))

        assertTrue(aasdk.contains("fun sendKeyPress"))
        assertTrue(aasdk.contains("currentNativeSessionGeneration"))
        assertTrue(aasdk.contains("fun setHeadUnitMuted"))
        assertTrue(aasdk.contains("private val headUnitMuteLock"))
        assertTrue(aasdk.contains("synchronized(headUnitMuteLock)"))
        assertTrue(nativeApi.contains("nativeSendKeyPress"))
        assertTrue(nativeApi.contains("nativeSetHeadUnitMuted"))
        assertTrue(nativeApi.contains("nativePrimeHeadUnitMuted"))
        assertTrue(jni.contains("AasdkNative_nativeSendKeyPress"))
        assertTrue(jni.contains("AasdkNative_nativeSetHeadUnitMuted"))
        assertTrue(jni.contains("gSessionGeneration.load() != expectedGeneration"))

        val keyPressBody = nativeImpl
            .substringAfter("bool JniSession::sendKeyPress")
            .substringBefore("void JniSession::sendGpsLocation")
        assertTrue(keyPressBody.contains("for (bool down : {true, false})"))
        assertTrue(keyPressBody.contains("edges == 2"))
        assertFalse(keyPressBody.contains("experimental"))

        val muteSetterBody = nativeImpl
            .substringAfter("bool JniSession::setHeadUnitMuted")
            .substringBefore("bool JniSession::flushHeadUnitAudioFocusState")
        assertTrue(muteSetterBody.contains("ioService_->post"))
        assertTrue(muteSetterBody.contains("flushHeadUnitAudioFocusState(true)"))
        assertFalse(muteSetterBody.contains("controlChannel_"))
        assertFalse(muteSetterBody.contains("strand_"))

        val setupFocusBody = nativeImpl
            .substringAfter("void JniSession::sendUnsolicitedAudioFocusGain")
            .substringBefore("bool JniSession::setHeadUnitMuted")
        assertTrue(setupFocusBody.contains("flushHeadUnitAudioFocusState(headUnitMuteExplicit_.load())"))
        assertFalse(setupFocusBody.contains("AUDIO_FOCUS_STATE_GAIN"))
        val focusFlushBody = nativeImpl
            .substringAfter("bool JniSession::flushHeadUnitAudioFocusState")
            .substringBefore("bool JniSession::sendUnsolicitedAudioFocusState")
        assertTrue(focusFlushBody.contains("!streaming_"))
        val streamingReady = nativeImpl.indexOf("streaming_ = true;")
        val sessionStartedCallback = nativeImpl.indexOf(
            "callVoidCallback(cbMethods_.onSessionStarted);",
            startIndex = streamingReady,
        )
        assertTrue(streamingReady >= 0 && sessionStartedCallback > streamingReady)
        val phoneConnectedBlock = manager
            .substringAfter("is ControlMessage.PhoneConnected ->")
            .substringBefore("is ControlMessage.PhoneDisconnected ->")
        assertTrue(phoneConnectedBlock.contains("_sessionState.value = SessionState.STREAMING"))
        assertTrue(
            phoneConnectedBlock.contains(
                "scope.launch { syncGmMuteState(\"phone-connected\") }",
            ),
        )
        assertTrue(nativeImpl.contains("Key press send complete:"))
        assertTrue(nativeImpl.contains("AA audio focus sync outcome=sent:"))
        assertTrue(manager.contains("retainedMuted = currentSession?.desiredHeadUnitMuted"))
        assertTrue(manager.contains("session.primeHeadUnitMuted("))
    }

    @Test
    fun `existing custom key remapping remains independent of built-in MediaSession controls`() {
        val steering = source("app/src/main/java/com/openautolink/app/input/SteeringWheelController.kt")
        val customBranch = steering
            .substringAfter("val customMapped = customKeyMap[keycode]")
            .substringBefore("return when")

        assertTrue(customBranch.contains("sendButtonToAA(customMapped"))
        assertFalse(steering.contains("GmMediaControlPolicy"))
    }

    private fun source(path: String): String = projectFile(path).readText()

    private fun projectFile(path: String): File {
        var dir = File(System.getProperty("user.dir") ?: error("user.dir unavailable"))
        repeat(8) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("Cannot locate project file: $path")
    }
}
