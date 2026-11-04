package com.openautolink.app.cluster

import com.openautolink.app.secondary.*
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Real optional runtime, manager and AndroidX Surface callbacks; controlled OS/GPU boundaries. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk=[32,34], application=android.app.Application::class, manifest=Config.NONE)
class IgnitionReadinessRuntimeTest {
    @Before fun setup() {
        mockkObject(com.openautolink.app.diagnostics.DiagnosticLog)
        every { com.openautolink.app.diagnostics.DiagnosticLog.i(any(),any()) } just Runs
        every { com.openautolink.app.diagnostics.DiagnosticLog.w(any(),any()) } just Runs
    }
    @After fun after() { unmockkAll() }
    @Test fun cancelledReadinessPublicationCannotEraseSuccessorStatus() {
        val f=RetainedHostRuntimeFixture();val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        every { com.openautolink.app.diagnostics.DiagnosticLog.i(any(),any()) } answers {
            if(secondArg<String>().contains("session=202") && secondArg<String>().contains("stage=readiness_cancelled")) {
                entered.countDown();check(release.await(4,TimeUnit.SECONDS))
            }
        }
        try {
            f.ignition(false);val old=f.start(false,202);Thread.sleep(150);assertFalse(old.isCompleted)
            val replacement=f.scope.async { f.runtime.prepareAndroidAuto(203,f.frozen.copy(outputs=mapOf(f.descriptor.id to SecondaryOutputPreference()))) }
            f.pump { replacement.isCompleted };assertTrue(entered.await(2,TimeUnit.SECONDS))
            f.ignition(true);val successor=f.prepared(false,204);assertTrue(f.offered(successor));assertTrue(f.runtime.statuses.value.getValue(f.descriptor.id).hostAttached)
            release.countDown();f.pump { old.isCompleted }
            assertTrue("A late canceled wait must not erase C's admitted status",f.runtime.statuses.value.getValue(f.descriptor.id).hostAttached)
        } finally { release.countDown();f.close() }
    }
    @Test fun realLegacyMigrationPersistsOnlyAfterOn() = withRealPreferences { prefs ->
        runBlocking { prefs.setClusterOutputSelection(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)) }
        val f=RetainedHostRuntimeFixture(preferences=prefs)
        try {
            f.ignition(false);val frozen=runBlocking { prefs.getSecondaryDisplayPreferences() };assertTrue(frozen.outputs.isEmpty())
            val next=f.scope.async { f.runtime.prepareAndroidAuto(202,frozen) };Thread.sleep(150);assertFalse(next.isCompleted)
            assertTrue(runBlocking { prefs.getSecondaryDisplayPreferences() }.outputs.isEmpty())
            f.ignition(true);f.pump { next.isCompleted }
            val offered = runBlocking { next.await() }.offers.single()
            assertEquals(3, offered.channelId)
            assertEquals("Fresh GM intent must reach the phone as Auxiliary", 2, offered.displayType)
            assertEquals(SecondaryOutputPreference(true,AaSecondaryRole.AUXILIARY,true,true),runBlocking { prefs.getSecondaryDisplayPreferences() }.outputs[f.descriptor.id])
        } finally { f.close() }
    }
    @Test fun explicitGmClusterRoleRemainsClusterInTheActualPhoneOffer() {
        val f = RetainedHostRuntimeFixture()
        try {
            val prepared = f.prepared(false, 205) as PreparedAaSecondaryOutputs
            assertEquals(AaSecondaryRole.CLUSTER, f.frozen.outputs.getValue(f.descriptor.id).aaRole)
            assertEquals(1, prepared.offers.single().displayType)
        } finally { f.close() }
    }

    @Test fun unsetGmRoleUsesAuxiliaryWithoutChangingOtherFrozenIntent() {
        val f = RetainedHostRuntimeFixture()
        try {
            val old = f.frozen.outputs.getValue(f.descriptor.id).copy(aaRole = null)
            val frozen = f.frozen.copy(outputs = mapOf(f.descriptor.id to old))
            val next = f.scope.async { f.runtime.prepareAndroidAuto(206, frozen) }
            f.pump { next.isCompleted }
            assertEquals(2, runBlocking { next.await() }.offers.single().displayType)
            assertEquals(old, frozen.outputs.getValue(f.descriptor.id)) // resolving is memory-only
        } finally { f.close() }
    }

    @Test fun explicitSaveDuringReadinessAffectsOnlyNextConnection() = saveDuringReadiness(false)
    @Test fun missingLegacyRowConcurrentExplicitSaveIsNeverOverwritten() = saveDuringReadiness(true)
    private fun saveDuringReadiness(missing:Boolean)=withRealPreferences { prefs ->
        runBlocking { prefs.setClusterOutputSelection(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)) }
        val f=RetainedHostRuntimeFixture(preferences=prefs)
        try {
            if(!missing) runBlocking { prefs.migrateSecondaryClusterPreference(f.descriptor.id) }
            f.ignition(false);val frozen=runBlocking { prefs.getSecondaryDisplayPreferences() }
            val next=f.scope.async { f.runtime.prepareAndroidAuto(202,frozen) };Thread.sleep(150);assertFalse(next.isCompleted)
            val explicit=SecondaryOutputPreference(false,AaSecondaryRole.CLUSTER,true,true)
            runBlocking { prefs.updateSecondaryDisplayPreferences(frozen.revision,mapOf(f.descriptor.id to explicit)) }
            f.ignition(true);f.pump { next.isCompleted };assertEquals(listOf(3),runBlocking { next.await() }.offers.map { it.channelId })
            val stored=runBlocking { prefs.getSecondaryDisplayPreferences() };assertEquals(explicit,stored.outputs[f.descriptor.id])
            val retired=runBlocking { next.await() }.retire("next connection");f.pump { retired.isDone };assertTrue(retired.get())
            val later=f.scope.async { f.runtime.prepareAndroidAuto(203,stored) };f.pump { later.isCompleted };assertTrue(runBlocking { later.await() }.offers.isEmpty())
        } finally { f.close() }
    }
    private fun withRealPreferences(test:(com.openautolink.app.data.AppPreferences)->Unit) {
        val dir=java.io.File("build/ignition-fixtures/"+java.util.UUID.randomUUID()).apply { mkdirs() }
        val job=SupervisorJob()
        val store=androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope=CoroutineScope(job+Dispatchers.IO)) { java.io.File(dir,"settings.preferences_pb") }
        val prefs=com.openautolink.app.data.AppPreferences::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java).apply { isAccessible=true }.newInstance(store)
        mockkObject(com.openautolink.app.data.AppPreferences.Companion)
        every { com.openautolink.app.data.AppPreferences.getInstance(any()) } returns prefs
        try { test(prefs) } finally { runBlocking { job.cancelAndJoin() };dir.deleteRecursively() }
    }
    @Test fun ticketCancelledDuringSavedLayoutReadNeverLaunchesStaleHost() {
        val f=RetainedHostRuntimeFixture()
        val pref=mockk<com.openautolink.app.data.AppPreferences>()
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=CompletableDeferred<Unit>()
        val authority=java.util.concurrent.atomic.AtomicBoolean(true)
        every { com.openautolink.app.data.AppPreferences.getInstance(any()) } returns pref
        coEvery { pref.getClusterLayout(any(),any()) } coAnswers {
            entered.countDown();release.await()
            com.openautolink.app.data.ClusterLayoutRead(null,com.openautolink.app.data.ClusterLayoutUnavailableReason.NO_CUSTOM_LAYOUT)
        }
        try {
            val next=f.scope.async { f.runtime.prepareAndroidAuto(202,f.frozen) { authority.get() } }
            assertTrue(entered.await(2,TimeUnit.SECONDS));authority.set(false);release.complete(Unit);f.pump { next.isCompleted }
            assertTrue(runBlocking { next.await() }.offers.isEmpty())
            assertTrue("No stale renderer/manager may be launched after suspended read",f.bridges.isEmpty())
            assertNull(ClusterBindingState.frozenMapBinding())
        } finally { release.complete(Unit);f.close() }
    }
    @Test fun legacyMissingModernIntentWaitsAndMigratesAfterQualification() {
        val f=RetainedHostRuntimeFixture()
        val pref=mockk<com.openautolink.app.data.AppPreferences>()
        val migrated=java.util.concurrent.atomic.AtomicInteger()
        every { com.openautolink.app.data.AppPreferences.getInstance(any()) } returns pref
        coEvery { pref.getClusterLayout(any(),any()) } returns com.openautolink.app.data.ClusterLayoutRead(null, com.openautolink.app.data.ClusterLayoutUnavailableReason.NO_CUSTOM_LAYOUT)
        coEvery { pref.migrateSecondaryClusterPreference(f.descriptor.id) } answers { migrated.incrementAndGet(); f.frozen }
        try {
            f.ignition(false)
            val frozen=f.frozen.copy(outputs=emptyMap())
            val next=f.scope.async { f.runtime.prepareAndroidAuto(202,frozen) }
            Thread.sleep(150)
            assertFalse("Legacy enabled intent must survive initially OFF target",next.isCompleted)
            assertEquals(0,migrated.get())
            f.ignition(true); f.pump { next.isCompleted }
            assertEquals(listOf(3),runBlocking { next.await() }.offers.map { it.channelId })
            assertEquals(1,migrated.get())
        } finally { f.close() }
    }

    @Test fun offForeverIsBoundedAndLateOnNeverHotAdds() {
        val f=RetainedHostRuntimeFixture()
        try {
            f.ignition(false); val start=System.nanoTime(); val next=f.start(false,202)
            f.pump { next.isCompleted }; val frozen=runBlocking { next.await() } as PreparedAaSecondaryOutputs
            val elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)
            assertTrue("Must wait boundedly, not immediate freeze",elapsed>=1400)
            assertTrue("One budget",elapsed<1900); assertTrue(frozen.offers.isEmpty()); assertTrue(f.bridges.isEmpty())
            f.ignition(true); Thread.sleep(150); assertTrue(frozen.offers.isEmpty()); assertTrue(f.bridges.isEmpty())
        } finally { f.close() }
    }
    @Test fun readinessAndHeldHostShareOneDeadline() {
        val f=RetainedHostRuntimeFixture()
        try {
            val a=f.prepared(false,201);val old=f.retire(a);f.pump { old.isDone }
            f.ignition(false);f.attachHosts=false
            val start=System.nanoTime();val next=f.start(false,202);Thread.sleep(809);f.ignition(true)
            f.pump { next.isCompleted }
            assertTrue((runBlocking { next.await() } as PreparedAaSecondaryOutputs).offers.isEmpty())
            val elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)
            assertTrue("Readiness may not grant a fresh host budget: elapsed=$elapsed",elapsed<1900)
        } finally { f.close() }
    }
    @Test fun cancelledTicketDoesNotLaunchLateHost() {
        val f=RetainedHostRuntimeFixture(); val authority=java.util.concurrent.atomic.AtomicBoolean(true)
        try {
            f.ignition(false);val next=f.scope.async { f.runtime.prepareAndroidAuto(202,f.frozen) { authority.get() } }
            Thread.sleep(150);assertFalse(next.isCompleted);authority.set(false);f.ignition(true);f.pump { next.isCompleted }
            assertTrue(runBlocking { next.await() }.offers.isEmpty());assertTrue(f.bridges.isEmpty())
            val successor=f.prepared(false,203);assertTrue(f.offered(successor))
            val old=runBlocking { next.await() }.retire("late predecessor"); f.pump { old.isDone }
            assertTrue(requireNotNull(f.binding).coordinator.isHostAttached())
        } finally { f.close() }
    }
    @Test fun coroutineCancellationWhileWaitingDoesNotLaunch() {
        val f=RetainedHostRuntimeFixture()
        try {
            f.ignition(false);val next=f.start(false,202);Thread.sleep(150);assertFalse(next.isCompleted)
            next.cancel();f.pump { next.isCompleted };f.ignition(true);Thread.sleep(100);assertTrue(f.bridges.isEmpty())
        } finally { f.close() }
    }
    @Test fun replacedAttemptNeverRetiresSuccessor() {
        val f=RetainedHostRuntimeFixture()
        try {
            f.ignition(false);val old=f.start(false,202);Thread.sleep(150);assertFalse(old.isCompleted)
            val replace=f.scope.async { f.runtime.prepareAndroidAuto(203,f.frozen.copy(outputs=mapOf(f.descriptor.id to SecondaryOutputPreference()))) }
            f.pump { replace.isCompleted && old.isCompleted };assertTrue((runBlocking { old.await() } as PreparedAaSecondaryOutputs).offers.isEmpty())
            f.ignition(true);val successor=f.prepared(false,204);assertTrue(f.offered(successor))
            val ack=f.retire(runBlocking { old.await() });f.pump { ack.isDone };assertTrue(requireNotNull(f.binding).coordinator.isHostAttached())
        } finally { f.close() }
    }
    @Test fun carPlayPredecessorOffOnReturnsWithAndroidAutoOffer() {
        val f=RetainedHostRuntimeFixture()
        try {
            val a=f.prepared(true,201);assertTrue(f.offered(a));val ack=f.retire(a);f.pump { ack.isDone };assertTrue(ack.get())
            f.ignition(false);val next=f.start(false,202);Thread.sleep(150);assertFalse(next.isCompleted)
            f.ignition(true);f.pump { next.isCompleted };assertEquals(listOf(3),(runBlocking { next.await() } as PreparedAaSecondaryOutputs).offers.map { it.channelId })
        } finally { f.close() }
    }
    @Test fun unknownVehicleDoesNotWait()=rejected { f -> org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java,"PRODUCT","unknown");f.frozen }
    @Test fun privatePhysicalTargetDoesNotWait()=rejected { f -> every { f.display.flags } returns android.view.Display.FLAG_PRIVATE;f.frozen }
    @Test fun ambiguousPhysicalTargetsDoNotWait()=rejected { f ->
        val passenger=mockk<android.view.Display>(relaxed=true)
        every { passenger.displayId } returns 8;every { passenger.getRealSize(any()) } answers { firstArg<android.graphics.Point>().set(2000,810) }
        every { f.displays.displays } returns arrayOf(f.display,passenger);f.frozen
    }
    @Test fun disabledOutputDoesNotWait()=rejected { f -> f.frozen.copy(outputs=mapOf(f.descriptor.id to SecondaryOutputPreference(false,AaSecondaryRole.CLUSTER,true,true))) }
    @Test fun disabledAaDoesNotWait()=rejected { f -> f.frozen.copy(outputs=mapOf(f.descriptor.id to SecondaryOutputPreference(true,AaSecondaryRole.CLUSTER,false,true))) }
    @Test fun unsetGmRoleWaitsForQualifiedTargetThenUsesAuxiliary() {
        val f=RetainedHostRuntimeFixture()
        try {
            f.ignition(false)
            val row=SecondaryOutputPreference(true,null,true,true)
            val frozen=f.frozen.copy(outputs=mapOf(f.descriptor.id to row))
            val next=f.scope.async { f.runtime.prepareAndroidAuto(202,frozen) }
            Thread.sleep(150);assertFalse("Unset GM role now defaults to Auxiliary and must wait for its exact target",next.isCompleted)
            f.ignition(true);f.pump { next.isCompleted }
            assertEquals(2,runBlocking { next.await() }.offers.single().displayType)
            assertEquals(row,frozen.outputs.getValue(f.descriptor.id))
        } finally { f.close() }
    }
    @Test fun changedIdentityCannotReplaceWaitingTarget() {
        val f=RetainedHostRuntimeFixture()
        try {
            f.ignition(false);val next=f.start(false,202);Thread.sleep(150);assertFalse(next.isCompleted)
            f.ignition(true);f.inventory.value=SecondaryInventorySnapshot(4,listOf(f.descriptor.copy(id=SecondaryOutputId("different"),attachmentEpoch=4)))
            f.pump { next.isCompleted };assertTrue((runBlocking { next.await() } as PreparedAaSecondaryOutputs).offers.isEmpty());assertTrue(f.bridges.isEmpty())
        } finally { f.close() }
    }
    private fun rejected(configure:(RetainedHostRuntimeFixture)->SecondaryPreferencesSnapshot) {
        val f=RetainedHostRuntimeFixture()
        try {
            f.ignition(false);val prefs=configure(f);val start=System.nanoTime()
            val next=f.scope.async { f.runtime.prepareAndroidAuto(202,prefs) };f.pump { next.isCompleted }
            assertTrue(runBlocking { next.await() }.offers.isEmpty());assertTrue(f.bridges.isEmpty())
            assertTrue("No broad readiness wait",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<700)
        } finally { f.close() }
    }
    @Test fun offTargetRequalifiesAt809msAndOffersOnlyAfterRealHostAck() {
        val f=RetainedHostRuntimeFixture()
        try {
            // Warm actual host construction first, just as the captured same-process return.
            val a=f.prepared(false,201); assertTrue(f.offered(a))
            val retired=f.retire(a); f.pump { retired.isDone }; assertTrue(retired.get())
            f.ignition(false)
            val start=System.nanoTime()
            val next=f.start(false,202)
            Thread.sleep(809)
            assertFalse("OFF preparation must wait for exact target, not freeze primary-only",next.isCompleted)
            assertEquals(1,f.bridges.size)
            f.ignition(true)
            f.pump { next.isCompleted }
            val result=runBlocking { next.await() } as PreparedAaSecondaryOutputs
            assertEquals(listOf(3),result.offers.map { it.channelId })
            assertEquals(listOf(1),result.offers.map { it.protocolDisplayId })
            assertEquals(202L,requireNotNull(f.binding).snapshot.lease.projectionEpoch)
            assertTrue(requireNotNull(f.binding).coordinator.isHostAttached())
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1900)
        } finally { f.close() }
    }
}
