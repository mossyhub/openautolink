package com.openautolink.app.diagnostics.live

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,35],application=Application::class,manifest=Config.NONE)
class AndroidLiveNetworkTest {
    @Test fun customHttpsCanUseLocalWifiWithoutInternetValidationAndDoesNotBindNetwork() {
        val manager=RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java)
        val shadow=shadowOf(manager)
        shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,ConnectivityManager.TYPE_WIFI,0,true,true))
        val network=manager.activeNetwork!!
        val caps=org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadow.setNetworkCapabilities(network,caps)
        val before=shadow.networkCallbacks.toSet()
        val monitor=AndroidLiveNetwork(RuntimeEnvironment.getApplication(),requireValidatedInternet=false)
        val callback=(shadow.networkCallbacks-before).single()
        try {
            assertEquals(LiveInternet.VALIDATED,monitor.state.value)
            shadow.setActiveNetworkInfo(null);callback.onLost(network)
            assertEquals(LiveInternet.OFFLINE,monitor.state.value)
            monitor.close();assertEquals(before,shadow.networkCallbacks.toSet())
            callback.onAvailable(network);assertEquals(LiveInternet.OFFLINE,monitor.state.value)
            assertNull(manager.boundNetworkForProcess)
        }finally{monitor.close()}
    }
    @Test fun defaultCellularRequiresInternetAndValidationAndExactCallbackIsRetired() {
        val manager=RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java)
        val shadow=shadowOf(manager)
        shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,ConnectivityManager.TYPE_MOBILE,0,true,true))
        val network=manager.activeNetwork!!
        val caps=org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadow.setNetworkCapabilities(network,caps)
        val before=shadow.networkCallbacks.toSet()
        val monitor=AndroidLiveNetwork(RuntimeEnvironment.getApplication())
        val callback=(shadow.networkCallbacks-before).single()
        try {
            assertEquals(LiveInternet.OFFLINE,monitor.state.value)
            shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            callback.onCapabilitiesChanged(network,caps)
            assertEquals(LiveInternet.VALIDATED,monitor.state.value)
            shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            callback.onCapabilitiesChanged(network,caps)
            assertEquals(LiveInternet.OFFLINE,monitor.state.value)
            shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            callback.onCapabilitiesChanged(network,caps)
            assertEquals(LiveInternet.VALIDATED,monitor.state.value)
            monitor.close();assertEquals(before,shadow.networkCallbacks.toSet())
            shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            callback.onCapabilitiesChanged(network,caps);callback.onLost(network)
            assertEquals(LiveInternet.VALIDATED,monitor.state.value)
            assertNull(manager.boundNetworkForProcess)
        }finally{monitor.close()}
    }
}
