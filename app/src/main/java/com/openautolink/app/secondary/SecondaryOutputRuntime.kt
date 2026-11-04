package com.openautolink.app.secondary

import android.content.Context
import com.openautolink.app.cluster.*
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.transport.aasdk.*
import com.openautolink.carplaylab.media.CarPlayClusterVideoConfiguration
import com.openautolink.carplaylab.media.CarPlayClusterVideoListener
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.media.CarPlayVideoQualification
import com.shilapi.xcertplay.media.MediaCodecSupport
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Optional outputs never own the primary decoder, audio, input, or transport lifecycle.
 * Preferences are frozen before bounded AA readiness; inventory then freezes for the offer.
 * After preparation only revocation is live; readiness never hot-adds channels.
 */
class SecondaryOutputRuntime internal constructor(
    context: Context,
    private val inventory: StateFlow<SecondaryInventorySnapshot>,
    private val renderFactory: (VideoMapping, String) -> ClusterProjectionOutput,
    private val decoder: (Int, Int) -> String,
) {
    constructor(context: Context, inventory: StateFlow<SecondaryInventorySnapshot>) : this(context, inventory,
        { mapping, name -> ClusterProjectionOutput(mapping, avcDecoderName = name) },
        { width, height -> requireNotNull(CarPlayVideoQualification.androidProbe().decoderFor(
            com.shilapi.xcertplay.media.VideoDecoderRequest(com.shilapi.xcertplay.airplay.VideoCodec.H264, width, height, 30))) {
            "No qualified AVC decoder" } })
    private val context = context.applicationContext
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private data class StatusSnapshot(val revision: Long, val values: Map<SecondaryOutputId, SecondaryOutputStatus>)
    private var desiredStatus = StatusSnapshot(0, emptyMap())
    private val state = MutableStateFlow(desiredStatus)
    @OptIn(InternalCoroutinesApi::class)
    val statuses: StateFlow<Map<SecondaryOutputId, SecondaryOutputStatus>> = object : StateFlow<Map<SecondaryOutputId, SecondaryOutputStatus>> {
        override val value get() = state.value.values
        override val replayCache get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<Map<SecondaryOutputId, SecondaryOutputStatus>>): Nothing {
            state.map { it.values }.distinctUntilChanged().collect(collector)
            error("StateFlow completed")
        }
    }
    private fun publishStatus(snapshot: StatusSnapshot) {
        while (true) {
            val old = state.value
            if (old.revision >= snapshot.revision || state.compareAndSet(old, snapshot)) return
        }
    }
    private var generation = 0L
    private var reservation: SecondaryOutputReservation<RuntimeOutput>? = null
    // Failed detach retains this exact owner, but cannot veto an unrelated identity.
    private val owners = mutableMapOf<SecondaryOutputId, RuntimeOutput>()
    private val hostOwners = mutableMapOf<SecondaryHostOwner, RuntimeOutput>()
    private var retainedOutput: RuntimeOutput? = null
    private val liveDisplay = MutableStateFlow<QualifiedClusterDisplay?>(null)
    val liveHostedDisplay: StateFlow<QualifiedClusterDisplay?> = liveDisplay.asStateFlow()

    private val restoreRevision=MutableStateFlow(0L)
    internal val restoreGenerations:StateFlow<Long> = restoreRevision.asStateFlow()
    private fun restoreChanged() { restoreRevision.update { it+1 } }
    /** Receipt of the real current preferences binding revokes queued policy authority only;
     * observation never restores a task, adds a stream or reconnects projection. */
    fun updateRestorePolicyGeneration(revision:Long?) {
        val exact=synchronized(lock) { owners.values.toList() }
        var changed=false
        exact.forEach { if(it.restoreAuthority.observePolicy(revision))changed=true }
        if(changed)restoreChanged()
    }

    /** Explicit floating-menu action only. Never creates an output or reconnects transport. */
    fun restoreDriverDisplayVideo(): CompletableFuture<DriverVideoRestoreResult> {
        val output=synchronized(lock) { owners.values.singleOrNull { it.directWindow && it.isCurrent() } }
            ?: return CompletableFuture.completedFuture(DriverVideoRestoreResult.BLOCKED)
        val captured=synchronized(output.guard) {
            val identity=output.restoreAuthority.snapshot()
            output.handle?.takeIf { identity!=null && output.advertised && output.selected===identity.selection &&
                output.stream===identity.stream && output.session===identity.session && output.retirement==null && output.isCurrent() }
                ?.let { it to requireNotNull(identity) }
        } ?: return CompletableFuture.completedFuture(DriverVideoRestoreResult.BLOCKED)
        return captured.first.restoreVideo(captured.second)
    }

    /** Explicit user action only, before closing the captured phone runtime. */
    fun retainForExplicitReconnect(): Boolean {
        val predecessor = synchronized(lock) {
            if (retainedOutput != null) return false
            owners.values.singleOrNull { it.descriptor.route == SecondaryRoute.GM_HOSTED && !it.directWindow && it.manager != null } ?: return false
        }
        val attempt = CompletableFuture<ClusterManager.RetainedMapHost?>()
        synchronized(predecessor.guard) {
            if (predecessor.revoked || !predecessor.construction.isDone || predecessor.retirement != null ||
                predecessor.handoffAttempt != null) return false
            predecessor.handoffAttempt = attempt
        }
        synchronized(lock) { retainedOutput = predecessor }
        val ticket = try { predecessor.manager?.retainMapOutputForReconnect(predecessor.lease, predecessor.selection) }
            catch (_: Exception) { null }
        attempt.complete(ticket)
        if (ticket == null) {
            synchronized(lock) { if (retainedOutput === predecessor) retainedOutput = null }
            return false
        }
        ticket.finished.whenComplete { _, _ ->
            val cleared = synchronized(lock) {
                if (retainedOutput === predecessor) { retainedOutput = null; true } else false
            }
            if (cleared) liveDisplay.value = currentLiveHostedDisplay()
        }
        predecessor.trace("explicit_handoff_admitted", "deadlineMs=15000 cachedGeometry=false")
        return true
    }
    internal fun captureRetainedHostCancellation(): () -> Unit {
        val exact = synchronized(lock) { retainedOutput }
        return { if (exact != null) cancelRetainedHost(exact) }
    }
    fun cancelRetainedHost(): CompletableFuture<Boolean> {
        val exact = synchronized(lock) { retainedOutput } ?: return CompletableFuture.completedFuture(true)
        return cancelRetainedHost(exact)
    }
    private fun cancelRetainedHost(exact: RuntimeOutput): CompletableFuture<Boolean> {
        synchronized(lock) { if (retainedOutput !== exact) return CompletableFuture.completedFuture(true); retainedOutput = null }
        exact.handoffCancelled = true
        return exact.handoffAttempt?.thenCompose { ticket ->
            if (ticket == null) CompletableFuture.completedFuture(true)
            else exact.manager?.cancelRetainedMapOutput(ticket) ?: CompletableFuture.completedFuture(false)
        } ?: CompletableFuture.completedFuture(true)
    }
    internal fun currentLiveHostedDisplay(): QualifiedClusterDisplay? {
        val exact = synchronized(lock) { owners.values.singleOrNull { it.descriptor.route == SecondaryRoute.GM_HOSTED }
            ?: retainedOutput } ?: return null
        return exact.manager?.currentLiveDisplay()
    }
    private val registry = SecondaryHostRegistry(this.context, inventory, object : HostConsumer {
        override fun onReady(receipt: SecondaryHostReceipt): CompletableFuture<Boolean> {
            val output = synchronized(lock) { hostOwners[receipt.owner] ?: owners[receipt.owner.descriptor.id]
                ?.takeIf { it.descriptor == receipt.owner.descriptor && it.projectionEpoch == receipt.owner.projectionEpoch } }
                ?: return CompletableFuture.completedFuture(false)
            // Codec enumeration/construction never runs on Main. The registry awaits this real ack.
            val result = CompletableFuture<Boolean>()
            scope.launch {
                try { output.attach(receipt).whenComplete { ready, failure ->
                    result.complete(failure == null && ready == true)
                } } catch (_: Exception) { result.complete(false) }
            }
            return result
        }
        override fun onRevoke(owner: SecondaryHostOwner) {
            synchronized(lock) { hostOwners[owner] }?.let { revoke(it, SecondaryReason.HOST_UNAVAILABLE) }
        }
        override fun onWithdraw(receipt: SecondaryHostReceipt): CompletableFuture<Boolean> {
            val output = synchronized(lock) { hostOwners[receipt.owner] }
                ?: return CompletableFuture.completedFuture(false)
            return output.bridge?.let { if (output.directWindow) it.retire(output.lease) else it.detach(output.lease, receipt.surface) }
                ?: CompletableFuture.completedFuture(true)
        }
        override fun onSuspend(receipt: SecondaryHostReceipt): CompletableFuture<Boolean> {
            val output = synchronized(lock) { hostOwners[receipt.owner] } ?: return CompletableFuture.completedFuture(false)
            return output.bridge?.park(output.lease, receipt.surface) ?: CompletableFuture.completedFuture(false)
        }
    })

    init {
        scope.launch {
            inventory.collect { current ->
                val invalid = synchronized(lock) { owners.values.filter { output ->
                    current.displays.none { it == output.descriptor }
                } }
                val retainedInvalid = synchronized(lock) { retainedOutput?.takeIf { old -> current.displays.none { it == old.descriptor } } }
                retainedInvalid?.let { cancelRetainedHost(it) }
                invalid.forEach { output ->
                    revoke(output, SecondaryReason.DISCONNECTED)
                    retireOutput(output)
                }
            }
        }
    }

    private fun publish(output: RuntimeOutput, reason: SecondaryReason? = null) {
        val status = output.bridge?.status?.value ?: ClusterProjectionStatus()
        val snapshot = synchronized(lock) {
            if (owners[output.descriptor.id] !== output) return
            val old = desiredStatus.values[output.descriptor.id] ?: SecondaryOutputStatus(output.descriptor.id)
            val next = old.copy(reason = reason ?: old.reason,
                advertised = output.advertised && !output.revoked,
                phoneSelected = output.selected != null && !output.revoked,
                hostAttached = status.hostAttached && !output.revoked,
                received = status.received, decoded = status.decoded,
                presented = status.presented, dropped = status.dropped)
            StatusSnapshot(desiredStatus.revision + 1, java.util.Collections.unmodifiableMap(
                desiredStatus.values + (output.descriptor.id to next))).also { desiredStatus = it }
        }
        publishStatus(snapshot)
    }
    private fun reason(id: SecondaryOutputId, reason: SecondaryReason, token: Long? = null) {
        val snapshot = synchronized(lock) {
            if (token != null && generation != token) return
            StatusSnapshot(desiredStatus.revision + 1, java.util.Collections.unmodifiableMap(
                desiredStatus.values + (id to SecondaryOutputStatus(id, reason)))).also { desiredStatus = it }
        }
        publishStatus(snapshot)
    }

    private fun begin(authority: () -> Boolean = { true }): Pair<Long, SecondaryOutputReservation<RuntimeOutput>>? {
        val previous: SecondaryOutputReservation<RuntimeOutput>?
        val next: SecondaryOutputReservation<RuntimeOutput>
        val token: Long
        synchronized(lock) {
            // Caller supplies an atomic connection ticket, not a blocking/locking callback.
            if (!authority()) return null
            previous = reservation
            token = ++generation
            next = SecondaryOutputReservation({ revoke(it, SecondaryReason.DISABLED) }, ::retireOutput)
            reservation = next
        }
        previous?.retire() // exact predecessor; never retire a concurrently installed successor
        return token to next
    }
    private fun current(token: Long): Boolean = synchronized(lock) { generation == token }

    suspend fun prepareAndroidAuto(projectionEpoch: Long, preferences: SecondaryPreferencesSnapshot): PreparedAaSecondaryOutputs =
        prepareAndroidAuto(projectionEpoch, preferences) { true }

    internal suspend fun prepareAndroidAuto(projectionEpoch: Long, frozenPreferences: SecondaryPreferencesSnapshot,
        authority: () -> Boolean): PreparedAaSecondaryOutputs {
        val deadline = System.nanoTime() + PREPARATION_BUDGET_MS * 1_000_000
        val preferences = frozenAndroidAutoIntent(withDefaultRoles(frozenPreferences), inventory.value)
        val (token, pending) = begin(authority) ?: return PreparedAaSecondaryOutputs(this, Long.MIN_VALUE,
            SecondaryOutputReservation({ revoke(it, SecondaryReason.DISABLED) }, ::retireOutput), emptyList(), emptyList(), preferences.legacyCluster.selection)
        val retainedAtStart = synchronized(lock) { retainedOutput?.takeIf { generation == token } }
        val frozenInventory = try {
            val live = awaitAndroidAutoInventory(projectionEpoch, token, preferences, deadline, authority)
            val gm = live.displays.singleOrNull { it.route == SecondaryRoute.GM_HOSTED }
            if (preferences.legacyCluster.selection.mode == ClusterDisplayMode.MAP_VIDEO &&
                gm != null && preferences.outputs[gm.id]?.enabled == true && gm.id !in frozenPreferences.outputs && current(token) && authority() &&
                ClusterDriverDisplayReceipt.QUALIFIED_GM.resolveCandidate(context, gm.displayId, "readiness_migration") != null) {
                // Durable migration merges current storage, but its returned Save/latest state
                // must NEVER replace this attempt's pre-wait frozen intent.
                val migrated = withTimeoutOrNull(remainingBudget(deadline)) {
                    AppPreferences.getInstance(context).migrateSecondaryClusterPreference(gm.id)
                    true
                } == true
                if (!migrated) live.copy(displays = live.displays.map { if (it.id == gm.id) it.copy(
                    route = SecondaryRoute.INVENTORY_ONLY, reason = SecondaryReason.DISCONNECTED) else it }) else live
            } else live
        } catch (cancelled: CancellationException) {
            pending.retire()
            preparationTrace(projectionEpoch, token, null, "readiness_cancelled")
            throw cancelled
        }
        val selected = if (current(token) && authority()) candidates(preferences, frozenInventory, carPlay = false) else emptyList()
        traceSettings(projectionEpoch, token, preferences, frozenInventory, false)
        val retainedRetirement = if (selected.none { it.route == SecondaryRoute.GM_HOSTED } && current(token) && authority())
            retainedAtStart?.let { cancelRetainedHost(it) } ?: CompletableFuture.completedFuture(true)
            else CompletableFuture.completedFuture(true)
        val prepared = prepare(selected, projectionEpoch, token, pending, preferences, false, authority, deadline)
        val offers = mutableListOf<AasdkSecondaryVideoOffer>()
        var publicChannel = 19
        var publicDisplay = 2
        for (output in prepared) {
            if (!current(token) || !authority() || output.revoked || output.preparedAtNanos > deadline || !output.isCurrent()) {
                revoke(output, SecondaryReason.HOST_UNAVAILABLE); retireOutput(output); continue
            }
            val hosted = output.descriptor.route == SecondaryRoute.GM_HOSTED
            // Page visibility is not decoder/geometry readiness. A direct host may have
            // positively parked before the first phone frame. Await in-flight exact cleanup
            // within the ORIGINAL budget; never advertise failed/pending GPU retirement.
            val hostReady = if (output.directWindow) withTimeoutOrNull(remainingBudget(deadline)) {
                output.handle?.let { await(it.readyForOffer()) }
            } == true else output.bridge?.status?.value?.hostAttached == true
            if (!hostReady || !current(token) || !authority() || !output.isCurrent()) {
                revoke(output, SecondaryReason.HOST_UNAVAILABLE); retireOutput(output); continue
            }
            val mapping = output.bridge?.mapping ?: continue
            val crop = mapping.codecCrop
            val safe = mapping.phoneSafeInsets
            val stable = mapping.stableContentInsets
            val role = preferences.outputs[output.descriptor.id]?.aaRole ?: continue
            val offer = runCatching { AasdkSecondaryVideoOffer.create(
                channelId = if (hosted) 3 else publicChannel++, protocolDisplayId = if (hosted) 1 else publicDisplay++,
                displayType = if (role == AaSecondaryRole.CLUSTER) 1 else 2,
                density = output.descriptor.densityDpi,
                cropLeft = crop.left, cropTop = crop.top, cropRight = crop.right, cropBottom = crop.bottom,
                safeLeft = safe.left, safeTop = safe.top, safeRight = safe.right, safeBottom = safe.bottom,
                stableLeft = stable.left, stableTop = stable.top, stableRight = stable.right, stableBottom = stable.bottom) }.getOrNull()
            if (offer == null) {
                revoke(output, SecondaryReason.HOST_UNAVAILABLE); retireOutput(output); continue
            }
            output.offer = offer
            output.trace("aa_offer_frozen", "channel=${offer.channelId} display=${offer.protocolDisplayId} role=${offer.displayType} hostAttached=${output.bridge?.status?.value?.hostAttached == true}")
            com.openautolink.app.diagnostics.DiagnosticLog.i("secondary",
                "AA optional offer frozen channel=${offer.channelId} display=${offer.protocolDisplayId} role=${offer.displayType} " +
                    "codec=3 resolution=2 fps=2 density=${offer.density} crop=$crop safe=$safe stable=$stable hostAttached=${output.bridge?.status?.value?.hostAttached == true}; phone acceptance unverified")
            offers += offer
        }
        preparationTrace(projectionEpoch, token, null, "preparation_complete", "offered=${offers.size} remainingMs=${remainingBudget(deadline)}")
        return PreparedAaSecondaryOutputs(this, token, pending, prepared.filter { it.offer != null }, offers.toList(),
            nativeSelectionAfterRetirement(effectiveLegacySelection(preferences, frozenInventory, ClusterProjectionProtocol.ANDROID_AUTO),
                ClusterProjectionProtocol.ANDROID_AUTO, projectionEpoch, token, deadline, authority, retainedRetirement))
    }

    /** Resolve only unset presentation roles inside this frozen attempt; never rewrite storage. */
    private fun withDefaultRoles(preferences: SecondaryPreferencesSnapshot): SecondaryPreferencesSnapshot =
        if (preferences.error != null) preferences else preferences.copy(outputs = preferences.outputs.mapValues {
            it.value.withDefaultAaRole(android.os.Build.PRODUCT)
        })

    /** Derive only missing legacy intent for an exact retained physical identity; no durable
     * migration/admission until the strict live GM receipt arrives. Explicit records always win. */
    private fun frozenAndroidAutoIntent(preferences: SecondaryPreferencesSnapshot,
        initial: SecondaryInventorySnapshot): SecondaryPreferencesSnapshot {
        if (preferences.error != null || preferences.legacyCluster.selection.mode != ClusterDisplayMode.MAP_VIDEO) return preferences
        // An explicitly disabled known target needs no video qualification or migration.
        val known = initial.displays.singleOrNull { it.route == SecondaryRoute.GM_HOSTED }
        if (known != null && preferences.outputs[known.id]?.enabled == false) return preferences
        val physical = ClusterDriverDisplayReceipt.QUALIFIED_GM.resolveReadinessCandidate(context) ?: return preferences
        val target = initial.displays.singleOrNull { it.displayId == physical.displayId } ?: return preferences
        if (target.id in preferences.outputs || initial.displays.count { it.id == target.id } != 1 ||
            (target.route != SecondaryRoute.GM_HOSTED && !(target.route == SecondaryRoute.INVENTORY_ONLY && target.reason == SecondaryReason.DISCONNECTED))) return preferences
        val legacy = preferences.legacyCluster.selection
        return preferences.copy(outputs = preferences.outputs + (target.id to SecondaryOutputPreference(
            legacy.mode != ClusterDisplayMode.OFF, AaSecondaryRole.AUXILIARY, legacy.androidAuto, legacy.carPlay)))
    }

    private fun remainingBudget(deadline: Long): Long = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(0)

    private fun preparationTrace(session: Long, attempt: Long, target: SecondaryDisplayDescriptor?, stage: String,
        fields: String = "") = runCatching { com.openautolink.app.diagnostics.DiagnosticLog.i("secondary",
        "OPTIONAL_RUNTIME_V1 session=$session attempt=$attempt physical=${target?.displayId ?: "none"} stage=$stage $fields") }.let { Unit }

    /** Requalification is limited to this frozen known-vehicle identity. Not a hot-add listener. */
    private suspend fun awaitAndroidAutoInventory(session: Long, token: Long, preferences: SecondaryPreferencesSnapshot,
        deadline: Long, authority: () -> Boolean): SecondaryInventorySnapshot {
        val initial = inventory.value
        if (!ClusterOutputPolicy.shouldOfferMapVideo(preferences.legacyCluster.selection, ClusterProjectionProtocol.ANDROID_AUTO)) return initial
        if (preferences.outputs.values.none { it.enabled }) return initial
        val physical = ClusterDriverDisplayReceipt.QUALIFIED_GM.resolveReadinessCandidate(context) ?: return initial
        val target = initial.displays.singleOrNull { it.displayId == physical.displayId } ?: return initial
        val pref = preferences.outputs[target.id] ?: return initial
        if (!pref.enabled || pref.aaRole == null ||
            target.route != SecondaryRoute.INVENTORY_ONLY || target.reason != SecondaryReason.DISCONNECTED ||
            initial.displays.count { it.id == target.id } != 1) return initial
        preparationTrace(session, token, target, "readiness_wait", "budgetMs=${remainingBudget(deadline)}")
        val ready = withTimeoutOrNull(remainingBudget(deadline)) {
            while (current(token) && authority()) {
                val live = inventory.value
                val exact = live.displays.singleOrNull { it.id == target.id }
                if (exact == null || exact.displayId != target.displayId || exact.name != target.name ||
                    exact.width != target.width || exact.height != target.height || exact.densityDpi != target.densityDpi) return@withTimeoutOrNull null
                if (exact.route == SecondaryRoute.GM_HOSTED) {
                    if (ClusterDriverDisplayReceipt.QUALIFIED_GM.resolveCandidate(context, target.displayId, "readiness_live") == null) return@withTimeoutOrNull null
                    return@withTimeoutOrNull live
                }
                if (exact.route != SecondaryRoute.INVENTORY_ONLY || exact.reason != SecondaryReason.DISCONNECTED) return@withTimeoutOrNull null
                // Poll boundedly as connection-ticket cancellation need not publish inventory.
                delay(25)
            }
            null
        }
        val valid = current(token) && authority()
        preparationTrace(session, token, target, if (!valid) "readiness_cancelled" else if (ready == null) "readiness_unavailable" else "readiness_requalified",
            "remainingMs=${remainingBudget(deadline)} deadline=${remainingBudget(deadline) == 0L}")
        if (ready == null) reason(target.id, SecondaryReason.DISCONNECTED, token)
        // No unrelated newly arrived outputs may enter this attempt during the wait.
        return initial.copy(displays = initial.displays.map { old ->
            if (old.id == target.id && valid && ready != null) ready.displays.single { it.id == target.id } else old
        })
    }

    suspend fun prepareCarPlay(projectionEpoch: Long, preferences: SecondaryPreferencesSnapshot): PreparedCarPlaySecondaryOutput {
        val deadline = System.nanoTime() + PREPARATION_BUDGET_MS * 1_000_000
        val intent = withDefaultRoles(preferences)
        val (token, pending) = requireNotNull(begin())
        val frozenInventory = inventory.value
        val legacy = effectiveLegacySelection(intent, frozenInventory, ClusterProjectionProtocol.CARPLAY)
        traceSettings(projectionEpoch, token, intent, frozenInventory, true)
        val choices = candidates(intent, frozenInventory, carPlay = true)
        if (choices.size > 1) {
            choices.forEach { reason(it.id, SecondaryReason.CARPLAY_DESTINATION_CONFLICT) }
            return PreparedCarPlaySecondaryOutput(this, pending, null, legacy)
        }
        val retainedRetirement = if (choices.none { it.route == SecondaryRoute.GM_HOSTED }) cancelRetainedHost()
            else CompletableFuture.completedFuture(true)
        val prepared = prepare(choices, projectionEpoch, token, pending, intent, true, deadline = deadline)
        val candidate=prepared.singleOrNull()
        val accepted=try {
            candidate?.takeIf { output ->
                output.preparedAtNanos<=deadline && current(token) && output.isCurrent() &&
                    withTimeoutOrNull(remainingBudget(deadline)) { output.handle?.let { await(it.readyForOffer()) } }==true &&
                    current(token) && output.isCurrent() && remainingBudget(deadline)>0
            }
        } catch(cancelled:CancellationException) { pending.retire();throw cancelled }
        if(candidate!=null && accepted==null) { revoke(candidate,SecondaryReason.HOST_UNAVAILABLE);retireOutput(candidate) }
        return PreparedCarPlaySecondaryOutput(this, pending, accepted,
            nativeSelectionAfterRetirement(legacy, ClusterProjectionProtocol.CARPLAY, projectionEpoch, token, deadline, { true }, retainedRetirement))
    }

    /** Native admission has no video reserve() call. It must still await every captured prior
     * process owner, including a failed earlier retirement and an exact retained-host cancel.
     * Never await or complete a future under an owner monitor; timeout/cancellation leaves debt.
     */
    private suspend fun nativeSelectionAfterRetirement(selection: ClusterOutputSelection,
        protocol: ClusterProjectionProtocol, projectionEpoch: Long, token: Long, deadline: Long,
        authority: () -> Boolean, retainedRetirement: CompletableFuture<Boolean>): ClusterOutputSelection {
        if (!ClusterOutputPolicy.shouldPublishNativeIcons(selection, protocol, hasSourcedManeuver = true)) return selection
        val predecessors = synchronized(lock) {
            if (generation != token || !authority()) return selection.copy(mode = ClusterDisplayMode.OFF)
            owners.values.filter { it.token != token }
        }
        val retirements = predecessors.map(::retireOutput) + retainedRetirement
        preparationTrace(projectionEpoch, token, null, "native_retirement_wait", "predecessors=${predecessors.size}")
        val acknowledged = try {
            withTimeoutOrNull(remainingBudget(deadline)) { retirements.all { await(it) } } == true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        val admitted = acknowledged && current(token) && authority()
        preparationTrace(projectionEpoch, token, null, "native_retirement_ack", "success=$admitted")
        if (!admitted) predecessors.forEach { reason(it.descriptor.id, SecondaryReason.RETIREMENT_PENDING, token) }
        // Effective attempt only: retain saved Icons-only intent and all exact cleanup debt.
        return if (admitted) selection else selection.copy(mode = ClusterDisplayMode.OFF)
    }

    private fun traceSettings(session: Long, attempt: Long, preferences: SecondaryPreferencesSnapshot,
        inventory: SecondaryInventorySnapshot, carPlay: Boolean) {
        inventory.displays.take(MAX_OUTPUTS).forEach { descriptor ->
            val pref = preferences.outputs[descriptor.id]
            com.openautolink.app.diagnostics.DiagnosticLog.i("secondary",
                "OPTIONAL_RUNTIME_V1 session=$session attempt=$attempt stage=settings_snapshot physical=${descriptor.displayId} protocol=${if (carPlay) "CP" else "AA"} enabled=${pref?.enabled == true} aa=${pref?.androidAuto == true} cp=${pref?.carPlay == true} role=${pref?.aaRole} route=${descriptor.route} legacyMode=${preferences.legacyCluster.selection.mode}")
        }
    }
    private fun effectiveLegacySelection(preferences: SecondaryPreferencesSnapshot, frozen: SecondaryInventorySnapshot,
        protocol: ClusterProjectionProtocol): ClusterOutputSelection {
        val selection = preferences.legacyCluster.selection
        // Per-display video consent must not suppress the legacy native-guidance path.
        if (selection.mode != ClusterDisplayMode.MAP_VIDEO) return selection
        val gm = frozen.displays.singleOrNull { it.route == SecondaryRoute.GM_HOSTED } ?: return selection
        val pref = preferences.outputs[gm.id] ?: return selection // unchanged pre-migration legacy path
        // Per-output enable remains real consent; removed protocol fields cannot veto it.
        return if (pref.enabled) selection else selection.copy(mode = ClusterDisplayMode.OFF)
    }

    private fun candidates(preferences: SecondaryPreferencesSnapshot, frozenInventory: SecondaryInventorySnapshot,
        carPlay: Boolean): List<SecondaryDisplayDescriptor> {
        val frozen = frozenInventory.displays
        val selected = frozen.filter { descriptor ->
            val pref = preferences.outputs[descriptor.id]
            val enabled = pref?.enabled == true
            val protocol = if (carPlay) ClusterProjectionProtocol.CARPLAY else ClusterProjectionProtocol.ANDROID_AUTO
            // One shared presentation gate covers every physical route and AA role, including AUX.
            val videoEnabled = ClusterOutputPolicy.shouldOfferMapVideo(preferences.legacyCluster.selection, protocol)
            when {
                !enabled || !videoEnabled -> { reason(descriptor.id, SecondaryReason.DISABLED); false }
                descriptor.route == SecondaryRoute.INVENTORY_ONLY -> { reason(descriptor.id, descriptor.reason); false }
                !carPlay && pref?.aaRole == null -> { reason(descriptor.id, SecondaryReason.NO_AA_ROLE); false }
                else -> true
            }
        }.sortedWith(compareBy<SecondaryDisplayDescriptor> { it.route != SecondaryRoute.GM_HOSTED }.thenBy { it.id.value })
        // CP conflict counts every enabled eligible destination, even beyond the AA budget.
        if (carPlay) return selected
        selected.drop(1).forEach { reason(it.id, SecondaryReason.OUTPUT_LIMIT) }
        return selected.take(1)
    }

    /** Reconnect can enter preparation before the old host's asynchronous cleanup finishes.
     * Wait only for this physical destination's captured predecessor, under the existing joint
     * preparation budget. A failed ack remains debt; a superseded attempt never claims its slot.
     */
    private suspend fun reserve(output: RuntimeOutput, authority: () -> Boolean): Boolean {
        val predecessor = synchronized(lock) {
            if (generation != output.token || !authority()) return false
            owners[output.descriptor.id]
        }
        if (predecessor != null) {
            reason(output.descriptor.id, SecondaryReason.RETIREMENT_PENDING)
            if (!await(retireOutput(predecessor))) return false
        }
        return synchronized(lock) {
            if (generation != output.token || !authority() || owners.containsKey(output.descriptor.id)) false
            else { owners[output.descriptor.id] = output; true }
        }
    }

    private suspend fun prepare(
        selected: List<SecondaryDisplayDescriptor>, projectionEpoch: Long, token: Long,
        pending: SecondaryOutputReservation<RuntimeOutput>, preferences: SecondaryPreferencesSnapshot, carPlay: Boolean,
        authority: () -> Boolean = { true },
        deadline: Long = System.nanoTime() + PREPARATION_BUDGET_MS * 1_000_000,
    ): List<RuntimeOutput> = supervisorScope {
        val attempts = selected.map { initialDescriptor -> scope.async(Dispatchers.IO) {
            var descriptor = initialDescriptor
            // One exact-epoch replacement only, before offer freeze and inside the ORIGINAL
            // joint deadline. Never restart primary transport or reuse invalidated host receipts.
            for (replacement in 0..1) {
                var output: RuntimeOutput? = null
                try {
                    if (!current(token)) return@async null
                    val owner = RuntimeOutput(descriptor, token, projectionEpoch, authority)
                    output = owner
                    val finalPreference = preferences.outputs[descriptor.id]
                    owner.trace("settings_snapshot", "enabled=${finalPreference?.enabled == true} aa=${finalPreference?.androidAuto == true} cp=${finalPreference?.carPlay == true} role=${finalPreference?.aaRole} protocol=${if (carPlay) "CP" else "AA"}")
                    owner.layout = preferences.outputs[descriptor.id]?.layout
                    owner.directWindow = true
                    owner.selection = preferences.legacyCluster.selection
                    owner.restoreAuthority.observePolicy(preferences.revision)
                    if (!reserve(owner, authority)) {
                        if (current(token) && authority()) reason(descriptor.id, SecondaryReason.RETIREMENT_PENDING)
                        return@async null
                    }
                    if (!pending.install(owner)) return@async null
                    reason(descriptor.id, SecondaryReason.HOST_PENDING)
                    // Every admitted output uses the same direct window. Never fall back to an
                    // AndroidX hosted map because an obsolete saved switch was false.
                    prepareDirect(owner, carPlay)
                    check(owner.isCurrent()) { "Output epoch revoked before offer" }
                    owner.bridge?.let { bridge -> owner.statusJob = scope.launch {
                        launch {
                            owner.manager?.observedMapHost(owner.lease)?.collect {
                                val observed = owner.manager?.currentLiveDisplay()
                                if (synchronized(lock) { owners[owner.descriptor.id] === owner }) liveDisplay.value = observed
                            }
                        }
                        bridge.status.collect { status ->
                            publish(owner)
                            if (status.presented > 0) owner.startupSummary("presented")
                            if (status.error != null) { revoke(owner, SecondaryReason.HOST_UNAVAILABLE); retireOutput(owner) }
                        }
                    } }
                    publish(owner, SecondaryReason.PHONE_NOT_SELECTED)
                    owner.preparedAtNanos = System.nanoTime()
                    return@async owner
                } catch (cancelled: CancellationException) {
                    output?.let { revoke(it, SecondaryReason.HOST_UNAVAILABLE); retireOutput(it) }
                    throw cancelled
                } catch (_: Exception) {
                    output?.let { revoke(it, SecondaryReason.HOST_UNAVAILABLE); retireOutput(it) }
                    output?.let { publish(it, SecondaryReason.HOST_UNAVAILABLE) }
                } finally { output?.construction?.complete(Unit) }
                val old = output ?: return@async null
                val latest = inventory.value.displays.singleOrNull { it.id == descriptor.id }
                if (replacement != 0 || carPlay || descriptor.route != SecondaryRoute.GM_HOSTED ||
                    old.preparedAtNanos != Long.MAX_VALUE || old.offer != null || latest == null ||
                    latest.attachmentEpoch == descriptor.attachmentEpoch ||
                    latest.copy(attachmentEpoch = descriptor.attachmentEpoch) != descriptor ||
                    !current(token) || !authority() || remainingBudget(deadline) == 0L ||
                    ClusterDriverDisplayReceipt.QUALIFIED_GM.resolveCandidate(context, descriptor.displayId, "preoffer_epoch_retry") == null)
                    return@async null
                old.trace("preoffer_epoch_retry_wait", "oldEpoch=${descriptor.attachmentEpoch} nextEpoch=${latest.attachmentEpoch} remainingMs=${remainingBudget(deadline)}")
                // Exact completion, not revoked/idle flags. Failed cleanup remains retirement debt.
                if (!await(retireOutput(old)) || !current(token) || !authority() ||
                    remainingBudget(deadline) == 0L || inventory.value.displays.singleOrNull { it.id == latest.id } != latest)
                    return@async null
                descriptor = latest
                old.trace("preoffer_epoch_retry", "nextEpoch=${latest.attachmentEpoch} remainingMs=${remainingBudget(deadline)}")
            }
            null
        } }
        // Independent workers cannot make a canceled preparation wait for a blocking codec query.
        try {
            withTimeoutOrNull(remainingBudget(deadline)) { attempts.awaitAll() }?.filterNotNull()
                ?: attempts.mapNotNull { attempt ->
                    if (attempt.isCompleted && !attempt.isCancelled) runCatching { attempt.await() }.getOrNull()
                    else { attempt.cancel(); null }
                }
        } catch (cancelled: CancellationException) {
            pending.retire()
            throw cancelled
        } finally { attempts.filter { !it.isCompleted }.forEach { it.cancel() } }
    }

    private suspend fun prepareHosted(output: RuntimeOutput, preferences: SecondaryPreferencesSnapshot, carPlay: Boolean) {
        val predecessor = synchronized(lock) { retainedOutput?.takeIf { it.descriptor == output.descriptor } }
        val retainedTicket = predecessor?.handoffAttempt?.let { await(it) }
        if (predecessor != null) {
            check(retainedTicket != null && !predecessor.handoffCancelled && await(retainedTicket.ready)) { "Exact old GPU retirement pending/failed" }
        }
        check(output.isCurrent()) { "GM preparation authority lost after retirement wait" }
        val automatic = if (retainedTicket != null) predecessor?.manager?.readRetainedHost(retainedTicket)
            ?: error("Retained exact host unavailable")
        else ClusterProjectionOutput.qualifiedInitialDisplay(context)
            ?.takeIf { it.displayId == output.descriptor.displayId } ?: error("GM receipt unavailable")
        val resolution = ClusterSavedLayoutResolver.resolve(automatic) { scope, canvas ->
            AppPreferences.getInstance(context).getClusterLayout(scope, canvas).also { read ->
                val saved = read.custom
                output.trace("layout_read", "record=${saved != null} scopeMatch=${saved?.scope == scope} reason=${read.unavailableReason} canvas=${saved?.canvas} output=${saved?.output} safe=${saved?.safe}")
            }
        }
        check(output.isCurrent()) { "GM preparation authority lost after layout read" }
        val display = resolution.display
        output.layoutSelectionReason = resolution.fallbackReason
        output.trace("layout_decision", "mode=${if (resolution.fallbackReason == null) "Custom" else "Auto"} fallback=${resolution.fallbackReason ?: "none"} role=${display.profile.role} panel=${display.profile.panel}")
        output.trace("layout_applied_next_session", "canvas=${display.host.visible.intersect(display.host.stable)} output=${display.effectiveOutput} safe=${display.safe}")
        val mapping = if (carPlay) ClusterVideoGeometry.carPlay(display) else ClusterVideoGeometry.androidAuto(display)
        output.trace("mapping", "protocol=${if (carPlay) "CP" else "AA"} coded=${mapping.codedSize} crop=${mapping.codecCrop} destination=${mapping.destination}")
        output.trace("safe_insets", "safe=${mapping.phoneSafeInsets} stable=${mapping.stableContentInsets}")
        val config = if (carPlay) configuration(mapping) else null
        val decoderName = config?.videoSelection?.avcDecoderName ?: decoder(1280, 720)
        check(output.isCurrent()) { "GM preparation authority lost after decoder qualification" }
        val bridge = renderFactory(mapping, decoderName)
        val manager: ClusterManager
        synchronized(output.guard) {
            if (!output.isCurrent()) {
                bridge.bindLease(output.lease); bridge.retire(output.lease)
                error("GM preparation revoked")
            }
            output.bridge = bridge
            output.configuration = config
            manager = predecessor?.manager ?: ClusterManager(context)
            output.manager = manager
            output.selection = preferences.legacyCluster.selection
        }
        // Manager installation is serialized with retirement by a separate worker-only boundary;
        // Main revocation only flips packet authority and never waits on manager/codec work.
        output.hostedAttempt = true
        val protocol = if (carPlay) ClusterProjectionProtocol.CARPLAY else ClusterProjectionProtocol.ANDROID_AUTO
        val lease = if (retainedTicket != null) {
            val installed = manager.installRetainedMapOutput(retainedTicket, output.projectionEpoch,
                preferences.legacyCluster.selection, protocol, display, bridge)
            if (installed == null) { output.hostedAttempt = false; error("Retained host installation rejected") }
            // Manager already bound/installed this actual lease. Timeout/cancel/false attach must
            // retain it as the cleanup owner, never the RuntimeOutput's unbound placeholder.
            synchronized(output.guard) {
                output.lease = installed.lease
                output.leaseBound = true
                output.installedHost = installed
            }
            check(await(installed.attached)) { "Retained successor attach rejected" }
            check(output.isCurrent()) { "Retained successor authority lost after attach" }
            installed.lease
        } else manager.beginMapOutput(output.projectionEpoch, preferences.legacyCluster.selection, protocol, display, bridge)
        if (retainedTicket != null && lease != null) synchronized(lock) {
            if (retainedOutput === predecessor) retainedOutput = null
        }
        if (lease == null) { output.hostedAttempt = false; error("GM hosted owner unavailable") }
        synchronized(output.guard) { output.lease = lease; check(retainedTicket != null || bridge.bindLease(lease)); output.leaseBound = true }
        if (!output.isCurrent()) error("GM preparation revoked")
    }

    private suspend fun prepareDirect(output: RuntimeOutput, carPlay: Boolean) {
        output.carPlay = carPlay
        val handle = registry.create(context, output.descriptor, output.projectionEpoch, retainedWindow = output.directWindow,
            allowNativeGuidance = output.directWindow && ClusterOutputPolicy.shouldPublishNativeIcons(output.selection,
                if (carPlay) ClusterProjectionProtocol.CARPLAY else ClusterProjectionProtocol.ANDROID_AUTO, hasSourcedManeuver = true),
            restoreAllowed = {
                // Current saved Off/Icons-only or output disable vetoes restoration even if
                // ordinary retirement is still being dispatched. Retired flags are shadows.
                val saved=AppPreferences.getInstance(context).getSecondaryDisplayPreferences()
                // The ticket captured this generation before IO. A different committed read
                // invalidates it; never silently upgrade the old tap to a newer policy.
                output.restoreAuthority.observePolicy(saved.revision)
                val pref=saved.outputs[output.descriptor.id]
                output.directWindow && pref?.enabled==true &&
                    ClusterOutputPolicy.shouldOfferMapVideo(saved.legacyCluster.selection,
                        if(carPlay)ClusterProjectionProtocol.CARPLAY else ClusterProjectionProtocol.ANDROID_AUTO)
            }, restoreAuthority = output.restoreAuthority, restoreCurrent = { synchronized(output.guard) {
                output.directWindow && output.isCurrent() && output.selected!=null && output.stream!=null && output.retirement==null &&
                    (output.carPlay || output.session?.secondaryVideoSelections?.value?.get(output.offer?.channelId)===output.selected)
            } })
        synchronized(lock) {
            output.handle = handle
            hostOwners[handle.owner] = output
        }
        if (output.revoked) { handle.retire(); error("Preparation cancelled") }
        // Registry dispatches Activity startup on Main after create returns.
        await(handle.ready)
        check(output.isCurrent()) { "Direct host authority lost after readiness" }
    }

    private fun configuration(mapping: VideoMapping): CarPlayClusterVideoConfiguration {
        val safe = mapping.phoneSafeInsets
        val video = com.shilapi.xcertplay.media.CarPlayVideoSelection(1280, 480, 30, decoder(1280, 480))
        return CarPlayClusterVideoConfiguration.create(AirPlayInsets(0, 0, 0, 0),
            AirPlayInsets(safe.top, safe.bottom, safe.left, safe.right), video)
    }

    internal inner class RuntimeOutput(
        val descriptor: SecondaryDisplayDescriptor, val token: Long, val projectionEpoch: Long,
        private val authority: () -> Boolean = { true },
    ) {
        val restoreAuthority = RestoreAuthority().also { it.install(this,null,null,null) }
        val guard = restoreAuthority.monitor
        @Volatile var revoked = false
        var preparedAtNanos = Long.MAX_VALUE
        var advertised = false
        var carPlay = false
        var directWindow = false
        var initialHostGeometry: SecondaryHostGeometry? = null
        var layout: SecondaryLayoutPreference? = null
        var layoutSelectionReason: ClusterLayoutFallbackReason? = null
        var handle: HostHandle? = null
        var manager: ClusterManager? = null
        var hostedAttempt = false
        var installedHost: ClusterManager.RetainedMapInstallation? = null
        var leaseBound = false
        var selection = ClusterOutputSelection(mode = ClusterDisplayMode.OFF)
        var bridge: ClusterProjectionOutput? = null
        var lease = ClusterOutputLease(projectionEpoch, leaseEpoch.incrementAndGet())
        var offer: AasdkSecondaryVideoOffer? = null
        var configuration: CarPlayClusterVideoConfiguration? = null
        var session: AasdkSession? = null
        var selected: Any? = null
        var stream: ClusterProjectionStream? = null
        var statusJob: Job? = null
        var retirement: CompletableFuture<Boolean>? = null
        var handoffAttempt: CompletableFuture<ClusterManager.RetainedMapHost?>? = null
        @Volatile var handoffCancelled = false
        val construction = CompletableFuture<Unit>()
        var cpWireEpoch = Long.MIN_VALUE
        var lastPts = -1L
        private val startupReported = java.util.concurrent.atomic.AtomicBoolean()
        fun startupSummary(outcome: String) {
            if (!startupReported.compareAndSet(false, true)) return
            val status = bridge?.status?.value
            val measuredOutcome = if ((status?.presented ?: 0) > 0) "presented" else outcome
            trace("startup_summary", "outcome=$measuredOutcome hostAttached=${status?.hostAttached == true} selected=${selected != null} received=${status?.received ?: 0} configurations=${status?.configurations ?: 0} decoded=${status?.decoded ?: 0} presented=${status?.presented ?: 0} layoutFallback=$layoutSelectionReason peerReason=unknown oemVisibility=unknown")
        }
        fun trace(stage: String, fields: String = "") {
            runCatching {
                val message="OPTIONAL_RUNTIME_V1 session=$projectionEpoch attempt=$token output=${lease.outputEpoch} physical=${descriptor.displayId} stage=$stage $fields"
                if(directWindow)PageReturnDiagnostics.tryOffer(message)
                else com.openautolink.app.diagnostics.DiagnosticLog.i("secondary",message)
            }
        }
        fun isCurrent(): Boolean = !revoked && current(token) && authority() && handle?.owner?.isCurrent != false &&
            inventory.value.displays.any { it == descriptor }
        fun attach(receipt: SecondaryHostReceipt): CompletableFuture<Boolean> {
            // Potentially slow capability reads occur WITHOUT an owner monitor. Main revocation
            // never queues behind codec enumeration or a GPU acknowledgment.
            if (revoked || !current(token)) return CompletableFuture.completedFuture(false)
            val measured = receipt.geometry
            val panel = PixelSize(measured.width, measured.height)
            bridge?.let { existing ->
                val target = existing.mapping.target
                if (!directWindow || receipt.owner !== handle?.owner || target.panel != panel || initialHostGeometry != measured)
                    return CompletableFuture.completedFuture(false)
                return existing.attach(lease, receipt.surface, target)
            }
            val target = SecondaryRuntimeLayouts.target(descriptor, layout, panel, measured.safe,
                android.os.Build.PRODUCT, android.os.Build.FINGERPRINT, carPlay = carPlay, safeOnly = directWindow)
            val baseMapping = if (carPlay) SecondaryVideoMapping.carPlay(target) else SecondaryVideoMapping.androidAuto(target)
            val mapping = if (directWindow) RetainedWindowMapping(baseMapping) else baseMapping
            val config = if (carPlay) configuration(mapping) else null
            val name = config?.videoSelection?.avcDecoderName ?: decoder(1280, 720)
            val next = renderFactory(mapping, name)
            check(next.bindLease(lease))
            val accepted = synchronized(guard) {
                if (revoked || !current(token) || bridge != null ||
                    (handle != null && handle?.owner !== receipt.owner) || receipt.owner.descriptor != descriptor) false
                else { configuration = config; bridge = next; initialHostGeometry = measured; leaseBound = true; true }
            }
            if (!accepted) return next.retire(lease).thenApply { false }
            return next.attach(lease, receipt.surface, target)
        }
    }

    internal fun revoke(output: RuntimeOutput, reason: SecondaryReason) {
        synchronized(output.guard) {
            if (output.revoked) return
            output.restoreAuthority.install(output,null,null,null)
            output.revoked = true
            output.stream?.let { output.bridge?.endStream(output.lease, it) }
            output.stream = null; output.selected = null
        }
        output.startupSummary("retired_without_present")
        output.trace("revoke", "reason=$reason phoneReason=unknown received=${output.bridge?.status?.value?.received ?: 0} decoded=${output.bridge?.status?.value?.decoded ?: 0} presented=${output.bridge?.status?.value?.presented ?: 0}")
        output.offer?.let { output.session?.retireSecondaryVideo(it.channelId) }
        publish(output, reason)
    }

    private fun retireOutput(output: RuntimeOutput): CompletableFuture<Boolean> {
        revoke(output, SecondaryReason.DISABLED)
        val result: CompletableFuture<Boolean>
        synchronized(output.guard) {
            output.retirement?.let { return it }
            result = CompletableFuture<Boolean>().also { output.retirement = it }
        }
        // Retirement reserves cancellation immediately, but may not acknowledge before a
        // worker has installed every partially constructed exact owner (including GM lease).
        val host = output.construction.thenCompose {
            try {
                val failedAttachment = output.installedHost?.failedAttachmentRetirement
                val handoff = output.handoffAttempt
                if (failedAttachment != null) failedAttachment
                else if (handoff != null) handoff.thenCompose { ticket ->
                    if (ticket != null && !output.handoffCancelled) ticket.ready
                    else output.manager?.takeIf { output.hostedAttempt }
                        ?.endMapOutput(output.lease, output.selection, "secondary handoff unavailable")
                        ?: CompletableFuture.completedFuture(true)
                } else output.handle?.retire() ?: output.manager?.takeIf { output.hostedAttempt }
                    ?.endMapOutput(output.lease, output.selection, "secondary output retired")
                    ?: CompletableFuture.completedFuture(true)
            } catch (_: Exception) { CompletableFuture.completedFuture(false) }
        }
        host.thenCompose { acknowledged ->
            if (!acknowledged) CompletableFuture.completedFuture(false)
            else if (output.handoffAttempt?.getNow(null) != null && !output.handoffCancelled)
                CompletableFuture.completedFuture(true) // manager retained host after EXACT old GPU retire
            else output.bridge?.let { bridge ->
                if (!output.leaseBound) output.leaseBound = bridge.bindLease(output.lease)
                bridge.retire(output.lease)
            } ?: CompletableFuture.completedFuture(true)
        }.whenComplete { acknowledged, failure ->
            val success = failure == null && acknowledged == true
            output.trace("retirement_ack", "success=$success")
            if (success) {
                output.statusJob?.cancel()
                if (output.handoffAttempt?.getNow(null) == null || output.handoffCancelled) output.manager?.release()
                synchronized(lock) {
                    if (owners[output.descriptor.id] === output) owners.remove(output.descriptor.id)
                    output.handle?.let { hostOwners.remove(it.owner) }
                }
                if (output.manager != null) liveDisplay.value = currentLiveHostedDisplay()
            } else publish(output, SecondaryReason.RETIREMENT_PENDING)
            result.complete(success)
        }
        return result
    }

    fun retire(reason: String): CompletableFuture<Boolean> {
        val exact = synchronized(lock) { ++generation; reservation }
        return exact?.retire() ?: CompletableFuture.completedFuture(true)
    }

    internal fun bind(token: Long, outputs: List<RuntimeOutput>, session: AasdkSession) {
        outputs.firstOrNull()?.let { session.bindSecondaryDiagnosticOwner(it.projectionEpoch, token) }
        outputs.forEach { output -> synchronized(output.guard) {
            output.session = session
            if (output.revoked || !current(token)) return@forEach
            output.stream?.let { output.bridge?.endStream(output.lease, it) }
            output.stream = null; output.selected = null
            output.restoreAuthority.install(output,session,null,null)
            // No JNI advertisement receipt exists yet; selection is a truthful lower bound.
            output.advertised = false
        }; restoreChanged();publish(output) }
    }
    internal fun collectSelections(outputs: List<RuntimeOutput>, session: AasdkSession) {
        outputs.forEach { output ->
            var changed=false
            synchronized(output.guard) {
            if (output.session !== session) return@forEach
            if (output.revoked) {
                output.offer?.channelId?.let { channel ->
                    if (session.secondaryVideoSelections.value.containsKey(channel)) session.retireSecondaryVideo(channel)
                }
                return@forEach
            }
            val selected = session.secondaryVideoSelections.value[output.offer?.channelId]
            if (output.selected !== selected) {
                changed=true
                output.stream?.let { output.bridge?.endStream(output.lease, it) }
                output.selected = selected
                if (selected != null) output.advertised = true
                output.stream = selected?.let { exact -> output.bridge?.beginStream(output.lease, exact) {
                    output.isCurrent() && output.session === session && session.secondaryVideoSelections.value[exact.channelId] === exact
                } }
                output.restoreAuthority.install(output,session,selected,output.stream)
            }
        }; if(changed) { restoreChanged();output.trace("aa_selection", "channel=${output.offer?.channelId} selected=${output.selected != null}") };if (!output.revoked) publish(output, if (output.selected != null) SecondaryReason.NONE else SecondaryReason.PHONE_NOT_SELECTED) }
    }
    internal fun frame(outputs: List<RuntimeOutput>, session: AasdkSession, frame: AasdkSecondaryVideoFrame) {
        val output = outputs.firstOrNull { it.offer === frame.selection.offer } ?: return
        collectSelections(listOf(output), session)
        synchronized(output.guard) {
            if (output.revoked || output.session !== session || !session.isCurrentSecondaryVideo(frame)) return
            val stream = output.stream ?: return
            if (!session.isCurrentSecondaryVideo(frame)) return
            if (frame.flags and com.openautolink.app.video.VideoFrame.FLAG_END_OF_STREAM != 0)
                output.bridge?.endStream(output.lease, stream)
            else output.bridge?.annexB(output.lease, stream, frame.data, frame.timestampUs)
        }
    }
    internal fun cpListener(output: RuntimeOutput): CarPlayClusterVideoListener = object : CarPlayClusterVideoListener {
        override fun onStreamActive(streamEpoch: Long, active: Boolean) {
            output.trace("cp_stream", "wireEpoch=$streamEpoch active=$active")
            var changed=false
            synchronized(output.guard) {
            if (output.revoked) return@synchronized
            if (!active) {
                if (streamEpoch != output.cpWireEpoch) return@synchronized
                output.stream?.let { output.bridge?.endStream(output.lease, it) }
                output.stream = null; output.selected = null
            } else {
                if (streamEpoch <= output.cpWireEpoch) return@synchronized
                output.cpWireEpoch = streamEpoch
                output.advertised = true
                val identity = Any()
                output.selected = identity
                output.stream = output.bridge?.beginStream(output.lease, identity) {
                    output.isCurrent() && output.cpWireEpoch == streamEpoch
                }
            }
            output.restoreAuthority.install(output,null,output.selected,output.stream)
            changed=true
            }
            if(changed)restoreChanged()
            publish(output, if (output.stream != null) SecondaryReason.NONE else SecondaryReason.PHONE_NOT_SELECTED)
        }
        override fun onCodecConfig(streamEpoch: Long, codecData: ByteArray) = synchronized(output.guard) {
            output.trace("cp_config", "wireEpoch=$streamEpoch validSize=${codecData.size in 7..65536} current=${!output.revoked && output.cpWireEpoch == streamEpoch}")
            if (output.revoked || output.cpWireEpoch != streamEpoch || codecData.size !in 7..65536) return@synchronized
            val stream = output.stream ?: return@synchronized
            runCatching {
                val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
                if (sps.isNotEmpty() && pps.isNotEmpty()) output.bridge?.let { bridge ->
                    bridge.configure(output.lease, stream, ClusterCodecConfig(bridge.mapping.codedSize,
                        START + sps, START + pps, output.configuration?.videoSelection?.avcDecoderName))
                }
            }
            Unit
        }
        override fun onFrame(streamEpoch: Long, naluBytes: ByteArray) = synchronized(output.guard) {
            if (output.revoked || output.cpWireEpoch != streamEpoch) return@synchronized
            val stream = output.stream ?: return@synchronized
            val pts = maxOf(System.nanoTime() / 1000, output.lastPts + 1)
            output.lastPts = pts
            output.bridge?.annexB(output.lease, stream, naluBytes, pts)
            Unit
        }
    }
    internal fun close(): CompletableFuture<Boolean> = cancelRetainedHost().thenCompose { cancelled ->
        if (cancelled) retire("runtime closed") else CompletableFuture.completedFuture(false)
    }.whenComplete { success, failure ->
        if (failure == null && success == true) { registry.close(); scope.cancel() }
    }
    internal fun hostedManager(outputs: List<RuntimeOutput>): ClusterManager? = outputs.firstNotNullOfOrNull { it.manager }
    internal fun hostedLayoutSelectionReason(): ClusterLayoutFallbackReason? = synchronized(lock) {
        owners.values.firstOrNull { it.descriptor.route == SecondaryRoute.GM_HOSTED }?.layoutSelectionReason
    }
    internal fun mapping(id: SecondaryOutputId): VideoMapping? = synchronized(lock) { owners[id]?.bridge?.mapping }
    internal fun hostedProjectionStatus(): ClusterProjectionStatus? = synchronized(lock) {
        owners.values.firstOrNull { it.descriptor.route == SecondaryRoute.GM_HOSTED }?.bridge?.status?.value
    }
    private fun hasHostedOwner(): Boolean = synchronized(lock) {
        owners.values.any { it.descriptor.route == SecondaryRoute.GM_HOSTED && !it.directWindow }
    }

    companion object {
        private const val MAX_OUTPUTS = 8
        private const val PREPARATION_BUDGET_MS = 1500L
        private val START = byteArrayOf(0, 0, 0, 1)
        private val leaseEpoch = AtomicLong()
        @Volatile private var process: SecondaryOutputRuntime? = null
        /** Read-only observer: must never create optional runtime or acquire a host. */
        internal fun peekForDiagnostics(): SecondaryOutputRuntime? = process
        internal fun hasOutstandingHostedMapOutput(): Boolean = process?.hasHostedOwner() == true
        fun get(context: Context): SecondaryOutputRuntime = process ?: synchronized(this) {
            process ?: SecondaryOutputRuntime(context, SecondaryDisplayPolicyInventory.get(context).snapshots).also { process = it }
        }
        internal suspend fun <T> await(future: CompletableFuture<T>): T = suspendCancellableCoroutine { continuation ->
            future.whenComplete { value, failure ->
                if (failure != null) continuation.resumeWith(Result.failure(failure))
                else continuation.resumeWith(Result.success(value))
            }
        }
    }
}

class PreparedAaSecondaryOutputs internal constructor(
    private val runtime: SecondaryOutputRuntime, private val token: Long,
    private val reservation: SecondaryOutputReservation<SecondaryOutputRuntime.RuntimeOutput>,
    private val outputs: List<SecondaryOutputRuntime.RuntimeOutput>,
    val offers: List<AasdkSecondaryVideoOffer>,
    internal val legacySelection: ClusterOutputSelection,
) {
    internal val hostedManager: ClusterManager? get() = runtime.hostedManager(outputs)
    internal val layoutSelectionReason: ClusterLayoutFallbackReason? get() = runtime.hostedLayoutSelectionReason()
    private var collection: Job? = null
    fun bind(session: AasdkSession) { runtime.bind(token, outputs, session) }
    /** One collector per transport generation; caller owns its lifecycle. */
    internal fun collect(scope: CoroutineScope, session: AasdkSession) {
        collection?.cancel()
        collection = scope.launch {
            launch { session.secondaryVideoSelections.collect { runtime.collectSelections(outputs, session) } }
            launch { session.secondaryVideoFrames.collect { runtime.frame(outputs, session, it) } }
        }
    }
    fun retire(reason: String): CompletableFuture<Boolean> { collection?.cancel(); return reservation.retire() }
}

class PreparedCarPlaySecondaryOutput internal constructor(
    private val runtime: SecondaryOutputRuntime,
    private val reservation: SecondaryOutputReservation<SecondaryOutputRuntime.RuntimeOutput>,
    private val output: SecondaryOutputRuntime.RuntimeOutput?,
    val legacySelection: ClusterOutputSelection,
) {
    val configuration: CarPlayClusterVideoConfiguration? get() = output?.takeIf { it.isCurrent() }?.configuration
    val listener: CarPlayClusterVideoListener? = output?.takeIf { it.configuration != null }?.let {
        runtime.cpListener(it)
    }
    fun revoke() { output?.let { runtime.revoke(it, SecondaryReason.DISABLED) } }
    fun retire(reason: String): CompletableFuture<Boolean> { revoke(); return reservation.retire() }
    internal fun snapshot(): com.openautolink.app.carplay.CarPlayClusterStatus =
        com.openautolink.app.carplay.CarPlayClusterStatus(configuration != null, output?.selected != null,
            configuration?.let { CarPlayClusterVideoConfiguration.PILOT_STATUS }, output?.bridge?.status?.value ?: ClusterProjectionStatus(),
            output?.layoutSelectionReason)
}
