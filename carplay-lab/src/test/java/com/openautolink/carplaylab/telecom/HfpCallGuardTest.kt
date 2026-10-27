package com.openautolink.carplaylab.telecom

import android.bluetooth.*
import android.content.Context
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 33, 34], manifest = Config.NONE)
class HfpCallGuardTest {
    private class Profile : BluetoothProfile {
        var devices = emptyList<BluetoothDevice>()
        var denied = false
        var duringQuery: (() -> Unit)? = null
        override fun getConnectedDevices(): List<BluetoothDevice> {
            if (denied) throw SecurityException()
            val result = devices
            duringQuery?.also { duringQuery = null; it() }
            return result
        }
        override fun getDevicesMatchingConnectionStates(states: IntArray) = connectedDevices
        override fun getConnectionState(device: BluetoothDevice) = BluetoothProfile.STATE_CONNECTED
    }
    @Test fun slowQueryCannotMintFreshDisconnectedAuthorityAfterItsCoverageWindow() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_ON)
        val guard = HfpCallGuard(app); val profile = Profile()
        val worker = guard.javaClass.getDeclaredField("worker").apply { isAccessible = true }.get(guard) as java.util.concurrent.ExecutorService
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        worker.submit { entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        try {
            guard.listener.onServiceConnected(16, profile)
            profile.duringQuery = { org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(2)) }
            guard.refresh()
            assertFalse("held OS query minted fresh clear snapshot", guard.isClear)
        } finally { release.countDown(); guard.close() }
    }
    @Test fun resultHeldAcrossSameProxyRebindingCannotOverwriteNewerConnectedCensus() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        val guard = HfpCallGuard(app); val profile = Profile()
        val worker = guard.javaClass.getDeclaredField("worker").apply { isAccessible = true }.get(guard) as java.util.concurrent.ExecutorService
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        worker.submit { entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        try {
            guard.listener.onServiceConnected(16, profile)
            profile.duringQuery = {
                guard.listener.onServiceDisconnected(16)
                guard.listener.onServiceConnected(16, profile)
                profile.devices = listOf(adapter.getRemoteDevice("02:00:00:00:00:01"))
                guard.refresh()
            }
            guard.refresh()
            assertFalse("old empty query crossed rebind epoch", guard.isClear)
        } finally { release.countDown(); guard.close() }
    }
    @Test fun repeatedRadioOffGetterCannotConvertPlatformErrorSentinelIntoAbsenceAuthority() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_OFF)
        val guard = HfpCallGuard(app)
        guard.refresh(); assertFalse(guard.isClear)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(500))
        guard.refresh(); assertFalse("OFF is also the platform service/error sentinel", guard.isClear)
        guard.close()
    }
    @Test fun startupSilenceIsUnknownAndOnlyLiveCurrentProxyQueryEstablishesDisconnected() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        val guard = HfpCallGuard(app)
        assertFalse(guard.isClear)
        val profile = Profile()
        guard.listener.onServiceConnected(16, profile)
        guard.refresh()
        assertFalse("empty profile result is also the platform's swallowed-error sentinel", guard.isClear)
        profile.denied = true
        guard.refresh()
        assertEquals(HfpCallGuard.Status.UNKNOWN, guard.status)
        guard.listener.onServiceDisconnected(16)
        profile.denied = false
        guard.refresh()
        assertFalse("old proxy survives disconnect", guard.isClear)
        guard.close()
    }
    @Test fun anyPassengerHfpAlsoBlocksAndPermissionRevocationInvalidatesCachedClear() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        val guard = HfpCallGuard(app); val profile = Profile()
        guard.listener.onServiceConnected(16, profile); guard.refresh(); assertFalse(guard.isClear)
        profile.devices = listOf(adapter.getRemoteDevice("02:00:00:00:00:01"))
        guard.refresh(); assertEquals(HfpCallGuard.Status.HFP_OWNED, guard.status)
        profile.devices = emptyList(); guard.refresh(); assertFalse(guard.isClear)
        shadowOf(app).denyPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        assertFalse(guard.isClear)
        guard.close()
    }
}
