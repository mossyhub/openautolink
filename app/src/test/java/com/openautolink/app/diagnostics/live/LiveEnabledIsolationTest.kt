package com.openautolink.app.diagnostics.live

import android.app.Application
import android.os.Looper
import com.openautolink.app.cluster.*
import com.openautolink.app.data.*
import com.openautolink.app.diagnostics.DiagnosticLog
import com.openautolink.app.secondary.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Real process Enable/tee/snapshot and frozen AA/CP preparation together.
 * Only encrypted-storage and HTTPS endpoints are mocked; no real pairing or network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34], application=Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class LiveEnabledIsolationTest {
    @org.junit.Before fun validatedBenchDefaultNetwork() {
        val manager=RuntimeEnvironment.getApplication().getSystemService(android.net.ConnectivityManager::class.java)
        val shadow=shadowOf(manager)
        shadow.setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED,android.net.ConnectivityManager.TYPE_MOBILE,0,true,true))
        val caps=org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadow.setNetworkCapabilities(manager.activeNetwork,caps)
    }
    private fun await(condition:()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(!condition() && System.nanoTime()<deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
        }
        assertTrue("actual observer completion: ${LiveDiagnostics.status.value}",condition())
    }
    @Test fun enabledObserverDoesNotCreateOptionalRuntimeOrHost() {
        val app=RuntimeEnvironment.getApplication()
        val payloads=CopyOnWriteArrayList<JSONObject>()
        mockkConstructor(LiveCredentialStore::class,LiveHttps::class)
        every { anyConstructed<LiveCredentialStore>().load() } returns "synthetic-isolation-credential"
        every { anyConstructed<LiveHttps>().close() } just Runs
        every { anyConstructed<LiveHttps>().post(any(),any(),any()) } answers {
            val body=secondArg<JSONObject>();payloads.add(JSONObject(body.toString()))
            val rows=secondArg<JSONObject>().optJSONArray("events")
            JSONObject().put("token","synthetic-isolation-bearer").put("ack",if(rows==null || rows.length()==0)0L else rows.getJSONObject(rows.length()-1).getLong("seq"))
        }
        try {
            assertNull(SecondaryOutputRuntime.peekForDiagnostics())
            assertNull(com.openautolink.app.session.SessionManager.instanceOrNull())
            LiveDiagnostics.initialize(app)
            assertEquals("Off",LiveDiagnostics.status.value)
            LiveDiagnostics.enable(app,"")
            await { LiveDiagnostics.status.value.startsWith("Live read-only") }
            DiagnosticLog.i("IsolationBench","optional observer isolation marker")
            await { payloads.any { it.toString().contains("optional observer isolation marker") } }
            assertNull("snapshots must never initialize optional runtime",SecondaryOutputRuntime.peekForDiagnostics())
            assertNull("snapshots must never initialize primary session",com.openautolink.app.session.SessionManager.instanceOrNull())
            assertEquals("unavailable",LiveSnapshot.read(app,-1).getString("state"))
        } finally { LiveDiagnostics.stop();unmockkAll() }
    }
    // A distinct SDK sandbox keeps constructor-mocked encrypted storage owned by this fixture.
    @Config(instrumentedPackages=["com.openautolink.app.diagnostics.live"])
    @Test fun enabledObserverPreservesOffAndIconsOnlyIntentAndZeroSecondaryOffers() {
        val f=HostedSecondaryRuntimeFixture(2)
        // Observe actual Android DisplayManager facts, not MockK-generated final Display.Mode.
        val payloads=CopyOnWriteArrayList<JSONObject>()
        mockkConstructor(LiveCredentialStore::class,LiveHttps::class)
        every { anyConstructed<LiveCredentialStore>().load() } returns "synthetic-isolation-credential"
        every { anyConstructed<LiveHttps>().close() } just Runs
        every { anyConstructed<LiveHttps>().post(any(),any(),any()) } answers {
            payloads.add(JSONObject(secondArg<JSONObject>().toString()))
            val rows=secondArg<JSONObject>().optJSONArray("events")
            JSONObject().put("token","synthetic-isolation-bearer").put("ack",if(rows==null || rows.length()==0)0L else rows.getJSONObject(rows.length()-1).getLong("seq"))
        }
        try {
            LiveDiagnostics.initialize(RuntimeEnvironment.getApplication())
            for(mode in listOf(ClusterDisplayMode.OFF,ClusterDisplayMode.NATIVE_ICONS)) {
                LiveDiagnostics.stop()
                val selection=ClusterOutputSelection(mode,false,false)
                runBlocking {
                    f.preferences.setClusterOutputSelection(selection)
                    val saved=f.preferences.getSecondaryDisplayPreferences()
                    f.preferences.updateSecondaryDisplayPreferences(saved.revision,
                        mapOf(f.descriptor.id to SecondaryOutputPreference(true,AaSecondaryRole.AUXILIARY,false,false)))
                }
                val before=runBlocking { f.preferences.getSecondaryDisplayPreferences() }
                LiveDiagnostics.enable(f.context,"")
                await { LiveDiagnostics.status.value.startsWith("Live read-only") }
                assertEquals(before,runBlocking { f.preferences.getSecondaryDisplayPreferences() })
                val prepared=runBlocking { f.runtime.prepareAndroidAuto(200+mode.ordinal.toLong(),before) }
                assertTrue("no secondary AA channel with live observer in $mode",prepared.offers.isEmpty())
                assertNull(prepared.hostedManager)
                assertEquals(mode,prepared.legacySelection.mode)
                val cp=runBlocking { f.runtime.prepareCarPlay(300+mode.ordinal.toLong(),before) }
                assertNull("no secondary CP channel with live observer in $mode",cp.configuration)
                assertNull(cp.listener)
                assertNull(f.bridge)
                assertEquals(before,runBlocking { f.preferences.getSecondaryDisplayPreferences() })
            }
            assertTrue(payloads.isNotEmpty())
        } finally { LiveDiagnostics.stop();f.close() }
    }
}
