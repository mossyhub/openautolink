package com.openautolink.app.ui.settings

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
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
class UnchangedGridSaveTransactionTest {
 @get:Rule val compose=createOalComposeRule()
 @Test fun unreadySaveReportsVisibleFailureInsteadOfSilentFalse() = kotlinx.coroutines.runBlocking {
  val vm=SettingsViewModel(org.robolectric.RuntimeEnvironment.getApplication())
  try {
   org.junit.Assert.assertFalse(vm.clusterLoaded.value)
   org.junit.Assert.assertFalse(vm.commitSettings(null))
   org.junit.Assert.assertTrue(vm.carPlayApplyError.value.isNotEmpty())
  } finally { vm.viewModelScope.cancel() }
 }
 @Test fun overlayUnchangedMigrationSaveThenRenderedReconnect() = DirectionsSettingsJourney(compose).run()
 @Test fun destinationUnchangedMigrationSaveThenRenderedReconnect() = DirectionsSettingsJourney(compose).run(destination=true)
 @Test fun overlayResetStartBackSaveRemovesLegacy() = DirectionsSettingsJourney(compose).run(reset=true)
 @Test fun destinationResetStartBackSaveRemovesLegacy() = DirectionsSettingsJourney(compose).run(reset=true,destination=true)
 @Test fun overlayLayoutFailureVisibleNoCloseNoReconnect() = DirectionsSettingsJourney(compose).run(failure="layout")
 @Test fun destinationClusterStoreFailureVisibleNoCloseNoReconnect() = DirectionsSettingsJourney(compose).run(destination=true,failure="cluster")
 @Test fun overlayCarPlayStoreFailureVisibleNoCloseNoReconnect() = DirectionsSettingsJourney(compose).run(failure="carplay")
 @Test fun destinationSecondaryStoreFailureVisibleNoCloseNoReconnect() = DirectionsSettingsJourney(compose).run(destination=true,failure="secondary")
}
