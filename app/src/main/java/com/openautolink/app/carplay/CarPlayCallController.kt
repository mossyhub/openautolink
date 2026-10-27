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
    private val backend = ManagedTelecomExperiment.get(context)
    private val guard = HfpCallGuard(context) { status ->
        // Positive HFP conflict: withdraw own local presentation only, NEVER send END/Reject.
        if (status == HfpCallGuard.Status.HFP_OWNED) {
            mutableState.update { it.copy(localCleanupPending = true) }
            backend.repairLocalPresentation().whenComplete { acknowledged, error ->
                mutableState.update { it.copy(localCleanupPending = false, localCleanupFailed = error != null || acknowledged != true) }
            }
        }
        mutableState.update { it.copy(hfp = status) }
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
        backend.applyProductionPolicy(false, false) { guard.isClear }
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
    private fun apply(value: CarPlayCallPreferences) {
        backend.applyProductionPolicy(value.handler == CarPlayCallPreferences.Handler.CARPLAY, value.extraRingtone) { guard.isClear }
    }
    private suspend fun prepareBackend(value: CarPlayCallPreferences): State {
        val result = withContext(Dispatchers.IO) { backend.prepareProduction() }
        val setup = when (result) {
            ManagedTelecomExperiment.Result.READ_PERMISSION_REQUIRED -> Setup.PERMISSION_NEEDED
            ManagedTelecomExperiment.Result.REGISTERED_USER_ENABLE_REQUIRED -> Setup.ACCOUNT_DISABLED
            ManagedTelecomExperiment.Result.ENABLED_FOR_THIS_PROCESS -> Setup.READY
            else -> Setup.UNAVAILABLE
        }
        return mutableState.updateAndGet { it.copy(preferences = value, setup = setup,
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
    suspend fun repairLocalPresentation(): Boolean = withContext(Dispatchers.IO) { backend.repairLocalPresentation().get() }
    override fun close() {
        scope.cancel()
        guard.close()
        (context.applicationContext as? Application)?.unregisterActivityLifecycleCallbacks(lifecycle)
        backend.repairLocalPresentation()
    }
    companion object {
        @Volatile private var instance: CarPlayCallController? = null
        fun get(context: Context): CarPlayCallController = instance ?: synchronized(this) {
            instance ?: CarPlayCallController(context.applicationContext, AppPreferences.getInstance(context)).also { instance = it }
        }
    }
}
