package com.openautolink.app.ui.projection

import android.view.WindowInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView

/** Insets of the owning Activity only, never a settings pane or OEM Dialog window. */
internal data class ProjectionChrome(val left: Int = 0, val top: Int = 0,
    val right: Int = 0, val bottom: Int = 0) {
    companion object {
        fun forMode(mode: String, bars: ProjectionChrome, cutout: ProjectionChrome): ProjectionChrome =
            if (mode == "system_ui_visible") ProjectionChrome(maxOf(bars.left, cutout.left),
                maxOf(bars.top, cutout.top), maxOf(bars.right, cutout.right), maxOf(bars.bottom, cutout.bottom))
            else ProjectionChrome()
    }
}

/** The single mode-constrained rectangle used by projection and parent-owned calibration. */
@Composable
fun ProjectionViewport(
    displayMode: String,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val insets = LocalView.current.rootWindowInsets
    fun chrome(type: Int): ProjectionChrome {
        val edges = insets?.getInsetsIgnoringVisibility(type)
        return ProjectionChrome(edges?.left ?: 0, edges?.top ?: 0, edges?.right ?: 0, edges?.bottom ?: 0)
    }
    val edges = ProjectionChrome.forMode(displayMode, chrome(WindowInsets.Type.systemBars()),
        chrome(WindowInsets.Type.displayCutout()))
    val padding = with(LocalDensity.current) {
        PaddingValues(start = edges.left.toDp(), top = edges.top.toDp(),
            end = edges.right.toDp(), bottom = edges.bottom.toDp())
    }
    Box(modifier.fillMaxSize().background(Color.Black).padding(padding), content = content)
}
