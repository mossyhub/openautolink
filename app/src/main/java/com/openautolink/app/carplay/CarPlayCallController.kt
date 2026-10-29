package com.openautolink.app.carplay

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.openautolink.app.data.*
import com.openautolink.carplaylab.telecom.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-owned production setup. Desired persistence is independent from account/HFP authority. */
class CarPlayCallController internal constructor(
    private val context: Context,
    private val preferences: AppPreferences,
) : AutoCloseable {
    enum class Setup { LOADING, PERMISSION_NEEDED, ACCOUNT_DISABLED, READY, UNAVAILABLE }
    data class State(val preferences: CarPlayCallPreferences? = null, val setup: Setup = Setup.LOADING,
        val appliesToNextCall: Boolean = false, val hfp: HfpCallGuard.Status = HfpCallGuard.Status.UNKNOWN,
        val localCleanupPending: Boolean = false, val localCleanupFailed: Boolean = false)
    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Mutex()
    @Volatile private var closed = false
    private val cleanupLock = Any()
    private var cleanupInFlight = 0
    private val backend = ManagedTelecomExperiment.get(context)
    private val guard = HfpCallGuard(context) { status ->
        mutableState.update { it.copy(hfp = status) }
        // ANY-device profile census is prospective conflict, not same-call handover proof.
        // Keep the exact admitted owner until remote end, authority loss or explicit repair.
        NativeCallDiagnostics.record(context, "media stage=native-policy actor=HFP state=${status.name} admitted=${backend.hasAdmittedCall} action=${if (backend.hasAdmittedCall) "PRESERVED" else if (status == HfpCallGuard.Status.HFP_OWNED) "BLOCKED" else "OBSERVED"} cause=PROFILE_CENSUS")
    }
    private fun requestLocalRepair(cause: String = "MANUAL"): java.util.concurrent.CompletableFuture<Boolean> {
        NativeCallDiagnostics.record(context, "media stage=native-policy actor=REPAIR state=CLOSED admitted=${backend.hasAdmittedCall} action=REQUESTED cause=$cause")
        val completion = java.util.concurrent.CompletableFuture<Boolean>()
        synchronized(cleanupLock) {
            cleanupInFlight++
            mutableState.update { it.copy(localCleanupPending = true) }
        }
        fun acknowledge(success: Boolean) {
            val repaired = synchronized(cleanupLock) {
                cleanupInFlight--
                // No exact predecessor retry handle is exposed by this backend API. Never let
                // a successful successor repair erase an earlier failed retirement: retain
                // the failure for this controller/process lifetime instead of claiming repair.
                val state = mutableState.updateAndGet {
                    val failed = it.localCleanupFailed || !success
                    it.copy(localCleanupPending = cleanupInFlight != 0, localCleanupFailed = failed,
                        setup = if (failed) Setup.UNAVAILABLE else it.setup)
                }
                !state.localCleanupPending && !state.localCleanupFailed
            }
            NativeCallDiagnostics.record(context, "media stage=native-policy actor=REPAIR state=CLOSED admitted=${backend.hasAdmittedCall} action=${if (success) "RETIRED" else "FAILED"} cause=CLEANUP")
            completion.complete(repaired)
        }
        try {
            backend.repairLocalPresentation().whenComplete { acknowledged, error ->
                acknowledge(error == null && acknowledged == true)
            }
        } catch (_: RuntimeException) { acknowledge(false) }
        return completion
    }
    private var nextCall = false
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) { scope.launch { runCatching { prepare() } } }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
    init {
        // Fail closed until durable intent has loaded; no diagnostics/Compose visit is involved.
        backend.applyProductionPolicy(false, false, ::permitsNativeCallEffects)
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(lifecycle)
        guard.start()
        scope.launch {
            try {
                preferences.carPlayCallPreferences.collect { value ->
                    operations.withLock {
                        apply(value)
                        prepareBackend(value)
                    }
                }
            } catch (error: CancellationException) { throw error }
              catch (_: Exception) { mutableState.update { it.copy(setup = Setup.UNAVAILABLE) } }
        }
        scope.launch {
            while (isActive) {
                delay(500)
                operations.withLock {
                    if (!backend.hasAdmittedCall) nextCall = false
                    mutableState.update { it.copy(appliesToNextCall = nextCall, hfp = guard.status,
                        setup = if (!backend.hasReadPermission && it.preferences != null) Setup.PERMISSION_NEEDED else it.setup) }
                }
            }
        }
    }
    /** Commands retain exact backend owner/epoch/account fences, not prospective HFP policy. */
    private fun permitsNativeCallEffects(): Boolean {
        val state = mutableState.value
        return !closed && !state.localCleanupPending && !state.localCleanupFailed
    }
    private fun permitsNewCall(): Boolean {
        val allowed = permitsNativeCallEffects() && guard.status != HfpCallGuard.Status.HFP_OWNED
        if (!allowed) NativeCallDiagnostics.record(context, "media stage=native-policy actor=ADMISSION state=${guard.status.name} admitted=${backend.hasAdmittedCall} action=BLOCKED cause=NEW_CALL")
        return allowed
    }
    private fun apply(value: CarPlayCallPreferences) {
        backend.applyProductionPolicy(value.handler == CarPlayCallPreferences.Handler.CARPLAY,
            value.extraRingtone, ::permitsNewCall, ::permitsNativeCallEffects)
    }
    private suspend fun prepareBackend(value: CarPlayCallPreferences): State {
        val result = withContext(Dispatchers.IO) { backend.prepareProduction() }
        val setup = when (result) {
            ManagedTelecomExperiment.Result.READ_PERMISSION_REQUIRED -> Setup.PERMISSION_NEEDED
            ManagedTelecomExperiment.Result.REGISTERED_USER_ENABLE_REQUIRED -> Setup.ACCOUNT_DISABLED
            ManagedTelecomExperiment.Result.ENABLED_FOR_THIS_PROCESS -> Setup.READY
            else -> Setup.UNAVAILABLE
        }
        return mutableState.updateAndGet { it.copy(preferences = value,
            setup = if (it.localCleanupFailed) Setup.UNAVAILABLE else setup,
            appliesToNextCall = nextCall && backend.hasAdmittedCall, hfp = guard.status) }
    }
    /** Startup, visible resume, or actual permission-result callback. Never prompts or launches UI. */
    suspend fun prepare(): State = operations.withLock {
        val value = preferences.carPlayCallPreferences.first()
        apply(value)
        prepareBackend(value)
    }
    /** UI requests READ_PHONE_NUMBERS when PERMISSION_NEEDED; launches accounts when ACCOUNT_DISABLED.
     * A late permission result only rereads current intent; it cannot reselect an old handler. */
    suspend fun setup(): State = prepare()
    suspend fun setHandler(value: CarPlayCallPreferences.Handler) = operations.withLock {
        val previous = preferences.carPlayCallPreferences.first()
        preferences.updateCarPlayCallPreferences { it.copy(handler = value) }
        val saved = preferences.carPlayCallPreferences.first()
        if (previous.handler != saved.handler && backend.hasAdmittedCall) nextCall = true
        apply(saved)
        mutableState.update { it.copy(preferences = saved, appliesToNextCall = nextCall && backend.hasAdmittedCall) }
    }
    suspend fun setExtraRingtone(value: Boolean) = operations.withLock {
        preferences.updateCarPlayCallPreferences { it.copy(extraRingtone = value) }
        val saved = preferences.carPlayCallPreferences.first()
        apply(saved)
        mutableState.update { it.copy(preferences = saved) }
    }
    suspend fun openAccountSettings(): Boolean = withContext(Dispatchers.IO) { backend.openEnableSettings() }
    suspend fun openSoundSettings(): Boolean = withContext(Dispatchers.IO) {
        CarPlaySoundSettings.launch(context) in setOf(TelecomAccountSettings.Launch.EXACT_CANDIDATE_LAUNCHED,
            TelecomAccountSettings.Launch.PUBLIC_ACTION_CANDIDATE_LAUNCHED)
    }
    /** Local repair is intentionally not a handler setter and does not unregister the approved account. */
    suspend fun repairLocalPresentation(): Boolean = withContext(Dispatchers.IO) { requestLocalRepair().get() }
    override fun close() {
        closed = true
        scope.cancel()
        guard.close()
        (context.applicationContext as? Application)?.unregisterActivityLifecycleCallbacks(lifecycle)
        requestLocalRepair("CONTROLLER_CLOSE")
    }
    companion object {
        @Volatile private var instance: CarPlayCallController? = null
        fun get(context: Context): CarPlayCallController = instance ?: synchronized(this) {
            instance ?: CarPlayCallController(context.applicationContext, AppPreferences.getInstance(context)).also { instance = it }
        }
    }
}
