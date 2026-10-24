package com.openautolink.carplaylab.telecom

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Connection
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import java.util.concurrent.atomic.AtomicInteger

/** Default-off process selection; constructing/reading never registers an account. */
class ManagedTelecomExperiment internal constructor(private val context: Context) {
    val handle = PhoneAccountHandle(ComponentName(context, ManagedCarPlayConnectionService::class.java), "carplay-incoming-experiment")
    private val lifecycle = Any()
    @Volatile private var enabledRequested = false
    @Volatile private var epoch = 0L
    @Volatile private var controls = false
    @Volatile private var controlsEpoch = 0L
    enum class ControlResult { ACCOUNT_REQUIRED, CONTROLS_ENABLED }
    /** Explicit native-controls enable; exact link and enable generations still fence every queued command. */
    fun enableNativeControls(): ControlResult {
        val generation = epoch
        if (!enabledRequested) return ControlResult.ACCOUNT_REQUIRED
        accountOperation { if (epoch == generation) readAuthority(generation) }
        if (!armed) return ControlResult.ACCOUNT_REQUIRED
        val old: ManagedIncomingBridge?
        synchronized(lifecycle) {
            if (!enabledRequested || epoch != generation) return ControlResult.ACCOUNT_REQUIRED
            controlsEpoch++; controls = true
            old = link; link = null; old?.let(retiring::add)
        }
        old?.close()
        return ControlResult.CONTROLS_ENABLED
    }
    fun disableNativeControls() { synchronized(lifecycle) { controls = false; controlsEpoch++ } }
    val enabledForProcess: Boolean get() = enabledRequested
    val nativeControlsEnabled: Boolean get() = controls
    private val main = Handler(Looper.getMainLooper())
    // Registration/removal and account reads are ordered here, never under lifecycle.
    // Receipt methods block their caller; the panel runs them on IO, not the Android main thread.
    private val accounts = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { action ->
        Thread(action, "carplay-own-account").apply { isDaemon = true }
    }
    private fun <T> accountOperation(action: () -> T): T = accounts.submit<T> { action() }.get()
    @Volatile private var link: ManagedIncomingBridge? = null
    private val retiring = java.util.concurrent.CopyOnWriteArraySet<ManagedIncomingBridge>()
    private val telecom get() = context.getSystemService(TelecomManager::class.java)
    enum class Result { REGISTERED_USER_ENABLE_REQUIRED, ENABLED_FOR_THIS_PROCESS, DENIED_OR_UNAVAILABLE, DISABLED_AND_REMOVED, ACCOUNT_REMOVED_CLEANUP_PENDING, CLEANUP_UNCONFIRMED }
    fun census() = accountOperation { PassiveTelecomCensus.read(context, handle) }
    /** Nonprivate finite local diagnostics; no caller identity, exception text or fake inventory. */
    fun observationStatus(): String {
        val current = link
        val capacity = current?.capacityDrops ?: 0
        val queue = current?.queueDrops ?: 0
        val snapshot = authority
        val age = android.os.SystemClock.elapsedRealtime() - snapshot.readAt
        val account = if (snapshot.generation != epoch || age !in 0..1500) "STALE" else snapshot.value.name
        return "capacityDrops=$capacity queueDrops=$queue reconnectRequired=${capacity > 0 || queue > 0} cleanupPending=${retiring.any { !it.awaitClosed(0) }} accountAuthority=$account controls=${current?.controlStatus ?: "IDLE_OR_RECONNECT_REQUIRED"}"
    }
    // Bounded cached authority: no Binder in ingress/native/writer callbacks. Unknown is not disabled/absent.
    enum class AccountAuthority { UNKNOWN, ABSENT, DISABLED, ENABLED }
    private data class Authority(val generation: Long, val value: AccountAuthority, val readAt: Long)
    @Volatile private var authority = Authority(-1, AccountAuthority.UNKNOWN, 0)
    private val reading = java.util.concurrent.atomic.AtomicBoolean()
    private fun readAuthority(generation: Long): Authority {
        val value = try {
            val manager = telecom
            if (manager == null) AccountAuthority.UNKNOWN else {
                val own = manager.getPhoneAccount(handle)
                when { own == null -> AccountAuthority.ABSENT; own.isEnabled -> AccountAuthority.ENABLED; else -> AccountAuthority.DISABLED }
            }
        } catch (_: RuntimeException) { AccountAuthority.UNKNOWN }
        val snapshot = Authority(generation, value, android.os.SystemClock.elapsedRealtime())
        synchronized(lifecycle) { if (epoch == generation) authority = snapshot }
        return snapshot
    }
    val armed: Boolean get() {
        if (!enabledRequested) return false
        val generation = epoch
        val snapshot = authority
        val age = android.os.SystemClock.elapsedRealtime() - snapshot.readAt
        if ((snapshot.generation != generation || age > 500) && reading.compareAndSet(false, true)) {
            accounts.execute { try { if (enabledRequested && epoch == generation) readAuthority(generation) } finally { reading.set(false) } }
        }
        return enabledRequested && epoch == generation && snapshot.generation == generation && age in 0..1500 && snapshot.value == AccountAuthority.ENABLED
    }
    /** Exact installed owner keeps a bounded cache warm even when iAP2 call observations are silent. */
    private fun renewAuthority(generation: Long, owner: ManagedIncomingBridge) {
        accounts.schedule({
            fun installed() = enabledRequested && epoch == generation && link === owner && owner.cleanup == ManagedIncomingBridge.Cleanup.OPEN
            if (installed()) {
                if (reading.compareAndSet(false, true)) {
                    try {
                        val snapshot = readAuthority(generation)
                        if (installed() && snapshot.value != AccountAuthority.ENABLED) owner.close()
                    } finally { reading.set(false) }
                }
                if (installed()) renewAuthority(generation, owner)
            }
        }, 500, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
    // No Binder, external predicate, dispatch or socket write while holding lifecycle.
    private fun disableGeneration(): Long {
        val old: ManagedIncomingBridge?
        val generation: Long
        synchronized(lifecycle) {
            epoch++; generation = epoch; enabledRequested = false
            controls = false; controlsEpoch++
            old = link; link = null
            old?.let { retiring.add(it) }
        }
        old?.close()
        return generation
    }
    private fun pruneRetired() { retiring.filter { it.awaitClosed(0) }.forEach { retiring.remove(it) } }
    /** Called exclusively by the enable toggle, never a lifecycle/startup callback. */
    fun enable(): Result {
        val generation = disableGeneration()
        val result = accountOperation {
            if (epoch != generation) return@accountOperation Result.DENIED_OR_UNAVAILABLE
            try {
                val manager = telecom ?: return@accountOperation Result.DENIED_OR_UNAVAILABLE
                manager.registerPhoneAccount(PhoneAccount.builder(handle, "OAL CarPlay incoming experiment")
                    .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                    .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL)).build())
                if (manager.getPhoneAccount(handle) == null) return@accountOperation Result.DENIED_OR_UNAVAILABLE
                synchronized(lifecycle) {
                    if (epoch != generation) return@accountOperation Result.DENIED_OR_UNAVAILABLE
                    enabledRequested = true
                }
                Result.REGISTERED_USER_ENABLE_REQUIRED
            } catch (_: RuntimeException) { Result.DENIED_OR_UNAVAILABLE }
        }
        return if (result == Result.REGISTERED_USER_ENABLE_REQUIRED) refresh() else result
    }
    fun refresh(): Result {
        val generation = epoch
        val snapshot = accountOperation { readAuthority(generation) }
        if (enabledRequested && generation == epoch && snapshot.value == AccountAuthority.ENABLED) return Result.ENABLED_FOR_THIS_PROCESS
        val old = synchronized(lifecycle) {
            if (generation != epoch) return Result.DENIED_OR_UNAVAILABLE
            link.also { link = null; it?.let(retiring::add) }
        }
        old?.close()
        return if (enabledRequested && snapshot.value == AccountAuthority.DISABLED)
            Result.REGISTERED_USER_ENABLE_REQUIRED else Result.DENIED_OR_UNAVAILABLE
    }
    fun openEnableSettings(): Boolean {
        if (!enabledRequested) return false
        return try {
            context.startActivity(Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: RuntimeException) { false }
    }
    fun disable(): Result {
        val generation = disableGeneration()
        retiring.forEach { it.close() }; pruneRetired()
        return accountOperation {
            // A superseded disable must not remove a successor's explicit registration.
            if (epoch != generation) return@accountOperation Result.DENIED_OR_UNAVAILABLE
            try {
                val manager = telecom ?: return@accountOperation Result.CLEANUP_UNCONFIRMED
                manager.unregisterPhoneAccount(handle)
                if (manager.getPhoneAccount(handle) != null) Result.CLEANUP_UNCONFIRMED
                else {
                    pruneRetired()
                    if (retiring.isEmpty()) Result.DISABLED_AND_REMOVED else Result.ACCOUNT_REMOVED_CLEANUP_PENDING
                }
            } catch (_: RuntimeException) { Result.CLEANUP_UNCONFIRMED }
        }
    }
    /** Actual authenticated receiver calls this AFTER iAP2 MFi + AirPlay authentication. */
    fun authenticatedLink(
        controlSender: ((com.shilapi.xcertplay.airplay.PhoneControl, () -> Boolean) -> Boolean)? = null,
        isCurrent: () -> Boolean,
    ): ManagedIncomingBridge? {
        val generation = epoch // capture BEFORE any external predicate/construction can stall
        val controlGeneration = controlsEpoch
        // One-time runtime admission is off-main and must not miss an expired cache renewal.
        if (!enabledRequested) return null
        accountOperation { if (epoch == generation) readAuthority(generation) }
        if (!armed || !isCurrent()) return null
        pruneRetired()
        val pending = AtomicInteger()
        lateinit var bridge: ManagedIncomingBridge
        fun installed() = enabledRequested && epoch == generation && link === bridge
        fun current() = installed() && isCurrent() && installed()
        bridge = ManagedIncomingBridge({ armed }, { token ->
            check(current() && armed && current())
            telecom?.addNewIncomingCall(handle, Bundle().apply { putString(REQUEST_TOKEN, token) })
                ?: throw IllegalStateException("Telecom unavailable")
        }, { action ->
            if (pending.incrementAndGet() > 32) { pending.decrementAndGet(); false }
            else if (main.post { try { action() } finally { pending.decrementAndGet() } }) true
            else { pending.decrementAndGet(); false }
        }, ::current, { action -> main.post { action() } }, controlSender?.let { sender ->
            { command, commandCurrent ->
                fun allowed() = controls && controlsEpoch == controlGeneration && armed && current() && commandCurrent()
                if (!allowed()) false else {
                    accounts.execute {
                        if (allowed()) {
                            readAuthority(generation) // fresh exact-own read before queue, never inside writer lock
                            if (allowed()) sender(command, ::allowed)
                        }
                    }
                    true // queued for authority read, not written or phone-confirmed
                }
            }
        })
        if (!isCurrent()) { bridge.close(); return null }
        var old: ManagedIncomingBridge? = null
        val accepted = synchronized(lifecycle) {
            if (!enabledRequested || generation != epoch || retiring.size >= 16) false
            else {
                old = link
                old?.let { retiring.add(it) }
                link = bridge // linearization: only this exact identity may pass queued callbacks
                true
            }
        }
        if (!accepted) { bridge.close(); return null }
        old?.close()
        renewAuthority(generation, bridge)
        return bridge
    }
    internal fun create(account: PhoneAccountHandle?, token: String?): Connection {
        if (account != handle || token == null) return ManagedIncomingBridge.failed()
        return link?.create(token) ?: ManagedIncomingBridge.failed()
    }
    internal fun admissionFailed(account: PhoneAccountHandle?, token: String?) {
        if (account == handle && token != null) link?.admissionFailed(token)
    }
    companion object {
        internal const val REQUEST_TOKEN = "com.openautolink.carplay.INCOMING_LOCAL_REQUEST"
        @Volatile private var instance: ManagedTelecomExperiment? = null
        fun get(context: Context): ManagedTelecomExperiment = instance ?: synchronized(this) {
            instance ?: ManagedTelecomExperiment(context.applicationContext).also { instance = it }
        }
    }
}
