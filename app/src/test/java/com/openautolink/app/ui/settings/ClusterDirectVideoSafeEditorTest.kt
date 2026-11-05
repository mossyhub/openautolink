package com.openautolink.app.ui.settings

import android.app.*
import android.os.Looper
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import kotlin.math.roundToInt
import androidx.compose.ui.test.*
import androidx.lifecycle.viewModelScope
import com.openautolink.app.cluster.*
import com.openautolink.app.data.*
import com.openautolink.app.secondary.*
import com.openautolink.app.ui.testing.createOalComposeRule
import io.mockk.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],application=Application::class,qualifiers="w1000dp-h500dp-land-mdpi",shadows=[LiveGridWindowActivityTest.Admission::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ClusterDirectVideoSafeEditorTest {
    @get:Rule val compose=createOalComposeRule()
    @Test fun realSettingsBackSaveColdReopenAndResetOnlyChangeImportantContent()=runBlocking<Unit> {
        val app=RuntimeEnvironment.getApplication() as Application
        val id=ShadowDisplayManager.addDisplay("w1600dp-h600dp-mdpi","No calibration database")
        val physical=app.getSystemService(DisplayManager::class.java).getDisplay(id)
        shadowOf(physical).setFlags(Display.FLAG_PRESENTATION);shadowOf(physical).setState(Display.STATE_ON)
        RawDisplayTestFixture(app).use { catalog ->
        val inventory=catalog.policy
        val catalogContext=SecondaryDisplayPolicyInventory::class.java.getDeclaredField("context").apply { isAccessible=true }.get(inventory) as android.content.Context
        assertSame("Catalog must belong to this fixture application, not an earlier Robolectric sandbox",app.applicationContext,catalogContext.applicationContext)
        // Await the actual policy publication, not a guessed number of looper drains.
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle()
            inventory.snapshots.value.displays.any { it.displayId == id } }
        val descriptor=catalog.target(id,1600,600,SecondaryRoute.PUBLIC_ACTIVITY)
        val source=SecondaryDisplaysSource(inventory.snapshots,inventory.raw.displays,null)
        val file=File(app.filesDir,"direct-safe-${java.util.UUID.randomUUID()}.preferences_pb")
        var storeJob=SupervisorJob()
        fun preferences():AppPreferences {
            val store=androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope=CoroutineScope(storeJob+Dispatchers.IO),produceFile={file})
            return AppPreferences::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java).apply { isAccessible=true }.newInstance(store)
        }
        var prefs=preferences()
        val legacy=SecondaryLayoutPreference(secondaryLayoutScope(descriptor)!!,SecondaryLayoutBox(.1,.1,.9,.9),SecondaryLayoutBox(.3,.3,.7,.7))
        prefs.setCarPlaySettings(CarPlaySettings(enabled=false));prefs.setDirectTransport("usb")
        prefs.setClusterOutputSelection(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO,androidAuto=false,carPlay=false,directVideoWindow=false))
        val otherId = SecondaryOutputId("saved-auxiliary")
        val untouched = SecondaryOutputPreference(true, AaSecondaryRole.AUXILIARY, true, false)
        prefs.updateSecondaryDisplayPreferences(0,mapOf(descriptor.id to SecondaryOutputPreference(true,AaSecondaryRole.CLUSTER,true,true,legacy), otherId to untouched))
        mockkObject(AppPreferences.Companion);every { AppPreferences.getInstance(any()) } answers { prefs }
        var vm=SettingsViewModel(app);var mounted by mutableStateOf(true);var mountEpoch by mutableIntStateOf(0)
        val saveJob=SupervisorJob();val saves=CoroutineScope(saveJob+Dispatchers.Main.immediate);var saved=0
        var grid:ActivityController<LiveGridWindowActivity>?=null
        fun loaded() { compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle();vm.clusterLoaded.value&&vm.carPlaySettingsLoaded.value } }
        fun openEditor() {
            compose.onNodeWithText("Secondary displays").performClick()
            compose.onNodeWithTag("secondaryImportantContentEditor").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithTag("liveGridOutput").assertDoesNotExist()
        }
        try {
            compose.setContent { if(mounted) key(mountEpoch) { MaterialTheme { SettingsScreen(clusterBindings=null,viewModel=vm,secondarySource=source,
                onSaveAndConnect={ saves.launch { assertTrue(vm.commitSettings(null));saved++ } }) } } }
            loaded();openEditor()
            compose.onNodeWithTag("liveGridStart").performClick()
            val launched=shadowOf(app).nextStartedActivity
            if(launched==null) throw AssertionError("Preview launch missing: captured=$descriptor; current=${inventory.snapshots.value}; UI="+
                compose.onRoot(useUnmergedTree=true).printToString())
            val intent=launched
            grid=ActivityController.of(LiveGridWindowActivity(),intent,ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle()).create().start().resume()
            val before=grid!!.get().window.attributes.let { listOf(it.x,it.y,it.width,it.height) }
            val canvas=compose.onNodeWithTag("liveGridCanvas")
            val bounds=canvas.fetchSemanticsNode().boundsInRoot
            val dx=20f
            val pixelDx=(dx*1600/bounds.width).roundToInt()
            val expectedSafe=SecondaryLayoutBox((480+pixelDx)/1600.0,180/600.0,(1120+pixelDx)/1600.0,420/600.0)
            canvas.performTouchInput { down(center); moveBy(Offset(dx,0f)); up() }
            assertEquals(before,grid!!.get().window.attributes.let { listOf(it.x,it.y,it.width,it.height) })
            compose.onNodeWithText("After Done, tap Save changes. USB: unplug and reconnect to apply.").assertIsDisplayed()
            compose.onNodeWithTag("liveGridBack").performClick()
            assertTrue("Done must stop the exact physical preview",grid!!.get().isFinishing)
            assertEquals("Done stages a draft, never persists",legacy,prefs.getSecondaryDisplayPreferences().outputs.getValue(descriptor.id).layout)
            compose.onNodeWithText("Save changes").performClick()
            compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle();saved==1 }
            val committed = prefs.getSecondaryDisplayPreferences()
            val durable=committed.outputs.getValue(descriptor.id).layout!!
            assertEquals(untouched.copy(enabled = false), committed.outputs[otherId]) // one authoritative video target
            assertEquals(SecondaryOutputPreference(true,AaSecondaryRole.CLUSTER,true,true,durable), committed.outputs[descriptor.id])
            assertEquals(legacy.output,durable.output);assertEquals(legacy.safe,durable.safe)
            assertNotNull(durable.directSafe);assertNotEquals(legacy.safe,durable.directSafe)
            assertEquals(expectedSafe,durable.directSafe)
            for (cp in listOf(false,true)) {
                val target=SecondaryRuntimeLayouts.target(descriptor,durable,PixelSize(1600,600),
                    SecondaryProjectionActivity.directDefaultSafe(1600,600),android.os.Build.PRODUCT,android.os.Build.FINGERPRINT,
                    carPlay=cp,safeOnly=true)
                assertEquals(PixelRect(0,0,1600,600),target.output)
                assertEquals(PixelRect(480+pixelDx,180,1120+pixelDx,420),target.safe)
            }
            compose.runOnIdle { mounted=false };compose.waitForIdle();vm.viewModelScope.cancel();storeJob.cancelAndJoin()
            storeJob=SupervisorJob();prefs=preferences();vm=SettingsViewModel(app)
            compose.runOnIdle { mounted=true;mountEpoch++ };loaded();openEditor()
            compose.onNodeWithContentDescription("Move directions area").assertExists()
            assertEquals(durable,prefs.getSecondaryDisplayPreferences().outputs.getValue(descriptor.id).layout)
            compose.onNodeWithTag("liveGridReset").performClick()
            compose.onNodeWithTag("liveGrid_X_plus").assertDoesNotExist()
            compose.onNodeWithTag("liveGridBack").performClick()
            compose.onNodeWithText("Save changes").performClick();compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle();saved==2 }
            val reset=prefs.getSecondaryDisplayPreferences().outputs.getValue(descriptor.id).layout!!
            assertEquals(legacy.output,reset.output);assertEquals(legacy.safe,reset.safe);assertTrue(reset.directSafeAutomatic);assertNull(reset.directSafe)
            assertEquals(PixelRect(446,52,1355,495), SecondaryRuntimeLayouts.target(descriptor,reset,PixelSize(1600,600),
                SecondaryProjectionActivity.directDefaultSafe(1600,600),android.os.Build.PRODUCT,android.os.Build.FINGERPRINT,
                safeOnly=true).safe)
        } finally {
            compose.runOnIdle { mounted=false };compose.waitForIdle();grid?.let { if(!it.get().isDestroyed) it.pause().stop().destroy() }
            vm.viewModelScope.cancel();saveJob.cancel();storeJob.cancelAndJoin();unmockkAll();file.delete()
        }
        }
    }
}
