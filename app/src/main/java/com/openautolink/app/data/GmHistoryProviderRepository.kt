package com.openautolink.app.data

import android.content.Context
import android.net.Uri
import com.openautolink.app.diagnostics.OalLog

/**
 * Reads the latest sample from `com.gm.vehicleinfo.HistoryProvider`.
 *
 * This is a third-party provider exported by GM's Vehicle-Info app with
 * no manifest permission and no runtime `checkCallingPermission` in
 * `query()` (see `recon_dump/gm-aaos-recon.md` §15, Finding F).
 *
 * It exposes the car's electric-motor power timeseries, motor torque,
 * battery level, and ICE equivalents. We use it as an additional input
 * to the EV energy model: instantaneous motor power × dt = Wh used, which
 * is more responsive than the SOC-derived `Δbattery` approach.
 *
 * If GM ever closes the provider (patch, permission gate, or removed),
 * every call here returns `null` and the estimator transparently falls
 * back to its existing battery-delta math. No error spam in logs.
 *
 * Schema (from decompile):
 *   table  HistoricalData(id INT PK, value FLOAT, history_type INT)
 *          inserted in chronological order, ring-buffered to 6000 rows
 *   table  HistoryTimestamp(id INT PK, timestamp LONG)
 *          one row per history_type — latest insert timestamp in ms
 *
 * Query selection arg must be `"SELECTION_HISTORY_DATA"` (values) or
 * `"SELECTION_HISTORY_TIMESTAMP"` (epoch-ms of latest sample). Cursors
 * have a single column named `value` or `timestamp`.
 */
object GmHistoryProviderRepository {

    private const val TAG = "GmHistory"

    enum class Series(val path: String) {
        E_POWER("history/e_power"),
        E_TORQUE("history/e_torque"),
        E_BATTERY("history/e_battery"),
        ICE_POWER("history/ice_power"),
        ICE_TORQUE("history/ice_torque"),
    }

    private const val AUTHORITY = "com.gm.vehicleinfo.HistoryProvider"
    private const val SELECTION_DATA = "SELECTION_HISTORY_DATA"
    private const val SELECTION_TIMESTAMP = "SELECTION_HISTORY_TIMESTAMP"

    @Volatile private var disabled = false
    @Volatile private var available: Boolean? = null

    /**
     * Fast non-throwing reachability check. Returns the cached result after
     * the first call. If the very first query throws or the cursor is null,
     * we mark the provider permanently unavailable for this process lifetime
     * (e.g. GM patched it) so we don't keep paying IPC cost.
     */
    fun isAvailable(context: Context): Boolean {
        if (disabled) return false
        available?.let { return it }
        val ok = try {
            queryLatest(context, Series.E_BATTERY) != null
        } catch (_: Throwable) { false }
        available = ok
        if (!ok) {
            OalLog.i(TAG, "HistoryProvider unavailable — falling back to SOC-derived math")
        } else {
            OalLog.i(TAG, "HistoryProvider available — using motor-power for energy model")
        }
        return ok
    }

    /** Force-disable for the rest of the process (e.g. user toggle). */
    fun disable() { disabled = true; available = false }

    /** Latest motor power in W, or null when provider is unavailable / empty. */
    fun latestMotorPowerW(context: Context): Float? = queryLatest(context, Series.E_POWER)

    /** Latest motor torque in Nm, or null. */
    fun latestMotorTorqueNm(context: Context): Float? = queryLatest(context, Series.E_TORQUE)

    /** Latest battery level reading, or null (unit per GM HAL). */
    fun latestBatteryLevel(context: Context): Float? = queryLatest(context, Series.E_BATTERY)

    /**
     * Latest sample timestamp (epoch-ms) for the series. The HistoryTimestamp
     * table only stores the most recent timestamp per type, so this is the
     * time of the LATEST insert — not per-sample.
     */
    fun latestTimestampMs(context: Context, series: Series): Long? {
        if (disabled) return null
        val uri = Uri.parse("content://$AUTHORITY/${series.path}")
        return try {
            context.contentResolver.query(uri, null, SELECTION_TIMESTAMP, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        } catch (_: Throwable) { null }
    }

    /**
     * Returns the most recent sample value for `series`. Cursor schema is
     * `value` column ordered by insert (oldest first). We jump to last row.
     */
    private fun queryLatest(context: Context, series: Series): Float? {
        if (disabled) return null
        val uri = Uri.parse("content://$AUTHORITY/${series.path}")
        return try {
            context.contentResolver.query(uri, null, SELECTION_DATA, null, null)?.use { c ->
                if (c.count == 0) return@use null
                c.moveToLast()
                val idx = c.getColumnIndex("value").let { if (it >= 0) it else 0 }
                c.getFloat(idx)
            }
        } catch (_: SecurityException) {
            available = false
            null
        } catch (_: Throwable) {
            null
        }
    }
}
