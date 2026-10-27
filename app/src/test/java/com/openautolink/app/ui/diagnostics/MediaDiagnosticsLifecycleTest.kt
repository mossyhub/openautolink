package com.openautolink.app.ui.diagnostics

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.lifecycle.SavedStateHandle
import org.junit.*
import org.junit.Assert.*
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.openautolink.app.diagnostics.*

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [32, 34], application = Application::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class MediaDiagnosticsLifecycleTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Test fun oneOutstandingCanceledPickerConsumesLateUriBeforeAdmittingSuccessor() {
        val deleted = CountDownLatch(1); var writes = 0
        val vm = MediaDiagnosticsViewModel(app, SavedStateHandle(), MediaDiagnosticsDependencies(
            export = { _, _, _ -> writes++; MediaExportReceipt(MediaExportCode.SAVED) }, delete = { deleted.countDown(); true }))
        vm.requestExport(); val first = vm.claimPicker()!!
        vm.requestExport(); assertNull(vm.claimPicker())
        vm.cancelExport(); assertTrue(vm.state.value.exportBusy)
        vm.requestExport(); assertEquals(first, vm.state.value.pickerId)
        vm.onPickerResult(Uri.parse("content://private.canary/new"))
        assertTrue(deleted.await(5, TimeUnit.SECONDS)); drain { !vm.state.value.exportBusy }
        assertEquals(0, writes)
        vm.requestExport(); assertNotEquals(first, vm.claimPicker())
    }
    @Test fun restoredPickerIsCancelOnlyNeverAutomaticallyLaunched() {
        val vm = MediaDiagnosticsViewModel(app, SavedStateHandle(mapOf("media_export_picker" to "old", "media_export_claimed" to true)))
        assertNull(vm.claimPicker()); assertTrue(vm.state.value.exportBusy)
        vm.onPickerResult(null)
        assertEquals(MediaExportPhase.INTERRUPTED, vm.state.value.exportPhase)
        vm.requestExport(); assertNotNull(vm.claimPicker())
    }
    @Test fun cancelAfterExporterCommittedCannotDeleteCompletedDocument() {
        val committed = CountDownLatch(1); val release = CountDownLatch(1); var deleted = false
        val vm = MediaDiagnosticsViewModel(app, SavedStateHandle(), MediaDiagnosticsDependencies(export = { _, cancel, _ ->
            cancel.commit(); committed.countDown(); check(release.await(5, TimeUnit.SECONDS)); MediaExportReceipt(MediaExportCode.SAVED)
        }, delete = { deleted = true; true }))
        vm.requestExport(); vm.claimPicker(); vm.onPickerResult(Uri.EMPTY)
        drain { committed.count == 0L }
        vm.cancelExport(); release.countDown(); drain { !vm.state.value.exportBusy }
        assertEquals(MediaExportPhase.SAVED, vm.state.value.exportPhase)
        assertFalse(deleted)
    }
    @Test fun registryUsesActualCreateDocumentAndRetainsOwnerAcrossRecreation() {
        val controller = Robolectric.buildActivity(MediaRegistryActivity::class.java).setup()
        val activity = controller.get(); val vm = activity.vm
        vm.requestExport()
        var launched: org.robolectric.shadows.ShadowActivity.IntentForResult? = null
        drain { if (launched == null) launched = shadowOf(activity).nextStartedActivityForResult; launched != null }
        assertEquals(android.content.Intent.ACTION_CREATE_DOCUMENT, launched!!.intent.action)
        assertEquals("application/zip", launched!!.intent.type)
        // Request is claimed exactly once even after lifecycle re-entry.
        assertNull(vm.claimPicker())
        controller.recreate()
        val recreated = controller.get()
        assertSame(vm, recreated.vm)
        assertNull(shadowOf(recreated).nextStartedActivityForResult)
        vm.cancelExport()
        assertTrue(recreated.activityResultRegistry.dispatchResult(launched!!.requestCode, android.app.Activity.RESULT_CANCELED, null))
        drain { !vm.state.value.exportBusy }
        controller.pause().stop().destroy()
    }
    @Test fun cancellationRemainsClosingUntilWriterAcknowledgesAndCannotPublishLateSaved() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val vm = MediaDiagnosticsViewModel(app, SavedStateHandle(), MediaDiagnosticsDependencies(export = { _, _, _ ->
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); MediaExportReceipt(MediaExportCode.SAVED)
        }, delete = { true }))
        vm.requestExport(); vm.claimPicker(); vm.onPickerResult(Uri.EMPTY)
        drain { entered.count == 0L }
        vm.cancelExport(); assertEquals(MediaExportPhase.CLOSING, vm.state.value.exportPhase)
        vm.requestExport(); assertTrue(vm.state.value.exportBusy)
        release.countDown(); drain { !vm.state.value.exportBusy }
        assertEquals(MediaExportPhase.CANCELED, vm.state.value.exportPhase)
    }
    private fun drain(done: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!done() && System.nanoTime() < end) { shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.yield() }
        assertTrue(done())
    }
}

class MediaRegistryActivity : ComponentActivity() {
    lateinit var vm: MediaDiagnosticsViewModel
    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        vm = androidx.lifecycle.ViewModelProvider(this)[MediaDiagnosticsViewModel::class.java]
        bindMediaDiagnosticsExport(this, vm)
    }
}
