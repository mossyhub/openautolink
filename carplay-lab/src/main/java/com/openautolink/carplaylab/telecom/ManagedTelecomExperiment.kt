package com.openautolink.carplaylab.telecom

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Connection
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import java.util.concurrent.atomic.AtomicInteger
import com.openautolink.carplaylab.telecom.TelecomAccountDiagnostics.Stage
import com.openautolink.carplaylab.telecom.TelecomAccountDiagnostics.Outcome

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
    enum class Result { READ_PERMISSION_REQUIRED, REGISTER_RETURNED_READ_UNAVAILABLE, REGISTER_RETURNED_ACCOUNT_ABSENT, REGISTERED_USER_ENABLE_REQUIRED, ENABLED_FOR_THIS_PROCESS, DENIED_OR_UNAVAILABLE, DISABLED_AND_REMOVED, ACCOUNT_REMOVED_CLEANUP_PENDING, CLEANUP_UNCONFIRMED }
    fun census() = accountOperation { PassiveTelecomCensus.read(context, handle, epoch) }
    /** Nonprivate finite local diagnostics; no caller identity, exception text or fake inventory. */
    fun observationStatus(): String {
        val current = link
        val capacity = current?.capacityDrops ?: 0
        val queue = current?.queueDrops ?: 0
        val snapshot = authority
        val age = android.os.SystemClock.elapsedRealtime() - snapshot.readAt
        val account = if (!hasReadPermission) "UNKNOWN/read_permission_required" else if (snapshot.generation != epoch || age !in 0..1500) "STALE" else snapshot.value.name
        return "capacityDrops=$capacity queueDrops=$queue reconnectRequired=${capacity > 0 || queue > 0} cleanupPending=${retiring.any { !it.awaitClosed(0) }} accountAuthority=$account controls=${current?.controlStatus ?: "IDLE_OR_RECONNECT_REQUIRED"}"
    }
    // Bounded cached authority: no Binder in ingress/native/writer callbacks. Unknown is not disabled/absent.
    enum class AccountAuthority { UNKNOWN, ABSENT, DISABLED, ENABLED }
    private data class Authority(val generation: Long, val value: AccountAuthority, val readAt: Long)
    @Volatile private var authority = Authority(-1, AccountAuthority.UNKNOWN, 0)
    private val reading = java.util.concurrent.atomic.AtomicBoolean()
    private fun receipt(stage: Stage, outcome: Outcome, generation: Long) {
        TelecomAccountDiagnostics.record(context, stage, outcome, generation)
    }
    private fun readAuthority(generation: Long, stage: Stage = Stage.REFRESH_OWN_READ): Authority {
        val read = OwnPhoneAccountAccess.read(context, handle)
        val value = AccountAuthority.valueOf(read.account.name)
        val snapshot = Authority(generation, value, android.os.SystemClock.elapsedRealtime())
        synchronized(lifecycle) { if (epoch == generation) authority = snapshot }
        // Diagnostic fsync must never reset the age of an already completed authority read.
        receipt(stage, read.outcome, generation)
        return snapshot
    }
    val armed: Boolean get() {
        if (!enabledRequested || !hasReadPermission) return false
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
    private fun disableGeneration(deferClose: Boolean = false): Long {
        val old: ManagedIncomingBridge?
        val generation: Long
        synchronized(lifecycle) {
            epoch++; generation = epoch; enabledRequested = false
            controls = false; controlsEpoch++
            old = link; link = null
            old?.let { retiring.add(it) }
        }
        if (old != null) {
            if (deferClose) accounts.execute { old.close() } else old.close()
        }
        return generation
    }
    private fun pruneRetired() { retiring.filter { it.awaitClosed(0) }.forEach { retiring.remove(it) } }
    /** OS grant check only; never requests permission or reads accounts. */
    val hasReadPermission: Boolean get() = context.checkSelfPermission(android.Manifest.permission.READ_PHONE_NUMBERS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    /** UI may prepare before an OS prompt. No account Binder or permission prompt here. */
    fun prepareEnable(): Long = disableGeneration(deferClose = true)
    /** Route disposal/Off revokes only an unfinished request, never an established selection. */
    fun cancelPendingEnable(generation: Long) {
        synchronized(lifecycle) {
            if (epoch == generation && !enabledRequested) epoch++
        }
    }
    /** Queue the actual denied OS callback even if its UI coroutine is disposed before awaiting it.
     * This is a finite receipt only: never read, register or change a successor's selection.
     */
    fun permissionDenied(generation: Long): java.util.concurrent.Future<Result> = accounts.submit<Result> {
        receipt(Stage.FIRST_OWN_READ, Outcome.READ_PERMISSION_REQUIRED, generation)
        Result.READ_PERMISSION_REQUIRED
    }
    fun enable(): Result = enablePrepared(prepareEnable())
    /** Continuation must carry the exact generation captured before the real permission request. */
    fun enablePrepared(generation: Long): Result {
        val result = accountOperation {
            if (epoch != generation) {
                receipt(Stage.MANAGER_LOOKUP, Outcome.SUPERSEDED, generation)
                return@accountOperation Result.DENIED_OR_UNAVAILABLE
            }
            if (!hasReadPermission) {
                readAuthority(generation, Stage.FIRST_OWN_READ)
                return@accountOperation Result.READ_PERMISSION_REQUIRED
            }
            val manager = try { telecom } catch (error: RuntimeException) {
                receipt(Stage.MANAGER_LOOKUP, TelecomAccountDiagnostics.failure(error), generation)
                return@accountOperation Result.DENIED_OR_UNAVAILABLE
            }
            receipt(Stage.MANAGER_LOOKUP, if (manager == null) Outcome.NO_MANAGER else Outcome.RETURNED, generation)
            if (manager == null) return@accountOperation Result.DENIED_OR_UNAVAILABLE
            try {
                manager.registerPhoneAccount(PhoneAccount.builder(handle, "OAL CarPlay incoming experiment")
                    .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                    .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL)).build())
                receipt(Stage.REGISTER, Outcome.RETURNED, generation)
            } catch (error: RuntimeException) {
                receipt(Stage.REGISTER, TelecomAccountDiagnostics.failure(error), generation)
                return@accountOperation Result.DENIED_OR_UNAVAILABLE
            }
            val snapshot = readAuthority(generation, Stage.FIRST_OWN_READ)
            if (!hasReadPermission) return@accountOperation Result.READ_PERMISSION_REQUIRED
            if (snapshot.value == AccountAuthority.UNKNOWN) return@accountOperation if (!hasReadPermission)
                Result.READ_PERMISSION_REQUIRED else Result.REGISTER_RETURNED_READ_UNAVAILABLE
            if (snapshot.value == AccountAuthority.ABSENT) return@accountOperation Result.REGISTER_RETURNED_ACCOUNT_ABSENT
            synchronized(lifecycle) {
                if (epoch != generation) return@accountOperation Result.DENIED_OR_UNAVAILABLE
                enabledRequested = true
            }
            if (snapshot.value == AccountAuthority.ENABLED) Result.ENABLED_FOR_THIS_PROCESS else Result.REGISTERED_USER_ENABLE_REQUIRED
        }
        return result
    }
    fun refresh(): Result {
        val generation = epoch
        val snapshot = accountOperation { readAuthority(generation) }
        if (enabledRequested && generation == epoch && hasReadPermission && snapshot.value == AccountAuthority.ENABLED) return Result.ENABLED_FOR_THIS_PROCESS
        val old = synchronized(lifecycle) {
            if (generation != epoch) return Result.DENIED_OR_UNAVAILABLE
            link.also { link = null; it?.let(retiring::add) }
        }
        old?.close()
        return if (!hasReadPermission) Result.READ_PERMISSION_REQUIRED
        else if (enabledRequested && snapshot.value == AccountAuthority.DISABLED)
            Result.REGISTERED_USER_ENABLE_REQUIRED else Result.DENIED_OR_UNAVAILABLE
    }
    fun openSettings(): TelecomAccountSettings.Launch = accountOperation {
        val candidate = TelecomAccountSettings.inspect(context)
        receipt(Stage.SETTINGS_LOOKUP, Outcome.valueOf(candidate.availability.name), epoch)
        val result = TelecomAccountSettings.launch(context)
        receipt(Stage.SETTINGS_LAUNCH, Outcome.valueOf(result.name), epoch)
        result
    }
    fun openEnableSettings(): Boolean = openSettings() in setOf(
        TelecomAccountSettings.Launch.EXACT_CANDIDATE_LAUNCHED,
        TelecomAccountSettings.Launch.PUBLIC_ACTION_CANDIDATE_LAUNCHED)
    fun disable(): Result {
        val generation = disableGeneration()
        retiring.forEach { it.close() }; pruneRetired()
        return accountOperation {
            // A superseded disable must not remove a successor's explicit registration.
            if (epoch != generation) return@accountOperation Result.DENIED_OR_UNAVAILABLE
            try {
                val manager = telecom ?: return@accountOperation Result.CLEANUP_UNCONFIRMED
                manager.unregisterPhoneAccount(handle)
                if (readAuthority(generation, Stage.CLEANUP_OWN_READ).value != AccountAuthority.ABSENT) Result.CLEANUP_UNCONFIRMED
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
