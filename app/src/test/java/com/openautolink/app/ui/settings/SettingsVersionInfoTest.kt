package com.openautolink.app.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.openautolink.app.BuildConfig
import com.openautolink.app.ui.testing.createOalComposeRule
import com.openautolink.app.ui.theme.OalUiScale
import com.openautolink.app.ui.theme.OpenAutoLinkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [32], qualifiers = "w800dp-h600dp-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
class SettingsVersionInfoTest {
    @get:Rule val compose = createOalComposeRule()

    @Test fun `installed version and full reminder fit narrow pane at 125 percent`() {
        assertNarrowPane(125)
    }

    @Test fun `installed version and full reminder fit narrow pane at 150 percent`() {
        assertNarrowPane(150)
    }

    @Test
    @Config(sdk = [32, 34])
    fun `CarPlay upstream credits are readable in narrow scrollable settings`() {
        compose.setContent {
            OpenAutoLinkTheme(oalUiScalePercent = 150) {
                Box(Modifier.size(300.dp, 240.dp)) {
                    OalUiScale {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            SettingsVersionInfo()
                        }
                    }
                }
            }
        }
        val credits = listOf(
            "Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI)",
            "CarPlay receiver adapted through shilapi/xcertplay and shihabal3amri/DiPlay. " +
                "OpenAutoLink is licensed under GNU GPL v3; component notices and source: " +
                "https://github.com/mossyhub/openautolink",
        )
        for (text in credits) {
            val node = compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
            val results = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                assertTrue(action(results))
            }
            val layout = results.single()
            assertFalse("credits must wrap without overflow", layout.hasVisualOverflow)
            assertEquals("credits must retain their full text", text.length,
                layout.getLineEnd(layout.lineCount - 1))
            for (line in 0 until layout.lineCount) {
                assertFalse("credits must not be ellipsized", layout.isLineEllipsized(line))
            }
        }
    }

    private fun assertNarrowPane(percent: Int) {
        compose.setContent {
            OpenAutoLinkTheme(oalUiScalePercent = percent) {
                // Physical content-pane dimensions, outside the app's scaled density.
                Box(Modifier.size(300.dp, 240.dp).testTag("viewport")) {
                    OalUiScale {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            SettingsVersionInfo()
                            Spacer(Modifier.height(300.dp))
                            Text("Following settings", Modifier.testTag("followingSettings"))
                        }
                    }
                }
            }
        }

        val version = compose.onNodeWithTag("settingsInstalledVersion")
            .assertTextEquals("OAL ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        val reminder = compose.onNodeWithTag("settingsUpdateReminder")
            .assertTextEquals(REMINDER).assertIsDisplayed()
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        // Collect both layouts before asserting: an early version-label failure must
        // not hide the reminder's metrics. Keep the full visibility checks below;
        // these diagnostics distinguish clipping from a font/layout backend issue.
        val measured = listOf(version, reminder).map { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                assertTrue(action(results))
            }
            node.fetchSemanticsNode().boundsInRoot to results.single()
        }
        val diagnostics = buildString {
            appendLine("scale=$percent% viewport=$viewport build=${BuildConfig.VERSION_NAME}")
            for ((bounds, layout) in measured) {
                appendLine("bounds=$bounds ${describeLayout(layout)}")
            }
        }
        assertEquals(diagnostics, 300f, viewport.width, 0.01f)
        assertEquals(diagnostics, 240f, viewport.height, 0.01f)
        for ((bounds, layout) in measured) {
            assertFalse("text must not overflow or ellipsize\n$diagnostics", layout.hasVisualOverflow)
            assertEquals("all characters must be laid out\n$diagnostics",
                layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
            assertTrue("text stays within pane horizontally\n$diagnostics",
                bounds.left >= viewport.left && bounds.right <= viewport.right)
            assertTrue("text is fully visible initially\n$diagnostics",
                bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
            assertTrue("ancestor must not clip measured text width\n$diagnostics",
                bounds.width >= layout.size.width - 1f)
            assertTrue("ancestor must not clip measured text height\n$diagnostics",
                bounds.height >= layout.size.height - 1f)
            for (line in 0 until layout.lineCount) {
                assertFalse("no line may be ellipsized\n$diagnostics", layout.isLineEllipsized(line))
                assertTrue("each rendered line must fit horizontally\n$diagnostics",
                    layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= bounds.width + 1f)
                assertTrue("each rendered line must fit vertically\n$diagnostics",
                    layout.getLineTop(line) >= -1f && layout.getLineBottom(line) <= bounds.height + 1f)
            }
        }
        assertTrue("reminder wraps at narrow width\n$diagnostics", measured[1].second.lineCount > 1)
        assertTrue("version precedes reminder",
            version.fetchSemanticsNode().boundsInRoot.bottom <= reminder.fetchSemanticsNode().boundsInRoot.top)

        // The info is ordinary scroll content, not a sticky panel blocking later settings.
        compose.onNodeWithTag("followingSettings").performScrollTo().assertIsDisplayed()
        version.assertIsNotDisplayed()
        version.performScrollTo().assertIsDisplayed()
    }

    private fun describeLayout(layout: TextLayoutResult): String = buildString {
        val input = layout.layoutInput
        appendLine("text='${input.text}' length=${input.text.length} size=${layout.size} " +
            "overflowWidth=${layout.didOverflowWidth} overflowHeight=${layout.didOverflowHeight}")
        appendLine("constraints=${input.constraints} density=${input.density.density} " +
            "fontScale=${input.density.fontScale} softWrap=${input.softWrap} " +
            "maxLines=${input.maxLines} overflow=${input.overflow} style=${input.style}")
        appendLine("paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height} " +
            "exceededMaxLines=${layout.multiParagraph.didExceedMaxLines} lines=${layout.lineCount}")
        for (line in 0 until layout.lineCount) {
            val start = layout.getLineStart(line)
            val end = layout.getLineEnd(line)
            appendLine("line=$line range=$start..$end visibleEnd=${layout.getLineEnd(line, visibleEnd = true)} " +
                "left=${layout.getLineLeft(line)} right=${layout.getLineRight(line)} " +
                "top=${layout.getLineTop(line)} bottom=${layout.getLineBottom(line)} " +
                "baseline=${layout.getLineBaseline(line)} ellipsized=${layout.isLineEllipsized(line)} " +
                "text='${input.text.text.substring(start, end)}'")
        }
    }

    private companion object {
        const val REMINDER = "Connect your car to Wi-Fi with internet regularly so OAL and other Play Store apps can update."
    }
}
