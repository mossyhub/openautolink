package com.openautolink.app.drivecard

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import com.openautolink.app.cluster.ClusterNavigationState
import com.openautolink.app.cluster.PixelRect
import com.openautolink.app.input.ProcessVehicleDataRuntime
import com.openautolink.app.navigation.ManeuverState
import com.openautolink.app.navigation.ManeuverType
import com.openautolink.app.secondary.SecondaryHostGeometry
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],application=Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DriveCardSharedMetadataFreshnessTest {
    private fun data(at:Long,wh:Float):ControlMessage.VehicleData {
        val observation=VehiclePropertyObservation(at*1000000,at,0,registrationGeneration=1,subscriptionActive=true)
        return ControlMessage.VehicleData(speedKmh=90f,evBatteryLevelWh=wh,evCurrentBatteryCapacityWh=70000f,
            vhalRegistrationGeneration=1,evObservationMetadata=listOf("PERF_VEHICLE_SPEED","EV_BATTERY_LEVEL","EV_CURRENT_BATTERY_CAPACITY").associateWith{observation})
    }
    @Test fun mergedRoadUpdateCannotRefreshCachedRouteEnergyInputs() {
        val nav=ManeuverState(ManeuverType.STRAIGHT,100,"100 m","Fixture road",100,
            destination="fixture-route",destDistanceMeters=20000,timeToArrivalSeconds=1200)
        ClusterNavigationState.clearHost();ClusterNavigationState.updateHost(nav)
        val vehicles=MutableStateFlow(data(SystemClock.elapsedRealtime(),50000f))
        mockkObject(ProcessVehicleDataRuntime)
        every {ProcessVehicleDataRuntime.latestVehicleData()} returns vehicles
        every {ProcessVehicleDataRuntime.isActive()} returns true
        val view=DriveCardView(RuntimeEnvironment.getApplication(),SecondaryHostGeometry(1280,480,PixelRect(0,0,1280,480)),true,{true},{})
        try {
            view.start();shadowOf(Looper.getMainLooper()).idle()
            for(i in 1..80) {
                ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
                vehicles.value=data(SystemClock.elapsedRealtime(),50000f-i*5)
                shadowOf(Looper.getMainLooper()).idle()
            }
            ClusterNavigationState.updateHost(nav.copy(roadName="Different maneuver road"))
            shadowOf(Looper.getMainLooper()).idle()
            val state=ReflectionHelpers.getField<DriveCardState>(view,"state")
            assertNotNull("Positive control: actual live vehicle history was learned",state.recentKmPerKwh)
            assertNull("Merged cached destination distance/time have no new source receipt",state.arrival)
            ClusterNavigationState.updateHost(nav.copy(destDistanceMeters=19500,timeToArrivalSeconds=1170))
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull("Fresh changed route distance and time permit a local estimate",
                ReflectionHelpers.getField<DriveCardState>(view,"state").arrival)
        } finally {view.stop();ClusterNavigationState.clearHost();unmockkObject(ProcessVehicleDataRuntime)}
    }
}
