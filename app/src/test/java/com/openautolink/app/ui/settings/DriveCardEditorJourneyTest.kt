package com.openautolink.app.ui.settings

import android.app.Application
import android.app.ActivityOptions
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import android.view.Surface
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.lifecycle.viewModelScope
import com.openautolink.app.cluster.*
import com.openautolink.app.data.*
import com.openautolink.app.displays.DiscoveredSecondaryDisplay
import com.openautolink.app.drivecard.*
import com.openautolink.app.input.ProcessVehicleDataRuntime
import com.openautolink.app.secondary.*
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.ui.testing.createOalComposeRule
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDisplayManager
import java.io.File
import java.util.concurrent.CompletableFuture

/** Actual Settings root -> admitted existing Activity card -> touch edits -> Done -> Save -> cold store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,qualifiers="w1024dp-h480dp-land-mdpi",
    shadows=[SecondaryHostRegistryTest.PermissionShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DriveCardEditorJourneyTest {
    @get:Rule val compose=createOalComposeRule()
    @get:Rule val temp=org.junit.rules.TemporaryFolder()
    @Test fun androidAutoUsesExistingCardWithoutRetiringSurface()=journey(false)
    @Test fun carPlayUsesExistingCardWithoutRetiringSurface()=journey(true)
    @Test @Config(qualifiers="w480dp-h800dp-port-mdpi")
    fun narrowLargeTextEditorKeepsCompletionReachable() {
        val old=RuntimeEnvironment.getApplication().resources.configuration.fontScale
        try { RuntimeEnvironment.setFontScale(1.4f);journey(true) }
        finally {RuntimeEnvironment.setFontScale(old)}
    }
    private fun journey(carPlay:Boolean)=runBlocking<Unit> {
        val app=RuntimeEnvironment.getApplication() as Application
        val oldContext=System.getProperty("robolectric.createActivityContexts")
        System.setProperty("robolectric.createActivityContexts","true")
        val displayId=ShadowDisplayManager.addDisplay("w2000dp-h810dp-mdpi","Driver")
        val physical=app.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        shadowOf(physical).setFlags(Display.FLAG_PRESENTATION);shadowOf(physical).setState(Display.STATE_ON)
        val facts=requireNotNull(PublicDisplayFacts.read(app,displayId))
        val id=SecondaryOutputId("editor-live-$displayId")
        val descriptor=SecondaryDisplayDescriptor(id,displayId,1,"Driver",facts.width,facts.height,facts.densityDpi,
            SecondaryRoute.PUBLIC_ACTIVITY,SecondaryReason.NONE,"Driver")
        val inventory=MutableStateFlow(SecondaryInventorySnapshot(1,listOf(descriptor)))
        val vehicles=MutableStateFlow(ControlMessage.VehicleData())
        mockkObject(ProcessVehicleDataRuntime)
        every {ProcessVehicleDataRuntime.latestVehicleData()} returns vehicles
        every {ProcessVehicleDataRuntime.isActive()} returns true
        SecondaryHostRegistryTest.PermissionShadow.allowed=true
        var withdrawals=0
        val registry=SecondaryHostRegistry(app,inventory,object:HostConsumer {
            override fun onReady(receipt:SecondaryHostReceipt)=CompletableFuture.completedFuture(true)
            override fun onRevoke(owner:SecondaryHostOwner) {}
            override fun onWithdraw(receipt:SecondaryHostReceipt):CompletableFuture<Boolean> {withdrawals++;return CompletableFuture.completedFuture(true)}
        })
        val handle=registry.create(app,descriptor,1,driveCardEnabled=true,driveCardCarPlay=carPlay)
        shadowOf(Looper.getMainLooper()).idle()
        val intent=requireNotNull(shadowOf(app).nextStartedActivity)
        val activity=SecondaryProjectionActivity()
        val controller=ActivityController.of(activity,intent,ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
        controller.create().start().resume()
        val surface=mockk<Surface> {every {isValid} returns true}
        SecondaryHostRegistry.present(activity,intent.getStringExtra(SecondaryHostRegistry.TOKEN_EXTRA),surface,
            SecondaryHostGeometry(facts.width,facts.height,PixelRect(558,70,1694,668)))
        shadowOf(Looper.getMainLooper()).idle()
        val overlay=requireNotNull(activity.findViewById<android.view.ViewGroup>(android.R.id.content).findViewWithTag<DriveCardView>(DriveCardView.TAG))
        val original=overlay.layout
        var job=SupervisorJob()
        val file=File(temp.root,"editor.preferences_pb")
        fun open():AppPreferences {
            val store=PreferenceDataStoreFactory.create(scope=CoroutineScope(job+Dispatchers.IO),produceFile={file})
            return AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java).apply{isAccessible=true}.newInstance(store)
        }
        var prefs=open()
        prefs.setClusterOutputSelection(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO))
        prefs.updateSecondaryDisplayPreferences(0,mapOf(id to SecondaryOutputPreference(true,AaSecondaryRole.AUXILIARY,driveCardEnabled=true)))
        mockkObject(AppPreferences.Companion)
        every {AppPreferences.getInstance(any())} answers {prefs}
        var model=SettingsViewModel(app)
        val source=SecondaryDisplaysSource(inventory,MutableStateFlow(listOf(DiscoveredSecondaryDisplay(id.value,displayId,"Driver",facts.width,facts.height,true,true,false,null))),null)
        var mounted by mutableStateOf(true)
        var epoch by mutableIntStateOf(0)
        var root:android.view.View?=null
        var backActivity:androidx.activity.ComponentActivity?=null
        var saves=0
        fun loaded()=compose.waitUntil(5000) {shadowOf(Looper.getMainLooper()).idle();model.clusterLoaded.value && model.carPlaySettingsLoaded.value && model.secondaryPreferences.value!=null}
        fun editor() {compose.onNodeWithTag("secondaryAdjustDriveCard").performScrollTo().performClick()}
        try {
            compose.setContent {if(mounted) key(epoch) {
                val scope=rememberCoroutineScope()
                com.openautolink.app.ui.theme.OpenAutoLinkTheme(oalUiScalePercent=AppPreferences.DEFAULT_OAL_UI_SCALE_PERCENT) {
                    root=LocalView.current.rootView
                    backActivity=generateSequence(androidx.compose.ui.platform.LocalContext.current) {(it as? android.content.ContextWrapper)?.baseContext}.filterIsInstance<androidx.activity.ComponentActivity>().firstOrNull()
                    SettingsScreen(clusterBindings=clusterSettingsFixture(),viewModel=model,secondarySource=source,initialSecondaryDisplays=true,
                        onSaveAndConnect={scope.launch {assertTrue(model.commitSettings{true});saves++}})
                }
            }}
            loaded();compose.onNodeWithTag("secondaryAdjustDriveCard").performScrollTo()
            capture(root,"settings-root-${if(carPlay)"cp" else "aa"}");editor()
            compose.onNodeWithText("Android Auto and CarPlay").assertExists()
            compose.onNodeWithTag("driveCardDone").assertIsDisplayed()
            compose.onNodeWithTag("driveCardReset").assertIsDisplayed()
            compose.onNodeWithTag("driveCardShow").performScrollTo().performClick()
            compose.onNodeWithText("Stop preview").assertExists()
            capture(root,"editor-before-${if(carPlay)"cp" else "aa"}")
            val before=compose.onNodeWithTag("driveCardMove").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("driveCardMove").performTouchInput {swipe(center,center+Offset(-24f,-12f),300)}
            compose.runOnIdle {
                assertNotEquals(original,overlay.layout)
                assertEquals(0,withdrawals);assertFalse(handle.ready.isCompletedExceptionally)
                assertEquals(1,vehicles.subscriptionCount.value)
            }
            val edited=overlay.layout
            val after=compose.onNodeWithTag("driveCardMove").fetchSemanticsNode().boundsInRoot
            assertEquals(-24f,after.left-before.left,2f)
            compose.onNodeWithTag("driveCardResize").performTouchInput {swipe(center,center+Offset(-15f,-8f),300)}
            assertNotEquals(edited,overlay.layout)
            val staged=overlay.layout
            capture(root,"editor-${if(carPlay)"cp" else "aa"}")
            compose.onNodeWithTag("driveCardDone").performClick()
            compose.runOnIdle {assertEquals(original,overlay.layout);assertEquals(0,withdrawals)}
            assertNull(prefs.getSecondaryDisplayPreferences().outputs.getValue(id).driveCardLayout)
            editor();compose.onNodeWithTag("driveCardShow").performScrollTo().performClick()
            assertEquals(staged,overlay.layout)
            compose.runOnIdle {requireNotNull(backActivity).onBackPressedDispatcher.onBackPressed()}
            compose.waitForIdle();assertEquals(original,overlay.layout)
            compose.onNodeWithTag("saveAndConnectButton").performClick()
            compose.waitUntil(5000) {shadowOf(Looper.getMainLooper()).idle();saves==1}
            assertEquals(staged,prefs.getSecondaryDisplayPreferences().outputs.getValue(id).driveCardLayout)
            assertEquals("Save is next-session, not preview authority",original,overlay.layout)
            editor();compose.onNodeWithTag("driveCardShow").performScrollTo().performClick();assertEquals(staged,overlay.layout)
            compose.runOnIdle {mounted=false};compose.waitForIdle();assertEquals(original,overlay.layout)
            model.viewModelScope.cancel();job.cancelAndJoin();job=SupervisorJob();prefs=open();model=SettingsViewModel(app)
            compose.runOnIdle {epoch++;mounted=true};loaded();editor()
            assertEquals(staged,model.secondaryPreferences.value!!.outputs.getValue(id).driveCardLayout)
            compose.onNodeWithTag("driveCardShow").performScrollTo().performClick()
            assertEquals(staged,overlay.layout)
            compose.onNodeWithTag("driveCardReset").assertIsDisplayed().performClick()
            assertEquals(DriveCardLayout.DEFAULT,overlay.layout)
            compose.onNodeWithTag("driveCardDone").performClick()
            assertEquals(original,overlay.layout)
            assertEquals(staged,prefs.getSecondaryDisplayPreferences().outputs.getValue(id).driveCardLayout)
            compose.onNodeWithTag("saveAndConnectButton").performClick()
            compose.waitUntil(5000) {shadowOf(Looper.getMainLooper()).idle();saves==2}
            assertNull(prefs.getSecondaryDisplayPreferences().outputs.getValue(id).driveCardLayout)
            assertEquals(0,withdrawals)
        } finally {
            compose.runOnIdle {mounted=false};compose.waitForIdle();model.viewModelScope.cancel();job.cancelAndJoin()
            handle.retire();registry.close();controller.pause().stop().destroy();unmockkAll()
            if(oldContext==null)System.clearProperty("robolectric.createActivityContexts") else System.setProperty("robolectric.createActivityContexts",oldContext)
        }
    }
    private fun capture(root:android.view.View?,name:String)=compose.runOnIdle {
        val view=requireNotNull(root);val image=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(image))
        System.getProperty("drivecard.render.dir")?.let {dir -> File(dir).mkdirs();File(dir,"$name-${view.width}x${view.height}.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
        image.recycle()
    }
}
