package com.openautolink.app.ui.settings

import android.app.Application
import android.os.Build
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.lifecycle.viewModelScope
import com.openautolink.app.cluster.*
import com.openautolink.app.data.*
import com.openautolink.app.displays.DiscoveredSecondaryDisplay
import com.openautolink.app.secondary.*
import com.openautolink.app.ui.testing.createOalComposeRule
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],application=Application::class,qualifiers="w1024dp-h600dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class GmVideoPolicyUiTest {
    @get:Rule val compose=createOalComposeRule()
    @Test fun freshGmVideoIsOffWhileNativeGuidanceRemainsDefault()=root(true,false)
    @Test fun freshNonGmVideoIsOffWhileNativeGuidanceRemainsDefault()=root(false,false)
    @Test fun savedGmVideoHasNoNativeControlAndStillSuppressesNativePublication()=root(true,true)
    @Test fun savedNonGmCoexistenceIntentIsRetainedWithoutAnIndependentSwitch()=root(false,true)
    private fun root(gm:Boolean,savedVideo:Boolean)=runBlocking<Unit> {
        val oldProduct=Build.PRODUCT
        ReflectionHelpers.setStaticField(Build::class.java,"PRODUCT",if(gm)ClusterDisplayProfiles.AEGEAN_ORANGE_239.vehicle.product else "unprofiled")
        val app=RuntimeEnvironment.getApplication() as Application
        val file=File(app.filesDir,"gm-video-policy-${java.util.UUID.randomUUID()}.preferences_pb")
        val job=SupervisorJob()
        val store=androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope=CoroutineScope(job+Dispatchers.IO),produceFile={file})
        val preferences=AppPreferences::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java).apply{isAccessible=true}.newInstance(store)
        preferences.setCarPlaySettings(CarPlaySettings(enabled=false))
        val stored=ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO,false,false,nativeIconsWithMap=true,directVideoWindow=false)
        if(savedVideo)preferences.setClusterOutputSelection(stored)
        val id=SecondaryOutputId("gm-policy-display")
        val display=SecondaryDisplayDescriptor(id,2,1,"Driver display",2000,810,160,SecondaryRoute.PUBLIC_ACTIVITY,SecondaryReason.NONE,"Driver")
        val source=SecondaryDisplaysSource(MutableStateFlow(SecondaryInventorySnapshot(1,listOf(display))),
            MutableStateFlow(listOf(DiscoveredSecondaryDisplay(id.value,2,display.name,2000,810,true,true,false,null))),null)
        mockkObject(AppPreferences.Companion)
        every { AppPreferences.getInstance(any()) } returns preferences
        val vm=SettingsViewModel(app)
        var saved=0
        try {
            compose.setContent {
                val scope=rememberCoroutineScope()
                MaterialTheme { SettingsScreen(clusterBindings=clusterSettingsFixture(),viewModel=vm,secondarySource=source,
                    onSaveAndConnect={scope.launch{assertTrue(vm.commitSettings{true});saved++}}) }
            }
            compose.waitUntil(5000){shadowOf(Looper.getMainLooper()).idle();vm.clusterLoaded.value&&vm.carPlaySettingsLoaded.value&&vm.secondaryPreferences.value!=null}
            compose.onNodeWithTag("settingsTab_INSTRUMENT_CLUSTER").performScrollTo().performClick()
            val video=compose.onNodeWithTag("clusterDisplay_MAP_VIDEO")
            compose.onNodeWithTag("clusterNativeWithMap").assertDoesNotExist()
            compose.onNodeWithTag("clusterDisplay_NATIVE_ICONS").assertDoesNotExist()
            compose.onNodeWithTag("clusterDisplay_OFF").assertDoesNotExist()
            if(!savedVideo) {
                video.assertIsOff()
                assertEquals(ClusterDisplayMode.NATIVE_ICONS,preferences.getClusterOutputSelection().mode)
                assertTrue(preferences.clusterNavigation.first())
                compose.onNodeWithTag("secondaryImportantContentEditor").assertDoesNotExist()
                return@runBlocking
            }
            video.assertIsOn()
            if(gm) {
                val consequence=compose.onNodeWithTag("gmVideoConsequence").performScrollTo()
                consequence.assertTextEquals("Video disables navigation icons on the driver display and HUD.")
                assertTrue(video.getUnclippedBoundsInRoot().bottom<=consequence.getUnclippedBoundsInRoot().top)
            } else compose.onNodeWithTag("gmVideoConsequence").assertDoesNotExist()
            video.performScrollTo().performClick().assertIsOff()
            assertTrue(vm.clusterSelection.value.nativeIconsWithMap)
            compose.onNodeWithTag("secondaryImportantContentEditor").assertDoesNotExist()
            video.performScrollTo().performClick().assertIsOn()
            compose.onNodeWithTag("saveAndConnectButton").performClick()
            compose.waitUntil(5000){shadowOf(Looper.getMainLooper()).idle();saved==1}
            val durable=preferences.getClusterOutputSelection()
            assertEquals("Existing explicit Video and saved native intent must not be erased",stored,durable)
            assertTrue(preferences.getSecondaryDisplayPreferences().outputs.getValue(id).enabled)
            for(protocol in ClusterProjectionProtocol.entries)
                assertEquals(!gm,ClusterOutputPolicy.shouldPublishNativeIcons(durable,protocol,true))
        } finally {
            compose.runOnIdle{vm.viewModelScope.cancel()}
            unmockkObject(AppPreferences.Companion)
            job.cancelAndJoin();file.delete()
            ReflectionHelpers.setStaticField(Build::class.java,"PRODUCT",oldProduct)
        }
    }
}
