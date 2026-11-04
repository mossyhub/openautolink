package com.openautolink.app.ui.projection

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.openautolink.app.ui.testing.createOalComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class, qualifiers = "w640dp-h240dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SettingsSaveNoticeUiTest {
    @get:Rule val compose = createOalComposeRule()
    @Test fun shortLandscapeNoticeHasBoundedReachableDismissAndReopen() {
        var root: android.view.View? = null
        compose.setContent { MaterialTheme {
            root = LocalView.current.rootView
            var expanded by remember { mutableStateOf(true) }
            Box(Modifier.fillMaxSize()) {
                SettingsSaveNotice("Saved. Unplug and reconnect USB to apply.", expanded,
                    { expanded = it }, Modifier.align(androidx.compose.ui.Alignment.TopEnd))
            }
        } }
        val bounds = compose.onNodeWithTag("settingsSaveNotice").fetchSemanticsNode().boundsInRoot
        assertTrue("Notice must not cover most of a short viewport: $bounds", bounds.height <= 96f)
        assertTrue("Notice width must be bounded: $bounds", bounds.width <= 448f)
        compose.onNodeWithContentDescription("Hide reconnect guidance").assertIsDisplayed()
        capture(root!!, "expanded.png")
        compose.onNodeWithContentDescription("Hide reconnect guidance").performClick()
        compose.onNodeWithText("Changes pending").assertIsDisplayed()
        capture(root!!, "collapsed.png")
        compose.onNodeWithText("Changes pending").performClick()
        compose.onNodeWithText("Saved. Unplug and reconnect USB to apply.").assertIsDisplayed()
    }
    private fun capture(root: android.view.View, name: String) {
        val dir = System.getProperty("banner.render.dir").orEmpty()
        if (dir.isEmpty()) return
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            File(dir).mkdirs()
            File(dir, "sdk${android.os.Build.VERSION.SDK_INT}-$name").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
