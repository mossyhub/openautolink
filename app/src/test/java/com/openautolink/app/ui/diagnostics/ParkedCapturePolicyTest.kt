package com.openautolink.app.ui.diagnostics

import android.Manifest
import android.app.Application
import android.app.AppOpsManager
import android.media.AudioManager
import android.os.Process
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[32])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ParkedCapturePolicyTest {
    @Test fun actualPublicPermissionAppOpsMuteAndCallModeRevokeWithoutRoutingWrites() {
        val app=RuntimeEnvironment.getApplication()
        val audio=app.getSystemService(AudioManager::class.java)
        val ops=app.getSystemService(AppOpsManager::class.java)
        val policy=ParkedCapturePolicy(app)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(ops).setMode(AppOpsManager.OPSTR_RECORD_AUDIO,Process.myUid(),app.packageName,AppOpsManager.MODE_ALLOWED)
        assertTrue(policy.allowed())
        val bluetooth=org.robolectric.shadows.AudioDeviceInfoBuilder.newBuilder()
            .setType(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()
        shadowOf(audio).setInputDevices(listOf(bluetooth))
        assertTrue("observed Bluetooth endpoint is informational, never changed",policy.allowed())
        assertEquals(listOf(bluetooth),audio.getDevices(AudioManager.GET_DEVICES_INPUTS).toList())
        shadowOf(audio).setInputDevices(emptyList())
        assertTrue(policy.allowed())
        audio.isMicrophoneMute=true;assertFalse(policy.allowed());audio.isMicrophoneMute=false
        audio.mode=AudioManager.MODE_IN_COMMUNICATION;assertFalse(policy.allowed());audio.mode=AudioManager.MODE_NORMAL
        shadowOf(ops).setMode(AppOpsManager.OPSTR_RECORD_AUDIO,Process.myUid(),app.packageName,AppOpsManager.MODE_IGNORED)
        assertFalse(policy.allowed())
        shadowOf(ops).setMode(AppOpsManager.OPSTR_RECORD_AUDIO,Process.myUid(),app.packageName,AppOpsManager.MODE_ALLOWED)
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO);assertFalse(policy.allowed())
    }
}
