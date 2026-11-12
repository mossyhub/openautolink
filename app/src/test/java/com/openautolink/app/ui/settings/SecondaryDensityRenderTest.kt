package com.openautolink.app.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
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
@Config(sdk = [32, 34], application = Application::class, qualifiers = "w1200dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SecondaryDensityRenderTest {
    @get:Rule val compose = createOalComposeRule()
    @Test fun wideDensityControlsRenderAndRemainReachable() = render("wide", 1f)
    @Test @Config(qualifiers = "w1024dp-h400dp-land-mdpi")
    fun shortLandscapeDensityControlsRenderWithEnlargedText() = render("short-enlarged", 1.3f)

    private fun render(name: String, fontScale: Float) {
        val id = SecondaryOutputId("density-render")
        val descriptor = SecondaryDisplayDescriptor(id, 7, 1, "Driver display", 2000, 810, 220,
            SecondaryRoute.PUBLIC_ACTIVITY, SecondaryReason.NONE, "Unknown")
        val source = SecondaryDisplaysSource(MutableStateFlow(SecondaryInventorySnapshot(1, listOf(descriptor))),
            MutableStateFlow(listOf(DiscoveredSecondaryDisplay(id.value, 7, "Driver display", 2000, 810, true, true, false, null))), null)
        var snapshot by mutableStateOf(SecondaryPreferencesSnapshot(1,
            mapOf(id to SecondaryOutputPreference(true, AaSecondaryRole.AUXILIARY, aaDensityDpi = 140)),
            ClusterOutputPreferences(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO), null)))
        var root: android.view.View? = null
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Surface(Modifier.fillMaxSize()) {
                        root = LocalView.current.rootView
                        SecondaryDisplaysSettings(source, snapshot, true, { key, edit ->
                            snapshot = snapshot.copy(outputs = snapshot.outputs + (key to edit(snapshot.outputs.getValue(key))))
                        })
                    }
                }
            }
        }
        compose.onNodeWithTag("secondaryDensityCustom").performScrollTo().assertIsOn().assertIsDisplayed()
        compose.onNodeWithTag("secondaryDensitySlider").performScrollTo().assertIsDisplayed()
        // Material3 reports its internal slider semantics bounds, not the outer minimum-interaction layout.
        compose.onNodeWithTag("secondaryDensitySlider").assertIsEnabled()
        compose.onNodeWithContentDescription("Android Auto secondary density").assertIsDisplayed()
        val output = System.getProperty("density.render.dir").orEmpty()
        if (output.isNotEmpty()) compose.runOnIdle {
            val view = requireNotNull(root)
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            val dir = File(output).apply { mkdirs() }
            File(dir, "$name-sdk${android.os.Build.VERSION.SDK_INT}.png").outputStream().use {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            image.recycle()
        }
        compose.onNodeWithTag("secondaryImportantContentEditor").performScrollTo().assertIsDisplayed()
    }
}
