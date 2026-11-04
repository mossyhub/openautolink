package com.openautolink.app.secondary

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DeviceProductInfo
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import com.openautolink.app.cluster.ClusterDriverDisplayReceipt
import com.openautolink.app.cluster.ClusterProjectionOutput
import com.openautolink.app.displays.DiscoveredSecondaryDisplay
import com.openautolink.app.displays.SecondaryDisplayInventory
import com.openautolink.app.diagnostics.DiagnosticLog
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Policy over the one raw inventory. Discovery never launches a host or infers a passenger role. */
class SecondaryDisplayPolicyInventory internal constructor(
    context: Context,
    val raw: SecondaryDisplayInventory,
) : AutoCloseable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val closed = AtomicBoolean(false)
    private val manager = this.context.getSystemService(DisplayManager::class.java)
    private val state = MutableStateFlow(SecondaryInventorySnapshot(0, emptyList()))
    val snapshots: StateFlow<SecondaryInventorySnapshot> = state.asStateFlow()
    private var revision = 0L
    private var nextEpoch = 0L
    private data class Attachment(val raw: DiscoveredSecondaryDisplay, val facts: PublicDisplayFacts?, val epoch: Long)
    private var previous = emptyMap<SecondaryOutputId, Attachment>()
    private val dirtyIds = mutableSetOf<Int>()
    private var registered = false
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = changed(displayId, "added")
        override fun onDisplayChanged(displayId: Int) = changed(displayId, "changed")
        override fun onDisplayRemoved(displayId: Int) = changed(displayId, "removed")
    }
    init {
        onMain {
            if (!closed.get()) {
                try { manager?.registerDisplayListener(listener, main); registered = manager != null }
                catch (_: RuntimeException) { }
                scope.launch { raw.displays.collect { refresh() } }
            }
        }
    }
    private fun changed(id: Int, event: String) {
        if (closed.get()) return
        if (id > Display.DEFAULT_DISPLAY) runCatching { DiagnosticLog.i("secondary",
            "OPTIONAL_RUNTIME_V1 stage=policy_display_event event=$event physical=$id revision=$revision") }
        dirtyIds.add(id)
        // Refresh supplements even when raw metadata equality suppresses StateFlow emission.
        refresh()
    }
    private fun refresh() {
        if (closed.get()) return
        val rows = raw.displays.value
        val keyCounts = rows.mapNotNull { it.key }.groupingBy { it }.eachCount()
        val idCounts = rows.groupingBy { it.displayId }.eachCount()
        val qualified = runCatching { ClusterProjectionOutput.qualifiedInitialDisplay(context) }.getOrNull()
        val gmId = qualified?.let { runCatching {
            ClusterDriverDisplayReceipt.QUALIFIED_GM.resolve(context, it)?.displayId
        }.getOrNull() }
        val next = LinkedHashMap<SecondaryOutputId, Attachment>()
        val descriptors = rows.mapNotNull { row ->
            val key = row.key ?: return@mapNotNull null
            if (key.isBlank() || key.length > 512 || row.identityAmbiguous || keyCounts[key] != 1 ||
                idCounts[row.displayId] != 1 || row.displayId <= Display.DEFAULT_DISPLAY ||
                row.width <= 0 || row.height <= 0) return@mapNotNull null
            val id = SecondaryOutputId(key)
            val facts = PublicDisplayFacts.read(context, row.displayId)
            val old = previous[id]
            val epoch = if (old == null || old.raw != row || old.facts != facts || row.displayId in dirtyIds)
                ++nextEpoch else old.epoch
            if (old != null && epoch != old.epoch) runCatching { DiagnosticLog.i("secondary",
                "OPTIONAL_RUNTIME_V1 stage=policy_epoch physical=${row.displayId} previous=${old.epoch} next=$epoch dirty=${row.displayId in dirtyIds} rawChanged=${old.raw != row} factsChanged=${old.facts != facts} previousFacts=${factsFields(old.facts)} nextFacts=${factsFields(facts)}") }
            next[id] = Attachment(row, facts, epoch)
            val reason = when {
                !row.available || facts == null || !facts.valid || !facts.on -> SecondaryReason.DISCONNECTED
                facts.private -> SecondaryReason.PRIVATE_DISPLAY
                row.name.contains("AppHost", true) && row.name.contains("CarAppService", true) -> SecondaryReason.NO_PUBLIC_ROUTE
                row.displayId == gmId -> SecondaryReason.NONE

                !PublicDisplayFacts.launchAllowed(context, row.displayId, launchIntent(context)) -> SecondaryReason.LAUNCH_DENIED
                else -> SecondaryReason.NONE
            }
            val route = when {
                reason != SecondaryReason.NONE -> SecondaryRoute.INVENTORY_ONLY
                row.displayId == gmId -> SecondaryRoute.GM_HOSTED
                else -> SecondaryRoute.PUBLIC_ACTIVITY
            }
            SecondaryDisplayDescriptor(id, row.displayId, epoch, row.name, row.width, row.height,
                facts?.densityDpi?.takeIf { it > 0 } ?: 160, route, reason,
                if (route == SecondaryRoute.GM_HOSTED) "Qualified driver cluster" else "Unknown")
        }
        previous = next
        dirtyIds.clear()
        if (!closed.get() && state.value.displays != descriptors) {
            // Main serialization and close's immediate atomic invalidation reject stale publication.
            state.value = SecondaryInventorySnapshot(++revision, Collections.unmodifiableList(ArrayList(descriptors)))
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        onMain {
            if (registered) {
                registered = false
                runCatching { manager?.unregisterDisplayListener(listener) }
            }
        }
    }
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == main.looper) block() else main.post(block)
    }
    private fun factsFields(value: PublicDisplayFacts?): String = value?.let {
        "${it.width}x${it.height}@${it.densityDpi},valid=${it.valid},on=${it.on},private=${it.private},presentation=${it.presentation},builtin=${it.builtin}"
    } ?: "unavailable"
    companion object {
        @Volatile private var instance: SecondaryDisplayPolicyInventory? = null
        fun get(context: Context): SecondaryDisplayPolicyInventory = instance ?: synchronized(this) {
            instance ?: SecondaryDisplayPolicyInventory(context.applicationContext,
                SecondaryDisplayInventory(context.applicationContext)).also { instance = it }
        }
        internal fun launchIntent(context: Context) = Intent(context, SecondaryProjectionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }
}

/** Public connection facts, not a physical role receipt. Never uses hidden Display APIs. */
internal data class PublicDisplayFacts(
    val valid: Boolean, val on: Boolean, val private: Boolean, val presentation: Boolean,
    val builtin: Boolean, val width: Int, val height: Int, val densityDpi: Int,
) {
    // Explicit user-selected target plus actual launch permission; builtin/presentation are
    // observations, not a calibration database or proof of a physical cluster role.
    val publicRoute get() = valid && on && !private && width > 0 && height > 0 && densityDpi > 0
    companion object {
        @Suppress("DEPRECATION")
        fun read(context: Context, id: Int): PublicDisplayFacts? = runCatching {
            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(id) ?: return null
            val metrics = DisplayMetrics().also { display.getRealMetrics(it) }
            // Supplementary route facts may be unavailable on otherwise usable GM panels.
            // Neither optional API is a prerequisite for retaining the essential live facts.
            val builtin = (try { display.deviceProductInfo?.connectionToSinkType == DeviceProductInfo.CONNECTION_TO_SINK_BUILT_IN }
                catch (_: RuntimeException) { false }) || (try {
                Regex("\\btype[ =]+(?:BUILT_IN|INTERNAL)\\b").containsMatchIn(display.toString().take(16384))
            } catch (_: RuntimeException) { false })
            PublicDisplayFacts(display.isValid && id != Display.DEFAULT_DISPLAY, display.state == Display.STATE_ON,
                display.flags and Display.FLAG_PRIVATE != 0, display.flags and Display.FLAG_PRESENTATION != 0,
                builtin, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }.getOrNull()
        fun launchAllowed(context: Context, id: Int, intent: Intent): Boolean = runCatching {
            context.getSystemService(ActivityManager::class.java)?.isActivityStartAllowedOnDisplay(context, id, intent) == true
        }.getOrDefault(false)
    }
}
