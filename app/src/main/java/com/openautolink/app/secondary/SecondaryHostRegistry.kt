package com.openautolink.app.secondary

import android.app.ActivityOptions
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.openautolink.app.cluster.PixelRect
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Required renderer boundary. Withdrawal true means detached AND last image cleared. */
interface HostConsumer {
    fun onReady(receipt: SecondaryHostReceipt): CompletableFuture<Boolean>
    fun onRevoke(owner: SecondaryHostOwner)
    fun onWithdraw(receipt: SecondaryHostReceipt): CompletableFuture<Boolean>
    fun onSuspend(receipt: SecondaryHostReceipt): CompletableFuture<Boolean> = onWithdraw(receipt)
}
class SecondaryHostOwner internal constructor(val descriptor: SecondaryDisplayDescriptor, val projectionEpoch: Long) {
    private val revoked = AtomicBoolean(false)
    val isCurrent: Boolean get() = !revoked.get()
    internal fun revoke() { revoked.set(true) }
}
data class SecondaryHostGeometry(val width: Int, val height: Int, val safe: PixelRect) {
    init {
        require(width > 0 && height > 0 && safe.left >= 0 && safe.top >= 0 &&
            safe.right <= width && safe.bottom <= height && safe.right > safe.left && safe.bottom > safe.top)
    }
}
data class SecondaryHostReceipt(val surface: Surface, val geometry: SecondaryHostGeometry, val owner: SecondaryHostOwner)
enum class DriverVideoRestoreResult { ATTACHED, BLOCKED, TIMED_OUT }
class HostHandle internal constructor(
    val owner: SecondaryHostOwner,
    val ready: CompletableFuture<SecondaryHostReceipt>,
    private val retireOwner: () -> CompletableFuture<Boolean>,
    private val preparationReady: () -> CompletableFuture<Boolean>,
    private val restoreWindow: (RestoreAuthority.Identity?) -> CompletableFuture<DriverVideoRestoreResult>,
) {
    fun retire(): CompletableFuture<Boolean> = retireOwner()
    /** Negotiation readiness is not page visibility: a positively parked renderer is ready too. */
    internal fun readyForOffer(): CompletableFuture<Boolean> = preparationReady()
    internal fun restoreVideo(identity:RestoreAuthority.Identity?=null): CompletableFuture<DriverVideoRestoreResult> = restoreWindow(identity)
}

/**
 * Explicit runtime-only launcher. Main serializes effects, with no locks around consumer/futures.
 * Retained false detach debt fences this identity only. No queue can drop terminal cleanup.
 */
class SecondaryHostRegistry(
    context: Context,
    private val inventory: StateFlow<SecondaryInventorySnapshot>,
    private val consumer: HostConsumer,
) : AutoCloseable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val closed = AtomicBoolean(false)
    private val entries = mutableSetOf<Entry>()
    private val manager = this.context.getSystemService(DisplayManager::class.java)
    private var registered = false
    private var gmReceiverRegistered = false
    private data class GmAway(val entry:Entry,val key:String,val generation:Long,val elapsed:Long,val revision:Long)
    private var gmAway:GmAway? = null
    @Volatile private var pageRevision = 0L
    private var lastPage: String? = null
    private val pageGate=Any()
    private val intakeThread=android.os.HandlerThread("OalPageIntake").apply { start() }
    private val intake=Handler(intakeThread.looper)
    private val intakeEntries=java.util.concurrent.CopyOnWriteArraySet<Entry>()
    private data class PageEvent(val elapsed:Long,val generation:Long,val state:Int,val key:String?,val revision:Long)
    private val pendingPage=java.util.concurrent.atomic.AtomicReference<PageEvent?>()
    private val pageDrainScheduled=AtomicBoolean(false)
    private fun enqueuePage(event:PageEvent) { pendingPage.set(event);schedulePageDrain() }
    private fun schedulePageDrain() {
        if(pageDrainScheduled.compareAndSet(false,true))main.post {
            val latest=pendingPage.getAndSet(null)
            if(latest!=null)gmPage(latest)
            pageDrainScheduled.set(false)
            if(pendingPage.get()!=null)schedulePageDrain()
        }
    }
    private val gmReceiver=object:android.content.BroadcastReceiver() {
        override fun onReceive(context:Context,intent:android.content.Intent) {
            if(intent.action!=com.openautolink.app.diagnostics.gm.GmPageRelay.action(context) || closed.get())return
            // Own signature-protected, finite primitives only. Intake never waits for Main,
            // task lookup, preferences, diagnostic persistence or renderer acknowledgments.
            val elapsed=intent.getLongExtra("elapsed",-1)
            val generation=intent.getLongExtra("generation",Long.MIN_VALUE)
            val state=intent.getIntExtra("selected",-1)
            val type=intent.getStringExtra("type");val view=intent.getStringExtra("view")
            val kind=intent.getIntExtra("displayType",-1)
            val width=intent.getIntExtra("width",0);val height=intent.getIntExtra("height",0)
            val valid=elapsed>=0 && android.os.SystemClock.elapsedRealtime()-elapsed in 0..2000 &&
                state in 0..1 && type in setOf("NATIVE","PROJECTION") && !view.isNullOrBlank() && view.length<=96 &&
                kind>=0 && width in 1..16384 && height in 1..16384
            val key=if(valid) "$type:$view:$kind:$width:$height" else null
            val page=if(valid) "$generation:$state:$key" else "invalid:$generation:$state:${type?.take(16)}:${view?.take(96)}:$kind:$width:$height"
            var changed=false
            val revision=synchronized(pageGate) {
                if(lastPage!=page) {
                    lastPage=page
                    // Advance before any owner-monitor wait; release pageGate before invalidation.
                    pageRevision++;changed=true
                }
                pageRevision
            }
            if(changed)intakeEntries.forEach { it.restoreAuthority.invalidate() }
            val event=PageEvent(elapsed,generation,state,key,revision)
            enqueuePage(event)
        }
    }
    private fun observeGmReturns() {
        if(gmReceiverRegistered)return
        androidx.core.content.ContextCompat.registerReceiver(context,gmReceiver,
            android.content.IntentFilter(com.openautolink.app.diagnostics.gm.GmPageRelay.action(context)),
            context.packageName+".permission.OBSERVE_CLUSTER_PAGE",intake,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        gmReceiverRegistered=true
    }
    private fun gmPage(event:PageEvent) {
        if(closed.get() || event.revision!=pageRevision)return
        val key=event.key
        if(key==null) { gmAway=null;return }
        val entry=processEntries.singleOrNull { it.retainedWindow && !it.terminal }
        if(entry==null || entry.registry!==this || !current(entry)) { gmAway=null;return }
        if(event.state==0) {
            if(!entry.suspended && entry.receipt!=null && !entry.attaching && !entry.withdrawing &&
                entry.suspension==null && entry.activity?.isDestroyed==false)
                gmAway=GmAway(entry,key,event.generation,event.elapsed,event.revision)
            return
        }
        val away=gmAway;gmAway=null
        if(away==null || away.entry!==entry || away.key!=key || away.generation!=event.generation || away.revision+1!=event.revision ||
            event.elapsed-away.elapsed !in 0..120000 || !gmRestorable(entry))return
        val identity=entry.restoreAuthority.snapshot() ?: return
        requestRestore(entry,identity,event)
    }
    private fun gmRestorable(entry:Entry):Boolean {
        val activity=entry.activity ?: return false
        return current(entry) && runCatching { entry.restoreCurrent() }.getOrDefault(false) && entry.retainedWindow && entry.suspended && entry.parkedReceipt!=null &&
            entry.receipt==null && entry.suspension==null && !entry.attaching && !entry.withdrawing &&
            entry.pending==null && entry.releases.isEmpty() && entry.retirement==null &&
            !activity.isDestroyed && !activity.isFinishing && activity.taskId>=0 &&
            activity is com.openautolink.app.cluster.ClusterDirectVideoActivity &&
            activity.display?.displayId==entry.owner.descriptor.displayId
    }
    /** Runtime/session/selection/stream plus exact Activity/task and NEXT attach generation. */
    private class RestoreTicket(val identity:RestoreAuthority.Identity,val activity:SecondaryProjectionActivity,
        val taskId:Int,val activityGeneration:Long,val attachmentGeneration:Long,val policyGeneration:Long?,val pageGeneration:Long,
        val elapsed:Long,val result:CompletableFuture<DriverVideoRestoreResult>) {
        var expectedSurface:Surface?=null
    }
    private fun restoreVideo(entry:Entry,identity:RestoreAuthority.Identity?):CompletableFuture<DriverVideoRestoreResult> =
        requestRestore(entry,identity ?: entry.restoreAuthority.snapshot(),null)
    private fun requestRestore(entry:Entry,identity:RestoreAuthority.Identity?,automatic:PageEvent?):CompletableFuture<DriverVideoRestoreResult> {
        if(identity==null)return CompletableFuture.completedFuture(DriverVideoRestoreResult.BLOCKED)
        val result=entry.restoreAuthority.result(identity)
        onMain {
            if(entry.restoreResult?.isDone==true) { entry.restoreResult=null;entry.restoreTicket=null }
            if(entry.restoreResult!=null || !gmRestorable(entry)) {
                result.complete(DriverVideoRestoreResult.BLOCKED);return@onMain
            }
            val activity=entry.activity!!
            val ticket=synchronized(entry.restoreAuthority.monitor) {
                if(!entry.restoreAuthority.activate(identity,result))null else
                    RestoreTicket(identity,activity,activity.taskId,entry.activityGeneration,entry.attachmentGeneration+1,
                        identity.policyGeneration,pageRevision,android.os.SystemClock.elapsedRealtime(),result).also {
                        entry.restoreTicket=it;entry.restoreResult=result
                    }
            }
            if(ticket==null) { result.complete(DriverVideoRestoreResult.BLOCKED);return@onMain }
            val timeout=Runnable { entry.restoreAuthority.finish(identity,result,DriverVideoRestoreResult.TIMED_OUT) }
            main.postDelayed(timeout,2000)
            result.whenComplete { _,_ -> onMain {
                main.removeCallbacks(timeout)
                if(entry.restoreTicket===ticket) { entry.restoreTicket=null;entry.restoreResult=null }
            } }
            startRestoreWorker(entry,ticket,automatic)
        }
        return result
    }
    /** One actual IO worker plus one current pending ticket, never one blocked IO job per
     * cancel/retry. Replacing/canceling the pending request cannot drop terminal GPU cleanup. */
    private fun startRestoreWorker(entry:Entry,ticket:RestoreTicket,automatic:PageEvent?) {
        if(entry.restoreWorkerBusy) { entry.pendingRestore={ startRestoreWorker(entry,ticket,automatic) };return }
        if(!ticketCurrent(entry,ticket)) { entry.restoreAuthority.finish(ticket.identity,ticket.result,DriverVideoRestoreResult.BLOCKED);return }
        entry.restoreWorkerBusy=true
        val identity=ticket.identity;val result=ticket.result
        scope.launch(Dispatchers.IO) {
            try {
                var policy=runCatching { entry.restoreAllowed() }.getOrDefault(false)
                var automaticReason="ticket_or_page_expired"
                val automaticAllowed=if(automatic==null)true else {
                    val observer=com.openautolink.app.diagnostics.gm.GmObserverStore(context)
                    var refreshPolicy=false
                    var allowed=false
                    // Relay precedes diagnostic commit. Its actual file lock can transiently
                    // make the nonwaiting read unavailable; that is not a revoked generation.
                    // Wait only on this IO worker, inside BOTH original freshness deadlines.
                    // No cached enabled/policy value may authorize after a suspension.
                    while(policy && ticketCurrent(entry,ticket) &&
                        android.os.SystemClock.elapsedRealtime()-automatic.elapsed in 0..2000) {
                        val state=observer.read()
                        if(!state.available) {
                            automaticReason="observer_unavailable"
                            kotlinx.coroutines.delay(10)
                            refreshPolicy=true
                            continue
                        }
                        automaticReason=when {
                            !state.enabled->"observer_off"
                            state.generation!=automatic.generation->"observer_generation"
                            !com.openautolink.app.diagnostics.gm.GmObserverGate.qualified(context)->"observer_unqualified"
                            else->"none"
                        }
                        if(automaticReason!="none")break
                        if(refreshPolicy) {
                            policy=runCatching { entry.restoreAllowed() }.getOrDefault(false)
                            refreshPolicy=false
                            continue // Re-read consent/generation after suspendable policy IO.
                        }
                        allowed=true;break
                    }
                    allowed && android.os.SystemClock.elapsedRealtime()-automatic.elapsed in 0..2000
                }
                if(!policy || !automaticAllowed || !ticketCurrent(entry,ticket)) {
                    if(automatic!=null)trace(entry,"gm_restore_suppressed","reason=${if(!policy)"policy" else if(!automaticAllowed && automaticReason!="none")automaticReason else "ticket_invalid_or_page_stale"}")
                    entry.restoreAuthority.finish(identity,result,DriverVideoRestoreResult.BLOCKED)
                } else {
                    val requested=frontOwnTask(entry,ticket,if(automatic==null)"manual_restore_attempt" else "gm_restore_attempt")
                    if(!requested)entry.restoreAuthority.finish(identity,result,DriverVideoRestoreResult.BLOCKED)
                }
            } finally { onMain {
                entry.restoreWorkerBusy=false
                val next=entry.pendingRestore;entry.pendingRestore=null;next?.invoke()
            } }
        }
    }
    private fun ticketCurrent(entry:Entry,ticket:RestoreTicket):Boolean = synchronized(entry.restoreAuthority.monitor) {
        entry.restoreAuthority.owns(ticket.identity,ticket.result) && entry.restoreTicket===ticket &&
            entry.activity===ticket.activity && entry.activityGeneration==ticket.activityGeneration &&
            entry.owner.isCurrent && !closed.get() && !entry.terminal &&
            ticket.pageGeneration==pageRevision && ticket.activity.taskId==ticket.taskId &&
            !ticket.activity.isDestroyed && !ticket.activity.isFinishing &&
            runCatching { entry.restoreCurrent() }.getOrDefault(false) &&
            android.os.SystemClock.elapsedRealtime()-ticket.elapsed in 0..2000
    }
    /** Blocking task inventory is OFF Main and OUTSIDE the admission monitor. Only the exact
     * public task write runs under it. Dispatch initiation is irrevocable, not pixel success. */
    private fun frontOwnTask(entry:Entry,ticket:RestoreTicket,stage:String):Boolean {
        val component=android.content.ComponentName(context,com.openautolink.app.cluster.ClusterDirectVideoActivity::class.java)
        var reason="task_not_found"
        val outcome=runCatching {
            val manager=context.getSystemService(android.app.ActivityManager::class.java)
            manager.appTasks.singleOrNull {
                val info=it.taskInfo
                info.taskId==ticket.taskId && info.baseActivity==component && info.baseIntent.component==component
            } ?: return@runCatching false
            reason="stale_or_unavailable"
            synchronized(entry.restoreAuthority.monitor) {
                if(!ticketCurrent(entry,ticket) || !entry.suspended || entry.receipt!=null || entry.suspension!=null ||
                    entry.attaching || entry.withdrawing || entry.pending!=null || entry.releases.isNotEmpty() || entry.retirement!=null)false else {
                    // No callback/lookup/owner-monitor wait inside pageGate. Receipt and actual
                    // Binder dispatch share this final scalar revision admission boundary.
                    synchronized(pageGate) {
                        if(ticket.pageGeneration!=pageRevision)false else {
                            manager.moveTaskToFront(ticket.taskId,android.app.ActivityManager.MOVE_TASK_NO_USER_ACTION)
                            reason="requested";true
                        }
                    }
                }
            }
        }.getOrElse {
            reason=if(it is SecurityException) "permission_denied" else "platform_exception";false
        }
        trace(entry,stage,"requested=$outcome reason=$reason task=${ticket.taskId} physical=${entry.owner.descriptor.displayId} ownership=NOT_ESTABLISHED")
        return outcome
    }
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = changed(displayId, "display_added")
        override fun onDisplayChanged(displayId: Int) = changed(displayId, "display_changed")
        override fun onDisplayRemoved(displayId: Int) = changed(displayId, "display_removed")
    }
    private fun changed(id: Int, reason: String) {
        entries.filter { it.owner.descriptor.displayId == id }.toList().forEach { retire(it, reason) }
    }
    init {
        onMain {
            if (!closed.get()) {
                try { manager?.registerDisplayListener(listener, main); registered = manager != null }
                catch (_: RuntimeException) { }
                scope.launch {
                    inventory.collect {
                        entries.filter { !current(it) }.toList().forEach { retire(it, "inventory_invalidated") }
                    }
                }
            }
        }
    }
    private class Entry(val registry: SecondaryHostRegistry, val owner: SecondaryHostOwner, val token: String,
        val retainedWindow: Boolean, val restoreAllowed:suspend () -> Boolean, val restoreCurrent:() -> Boolean, val restoreAuthority:RestoreAuthority) {
        val ready = CompletableFuture<SecondaryHostReceipt>()
        @Volatile var activity: SecondaryProjectionActivity? = null
        @Volatile var receipt: SecondaryHostReceipt? = null
        @Volatile var parkedReceipt: SecondaryHostReceipt? = null
        val gpuRetired = CompletableFuture<Boolean>()
        @Volatile var attaching = false
        @Volatile var withdrawing = false
        @Volatile var terminal = false
        @Volatile var retirement: CompletableFuture<Boolean>? = null
        @Volatile var suspended = false
        @Volatile var suspension: CompletableFuture<Boolean>? = null
        var pending: (() -> Unit)? = null
        var pendingSurface: Surface? = null
        val releases = java.util.IdentityHashMap<Surface, () -> Unit>()
        var claim: com.openautolink.app.cluster.ClusterProcessAdmission.Claim? = null
        @Volatile var restoreTicket:RestoreTicket?=null
        @Volatile var activityGeneration=0L
        @Volatile var attachmentGeneration=0L
        var restoreWorkerBusy=false
        var pendingRestore:(()->Unit)?=null
        var restoreResult: CompletableFuture<DriverVideoRestoreResult>? = null
    }
    /** Caller supplies explicitly enabled frozen preference intent; discovery never calls this. */
    fun create(context: Context, descriptor: SecondaryDisplayDescriptor, projectionEpoch: Long,
        retainedWindow: Boolean = false, allowNativeGuidance: Boolean = false, restoreAllowed:suspend () -> Boolean = { false },
        restoreCurrent:() -> Boolean = { false }, restoreAuthority:RestoreAuthority=RestoreAuthority()): HostHandle {
        val token = ByteArray(32).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val entry = Entry(this, SecondaryHostOwner(descriptor, projectionEpoch), token, retainedWindow,restoreAllowed,restoreCurrent,restoreAuthority)
        val handle = HostHandle(entry.owner, entry.ready, retireOwner = {
            synchronized(entry.restoreAuthority.monitor) { entry.restoreAuthority.invalidate();entry.owner.revoke() } // exact write-admission fence
            val result = CompletableFuture<Boolean>()
            onMain { retire(entry, "requested_retirement").whenComplete { ok, error ->
                if (error != null) result.completeExceptionally(error) else result.complete(ok)
            } }
            result
        }, preparationReady = { offerReady(entry) }, restoreWindow = { restoreVideo(entry,it) })
        onMain {
            // Process-wide reservation includes live, attaching and failed-withdraw owners.
            // Every access is on Main, just like the per-registry lifecycle effects.
            val duplicate = processEntries.any { it.owner.descriptor.id == descriptor.id || it.owner.descriptor.displayId == descriptor.displayId }
            entries.add(entry)
            intakeEntries.add(entry)
            processEntries.add(entry)
            val rejection = when {
                context.packageName != this.context.packageName -> "create_wrong_package"
                projectionEpoch < 0 -> "create_invalid_epoch"
                duplicate -> "create_duplicate"
                !current(entry) -> "create_not_current"
                else -> null
            }
            if (rejection != null) { retire(entry, rejection); return@onMain }
            if (retainedWindow) {
                entry.claim = com.openautolink.app.cluster.ClusterProcessAdmission.acquireDirect(entry, allowNativeGuidance)
                if (entry.claim == null) { retire(entry, "process_claim_unavailable"); return@onMain }
                // Default remains video-only. Explicit coexistence lets the separately owned
                // guidance manager enable AndroidX; this direct provider never hosts its video.
                if (!allowNativeGuidance) {
                    val disabled = runCatching { this.context.packageManager.setComponentEnabledSetting(
                        android.content.ComponentName(this.context, "com.openautolink.app.cluster.OalClusterService"),
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP) }.isSuccess
                    if (!disabled) { retire(entry, "metadata_host_withdraw_failed"); return@onMain }
                }
                try { observeGmReturns() } catch (_:RuntimeException) {
                    trace(entry,"gm_restore_unavailable","reason=receiver_registration")
                }
            }
            tokens[token] = entry
            val intent = (if (retainedWindow) android.content.Intent(this.context,
                com.openautolink.app.cluster.ClusterDirectVideoActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                else SecondaryDisplayPolicyInventory.launchIntent(this.context)).putExtra(TOKEN_EXTRA, token)
            if (!PublicDisplayFacts.launchAllowed(this.context, descriptor.displayId, intent)) {
                retire(entry, "launch_denied"); return@onMain
            }
            try {
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(descriptor.displayId).toBundle()
                // Query and launch the same explicit token-bearing Intent.
                this.context.startActivity(intent, options)
            } catch (_: RuntimeException) { retire(entry, "launch_exception") }
        }
        return handle
    }
    private fun current(entry: Entry): Boolean = currentFailure(entry) == null
    private fun currentFailure(entry: Entry): String? {
        if (closed.get()) return "closed"
        if (entry.terminal) return "terminal"
        if (!entry.owner.isCurrent) return "owner_revoked"
        return descriptorFailure(entry.owner.descriptor, entry.retainedWindow)
    }
    private fun descriptorFailure(descriptor: SecondaryDisplayDescriptor, retainedWindow: Boolean = false): String? {
        if (descriptor.route != SecondaryRoute.PUBLIC_ACTIVITY && !(retainedWindow && descriptor.route == SecondaryRoute.GM_HOSTED)) return "wrong_route"
        val rows = inventory.value.displays
        if (rows.count { it.id == descriptor.id } != 1) return "identity_count"
        if (rows.count { it.displayId == descriptor.displayId } != 1) return "display_count"
        if (rows.singleOrNull { it.id == descriptor.id } != descriptor) return "descriptor_changed"
        val facts = PublicDisplayFacts.read(context, descriptor.displayId) ?: return "facts_missing"
        if (if (retainedWindow) !facts.valid || !facts.on || facts.private else !facts.publicRoute) return "public_route_lost"
        if (facts.width != descriptor.width || facts.height != descriptor.height) return "dimensions_changed"
        if (facts.densityDpi != descriptor.densityDpi) return "density_changed"
        return null
    }
    private fun bind(entry: Entry, activity: SecondaryProjectionActivity): Boolean {
        val rejection = when {
            entry.activity != null -> "bind_replayed_token"
            !current(entry) -> "bind_not_current"
            activity.display?.displayId != entry.owner.descriptor.displayId -> "bind_wrong_display"
            !PublicDisplayFacts.launchAllowed(context, entry.owner.descriptor.displayId, activity.intent) -> "bind_launch_denied"
            else -> null
        }
        if (rejection != null) {
            // A replayed token must not steal or retire the legitimate Activity.
            if (entry.activity == null) retire(entry, rejection)
            return false
        }
        synchronized(entry.restoreAuthority.monitor) {
            entry.restoreAuthority.invalidate();entry.activityGeneration++;entry.activity = activity
        }
        trace(entry, "host_bound", "actual=${activity.display?.displayId}")
        return true
    }
    private fun present(entry: Entry, activity: SecondaryProjectionActivity, surface: Surface, geometry: SecondaryHostGeometry) {
        if (entry.activity !== activity) return
        val rejection = when {
            !current(entry) -> "present_not_current"
            activity.display?.displayId != entry.owner.descriptor.displayId -> "present_wrong_display"
            !surface.isValid -> "present_invalid_surface"
            !PublicDisplayFacts.launchAllowed(context, entry.owner.descriptor.displayId, activity.intent) -> "present_launch_denied"
            else -> null
        }
        if (rejection != null) { retire(entry, rejection); return }
        if (entry.retainedWindow && (entry.suspension != null || entry.receipt?.surface?.let { it !== surface } == true)) {
            entry.pending = { if (!entry.suspended) present(entry, activity, surface, geometry) }
            entry.pendingSurface = surface
            suspendWindow(entry)
            return
        }
        if (entry.retainedWindow && entry.suspended) return
        val existing = entry.receipt
        if (existing != null) {
            if (existing.surface !== surface || existing.geometry != geometry) {
                retire(entry, if (existing.surface !== surface) "surface_replaced" else "geometry_changed", replacement = geometry)
            }
            return
        }
        val receipt = SecondaryHostReceipt(surface, geometry, entry.owner)
        val attachmentGeneration=synchronized(entry.restoreAuthority.monitor) {
            entry.receipt = receipt
            entry.attaching = true
            (++entry.attachmentGeneration).also { generation ->
                entry.restoreTicket?.takeIf { it.activity===activity && it.activityGeneration==entry.activityGeneration &&
                    it.attachmentGeneration==generation }?.expectedSurface=surface
            }
        }
        trace(entry, "host_surface", "geometry=${geometryFields(geometry)} actual=${activity.display?.displayId} ${windowFields(entry)}")
        var synchronousFailure: String? = null
        val attach = try { consumer.onReady(receipt) }
        catch (failure: RuntimeException) {
            synchronousFailure = com.openautolink.app.cluster.OptionalOutputDiagnostics.reason(failure)
            CompletableFuture.completedFuture(false)
        }
        attach.whenComplete { ok, failure -> onMain {
            entry.attaching = false
            val rejection = when {
                failure != null || synchronousFailure != null -> "attach_exception"
                ok != true -> "attach_rejected"
                !current(entry) -> "attach_not_current"
                entry.activity !== activity && !entry.retainedWindow -> "attach_stale_activity"
                (entry.activity === activity || !entry.retainedWindow) &&
                    activity.display?.displayId != entry.owner.descriptor.displayId -> "attach_wrong_display"
                else -> null
            }
            if (rejection != null) {
                retire(entry, rejection, attachFailure = synchronousFailure)
            } else if (entry.retainedWindow && (entry.suspended || entry.activity !== activity)) {
                // A stopped/destroyed predecessor can acknowledge late. Park its exact GPU
                // borrow, then admit the actual successor; never retire that successor's owner.
                suspendWindow(entry)
            } else {
                entry.parkedReceipt = null
                trace(entry, "host_ready", "actual=${activity.display?.displayId} ${windowFields(entry)}")
                entry.ready.complete(receipt)
                entry.restoreTicket?.let { ticket ->
                    val exact=synchronized(entry.restoreAuthority.monitor) {
                        ticketCurrent(entry,ticket) && ticket.activity===activity &&
                            ticket.attachmentGeneration==attachmentGeneration && ticket.expectedSurface===receipt.surface &&
                            entry.receipt===receipt
                    }
                    entry.restoreAuthority.finish(ticket.identity,ticket.result,
                        if(exact)DriverVideoRestoreResult.ATTACHED else DriverVideoRestoreResult.BLOCKED)
                }
            }
            if (entry.terminal) withdraw(entry)
            else if (entry.suspension != null) performSuspension(entry)
        } }
    }
    /** Page-away retains the phone contract and decoder; never launches an Activity. */
    private fun suspendWindow(entry: Entry): CompletableFuture<Boolean> {
        entry.suspension?.let { return it }
        val result = CompletableFuture<Boolean>()
        entry.suspension = result
        performSuspension(entry)
        return result
    }
    private fun performSuspension(entry: Entry) {
        if (entry.attaching || entry.withdrawing) return
        val result = entry.suspension ?: return
        if (result.isDone) return
        val receipt = entry.receipt
        if (receipt == null) {
            entry.suspension = null; result.complete(true)
            val pending = entry.pending; entry.pending = null; entry.pendingSurface = null; pending?.invoke()
            return
        }
        entry.withdrawing = true
        trace(entry, "host_suspend_submit", windowFields(entry))
        val ack = try { consumer.onSuspend(receipt) } catch (_: Exception) { CompletableFuture.completedFuture(false) }
        ack.whenComplete { ok, error -> onMain {
            entry.withdrawing = false
            val gpuDetached = error == null && ok == true
            if (gpuDetached) {
                entry.parkedReceipt = receipt
                if (entry.receipt === receipt) entry.receipt = null
            }
            val success = gpuDetached && releaseSurface(entry, receipt.surface)
            if (success) entry.suspension = null
            trace(entry, "host_suspend_ack", "success=$success gpuDetached=$gpuDetached surface=${System.identityHashCode(receipt.surface)} ${windowFields(entry)}")
            result.complete(success)
            // Initial startup can be hidden BEFORE onReady acknowledges. Its EGL/geometry
            // qualification still succeeded; acknowledge preparation only AFTER exact GPU
            // parking/Surface cleanup. Never fake an attached/visible window or wait for a
            // first frame which cannot arrive until this offer is negotiated.
            if (success && current(entry) && entry.suspended && entry.activity != null) {
                trace(entry, "host_prepared_parked", "visibility=false ${windowFields(entry)}")
                entry.ready.complete(receipt)
            }
            if (entry.terminal) withdraw(entry)
            else if (success) { val pending = entry.pending; entry.pending = null; entry.pendingSurface = null; pending?.invoke() }
        } }
    }
    private fun retire(entry: Entry, reason: String, replacement: SecondaryHostGeometry? = null,
        attachFailure: String? = null): CompletableFuture<Boolean> {
        if (!entry.terminal) {
            val wasCurrent = entry.owner.isCurrent
            synchronized(entry.restoreAuthority.monitor) {
                entry.restoreAuthority.invalidate();entry.terminal = true;entry.owner.revoke()
            }
            entry.restoreResult?.complete(DriverVideoRestoreResult.BLOCKED)
            entry.pendingRestore=null
            entry.pending = null; entry.pendingSurface = null
            // Never delay packet-authority revocation behind public fact reads or persisted IO.
            // Only diagnostic observation is fallible; the original cleanup below always runs.
            runCatching {
                val descriptor = entry.owner.descriptor
                val gate = when {
                    closed.get() -> "closed"
                    !wasCurrent -> "owner_revoked"
                    else -> descriptorFailure(descriptor) ?: "current"
                }
                val live = PublicDisplayFacts.read(context, descriptor.displayId)
                val row = inventory.value.displays.singleOrNull { it.id == descriptor.id }
                trace(entry, "host_revoke", "reason=$reason gate=$gate hadSurface=${entry.receipt != null} attaching=${entry.attaching} actual=${entry.activity?.display?.displayId} inventoryEpoch=${row?.attachmentEpoch} inventoryRoute=${row?.route} inventoryReason=${row?.reason}")
                trace(entry, "host_facts", "expected=${descriptor.width}x${descriptor.height}@${descriptor.densityDpi} live=${live?.let { "${it.width}x${it.height}@${it.densityDpi} valid=${it.valid} on=${it.on} private=${it.private} presentation=${it.presentation} builtin=${it.builtin}" } ?: "unavailable"}")
                if (replacement != null) entry.receipt?.let { previous ->
                    trace(entry, "host_geometry_change", "previous=${geometryFields(previous.geometry)} next=${geometryFields(replacement)} surfaceChanged=${reason == "surface_replaced"}")
                }
                if (attachFailure != null) trace(entry, "host_attach_exception", "failure=$attachFailure")
            }
            entry.activity?.concealSurface()
            runCatching { consumer.onRevoke(entry.owner) }
            entry.ready.completeExceptionally(IllegalStateException("HOST_UNAVAILABLE"))
        }
        val old = entry.retirement
        if (old != null && (!old.isDone || old.getNow(false))) return old
        val result = CompletableFuture<Boolean>()
        entry.retirement = result
        withdraw(entry)
        return result
    }
    private fun withdraw(entry: Entry) {
        if (!entry.terminal || entry.attaching || entry.withdrawing) return
        val result = entry.retirement ?: return
        if (result.isDone) return
        val receipt = entry.receipt ?: entry.parkedReceipt
        if (receipt == null) { finishRetirement(entry, result, true); return }
        entry.withdrawing = true
        val detach = try { consumer.onWithdraw(receipt) }
        catch (_: RuntimeException) { CompletableFuture.completedFuture(false) }
        detach.whenComplete { ok, failure -> onMain {
            entry.withdrawing = false
            finishRetirement(entry, result, failure == null && ok == true)
        } }
    }
    private fun releaseSurface(entry: Entry, surface: Surface): Boolean {
        val release = entry.releases[surface] ?: return true
        if (runCatching { release() }.isFailure) {
            trace(entry, "owned_surface_release_failed", "cleanupDebt=true")
            return false
        }
        if (entry.releases[surface] === release) entry.releases.remove(surface)
        return true
    }
    private fun finishRetirement(entry: Entry, result: CompletableFuture<Boolean>, success: Boolean) {
        val released = success && entry.releases.keys.toList().all { releaseSurface(entry, it) }
        if (released) {
            tokens.remove(entry.token, entry)
            entries.remove(entry)
            intakeEntries.remove(entry)
            processEntries.remove(entry) // exact owner only, after the real detach/clear acknowledgment
            entry.claim?.let { com.openautolink.app.cluster.ClusterProcessAdmission.release(it) }
            entry.claim = null
            entry.receipt = null // release borrowed reference only after renderer acknowledgment
            entry.parkedReceipt = null
            entry.gpuRetired.complete(true)
            val activity = entry.activity
            entry.activity = null
            if (entry.retainedWindow) activity?.finishAndRemoveTask() else activity?.finish()
        }
        result.complete(released)
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        intakeEntries.forEach { it.restoreAuthority.invalidate() }
        intakeThread.quitSafely()
        scope.cancel()
        onMain {
            gmAway=null
            if(gmReceiverRegistered) {
                gmReceiverRegistered=false
                runCatching { context.unregisterReceiver(gmReceiver) }
            }
            if (registered) {
                registered = false
                runCatching { manager?.unregisterDisplayListener(listener) }
            }
            entries.toList().forEach { retire(it, "registry_closed") }
        }
    }
    private fun offerReady(entry: Entry): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>()
        onMain {
            val pending = entry.suspension
            if (pending != null && !pending.isDone) {
                pending.whenComplete { ok, error ->
                    if (error != null || ok != true) result.complete(false)
                    else offerReady(entry).whenComplete { ready, failure -> result.complete(failure == null && ready == true) }
                }
            } else result.complete(current(entry) && entry.activity != null &&
                !entry.attaching && !entry.withdrawing && entry.suspension == null &&
                entry.pending == null && entry.releases.isEmpty() && entry.retirement == null &&
                ((!entry.suspended && entry.receipt != null) ||
                    (entry.retainedWindow && entry.suspended && entry.parkedReceipt != null && entry.receipt == null)))
        }
        return result
    }
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == main.looper) block() else main.post(block)
    }
    private fun trace(entry: Entry, stage: String, fields: String) {
        // Capture session/attachment and state now; the worker never reads mutable authority.
        runCatching {
            val message = "OPTIONAL_RUNTIME_V1 session=${entry.owner.projectionEpoch} physical=${entry.owner.descriptor.displayId} attachment=${entry.owner.descriptor.attachmentEpoch} stage=$stage $fields"
            if (entry.retainedWindow) PageReturnDiagnostics.tryOffer(message)
            else com.openautolink.app.diagnostics.DiagnosticLog.i("secondary", message)
        }
    }
    private fun geometryFields(value: SecondaryHostGeometry) =
        "${value.width}x${value.height} safe=${value.safe.left},${value.safe.top},${value.safe.right},${value.safe.bottom}"
    private fun windowFields(entry: Entry) =
        if (!entry.retainedWindow) "" else "pid=${android.os.Process.myPid()} elapsedMs=${android.os.SystemClock.elapsedRealtime()} activity=${System.identityHashCode(entry.activity)} receipt=${System.identityHashCode(entry.receipt?.surface)} parked=${System.identityHashCode(entry.parkedReceipt?.surface)} pending=${System.identityHashCode(entry.pendingSurface)} suspended=${entry.suspended} attaching=${entry.attaching} withdrawing=${entry.withdrawing} terminal=${entry.terminal}"
    companion object {
        internal const val TOKEN_EXTRA = "com.openautolink.app.secondary.OWNER"
        private val random = SecureRandom()
        private val tokens = ConcurrentHashMap<String, Entry>()
        // Main-confined, strong until acknowledged: close/token revocation must not erase debt.
        private val processEntries = mutableSetOf<Entry>()
        internal fun bind(activity: SecondaryProjectionActivity, token: String?): Boolean {
            val entry = token?.let { tokens[it] } ?: return false
            if (entry.terminal) return false
            return entry.registry.bind(entry, activity)
        }
        internal fun present(activity: SecondaryProjectionActivity, token: String?, surface: Surface, geometry: SecondaryHostGeometry) {
            val entry = token?.let { tokens[it] } ?: return
            if (entry.terminal) return
            entry.registry.present(entry, activity, surface, geometry)
        }
        internal fun withdraw(activity: SecondaryProjectionActivity, token: String?) {
            val entry = token?.let { tokens[it] } ?: return
            if (entry.terminal) return
            if (entry.activity === activity) entry.registry.retire(entry, "activity_withdraw")
        }
        internal fun suspend(activity: SecondaryProjectionActivity, token: String?, surface: Surface? = null, source: String = "explicit"): CompletableFuture<Boolean> {
            val entry = token?.let { tokens[it] } ?: return CompletableFuture.completedFuture(false)
            if (entry.retainedWindow) entry.registry.trace(entry, "host_suspend_request", "source=$source caller=${System.identityHashCode(activity)} surface=${System.identityHashCode(surface)} ${entry.registry.windowFields(entry)}")
            if (!entry.retainedWindow) return CompletableFuture.completedFuture(false)
            if (entry.terminal) return entry.retirement ?: CompletableFuture.completedFuture(false)
            if (entry.activity !== activity) {
                // An old Activity can receive its exact texture destruction after recreation.
                // It may await that old Surface's cleanup, never suspend the successor.
                if (surface == null) return CompletableFuture.completedFuture(false)
                return if (entry.receipt?.surface === surface) entry.suspension ?: CompletableFuture.completedFuture(false)
                    else CompletableFuture.completedFuture(true)
            }
            if (surface != null && entry.receipt?.surface !== surface) return CompletableFuture.completedFuture(true)
            synchronized(entry.restoreAuthority.monitor) {
                entry.restoreAuthority.invalidate()
                if(surface==null) { entry.suspended=true;entry.pending=null;entry.pendingSurface=null }
            }
            if (surface != null) return entry.registry.suspendWindow(entry)
            return entry.registry.suspendWindow(entry)
        }
        internal fun releaseTexture(token: String?, surface: Surface, release: () -> Unit) {
            val entry = token?.let { tokens[it] }
            if (entry?.retainedWindow == true) entry.registry.trace(entry, "host_texture_release", "surface=${System.identityHashCode(surface)} ${entry.registry.windowFields(entry)}")
            if (entry == null || !entry.retainedWindow || entry.receipt?.surface !== surface) {
                if (entry?.pendingSurface === surface) { entry.pending = null; entry.pendingSurface = null }
                release() // never submitted, or exact old borrow already positively parked/retired
                return
            }
            entry.releases.putIfAbsent(surface, release)
            // Surface loss is NOT evidence that its still-started Activity/page went away.
            if (entry.terminal) entry.registry.withdraw(entry) else entry.registry.suspendWindow(entry)
        }
        internal fun resume(activity: SecondaryProjectionActivity, token: String?) {
            val entry = token?.let { tokens[it] } ?: return
            if (entry.retainedWindow) entry.registry.trace(entry, "host_resume_request", "caller=${System.identityHashCode(activity)} ${entry.registry.windowFields(entry)}")
            synchronized(entry.restoreAuthority.monitor) {
                if (entry.activity === activity && entry.retainedWindow && !entry.terminal && entry.owner.isCurrent) entry.suspended = false
            }
        }
        internal fun destroyed(activity: SecondaryProjectionActivity, token: String?) {
            val entry = token?.let { tokens[it] } ?: return
            if (entry.activity === activity && entry.retainedWindow) {
                synchronized(entry.restoreAuthority.monitor) {
                    entry.restoreAuthority.invalidate();entry.activityGeneration++
                    entry.suspended = true; entry.pending = null; entry.pendingSurface = null;entry.activity = null
                }
                if (!entry.terminal) entry.registry.suspendWindow(entry)
            }
        }
    }
}
