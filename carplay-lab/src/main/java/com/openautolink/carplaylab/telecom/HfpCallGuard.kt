package com.openautolink.carplaylab.telecom

import android.Manifest
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Best-effort ANY-HFP-client conflict observation via BluetoothAdapter/BluetoothProfile methods.
 * Profile16 is source-qualified on AOSP12L/13/14 and retained GM, not a public SDK constant.
 * No reflection, direct Binder, privileged permission, device identity, or policy setter.
 * Broadcasts are invalidation/wakeup only, NEVER positive disconnected authority.
 * Nonempty results conservatively block. Empty/OFF are ambiguous platform error sentinels,
 * not absence authority, even after binding. No production positive-clear source is qualified.
 * Explicit CarPlay mode may separately permit UNKNOWN under the user-visible requirement to
 * disconnect the iPhone from car Bluetooth. That product choice must NOT turn isClear true.
 * A snapshot cannot atomically exclude a different provider announcing after the read.
 */
class HfpCallGuard(private val context: Context, private val changed: (Status) -> Unit = {}) : AutoCloseable {
    enum class Status { UNKNOWN, KNOWN_DISCONNECTED, HFP_OWNED }
    private data class Snapshot(val status: Status, val at: Long)
    private val lock = Any()
    private val revision = java.util.concurrent.atomic.AtomicLong()
    private val bindRequested = java.util.concurrent.atomic.AtomicBoolean()
    @Volatile private var closed = false
    @Volatile private var proxy: BluetoothProfile? = null
    @Volatile private var snapshot = Snapshot(Status.UNKNOWN, 0)
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "carplay-hfp-census").apply { isDaemon = true }
    }
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val hasPermission get() = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    val status: Status get() {
        val read = snapshot
        return if (!closed && hasPermission && SystemClock.elapsedRealtime() - read.at in 0..1000) read.status else Status.UNKNOWN
    }
    val isClear: Boolean get() = status == Status.KNOWN_DISCONNECTED
    internal val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != HEADSET_CLIENT) return
            synchronized(lock) { if (!closed) this@HfpCallGuard.proxy = proxy }
            invalidate()
            if (started && !closed) worker.execute { refresh() }
        }
        override fun onServiceDisconnected(profile: Int) {
            if (profile != HEADSET_CLIENT) return
            synchronized(lock) { proxy = null; bindRequested.set(false); invalidate() }
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            invalidate() // Intent state/device extras are not authority, including sender spoofing.
            if (started && !closed) worker.execute { refresh() }
        }
    }
    private var registered = false
    private var started = false
    fun start() = synchronized(lock) {
        if (closed || started) return@synchronized
        started = true
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction("android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED")
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, filter)
            registered = true
        } catch (_: RuntimeException) { invalidate() }
        worker.scheduleWithFixedDelay({
            // Retry binding after a missing startup grant or platform restart; never prompt.
            if (!closed && hasPermission && proxy == null && bindRequested.compareAndSet(false, true)) {
                val accepted = runCatching { adapter?.getProfileProxy(context, listener, HEADSET_CLIENT) == true }.getOrDefault(false)
                if (!accepted) bindRequested.set(false)
            }
            refresh()
        }, 0, 500, TimeUnit.MILLISECONDS)
    }
    private fun publish(value: Status, at: Long = SystemClock.elapsedRealtime()) {
        val previous = snapshot.status
        snapshot = Snapshot(value, at)
        if (previous != value) runCatching { changed(value) }
    }
    private fun invalidate() { revision.incrementAndGet(); publish(Status.UNKNOWN) }
    /** Off-main Binder work. A callback/ON getter does not prove service health or complete coverage. */
    fun refresh() {
        if (closed || !hasPermission) { invalidate(); return }
        val ticket = revision.incrementAndGet()
        val startedAt = SystemClock.elapsedRealtime()
        val exact = proxy ?: run { invalidate(); return }
        val value = try {
            val radio = adapter ?: run { invalidate(); return }
            if (radio.state != BluetoothAdapter.STATE_ON) Status.UNKNOWN
            else {
                // Include transition states conservatively; no phone selection or number heuristic.
                val any = exact.getDevicesMatchingConnectionStates(intArrayOf(
                    BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING, BluetoothProfile.STATE_DISCONNECTING)).isNotEmpty()
                if (!hasPermission || radio.state != BluetoothAdapter.STATE_ON) Status.UNKNOWN
                else if (any) Status.HFP_OWNED else Status.UNKNOWN
            }
        } catch (_: RuntimeException) { Status.UNKNOWN }
        synchronized(lock) {
            if (revision.get() == ticket && !closed && proxy === exact && hasPermission) publish(value, startedAt)
        }
    }
    override fun close() {
        val exact = synchronized(lock) { closed = true; proxy.also { proxy = null }; }
        invalidate()
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        exact?.let { runCatching { adapter?.closeProfileProxy(HEADSET_CLIENT, it) } }
        worker.shutdownNow()
    }
    companion object { private const val HEADSET_CLIENT = 16 }
}
