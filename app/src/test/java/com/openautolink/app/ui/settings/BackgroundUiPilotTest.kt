package com.openautolink.app.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import com.openautolink.app.cluster.*
import com.openautolink.app.data.ClusterOutputPreferences
import com.openautolink.app.secondary.*
import com.openautolink.app.ui.testing.createOalComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],application=Application::class,qualifiers="w1000dp-h360dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class BackgroundUiPilotTest {
    @get:Rule val compose=createOalComposeRule()
    @Test fun freshExpandedHostConsumesSourcedBackgroundSavedLayout() {
        val p=ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val h=ClusterHostGeometry(2,p.panel,p.panel.bounds,PixelRect(0,135,2000,810))
        val auto=requireNotNull(ClusterDisplayProfiles.qualify(p.vehicle,listOf(ClusterDisplayBinding(p.role,2)),h))
        val scope=com.openautolink.app.data.ClusterLayoutScope(p.vehicle,p.id,p.role,p.panel)
        val saved=com.openautolink.app.data.SavedClusterLayout(scope,p.output,p.output,p.safe)
        val resolved=ClusterSavedLayoutResolver.resolveReceipt(auto,com.openautolink.app.data.ClusterLayoutRead(saved,null))
        assertNull(resolved.fallbackReason);assertEquals(p.output,resolved.display.effectiveOutput);assertEquals(p.safe,resolved.display.safe)
    }
    @Test fun essentialBoundsRejectUnsafeCustomWhileKeepingSavedIntent() {
        val p=ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val h=ClusterHostGeometry(2,p.panel,p.panel.bounds,PixelRect(0,135,2000,810))
        val auto=requireNotNull(ClusterDisplayProfiles.qualify(p.vehicle,listOf(ClusterDisplayBinding(p.role,2)),h))
        val scope=com.openautolink.app.data.ClusterLayoutScope(p.vehicle,p.id,p.role,p.panel)
        val saved=com.openautolink.app.data.SavedClusterLayout(scope,p.output,p.output,PixelRect(622,65,1374,669))
        val resolved=ClusterSavedLayoutResolver.resolveReceipt(auto,com.openautolink.app.data.ClusterLayoutRead(saved,null))
        assertNotNull(resolved.fallbackReason);assertSame(auto,resolved.display);assertEquals(65,saved.safe.top)
    }
    @Test fun legacySavedMaximumRemainsExactAfterVisibleExpansion() {
        val p=ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val stable=PixelRect(0,135,2000,810)
        val h=ClusterHostGeometry(2,p.panel,p.panel.bounds,stable)
        val auto=requireNotNull(ClusterDisplayProfiles.qualify(p.vehicle,listOf(ClusterDisplayBinding(p.role,2)),h))
        val scope=com.openautolink.app.data.ClusterLayoutScope(p.vehicle,p.id,p.role,p.panel)
        val saved=com.openautolink.app.data.SavedClusterLayout(scope,stable,stable,PixelRect(636,150,1471,661))
        val result=ClusterSavedLayoutResolver.resolveReceipt(auto,com.openautolink.app.data.ClusterLayoutRead(saved,null))
        assertNull(result.fallbackReason);assertEquals(saved.output,result.display.effectiveOutput);assertEquals(saved.safe,result.display.safe)
    }
    @Test fun encodedSavedReadUsesBoundedSourceCanvasFallbackWithoutRewritingIntent():Unit=kotlinx.coroutines.runBlocking {
        val p=ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val h=ClusterHostGeometry(2,p.panel,p.panel.bounds,PixelRect(0,135,2000,810))
        val auto=requireNotNull(ClusterDisplayProfiles.qualify(p.vehicle,listOf(ClusterDisplayBinding(p.role,2)),h))
        val scope=com.openautolink.app.data.ClusterLayoutScope(p.vehicle,p.id,p.role,p.panel)
        val saved=com.openautolink.app.data.SavedClusterLayout(scope,p.output,p.output,p.safe)
        val bytes=saved.encode();val canvases=mutableListOf<PixelRect>()
        val result=ClusterSavedLayoutResolver.resolve(auto) {s,c->canvases+=c;com.openautolink.app.data.ClusterLayoutStorage.decode(bytes,s,c)}
        assertNull(result.fallbackReason);assertEquals(listOf(h.essentialCanvas,p.output),canvases);assertEquals(bytes,saved.encode())
    }
    @Test fun onlyLiveEditorEntryIsPromotedAndUsesExactCallback() {
        val source=SecondaryDisplaysSource(MutableStateFlow(SecondaryInventorySnapshot(1,emptyList())),MutableStateFlow(emptyList()),null)
        val selection=ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)
        val prefs=SecondaryPreferencesSnapshot(0,emptyMap(),ClusterOutputPreferences(selection,null))
        val p=ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val model=ClusterLayoutModel(ClusterLayoutKey(p.vehicle,p.role,p.panel),p.output,p.output,p.safe)
        var calls=0;var root:android.view.View?=null
        val bindings=unavailableClusterSettingsBindings().copy(layout=model,automaticLayout=model)
        compose.setContent {root=LocalView.current.rootView;MaterialTheme {
            SecondaryDisplaysSettings(source,prefs,true,{_,_->fail("Unexpected preference edit")},legacyCluster={
                InstrumentClusterSettings(selection,"auto",bindings,true,{},{},{},{},{calls++},embedded=true)
            })
        }}
        compose.onNodeWithTag("secondaryLocalGridEditor").assertDoesNotExist()
        compose.onNodeWithTag("adjustClusterLayout").assertDoesNotExist()
        val entry=compose.onNodeWithTag("secondaryImportantContentEditor")
        entry.performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        val presentation=compose.onNodeWithTag("clusterDisplay_MAP_VIDEO").getUnclippedBoundsInRoot()
        assertTrue("Single toggle precedes safe-area action",presentation.top<entry.getUnclippedBoundsInRoot().top)
        assertTrue("48dp target",entry.fetchSemanticsNode().boundsInRoot.height>=48f)
        assertEquals(0,calls) // missing physical target must not fall through to the legacy editor
        compose.runOnIdle {
            val v=requireNotNull(root);val bitmap=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(bitmap))
            val output=File("build/background-pilot-renders/sdk-${org.robolectric.RuntimeEnvironment.getApiLevel()}.png");output.parentFile.mkdirs()
            output.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
        }
    }
}
