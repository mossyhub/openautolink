package com.openautolink.app.ui.settings

import android.app.Application
import com.openautolink.app.cluster.ClusterDirectVideoLifecycleTest
import com.openautolink.app.ui.settings.cimigration.DirectionsSettingsJourney
import com.openautolink.app.ui.testing.createOalComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

/** Current public Directions-area entrypoint; legacy adapter/resolver coverage is separate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],application=Application::class,qualifiers="w1000dp-h360dp-land-mdpi",
    shadows=[ClusterDirectVideoLifecycleTest.Admission::class, ClusterDirectVideoLifecycleTest.TextureBoundary::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CombinedAppNavSavedLayoutApplyTest {
 @get:Rule val compose=createOalComposeRule()
 @Test fun actualAppNavEditorDragRestartSharedSaveColdRestoreExpandedExplicitSaveReconnect() = DirectionsSettingsJourney(compose).run(edit=true)
 @Test fun pendingLayoutGuidanceClearsOnExactHostLossWithoutRewritingSave() = DirectionsSettingsJourney(compose).run(edit=true,hostLoss=true)
}
