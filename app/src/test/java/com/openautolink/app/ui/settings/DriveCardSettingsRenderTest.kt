package com.openautolink.app.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import com.openautolink.app.cluster.*
import com.openautolink.app.data.ClusterOutputPreferences
import com.openautolink.app.displays.DiscoveredSecondaryDisplay
import com.openautolink.app.secondary.*
import com.openautolink.app.ui.testing.createOalComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],application=Application::class,qualifiers="w1024dp-h480dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DriveCardSettingsRenderTest {
    @get:Rule val compose=createOalComposeRule()
    @Test @Config(qualifiers="w480dp-h800dp-port-mdpi")
    fun narrowEnlargedTextKeepsSharedToggleAndHelperReachable() {
        val old=org.robolectric.RuntimeEnvironment.getApplication().resources.configuration.fontScale
        try {
            org.robolectric.RuntimeEnvironment.setFontScale(1.3f)
            sharedCardToggleStagesPerOutputOnlyInVideoMode()
        } finally {org.robolectric.RuntimeEnvironment.setFontScale(old)}
    }
    @Test fun sharedCardToggleStagesPerOutputOnlyInVideoMode() {
        val id=SecondaryOutputId("drivecard-render")
        val descriptor=SecondaryDisplayDescriptor(id,7,1,"Driver display",2000,810,220,SecondaryRoute.PUBLIC_ACTIVITY,SecondaryReason.NONE,"Unknown")
        val source=SecondaryDisplaysSource(MutableStateFlow(SecondaryInventorySnapshot(1,listOf(descriptor))),
            MutableStateFlow(listOf(DiscoveredSecondaryDisplay(id.value,7,"Driver display",2000,810,true,true,false,null))),null)
        var snapshot by mutableStateOf(SecondaryPreferencesSnapshot(1,mapOf(id to SecondaryOutputPreference(true,AaSecondaryRole.AUXILIARY)),
            ClusterOutputPreferences(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO),null)))
        var video by mutableStateOf(true)
        var root:android.view.View?=null
        compose.setContent {
            MaterialTheme(colorScheme=darkColorScheme()) { Surface(Modifier.fillMaxSize()) {
                root=LocalView.current.rootView
                SecondaryDisplaysSettings(source,snapshot,true,{key,edit->snapshot=snapshot.copy(outputs=snapshot.outputs+(key to edit(snapshot.outputs.getValue(key))))},
                    selection=ClusterOutputSelection(if(video)ClusterDisplayMode.MAP_VIDEO else ClusterDisplayMode.NATIVE_ICONS))
            } }
        }
        compose.onNodeWithTag("secondaryDriveCard").performScrollTo().assertIsOff().assertIsEnabled().performClick()
        compose.onNodeWithTag("secondaryDriveCard").assertIsOn().assertContentDescriptionEquals("Drive Card")
        compose.onNodeWithText("Android Auto and CarPlay · Adds efficiency and supported arrival estimates over video.").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(snapshot.outputs.getValue(id).driveCardEnabled)
            val view=requireNotNull(root)
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            System.getProperty("drivecard.render.dir")?.let { dir ->
                File(dir).mkdirs();File(dir,"settings-${view.width}x${view.height}-sdk${android.os.Build.VERSION.SDK_INT}.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            }
            bitmap.recycle();video=false
        }
        compose.onNodeWithTag("secondaryDriveCard").assertDoesNotExist()
        compose.runOnIdle { assertTrue("Icons mode remembers saved video choice",snapshot.outputs.getValue(id).driveCardEnabled) }
    }
}
