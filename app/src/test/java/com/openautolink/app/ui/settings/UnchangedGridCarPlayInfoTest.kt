package com.openautolink.app.ui.settings

import android.app.Application
import android.app.ActivityOptions
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Looper
import android.view.Display
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import com.openautolink.app.cluster.*
import com.openautolink.app.data.*
import com.openautolink.app.carplay.CarPlayUiState
import com.openautolink.app.session.SessionState
import com.openautolink.app.ui.testing.createOalComposeRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers
import java.io.File
import io.mockk.*
import com.openautolink.app.secondary.*

/** Supported legacy adapter/resolver coverage, deliberately not the public Directions entrypoint.
 * Real settings binding + production initial qualifier + real DataStore + rendered editor and
 * direct Activity. OS public display/Build identity are controlled fixtures, not a GM device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class, qualifiers = "w1000dp-h360dp-land-mdpi",
    shadows = [LiveGridWindowActivityTest.Admission::class, ClusterDirectVideoLifecycleTest.TextureBoundary::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class UnchangedGridCarPlayInfoTest {
    @Before fun setup() { mockkObject(com.openautolink.app.diagnostics.DiagnosticLog); every { com.openautolink.app.diagnostics.DiagnosticLog.i(any(),any()) } just Runs; every { com.openautolink.app.diagnostics.DiagnosticLog.w(any(),any()) } just Runs }
    @After fun cleanup() { unmockkAll() }
    @get:Rule val compose = createOalComposeRule()
    private val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
    private val legacy = PixelRect(0,135,2000,810)
    private val scope = ClusterLayoutScope(profile.vehicle, profile.id, profile.role, profile.panel)

    private fun journey(carPlay: Boolean, output: PixelRect, expected: PixelRect) = runBlocking<Unit> {
        val app = RuntimeEnvironment.getApplication()
        val oldProduct = Build.PRODUCT
        val oldBuild = Build.ID
        val oldRevision = Build.VERSION.INCREMENTAL
        ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", "aegean_orange")
        ReflectionHelpers.setStaticField(Build::class.java, "ID", profile.vehicle.buildFamily)
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "INCREMENTAL", profile.vehicle.buildRevision.toString())
        LiveGridWindowActivityTest.allowed = true
        val id = ShadowDisplayManager.addDisplay("w2000dp-h810dp-mdpi", "Controlled physical cluster")
        val physical = app.getSystemService(DisplayManager::class.java).getDisplay(id)
        shadowOf(physical).setFlags(Display.FLAG_PRESENTATION); shadowOf(physical).setState(Display.STATE_ON)
        val inventoryFixture = RawDisplayTestFixture(app)
        val liveTarget = inventoryFixture.target(id,2000,810,com.openautolink.app.secondary.SecondaryRoute.GM_HOSTED)
        val initial = requireNotNull(ClusterProjectionOutput.qualifiedInitialDisplay(app))
        assertEquals(legacy, initial.host.visible) // Real shipped initial qualifier, not an injected model.
        var job = SupervisorJob()
        val file = File(app.filesDir, "direct-journey-${java.util.UUID.randomUUID()}.preferences_pb")
        fun stored(): AppPreferences {
            val store=androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                scope=CoroutineScope(job+Dispatchers.IO),produceFile={file})
            return AppPreferences::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java)
                .apply { isAccessible=true }.newInstance(store)
        }
        var preferences=stored()
        val safe = PixelRect(617,135,1452,611) // Exact latest restored upload intent.
        val saved = SavedClusterLayout(scope, legacy, output, safe)
        preferences.saveClusterLayout(saved)
        val session = MutableStateFlow(SessionState.CONNECTING)
        val aa = MutableStateFlow(ClusterProjectionStatus())
        val cp = MutableStateFlow(CarPlayUiState())
        val preview = ClusterCalibrationPreview(ClusterCalibrationPreview.Backend { _,_,_,_,_ ->
            error("Settings must not create a hosted phone surface") }, { 0L }, { _,_ -> {} })
        var mounted by mutableStateOf(true)
        var opened by mutableStateOf(false)
        var bound: ClusterSettingsBindings? = null
        var activity: ActivityController<LiveGridWindowActivity>? = null
        var predecessor: ActivityController<LiveGridWindowActivity>? = null
        var runtimeFixture: RetainedHostRuntimeFixture? = null
        try {
            compose.setContent {
                if (mounted) {
                    val binding = rememberProductionClusterSettingsBinding(app, preferences, session, aa, cp, preview)
                    bound = binding
                    if (opened && binding != null) MaterialTheme { ClusterLayoutEditor(binding) { opened = false } }
                }
            }
            compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); bound?.liveGrid != null }
            // Projection ends before editor Start becomes permitted. No cached host survives.
            compose.runOnIdle { session.value = SessionState.IDLE; opened = true }
            compose.waitForIdle()
            assertEquals(expected, requireNotNull(bound?.layout).output)
            assertEquals(safe, bound?.layout?.safe)
            assertNull(bound?.actualSurface)
            compose.onNodeWithTag("liveGridStart").performClick()
            compose.runOnIdle {
                val intent = requireNotNull(shadowOf(app).nextStartedActivity)
                activity = ActivityController.of(LiveGridWindowActivity(), intent,
                    ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle()).create().start().resume()
                val attrs = requireNotNull(activity).get().window.attributes
                assertEquals(expected.left, attrs.x); assertEquals(expected.top, attrs.y)
                assertEquals(expected.width, attrs.width); assertEquals(expected.height, attrs.height)
            }
            val after=requireNotNull(bound?.layout)
            assertEquals(profile.output,after.output);assertEquals(safe,after.safe)
            compose.onNodeWithTag("liveGridBack").performClick()
            assertTrue(requireNotNull(bound).commitLayoutDraft())
            val committed = requireNotNull(preferences.getClusterLayout(scope, after.canvas).custom)
            assertEquals(after.output, committed.output); assertEquals(after.safe, committed.safe)
            compose.runOnIdle { mounted = false }; compose.waitForIdle()
            job.cancelAndJoin();job=SupervisorJob();preferences=stored() // actual cold file-backed reopen
            compose.runOnIdle { bound = null; opened = false; mounted = true }
            compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); bound?.layout != null }
            assertEquals(after, bound?.layout)
            // The supported legacy adapter/resolver retains its own durable geometry. It is
            // NOT permission for today's direct host to reshape the map output or adopt AndroidX.
            val rejected=ClusterSavedLayoutResolver.resolve(initial,preferences::getClusterLayout)
            if(after.output.top<135) assertEquals(ClusterLayoutFallbackReason.CANVAS_CHANGED,rejected.fallbackReason)
            val expanded=requireNotNull(ClusterDisplayProfiles.qualify(profile.vehicle,
                listOf(ClusterDisplayBinding(profile.role,id)),
                ClusterHostGeometry(id,profile.panel,profile.panel.bounds,legacy)))
            val legacyResolved=ClusterSavedLayoutResolver.resolve(expanded,preferences::getClusterLayout)
            assertNull(legacyResolved.fallbackReason)
            assertEquals(after.output,legacyResolved.display.effectiveOutput);assertEquals(after.safe,legacyResolved.display.safe)
            mockkObject(AppPreferences.Companion)
            every { AppPreferences.getInstance(any()) } returns preferences
            val f=RetainedHostRuntimeFixture(preferences,nativeGraphics = true).also { runtimeFixture=it }
            preferences.setClusterOutputSelection(f.selection)
            preferences.updateSecondaryDisplayPreferences(preferences.getSecondaryDisplayPreferences().revision,f.frozen.outputs)
            f.frozen=preferences.getSecondaryDisplayPreferences()
            val first=f.prepared(carPlay,501);assertTrue(f.offered(first))
            val oldMapping=f.runtime.mapping(f.descriptor.id)!!
            val exactOwner=f.owner()!!.handle!!.owner
            assertEquals(profile.panel.bounds,oldMapping.target.output)
            assertNull(ClusterBindingState.frozenMapBinding())
            fun normalized(rect:PixelRect)=SecondaryLayoutBox(rect.left/2000.0,rect.top/810.0,rect.right/2000.0,rect.bottom/810.0)
            val directLayout=SecondaryLayoutPreference(
                SecondaryLayoutScope(f.descriptor.id,Build.PRODUCT,Build.FINGERPRINT,2000,810,160),
                normalized(after.output),normalized(after.safe),directSafe=normalized(after.safe))
            val oldPrefs=preferences.getSecondaryDisplayPreferences()
            preferences.updateSecondaryDisplayPreferences(oldPrefs.revision,
                oldPrefs.outputs + (f.descriptor.id to oldPrefs.outputs.getValue(f.descriptor.id).copy(layout=directLayout)))
            assertSame("Save cannot mutate negotiated mapping",oldMapping,f.runtime.mapping(f.descriptor.id))
            assertFalse("Direct path has no retained AndroidX manager",f.runtime.retainForExplicitReconnect())
            val firstConfiguration = if (carPlay) (first as PreparedCarPlaySecondaryOutput).configuration else null
            val retired=f.retire(first);f.pump { retired.isDone };assertTrue(retired.get())
            if (carPlay) assertNull((first as PreparedCarPlaySecondaryOutput).configuration)
            assertFalse(exactOwner.isCurrent);assertEquals(1,f.detached.get())
            f.frozen=preferences.getSecondaryDisplayPreferences()
            val next=f.prepared(carPlay,502);assertTrue(f.offered(next))
            assertNotSame(exactOwner,f.owner()!!.handle!!.owner)
            assertEquals(502L,f.owner()!!.handle!!.owner.projectionEpoch)
            assertEquals(2,f.physical.size);assertEquals(2,f.bridges.size)
            val mapping=f.runtime.mapping(f.descriptor.id)!!
            assertEquals(profile.panel.bounds,mapping.target.output)
            assertEquals(after.safe,mapping.target.safe)
            assertEquals(committed,preferences.getClusterLayout(scope,after.canvas).custom)
            val receipt=System.getProperty("handoff.receipt.dir")?.let { File(it).also { d -> d.mkdirs() } }
            if(carPlay) {
                fun info(p: Any): Pair<ByteArray,Map<*,*>> {
                    val configuration = if (p === first) requireNotNull(firstConfiguration)
                        else requireNotNull((p as PreparedCarPlaySecondaryOutput).configuration)
                    val dict=com.shilapi.xcertplay.airplay.AirPlayInfoPlist.build(
                        com.shilapi.xcertplay.airplay.AirPlayConfig("fixture","00:00:00:00:00:00","00:00:00:00:00:00","fixture",
                            com.shilapi.xcertplay.airplay.AirPlayDisplayConfig(1280,720),cluster=configuration.display,
                            disableAudioOutput=true),availableAudioFormats=emptySet())
                    val bytes=com.shilapi.xcertplay.airplay.BplistCodec.encode(dict)
                    return bytes to (com.shilapi.xcertplay.airplay.BplistCodec.decode(bytes) as Map<*,*>)
                }
                val (oldBytes,oldInfo)=info(first);val (newBytes,newInfo)=info(next)
                val oldDisplays=oldInfo["displays"] as List<*>;val newDisplays=newInfo["displays"] as List<*>
                assertEquals(oldDisplays.first(),newDisplays.first())
                val display=newDisplays[1] as Map<*,*>;assertEquals(111L,(display["type"] as Number).toLong())
                val safe=((display["viewAreas"] as List<*>).single() as Map<*,*>)["safeArea"] as Map<*,*>
                assertEquals(mapping.phoneSafeInsets.top.toLong(),(safe["originYPixels"] as Number).toLong())
                assertEquals(false,safe["drawUIOutsideSafeArea"])
                receipt?.let { File(it,"cp-unchanged-before.bplist").writeBytes(oldBytes);File(it,"cp-unchanged-after.bplist").writeBytes(newBytes) }
            } else {
                val offer=(next as PreparedAaSecondaryOutputs).offers.single()
                assertEquals(mapping.codecCrop.top,offer.cropTop);assertEquals(mapping.phoneSafeInsets.top,offer.safeTop)
                assertEquals(3,offer.channelId);assertEquals(1,offer.protocolDisplayId)
                val fields=listOf("channelId","protocolDisplayId","displayType","density","cropLeft","cropTop","cropRight","cropBottom",
                    "safeLeft","safeTop","safeRight","safeBottom","stableLeft","stableTop","stableRight","stableBottom")
                fun json(p:Any)=org.json.JSONObject().also { obj -> val o=(p as PreparedAaSecondaryOutputs).offers.single();fields.forEach { field -> obj.put(field,o.javaClass.getField(field).getInt(o)) } }
                receipt?.let { File(it,"aa-runtime-offers.json").writeText(org.json.JSONObject().put("before",json(first)).put("after",json(next)).toString(2)) }
            }

        } finally {
            compose.runOnIdle { mounted = false }; compose.waitForIdle()
            runtimeFixture?.close()
            activity?.pause()?.stop()?.destroy()
            job.cancelAndJoin(); file.delete()
            inventoryFixture.close()
            ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", oldProduct)
            ReflectionHelpers.setStaticField(Build::class.java, "ID", oldBuild)
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "INCREMENTAL", oldRevision)
        }
    }
    @Test fun cpUnchangedLegacyAdapterSaveDirectInfoKeepsMainUnchanged() = journey(true, legacy, profile.output)
}
