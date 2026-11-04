package com.openautolink.app.diagnostics.gm

import android.app.Application
import android.content.Intent
import android.os.Looper
import com.openautolink.app.OalApplication
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

/** Own-process callback fixtures. Not OEM dispatch, permission grants or physical discovery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34], application=Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class GmAdmissionDiagnosticsTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Before fun reset() {
        GmAdmissionDiagnostics.awaitIdleForTest()
        File(context.noBackupFilesDir,"gm-page-admission").deleteRecursively()
        File(context.noBackupFilesDir,"gm-page-observer").deleteRecursively()
    }
    @Test fun callbackEntryIsRetainedWhenSavedOffPreventsReceipt() {
        assertTrue(GmObserverStore(context).setEnabled(false))
        val controller=Robolectric.buildService(GmPageObserverService::class.java).create()
        controller.get().onStartCommand(Intent(GmPageObserverService.FULL_MAP_SELECTED),0,1)
        await{GmAdmissionDiagnostics.read(context).optLong("START_RECOGNIZED")>=1}
        val diagnostic=GmAdmissionDiagnostics.read(context)
        assertEquals(1L,diagnostic.getLong("CREATE"))
        assertEquals(1L,diagnostic.getLong("CONSENT_OFF"))
        assertEquals(0L,GmObserverStore(context).read().received)
        assertFalse(diagnostic.toString().contains("gm.cluster.action"))
        controller.destroy()
    }
    @Test fun failedStateWriteIsNotReportedAsNoDeliveryOrGenerationChange() {
        val store=GmObserverStore(context)
        val epoch=store.read().generation
        val dir=File(context.noBackupFilesDir,"gm-page-observer")
        // Real filesystem failure at AtomicFile.startWrite; no OEM grant is simulated.
        dir.mkdirs();File(dir,"state.new").mkdirs();File(dir,"state.new/block").writeText("occupied")
        assertEquals(GmReceiveResult.PERSISTENCE_UNAVAILABLE,store.receiveResult(epoch,true,1,1))
        assertEquals(0L,store.read().received)
        File(dir,"state.new").deleteRecursively()
        assertEquals(GmReceiveResult.COMMITTED,store.receiveResult(epoch,true,1,1))
        assertEquals(1L,store.read().received)
    }
    @Test fun generationAndOffHaveDifferentResultsAndActualPositiveCommit() {
        val store=GmObserverStore(context);val old=store.read().generation
        assertTrue(store.setEnabled(false))
        assertEquals(GmReceiveResult.CONSENT_OFF,store.receiveResult(old,true,1,1))
        assertTrue(store.setEnabled(true))
        assertEquals(GmReceiveResult.GENERATION_CHANGED,store.receiveResult(old,true,1,1))
        assertEquals(GmReceiveResult.COMMITTED,store.receiveResult(store.read().generation,true,1,1))
        assertEquals(1L,store.read().received)
    }
    @Test fun readOnlyDiscoveryUsesExactGrammarAndDoesNotClaimOemMembership() {
        val facts=GmDiscoveryDiagnostics.read(context)
        assertEquals(0xc0080,GmDiscoveryDiagnostics.QUERY_FLAGS)
        assertTrue(facts.getBoolean("ownQueryMatches"))
        assertTrue(facts.getBoolean("metadataMatches"))
        assertEquals("UNKNOWN",facts.getString("oemCachedMembership"))
        assertTrue(facts.has("ownUserSerial"))
        assertEquals("NOT_VISIBLE_OR_NOT_INSTALLED",facts.getString("senderVisibility"))
        assertFalse(facts.has("senderUid"))
    }
    @Test fun strictWireShapeStaysThirteenFieldsAndNewDiagnosticsNeverContainActions() {
        val wire=GmObserverDiagnostics.snapshot(context)
        assertEquals(13,wire.length())
        System.getProperty("gm.admission.receipt.dir")?.let { output ->
            File(output).mkdirs()
            File(output,"gm-wire-api${android.os.Build.VERSION.SDK_INT}.json").writeText(wire.toString())
        }
        assertFalse(wire.has("admission"));assertFalse(wire.has("discovery"))
        val controller=Robolectric.buildService(GmPageObserverService::class.java).create()
        controller.get().onStartCommand(Intent("SECRET-"+"x".repeat(50000)),0,1)
        await{GmAdmissionDiagnostics.read(context).optLong("START_OTHER")>=1}
        val text=GmAdmissionDiagnostics.read(context).toString()
        assertFalse(text.contains("SECRET"));assertTrue(text.length<4096)
        controller.destroy()
    }
    @Test fun realServiceWorkerReportsFailedCommitThenSuccessfulReceiptWithoutPayloadInDiagnostics() {
        val controller=Robolectric.buildService(GmPageObserverService::class.java).create()
        val stateDir=File(context.noBackupFilesDir,"gm-page-observer").apply{mkdirs()}
        File(stateDir,"state.new").mkdirs();File(stateDir,"state.new/block").writeText("occupied")
        controller.get().onStartCommand(Intent(GmPageObserverService.FULL_MAP_SELECTED),0,1)
        await{GmAdmissionDiagnostics.read(context).optLong("COMMIT_UNAVAILABLE")>=1}
        assertEquals(0L,GmObserverStore(context).read().received)
        File(stateDir,"state.new").deleteRecursively()
        controller.get().onStartCommand(Intent(GmPageObserverService.FULL_MAP_SELECTED),0,2)
        await{GmAdmissionDiagnostics.read(context).optLong("COMMIT_OK")>=1}
        assertEquals(1L,GmObserverStore(context).read().received)
        assertTrue(GmAdmissionDiagnostics.read(context).getLong("START_RECOGNIZED")>=2)
        controller.destroy()
    }
    @Test fun corruptAdmissionStateIsUnavailableNotSavedOffAndStillCountsCallbackEntry() {
        val stateDir=File(context.noBackupFilesDir,"gm-page-observer").apply{mkdirs()}
        File(stateDir,"state").writeText("not-json")
        val controller=Robolectric.buildService(GmPageObserverService::class.java).create()
        controller.get().onStartCommand(Intent(GmPageObserverService.FULL_MAP_SELECTED),0,1)
        await{GmAdmissionDiagnostics.read(context).optLong("START_RECOGNIZED")>=1}
        val diagnostic=GmAdmissionDiagnostics.read(context)
        assertEquals(1L,diagnostic.getLong("CONFIG_UNAVAILABLE"))
        assertEquals(0L,diagnostic.optLong("CONSENT_OFF"))
        assertFalse(GmObserverStore(context).read().available)
        controller.destroy()
    }
    @Test fun absentOrCorruptBreadcrumbsAreUnavailableNotEvidenceOfNoCallback() {
        val empty=GmAdmissionDiagnostics.read(context)
        assertFalse(empty.getBoolean("hasPersisted"))
        val directory=File(context.noBackupFilesDir,"gm-page-admission")
        File(directory,"counts").writeText("SECRET-"+"x".repeat(5000))
        val unavailable=GmAdmissionDiagnostics.read(context)
        assertFalse(unavailable.getBoolean("available"))
        assertFalse(unavailable.toString().contains("SECRET"))
    }
    private fun await(predicate:()->Boolean) {
        val until=System.nanoTime()+5_000_000_000L
        while(!predicate() && System.nanoTime()<until){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)}
        assertTrue(predicate())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34], application=OalApplication::class,shadows=[ObserverProcessApplicationShadow::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class GmAdmissionApplicationTest {
    @Test fun actualEarlyBranchPersistsBreadcrumbWithoutNormalRuntime() {
        val app=RuntimeEnvironment.getApplication()
        GmAdmissionDiagnostics.awaitIdleForTest()
        assertTrue(GmAdmissionDiagnostics.read(app).optLong("APPLICATION")>=1)
        assertNull((app as OalApplication).carBluetoothConnectionObserver)
        assertNull(com.openautolink.app.session.SessionManager.instanceOrNull())
        assertFalse(com.openautolink.app.diagnostics.live.LiveDiagnostics.ready.value)
    }
}
