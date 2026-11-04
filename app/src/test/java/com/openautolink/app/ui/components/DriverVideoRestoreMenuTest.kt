package com.openautolink.app.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import com.openautolink.app.cluster.*
import com.openautolink.app.data.*
import com.openautolink.app.secondary.*
import com.openautolink.app.ui.projection.ProjectionUiState
import com.openautolink.app.ui.testing.createOalComposeRule
import io.mockk.*
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],application=Application::class,qualifiers="w960dp-h480dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DriverVideoRestoreMenuTest {
    @get:Rule val compose=createOalComposeRule()
    @After fun cleanup() { unmockkAll() }
    @Test fun productionBindingOnlyOffersNegotiatedVideoAndKeepsBoundedFeedbackWithoutReconnect() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("overlay_onboarding",android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("menu_drag_hint_dismissed",true).commit()
        val id=SecondaryOutputId("current-driver")
        val off=SecondaryPreferencesSnapshot(0,mapOf(id to SecondaryOutputPreference(true,AaSecondaryRole.AUXILIARY)),
            ClusterOutputPreferences(ClusterOutputSelection(mode=ClusterDisplayMode.NATIVE_ICONS),null))
        val saved=MutableStateFlow(off)
        val statuses=MutableStateFlow(mapOf(id to SecondaryOutputStatus(id,advertised=true,phoneSelected=true)))
        val preferences=mockk<AppPreferences>()
        every { preferences.secondaryDisplayPreferences } returns saved
        mockkObject(AppPreferences.Companion)
        every { AppPreferences.getInstance(any()) } returns preferences
        val runtime=mockk<SecondaryOutputRuntime>()
        every { runtime.statuses } returns statuses
        val generations=MutableStateFlow(0L)
        every { runtime getProperty "restoreGenerations" } returns generations
        every { runtime.updateRestorePolicyGeneration(any()) } returns Unit
        var request=CompletableFuture<DriverVideoRestoreResult>()
        every { runtime.restoreDriverDisplayVideo() } answers { request }
        mockkObject(SecondaryOutputRuntime.Companion)
        every { SecondaryOutputRuntime.peekForDiagnostics() } returns runtime
        var reconnects=0
        var root:android.view.View?=null
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize()) {
            root=LocalView.current.rootView
            ProjectionOverlayControls(ProjectionUiState(),{},{},{reconnects++},enableDriverVideoRestore=true)
        } } }
        compose.onNodeWithTag("settingsButton").performTouchInput { click() }
        compose.onNodeWithText("Restore driver display video").assertDoesNotExist()
        compose.runOnIdle { saved.value=off.copy(legacyCluster=ClusterOutputPreferences(ClusterOutputSelection(mode=ClusterDisplayMode.MAP_VIDEO),null)) }
        compose.onNodeWithText("Restore driver display video").assertIsDisplayed()
        verify(exactly=0) { runtime.restoreDriverDisplayVideo() }
        compose.runOnIdle { statuses.value=mapOf(id to SecondaryOutputStatus(id,advertised=true,phoneSelected=false)) }
        compose.onNodeWithText("Restore driver display video").assertDoesNotExist()
        compose.runOnIdle { statuses.value=mapOf(id to SecondaryOutputStatus(id,advertised=true,phoneSelected=true)) }
        compose.onNodeWithTag("overlayRecoveryAction:Restore driver display video").performScrollTo().performTouchInput { click() }
        compose.onNodeWithText("Restoring driver display window…").assertIsDisplayed()
        verify(exactly=1) { runtime.restoreDriverDisplayVideo() }
        assertEquals(0,reconnects)
        compose.runOnIdle { request.complete(DriverVideoRestoreResult.ATTACHED) }
        compose.onNodeWithText("Driver display window attached").assertIsDisplayed()
        assertEquals(0,reconnects)
        System.getProperty("restore.render.dir")?.let { dir -> compose.runOnIdle {
            val v=requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog()).window!!.decorView
            val bitmap=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bitmap));java.io.File(dir).mkdirs()
            java.io.File(dir,"sdk${android.os.Build.VERSION.SDK_INT}-restore-menu.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        } }
        compose.runOnIdle { request=CompletableFuture.completedFuture(DriverVideoRestoreResult.BLOCKED) }
        compose.onNodeWithTag("overlayRecoveryAction:Restore driver display video").performTouchInput { click() }
        compose.onNodeWithText("Restore unavailable — current video window not ready").assertIsDisplayed()
        assertEquals(0,reconnects)
        compose.runOnIdle { request=CompletableFuture() }
        compose.onNodeWithTag("overlayRecoveryAction:Restore driver display video").performTouchInput { click() }
        compose.onNodeWithText("Restoring driver display window…").assertIsDisplayed()
        compose.runOnIdle { generations.value++ }
        compose.runOnIdle { assertTrue("Same output/new selection disposes the exact request",request.isCancelled) }
        compose.onNodeWithText("Restoring driver display window…").assertDoesNotExist()
        compose.runOnIdle { statuses.value=mapOf(id to SecondaryOutputStatus(id,reason=SecondaryReason.HOST_UNAVAILABLE)) }
        compose.onNodeWithTag("overlayRecoveryAction:Reconnect required to enable driver display").assertIsNotEnabled()
        compose.onNodeWithText("Restore driver display video").assertDoesNotExist()
        compose.runOnIdle { saved.value=off }
        compose.onNodeWithText("Restore driver display video").assertDoesNotExist()
    }
}
