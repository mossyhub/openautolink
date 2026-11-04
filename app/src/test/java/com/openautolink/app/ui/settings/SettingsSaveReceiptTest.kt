package com.openautolink.app.ui.settings

import android.app.Application
import com.openautolink.app.cluster.*
import com.openautolink.app.diagnostics.DiagnosticLog
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34], application=Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SettingsSaveReceiptTest {
    @After fun cleanup() { unmockkAll() }
    private fun adapter(storage: ClusterSettingsRuntimeAdapter.LayoutStorage, scope: CoroutineScope) = runBlocking {
        val profile=ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val legacy=PixelRect(0,135,2000,810)
        val display=requireNotNull(ClusterDisplayProfiles.qualify(profile.vehicle,
            listOf(ClusterDisplayBinding(profile.role,7)),ClusterHostGeometry(7,profile.panel,legacy,legacy)))
        ClusterSettingsRuntimeAdapter.create(display,MutableStateFlow(ClusterSettingsObservation()),storage,null,scope,true)
    }
    @Test fun cancelledMigrationRetainsDirtyDraftAndExplicitRetryThenCleanAndClosedAreDistinct() = runBlocking {
        val events=mutableListOf<String>()
        mockkObject(DiagnosticLog)
        every { DiagnosticLog.i(any(),any()) } answers { events+=secondArg<String>();Unit }
        var cancel=true
        var writes=0
        val storage=object:ClusterSettingsRuntimeAdapter.LayoutStorage {
            override suspend fun read(automatic:ClusterLayoutModel,profileId:String)=automatic.copy(
                canvas=automatic.essentialCanvas,output=automatic.essentialCanvas,safe=PixelRect(617,135,1452,611))
            override suspend fun write(automatic:ClusterLayoutModel,profileId:String,custom:ClusterLayoutModel?):Boolean {
                writes++
                if(cancel) throw CancellationException("secret fixture")
                assertEquals(PixelRect(98,65,1904,745),custom!!.output)
                return true
            }
        }
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val a=adapter(storage,owner)
        try {
            val draft=a.bindings.value.layout!!
            val first=SettingsSaveTransaction.next()
            try { withContext(first) { a.bindings.value.commitLayoutDraft() };fail("Cancellation must propagate") }
            catch (_:CancellationException) { }
            assertEquals(draft,a.bindings.value.layout)
            assertTrue(events.any { it.contains("click=${first.click}") && it.contains("stage=durable result=cancelled") })
            cancel=false
            withContext(SettingsSaveTransaction.next()) { assertTrue(a.bindings.value.commitLayoutDraft()) }
            assertEquals(2,writes)
            withContext(SettingsSaveTransaction.next()) { assertTrue(a.bindings.value.commitLayoutDraft()) }
            assertEquals(2,writes)
            assertTrue(events.any { it.contains("stage=adapter result=clean") })
            a.close()
            withContext(SettingsSaveTransaction.next()) { assertFalse(a.bindings.value.commitLayoutDraft()) }
            assertTrue(events.any { it.contains("stage=adapter result=closed") })
            assertFalse(events.any { it.contains("secret fixture") })
            assertTrue(events.filter { it.contains("SETTINGS_SAVE_V1") }.all { it.length<1024 })
        } finally { a.close();owner.cancel() }
    }
    @Test fun diagnosticSinkFailureCannotPreventActualDirtyWrite() = runBlocking {
        mockkObject(DiagnosticLog)
        every { DiagnosticLog.i(any(),match { it.startsWith("SETTINGS_SAVE_V1") }) } throws IllegalStateException("sink")
        every { DiagnosticLog.i(any(),match { !it.startsWith("SETTINGS_SAVE_V1") }) } just Runs
        var written=false
        val storage=object:ClusterSettingsRuntimeAdapter.LayoutStorage {
            override suspend fun read(automatic:ClusterLayoutModel,profileId:String)=automatic.copy(canvas=automatic.essentialCanvas,output=automatic.essentialCanvas)
            override suspend fun write(automatic:ClusterLayoutModel,profileId:String,custom:ClusterLayoutModel?)=true.also { written=true }
        }
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val a=adapter(storage,owner)
        try { withContext(SettingsSaveTransaction.next()) { assertTrue(a.bindings.value.commitLayoutDraft()) };assertTrue(written) }
        finally { a.close();owner.cancel() }
    }
}
