package com.openautolink.app.diagnostics.live

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import io.mockk.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Same existing Application startup seam on released565 and successor; fake only storage identity/HTTPS. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,35],application=Application::class,manifest=Config.NONE)
class LiveLaunchIntegrationTest {
    private fun await(condition:()->Boolean) {
        val deadline=System.nanoTime()+5_000_000_000
        while(!condition()&&System.nanoTime()<deadline){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)}
        assertTrue("actual startup state: ${LiveDiagnostics.status.value}",condition())
    }
    @Test fun startupReadsDurableOptInRestoresPairingOfflineAndWakesOnValidatedDefaultNetwork() {
        val app=RuntimeEnvironment.getApplication()
        val choice=File(app.noBackupFilesDir,"oal-live-launch-consent.bin")
        choice.writeBytes(byteArrayOf(1))
        val manager=app.getSystemService(ConnectivityManager::class.java)
        val shadow=shadowOf(manager)
        shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,ConnectivityManager.TYPE_MOBILE,0,true,true))
        val network=manager.activeNetwork!!
        val caps=ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadow.setNetworkCapabilities(network,caps)
        val before=shadow.networkCallbacks.toSet();val calls=AtomicInteger()
        mockkConstructor(LiveCredentialStore::class,LiveHttps::class)
        every{anyConstructed<LiveCredentialStore>().load()} returns "synthetic-startup-identity"
        every{anyConstructed<LiveHttps>().close()} just Runs
        every{anyConstructed<LiveHttps>().post(any(),any(),any())} answers {
            val path=firstArg<String>();assertNotEquals("must reuse stored pairing", "/v2/pair",path)
            calls.incrementAndGet()
            val rows=secondArg<JSONObject>().optJSONArray("events")
            JSONObject().put("token","synthetic-startup-session").put("ack",if(rows==null || rows.length()==0)0L else rows.getJSONObject(rows.length()-1).getLong("seq"))
        }
        try {
            LiveDiagnostics.initialize(app)
            await{LiveDiagnostics.status.value.startsWith("Waiting for internet")}
            assertEquals(0,calls.get())
            val callback=(shadow.networkCallbacks-before).single()
            shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            shadow.setNetworkCapabilities(network,caps);callback.onCapabilitiesChanged(network,caps)
            await{LiveDiagnostics.status.value.startsWith("Live")};assertTrue(calls.get()>=2)
            LiveDiagnostics.stop();assertEquals(before,shadow.networkCallbacks.toSet())
            callback.onCapabilitiesChanged(network,caps);Thread.sleep(100)
            assertEquals("Off",LiveDiagnostics.status.value)
            assertNull(com.openautolink.app.session.SessionManager.instanceOrNull())
        }finally{LiveDiagnostics.stop();choice.delete();unmockkAll()}
    }
}
