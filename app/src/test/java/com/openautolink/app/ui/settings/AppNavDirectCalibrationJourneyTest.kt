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
class AppNavDirectCalibrationJourneyTest {
 @get:Rule val compose=createOalComposeRule()
 @Test fun realMenuSettingsStartBackSharedSaveWritesMigratedMaximum() = DirectionsSettingsJourney(compose).run()
 @Test fun publicDirectionsDoneCancelDoesNotPersistOrReconnect() = DirectionsSettingsJourney(compose).run(edit=true,cancel=true)
 @Test fun disappearedTargetCannotStageOrLaunchAnotherPreview() = DirectionsSettingsJourney(compose).run(edit=true,staleTarget=true)
 @Test fun concurrentSavedRevisionIsNotSilentlyRebased() = DirectionsSettingsJourney(compose).run(edit=true,failure="revision")
 @Test fun cancelledSecondaryWriteRetainsDraftAndNeverClosesOrReconnects() = DirectionsSettingsJourney(compose).run(edit=true,failure="cancelled")
 @Test fun legacyMaximumMigrationAndUnclippedResolverRemainSupported() =
     com.openautolink.app.ui.settings.cimigration.LegacyDirectionsAdapterContract.verify()
}
