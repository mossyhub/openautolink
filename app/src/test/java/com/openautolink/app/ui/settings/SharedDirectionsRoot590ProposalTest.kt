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

/** UNEXECUTED proposal. Real production root and existing current-intent API, not future API RED. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],application=Application::class,qualifiers="w1000dp-h360dp-land-mdpi",
    shadows=[ClusterDirectVideoLifecycleTest.Admission::class, ClusterDirectVideoLifecycleTest.TextureBoundary::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SharedDirectionsRoot590ProposalTest {
 @get:Rule val compose=createOalComposeRule()
 @Test fun idleOverlayCurrentSharedCpIntentPreviewsCurrentCpBox() = DirectionsSettingsJourney(compose).run(cpPreviewCase="entry")
 @Test fun idleDestinationCurrentSharedCpIntentPreviewsCurrentCpBox() = DirectionsSettingsJourney(compose).run(destination=true,cpPreviewCase="entry")
 @Test fun resetPreviewsNextCpAutomaticRatherThanAaDefault() = DirectionsSettingsJourney(compose).run(reset=true,cpPreviewCase="reset")
 @Test fun unchangedDoneAndGlobalSavePreserveAaOnlyOverrideAndCpCustom() = DirectionsSettingsJourney(compose).run(cpPreviewCase="noop")
 @Test fun actualSharedEditGlobalSaveUsbNextPrepareBplistAndColdRestore() = DirectionsSettingsJourney(compose).run(edit=true,cpPreviewCase="edit")
 @Test fun secondarySaveFailureRetainsDraftAndNoReconnect() = DirectionsSettingsJourney(compose).run(edit=true,failure="secondary",cpPreviewCase="edit")
}
