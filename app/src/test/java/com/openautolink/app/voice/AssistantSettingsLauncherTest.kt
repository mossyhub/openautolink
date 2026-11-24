package com.openautolink.app.voice

import android.content.Context
import android.content.Intent
import android.provider.Settings
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
class AssistantSettingsLauncherTest {
    @Test fun choosesTheDirectVoiceInputPickerWhenTheCarExposesIt() {
        assertEquals(Settings.ACTION_VOICE_INPUT_SETTINGS,
            AssistantSettingsLauncher.pickerIntent { intent -> intent.action == Settings.ACTION_VOICE_INPUT_SETTINGS }?.action)
    }

    @Test fun returnsNullWhenTheCarHasNoAssistantPicker() {
        assertNull(AssistantSettingsLauncher.pickerIntent { false })
    }

    @Test fun openRefusesToStartWhenTheCarHasNoAssistantPicker() {
        val context = mockk<Context>()
        every { context.packageManager.resolveActivity(any<Intent>(), any<Int>()) } returns null

        assertFalse(AssistantSettingsLauncher.open(context))
        verify(exactly = 0) { context.startActivity(any()) }
    }
}
