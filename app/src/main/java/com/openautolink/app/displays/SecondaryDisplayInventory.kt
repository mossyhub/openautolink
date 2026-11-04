package com.openautolink.app.displays

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Metadata identity is not proof of physical role, pixel access, or navigation ownership. */
data class DiscoveredSecondaryDisplay(
    val key: String?,
    val displayId: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val available: Boolean,
    val launchAllowed: Boolean,
    val identityAmbiguous: Boolean,
    val reason: String?
)

/** Stable, nonlocalized status tokens; never include exception messages. */
enum class SecondaryDisplayReason {
    PRIVATE_DISPLAY, INVALID_DISPLAY, DISPLAY_NOT_ON, INVALID_DIMENSIONS,
    METADATA_SECURITY_DENIED, METADATA_UNAVAILABLE, IDENTITY_AMBIGUOUS,
    LAUNCH_QUERY_DENIED, LAUNCH_QUERY_SECURITY_DENIED, LAUNCH_QUERY_UNAVAILABLE,
    DISPLAY_SERVICE_UNAVAILABLE, LISTENER_SECURITY_DENIED, LISTENER_UNAVAILABLE
}

/**
 * Inventory of additional displays visible to this process through public DisplayManager APIs.
 * Inaccessible OEM displays cannot be enumerated. No rendering, launch, persistence, role inference,
 * permissions, hidden identifiers or logging. Missing product metadata still permits a local
 * build/name/panel fingerprint; it is a candidate identity, not a hardware-unique identifier.
 * Duplicate fingerprints remain separate rows with null keys; never persist an ambiguous row.
 *
 * launchAllowed means only that ActivityManager admitted a query for our explicit MainActivity
 * intent. It does NOT mean arbitrary pixels can be shown, or that navigation ownership is granted.
 * Callbacks and publication run on Main without an inventory monitor. close is idempotent and
 * invalidates queued refresh work immediately; Main serializes listener registration/removal.
 * A failed listener registration retains visible rows but withholds live availability for this
 * inventory's lifetime. Successful enumeration is not successful monitoring registration.
 */
class SecondaryDisplayInventory(
    context: Context,
    private val onChanged: ((List<DiscoveredSecondaryDisplay>) -> Unit)? = null
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0)
    private val closed = AtomicBoolean(false)
    private val pending = AtomicBoolean(false)
    private val mutableDisplays = MutableStateFlow<List<DiscoveredSecondaryDisplay>>(emptyList())
    val displays: StateFlow<List<DiscoveredSecondaryDisplay>> = mutableDisplays.asStateFlow()
    private var manager: DisplayManager? = null
    private var registered = false
    private var listenerFailure: SecondaryDisplayReason? = null
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    init {
        val epoch = generation.get()
        onMain {
            if (isCurrent(epoch)) {
                try {
                    manager = appContext.getSystemService(DisplayManager::class.java)
                    manager?.registerDisplayListener(listener, main)
                    registered = manager != null
                } catch (_: SecurityException) {
                    listenerFailure = SecondaryDisplayReason.LISTENER_SECURITY_DENIED
                } catch (_: RuntimeException) {
                    listenerFailure = SecondaryDisplayReason.LISTENER_UNAVAILABLE
                }
                refresh()
            }
        }
    }

    /** Coalesce off-Main callers into at most one queued refresh; no worker per display event. */
    fun refresh() {
        if (closed.get() || !pending.compareAndSet(false, true)) return
        val epoch = generation.get()
        onMain {
            pending.set(false)
            if (isCurrent(epoch)) publish(epoch, snapshot())
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        onMain {
            if (registered) {
                registered = false
                try { manager?.unregisterDisplayListener(listener) } catch (_: RuntimeException) { }
            }
        }
    }

    private fun isCurrent(epoch: Long) = !closed.get() && generation.get() == epoch
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == main.looper) block() else main.post(block)
    }

    private fun publish(epoch: Long, rows: List<DiscoveredSecondaryDisplay>) {
        if (!isCurrent(epoch) || mutableDisplays.value == rows) return
        mutableDisplays.value = rows
        if (isCurrent(epoch)) onChanged?.invoke(rows)
    }

    private fun snapshot(): List<DiscoveredSecondaryDisplay> {
        val raw = try {
            manager?.displays?.filter { it.displayId != Display.DEFAULT_DISPLAY }
                ?: return unavailableSnapshot()
        } catch (_: RuntimeException) { return unavailableSnapshot() }
        val rows = raw.map(::readDisplay).map { row ->
            listenerFailure?.let { row.copy(available = false, launchAllowed = false, reason = it.name) }
                ?: row
        }
        val counts = rows.mapNotNull { it.key }.groupingBy { it }.eachCount()
        return immutable(rows.map { row ->
            if (row.key != null && counts.getValue(row.key) > 1) {
                row.copy(key = null, identityAmbiguous = true,
                    reason = row.reason ?: SecondaryDisplayReason.IDENTITY_AMBIGUOUS.name)
            } else row
        }.sortedBy { it.displayId })
    }

    private fun unavailableSnapshot() = immutable(mutableDisplays.value.map {
        it.copy(available = false, launchAllowed = false,
            reason = (listenerFailure ?: SecondaryDisplayReason.DISPLAY_SERVICE_UNAVAILABLE).name)
    })

    private fun readDisplay(display: Display): DiscoveredSecondaryDisplay {
        val id = display.displayId
        var name = ""
        var width = 0
        var height = 0
        try {
            name = display.name
            val mode = display.mode
            width = mode.physicalWidth
            height = mode.physicalHeight
            // Public EDID is optional. Its absence/denial must not erase a uniquely
            // identified public panel; absent metadata uses the existing null tuple slot.
            val product = try { display.deviceProductInfo } catch (_: RuntimeException) { null }
            // Fixed-order JSON tuple: escaping and null positions are unambiguous. No runtime ID,
            // state, rotation or flags: a temporary power/state/ID change must not change the key.
            val tuple = JSONArray().put("oal-secondary-display-v1")
                .put(Build.PRODUCT).put(Build.VERSION.SDK_INT).put(Build.ID)
                .put(Build.VERSION.INCREMENTAL).put(name).put(width).put(height)
                .put(if (product == null) org.json.JSONObject.NULL else JSONArray()
                    .put(product.manufacturerPnpId).put(product.productId).put(product.name)
                    .put(product.modelYear).put(product.manufactureYear).put(product.manufactureWeek))
            val key = "secondary-v1:" + MessageDigest.getInstance("SHA-256")
                .digest(tuple.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val unavailable = when {
                !display.isValid -> SecondaryDisplayReason.INVALID_DISPLAY
                display.flags and Display.FLAG_PRIVATE != 0 -> SecondaryDisplayReason.PRIVATE_DISPLAY
                display.state != Display.STATE_ON -> SecondaryDisplayReason.DISPLAY_NOT_ON
                width <= 0 || height <= 0 -> SecondaryDisplayReason.INVALID_DIMENSIONS
                else -> null
            }
            if (unavailable != null) return DiscoveredSecondaryDisplay(
                key, id, name, width, height, false, false, false, unavailable.name)
            val queryReason = try {
                val activityManager = appContext.getSystemService(ActivityManager::class.java)
                val intent = Intent().setClassName(appContext.packageName,
                    "com.openautolink.app.MainActivity")
                when (activityManager?.isActivityStartAllowedOnDisplay(appContext, id, intent)) {
                    true -> null
                    false -> SecondaryDisplayReason.LAUNCH_QUERY_DENIED
                    null -> SecondaryDisplayReason.LAUNCH_QUERY_UNAVAILABLE
                }
            } catch (_: SecurityException) { SecondaryDisplayReason.LAUNCH_QUERY_SECURITY_DENIED
            } catch (_: RuntimeException) { SecondaryDisplayReason.LAUNCH_QUERY_UNAVAILABLE }
            return DiscoveredSecondaryDisplay(key, id, name, width, height, true,
                queryReason == null, false, queryReason?.name)
        } catch (_: SecurityException) {
            return DiscoveredSecondaryDisplay(null, id, name, width, height, false, false, true,
                SecondaryDisplayReason.METADATA_SECURITY_DENIED.name)
        } catch (_: RuntimeException) {
            return DiscoveredSecondaryDisplay(null, id, name, width, height, false, false, true,
                SecondaryDisplayReason.METADATA_UNAVAILABLE.name)
        }
    }

    private fun immutable(rows: List<DiscoveredSecondaryDisplay>): List<DiscoveredSecondaryDisplay> =
        Collections.unmodifiableList(ArrayList(rows))
}
