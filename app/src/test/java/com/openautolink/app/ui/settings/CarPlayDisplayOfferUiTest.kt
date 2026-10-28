package com.openautolink.app.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.lifecycle.viewModelScope
import com.openautolink.app.carplay.CarPlayDisplayOfferStore
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.data.CarPlaySettings
import com.openautolink.app.ui.testing.createOalComposeRule
import com.openautolink.carplaylab.media.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32, 34], qualifiers = "w1000dp-h1800dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayDisplayOfferUiTest {
    @get:Rule val compose = createOalComposeRule()
    @get:Rule val temp = org.junit.rules.TemporaryFolder()
    private val job = SupervisorJob()
    private var vm: SettingsViewModel? = null
    @After fun cleanup() { vm?.viewModelScope?.cancel(); job.cancel(); unmockkAll() }

    @Test fun realDisplaySectionObservesLastOfferWithoutApplyingPending225Draft() {
        val dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { java.io.File(temp.root, "offer.preferences_pb") })
        val preferences = AppPreferences::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java)
            .apply { isAccessible = true }.newInstance(dataStore)
        runBlocking { preferences.setCarPlaySettings(CarPlaySettings(enabled = true, autoScale = true, widthPhysicalMm = 200)) }
        mockkObject(AppPreferences.Companion, CarPlayDisplayPolicy)
        every { AppPreferences.getInstance(any()) } returns preferences
        every { CarPlayDisplayPolicy.androidProbe() } throws AssertionError("Display observation must not probe")
        val store = CarPlayDisplayOfferStore()
        val viewModel = SettingsViewModel(RuntimeEnvironment.getApplication(), store)
        vm = viewModel
        val lease = store.begin()
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) { CarPlayDisplaySettingsSection(viewModel, {}) }
        } }
        compose.waitUntil(5000) { viewModel.carPlaySettingsLoaded.value && viewModel.carPlaySettings.value.enabled }
        compose.onNodeWithText("Connect to see applied size").assertExists()
        val actual = CarPlayDisplayPreferences(resolution = CarPlayResolution.CUSTOM, customWidth = 1920, customHeight = 750,
            widthPhysicalMm = 200, scaleMode = CarPlayScaleReceipt.Mode.AUTOMATIC).freezeScale()
        compose.runOnIdle { assertTrue(store.publish(lease, actual)) }
        compose.onNodeWithTag("carPlayLastOffer").assertTextContains("Auto · calibration needed", substring = true)
        compose.onNodeWithTag("carPlayLastOffer").assertTextContains("reference 200 → offered 200×78 mm", substring = true)
        compose.runOnIdle { viewModel.updateCarPlayDraft { it.copy(widthPhysicalMm = 225) } }
        compose.waitUntil(5000) { viewModel.carPlaySettings.value.widthPhysicalMm == 225 }
        compose.onNodeWithTag("carPlayPendingScale").assertTextContains("225 mm", substring = true)
        compose.onNodeWithTag("carPlayLastOffer").assertTextContains("offered 200×78 mm", substring = true)
        assertEquals(200, runBlocking { preferences.carPlaySettings.first().widthPhysicalMm })
        assertEquals(200, store.state.value!!.referenceWidthMm)
        compose.runOnIdle { store.retire(lease) }
        compose.onNodeWithTag("carPlayLastOffer").assertTextContains("Ended", substring = true)
        compose.onNodeWithText("Phone-visible size verified", substring = true).assertDoesNotExist()
        verify(exactly = 0) { CarPlayDisplayPolicy.androidProbe() }
    }
}
