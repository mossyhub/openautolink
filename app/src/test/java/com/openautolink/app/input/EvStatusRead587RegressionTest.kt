package com.openautolink.app.input

import android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import com.openautolink.app.carplay.OalCarPlayVehicleSnapshotAdapter
import com.openautolink.app.carplay.CarPlayDrivingRestrictionBridge
import com.openautolink.app.transport.ControlMessage
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test

/** Actual forwarder reflection boundary, with constructed Binder results; not a physical VHAL. */
class EvStatusRead587RegressionTest {
    class Config(private val mode: Int?) { fun getChangeMode(): Int = mode ?: error("mode unavailable") }
    class Value(private val id: Int, private val value: Any?, private val status: Int = 0, private val area: Int = 0) {
        fun getPropertyId() = id
        fun getAreaId() = area
        fun getValue() = value
        fun getTimestamp() = 1_000_000_000L
        fun getStatus() = status
    }
    class Manager(val mode: Int?) {
        var wrongId = false; var wrongArea = false; var fail = false; var badType = false; var status = 0
        var duringRead: (() -> Unit)? = null
        var duringPropertyRead: ((Int) -> Unit)? = null
        var includeInfo = false
        var includeUnits = false
        fun getCarPropertyConfig(id: Int): Any? = if (id in setOf(BATTERY,RANGE,GEAR) || (includeInfo && id == INFO) || (includeUnits && id == UNITS)) Config(mode) else null
        fun getProperty(id: Int, area: Int): Value {
            duringPropertyRead?.invoke(id)
            if (id == BATTERY) duringRead?.invoke(); if (fail) error("read failed")
            val v: Any = if (id == UNITS) { if (badType) "not-an-int" else 0x24 }
                else if (id == GEAR) 4 else if (badType) 55430 else if (id == INFO) 85990f else if (id == RANGE) 290984.38f else 55430f
            return Value(if (wrongId) id+1 else id, v, status, if (wrongArea) 1 else 0)
        }
        fun subscribePropertyEvents(id: Int, rate: Float, cb: CarPropertyEventCallback) = true
    }
    private fun fixture(mode: Int?, initial: (Manager) -> Unit = {}, body: (VehicleDataForwarderImpl, Manager, (Long) -> Unit, (String, Any) -> Unit) -> Unit) {
        mockkStatic(SystemClock::class)
        try {
            var now = 60000L
            every { SystemClock.elapsedRealtime() } answers { now }
            val context = mockk<Context>(relaxed=true)
            every { context.checkSelfPermission(any()) } returns PackageManager.PERMISSION_GRANTED
            val manager = Manager(mode); val forwarder = VehicleDataForwarderImpl(context,{})
            fun set(name: String, value: Any) = forwarder.javaClass.getDeclaredField(name).apply { isAccessible=true }.set(forwarder,value)
            set("desiredActive",true); set("isActive",true); set("propertyManager",manager); set("fuelTypes",listOf(10))
            val refresh = forwarder.javaClass.getDeclaredMethod("registerProperties",Long::class.javaPrimitiveType).apply { isAccessible=true }
            fun at(t: Long) { now=t; refresh.invoke(forwarder,0L) }
            initial(manager)
            at(60000)
            body(forwarder,manager,::at,::set)
        } finally { unmockkStatic(SystemClock::class) }
    }
    @Test fun failedKnownRangeReadPublishesRevocationBeforeNextBinderReadAndRecovers() {
        fixture(1) { f,m,at,_ ->
            assertNotNull(f.latestVehicleData.value.rangeKm)
            var revokedBeforeNextRead: Boolean? = null
            m.duringPropertyRead = { id ->
                if (id == RANGE) error("controlled current range read failure")
                if (id == BATTERY) revokedBeforeNextRead = f.latestVehicleData.value.rangeKm == null
            }
            at(66000)
            assertEquals("Known availability must be revoked before the next Binder call", true, revokedBeforeNextRead)
            assertNull(f.latestVehicleData.value.rangeKm)
            m.duringPropertyRead = null
            at(72000)
            assertNotNull("A later exact current read must recover range", f.latestVehicleData.value.rangeKm)
        }
    }

    @Test fun exactUnitReadsRefreshOldCarUnitsInEveryTelemetryModeWithoutRewritingTime() {
        for (mode in listOf(1,2,null)) fixture(mode,initial={it.includeUnits=true}) { f,_,at,_ ->
            at(170000)
            val d=f.latestVehicleData.value;val o=d.evObservationMetadata.getValue("DISTANCE_DISPLAY_UNITS")
            assertEquals(170000L,o.statusReadElapsedMs)
            assertEquals(170000L,o.receivedElapsedMs);assertEquals(1_000_000_000L,o.timestampElapsedNanos)
            assertEquals(0x24,d.distanceDisplayUnits)
            assertTrue(VehicleTelemetryFreshness.sourceTimeConfirmed(o,"DISTANCE_DISPLAY_UNITS",170000,15000))
        }
    }
    @Test fun unitReadsRetainExactIdentityTypeStatusAndPermissionFences() {
        for (bad in listOf("id","area","type","status","failure","permission")) fixture(1,initial={it.includeUnits=true}) { f,m,at,_ ->
            when(bad) {"id"->m.wrongId=true;"area"->m.wrongArea=true;"type"->m.badType=true;"status"->m.status=1;"failure"->m.fail=true
                else -> m.duringPropertyRead={id -> if(id==UNITS) {
                    val context=f.javaClass.getDeclaredField("context").apply{isAccessible=true}.get(f) as Context
                    every {context.checkSelfPermission("android.car.permission.READ_CAR_DISPLAY_UNITS")} returns PackageManager.PERMISSION_DENIED
                }} }
            at(66000)
            assertNull("Invalid unit status read: $bad",f.latestVehicleData.value.distanceDisplayUnits)
        }
    }
    @Test fun newerUnitCallbackOutranksInflightUnitRead() {
        fixture(1,initial={it.includeUnits=true}) { f,m,at,_ ->
            m.duringPropertyRead={id -> if(id==UNITS) {
                m.duringPropertyRead=null
                f.javaClass.getDeclaredMethod("handleChangeEvent",Any::class.java).apply{isAccessible=true}.invoke(f,Value(UNITS,0x23))
            }}
            at(66000)
            assertEquals(0x23,f.latestVehicleData.value.distanceDisplayUnits)
            assertNull(f.latestVehicleData.value.evObservationMetadata.getValue("DISTANCE_DISPLAY_UNITS").statusReadElapsedMs)
        }
    }
    @Test fun reportedCapacityRefreshIsCarInfoPermissionFencedAndNeverBecomesUsable() {
        fixture(2,initial={it.includeInfo=true}) { f,m,at,_ ->
            at(170000)
            assertEquals(170000L,f.latestVehicleData.value.evObservationMetadata.getValue("INFO_EV_BATTERY_CAPACITY").statusReadElapsedMs)
            assertEquals(85990f,f.latestVehicleData.value.evBatteryCapacityWh)
            assertNull(f.latestVehicleData.value.evCurrentBatteryCapacityWh)
            m.wrongId=true;at(176000)
            assertNull("Mismatched INFO read is never a reported model scale",f.latestVehicleData.value.evBatteryCapacityWh)
            m.wrongId=false;at(182000)
            assertEquals(85990f,f.latestVehicleData.value.evBatteryCapacityWh)
            m.duringPropertyRead={id -> if(id==INFO) {
                val context=f.javaClass.getDeclaredField("context").apply{isAccessible=true}.get(f) as Context
                every {context.checkSelfPermission("android.car.permission.CAR_INFO")} returns PackageManager.PERMISSION_DENIED
            }}
            at(188000)
            assertFalse(f.latestVehicleData.value.evObservationMetadata.containsKey("INFO_EV_BATTERY_CAPACITY"))
            assertNull(f.latestVehicleData.value.evBatteryCapacityWh)
        }
    }
    @Test fun initialReadCannotRelabelWrongPropertyAreaOrType() {
        for (bad in listOf("id","area","type")) fixture(1,initial={m ->
            when(bad) {"id" -> m.wrongId=true; "area" -> m.wrongArea=true; else -> m.badType=true}
        }) { f,_,_,_ ->
            assertNull("Initial read must not publish mismatched Binder value: $bad",f.latestVehicleData.value.evBatteryLevelWh)
        }
    }
    @Test fun exactLiveReadRefreshesContinuousOrUnknownModeWithoutChangingEventTimestamp() {
        for (mode in listOf(2,null)) fixture(mode) { f,m,at,_ ->
            at(170000)
            val o=f.latestVehicleData.value.evObservationMetadata.getValue("EV_BATTERY_LEVEL")
            assertEquals("587 receipt must advance after an actual exact read, mode=$mode",170000L,o.receivedElapsedMs)
            assertEquals(170000L,o.statusReadElapsedMs)
            assertEquals(1_000_000_000L,o.timestampElapsedNanos)
            val adapter=OalCarPlayVehicleSnapshotAdapter({f.latestVehicleData},{170000})
            val caps=adapter.capabilities(false)
            assertTrue(caps.currentEnergy); assertTrue(caps.range); assertFalse(caps.maximumEnergy)
            assertEquals(55430L,adapter.snapshot()!!.currentChargeWh)
            assertNull(adapter.snapshot()!!.maxChargeWh)
            // Telemetry refresh must NOT authorize actual Park for an unknown/continuous gear.
            val policy=CarPlayDrivingRestrictionBridge(f.latestVehicleData,Dispatchers.IO,{170000},15000)
            try { assertNotEquals("actual_park",policy.restrictionReason(f.latestVehicleData.value)) }
            finally { kotlinx.coroutines.runBlocking { policy.close() } }
        }
    }
    @Test fun exactPropertyAreaTypeStatusAndReadFailureAreRequired() {
        for (bad in listOf("id","area","type","status","failure")) fixture(1) { f,m,at,_ ->
            when(bad) { "id" -> m.wrongId=true; "area" -> m.wrongArea=true; "type" -> m.badType=true; "status" -> m.status=1; else -> m.fail=true }
            at(66000)
            assertNull("invalid read must revoke current energy: $bad",f.latestVehicleData.value.evBatteryLevelWh)
            val adapter=OalCarPlayVehicleSnapshotAdapter({f.latestVehicleData},{66000})
            assertFalse(adapter.capabilities(false).currentEnergy)
        }
    }
    @Test fun callbackWinsAnInflightReadIncludingFailure() {
        for (failure in listOf(false,true)) fixture(1) { f,m,at,_ ->
            m.fail=failure
            m.duringRead={
                m.duringRead=null
                f.javaClass.getDeclaredMethod("handleChangeEvent",Any::class.java).apply { isAccessible=true }
                    .invoke(f,Value(BATTERY,55320f))
            }
            at(66000)
            assertEquals(55320f,f.latestVehicleData.value.evBatteryLevelWh)
            assertNull(f.latestVehicleData.value.evObservationMetadata.getValue("EV_BATTERY_LEVEL").statusReadElapsedMs)
        }
    }
    @Test fun replacedManagerOrGenerationCannotCommitInflightRead() {
        for (boundary in listOf("manager","generation","permission","registration")) fixture(1,initial={it.includeUnits=true}) { f,m,at,set ->
            m.duringRead={
                m.duringRead=null
                when(boundary) {
                    "manager" -> set("propertyManager",Manager(1))
                    "generation" -> set("registrationGeneration",2L)
                    "permission" -> {
                        val context=f.javaClass.getDeclaredField("context").apply{isAccessible=true}.get(f) as Context
                        every {context.checkSelfPermission("android.car.permission.CAR_ENERGY")} returns PackageManager.PERMISSION_DENIED
                    }
                    else -> {
                        val ids=f.javaClass.getDeclaredField("trackedPropertyIds").apply{isAccessible=true}.get(f) as MutableSet<*>
                        ids.remove(BATTERY)
                    }
                }
            }
            at(66000)
            val o=f.latestVehicleData.value.evObservationMetadata["EV_BATTERY_LEVEL"]
            assertTrue("stale owner must not publish receipt: $boundary",o==null || o.receivedElapsedMs!=66000L)
            if (boundary in setOf("manager","generation")) assertNotEquals(66000L,
                f.latestVehicleData.value.evObservationMetadata["DISTANCE_DISPLAY_UNITS"]?.receivedElapsedMs)
        }
    }
    companion object { const val UNITS=0x11400600; const val INFO=0x11600106; const val BATTERY=0x11600309; const val RANGE=0x11600308; const val GEAR=0x11400400 }
}
