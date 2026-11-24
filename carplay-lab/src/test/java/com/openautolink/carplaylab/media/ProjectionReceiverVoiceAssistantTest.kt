// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.media

import android.content.Context
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
class ProjectionReceiverVoiceAssistantTest {
    private fun receiver(): ProjectionReceiver {
        val format = android.media.MediaFormat.createVideoFormat("video/avc", 1280, 720)
        val capabilities = org.robolectric.shadows.MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(format).setIsEncoder(false).setColorFormats(intArrayOf(2130708361))
            .setProfileLevels(arrayOf(android.media.MediaCodecInfo.CodecProfileLevel().apply {
                profile = android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                level = android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel52
            })).build()
        org.robolectric.shadows.ShadowMediaCodecList.addCodec(
            org.robolectric.shadows.MediaCodecInfoBuilder.newBuilder().setName("test.avc.decoder")
                .setIsEncoder(false).setCapabilities(capabilities).build())
        val app = RuntimeEnvironment.getApplication() as Context
        return ProjectionReceiver(app, "02:00:00:00:00:01", mock(Surface::class.java),
            mock(MfiAuthenticator::class.java), wired = true, onStatus = {})
    }

    @Test fun activeSessionReceivesVoiceAssistantRequest() {
        val receiver = receiver()
        val session = mock(AirPlaySession::class.java)
        try {
            val sessions = ProjectionReceiver::class.java.getDeclaredField("sessions").apply { isAccessible = true }
                .get(receiver) as MutableSet<AirPlaySession>
            sessions.add(session)
            ProjectionReceiver::class.java.getDeclaredField("active").apply { isAccessible = true }.set(receiver, session)
            ProjectionReceiver::class.java.getDeclaredField("runtimeOwner").apply { isAccessible = true }.set(receiver, session)
            ProjectionReceiver::class.java.getDeclaredField("runtimeReady").apply { isAccessible = true }.set(receiver, true)
            org.mockito.Mockito.`when`(session.isClosed).thenReturn(false)
            org.mockito.Mockito.`when`(session.controlAuthenticated).thenReturn(true)
            org.mockito.Mockito.`when`(session.encryptedEventReady).thenReturn(true)
            org.mockito.Mockito.`when`(session.invokeSiri(any<(() -> Boolean)?>())).thenReturn(true)

            assertTrue(receiver.requestVoiceAssistant())
            verify(session).invokeSiri(any<(() -> Boolean)?>())
        } finally {
            receiver.close()
            assertTrue(receiver.awaitClosed(10_000))
        }
    }

    @Test fun staleSessionCannotReceiveVoiceAssistantRequest() {
        val receiver = receiver()
        val session = mock(AirPlaySession::class.java)
        try {
            ProjectionReceiver::class.java.getDeclaredField("active").apply { isAccessible = true }.set(receiver, session)
            assertFalse(receiver.requestVoiceAssistant())
        } finally {
            receiver.close()
            assertTrue(receiver.awaitClosed(10_000))
        }
    }
}
