package com.openautolink.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.openautolink.app.video.AutoVideoTouchPolicy
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AaHighResolutionDefaultsTest {
    @Test fun freshInstallAutoPrefersHighResolutionCodec() = runBlocking {
        withPreferences { preferences ->
            assertTrue(preferences.videoAutoNegotiate.first())
            assertEquals("h265", preferences.videoCodec.first())
            assertEquals(2560 to 1440, AutoVideoTouchPolicy.resolve(
                true, preferences.videoCodec.first(), 2914, 1134, 1920, 1080))
        }
    }

    @Test fun explicitlySavedAvcAndManualResolutionRemainUnchanged() = runBlocking {
        withPreferences { preferences ->
            preferences.setVideoCodec("h264")
            preferences.setVideoAutoNegotiate(false)
            preferences.setAaResolution("1440p")
            assertEquals("h264", preferences.videoCodec.first())
            assertEquals(false, preferences.videoAutoNegotiate.first())
            assertEquals("1440p", preferences.aaResolution.first())
        }
    }

    private suspend fun withPreferences(block: suspend (AppPreferences) -> Unit) {
        val file = File.createTempFile("aa-high-res-", ".preferences_pb")
        file.delete()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            val constructor = AppPreferences::class.java.getDeclaredConstructor(
                androidx.datastore.core.DataStore::class.java).apply { isAccessible = true }
            block(constructor.newInstance(store))
        } finally {
            scope.cancel()
            file.delete()
        }
    }
}
