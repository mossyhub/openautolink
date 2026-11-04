package com.openautolink.app.data

import androidx.datastore.preferences.core.stringPreferencesKey
import com.openautolink.app.cluster.*
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Stable vehicle/profile namespace. Physical Android display IDs are deliberately absent. */
data class ClusterLayoutScope(
    val vehicle: ClusterVehicleIdentity,
    val profileId: String,
    val role: ClusterVehicleRole,
    val panel: PixelSize,
) {
    init {
        require(role == ClusterVehicleRole.DRIVER_CLUSTER)
        require(listOf(vehicle.product, vehicle.buildFamily, profileId).all { it.isNotBlank() && it.length <= 512 })
        require(vehicle.sdk in 1..1000 && vehicle.buildRevision >= 0)
    }
    internal fun json(): JsonArray = buildJsonArray {
        add(vehicle.product); add(vehicle.sdk); add(vehicle.buildFamily); add(vehicle.buildRevision)
        add(profileId); add(role.name); add(panel.width); add(panel.height)
    }
    /** Hash of a typed canonical JSON tuple, not delimiter-joined user strings. */
    val preferenceKey get() = stringPreferencesKey("cluster_layout_v1_" +
        MessageDigest.getInstance("SHA-256").digest(json().toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) })
}

/** Validated immutable editor intent only. Requires fresh host qualification before runtime use. */
data class SavedClusterLayout(val scope: ClusterLayoutScope, val canvas: PixelRect, val output: PixelRect, val safe: PixelRect) {
    init { require(scope.panel.bounds.contains(canvas) && canvas.contains(output) && output.contains(safe)) }
    fun encode(): String = buildJsonObject {
        put("version", 1); put("scope", scope.json()); put("canvas", canvas.json())
        put("output", output.json()); put("safe", safe.json())
    }.toString()
}

private fun PixelRect.json() = buildJsonArray { add(left); add(top); add(right); add(bottom) }

enum class ClusterLayoutUnavailableReason { NO_CUSTOM_LAYOUT, INVALID_RECORD, SCOPE_MISMATCH, CANVAS_CHANGED }

/** Null custom means Automatic; it does not assert that a qualified Automatic profile is available. */
data class ClusterLayoutRead internal constructor(val custom: SavedClusterLayout?, val unavailableReason: ClusterLayoutUnavailableReason?)

internal object ClusterLayoutStorage {
    fun decode(raw: String?, scope: ClusterLayoutScope, canvas: PixelRect): ClusterLayoutRead {
        require(scope.panel.bounds.contains(canvas))
        if (raw == null) return ClusterLayoutRead(null, ClusterLayoutUnavailableReason.NO_CUSTOM_LAYOUT)
        if (raw.length > 16384) return ClusterLayoutRead(null, ClusterLayoutUnavailableReason.INVALID_RECORD)
        return try {
            val record = Json.parseToJsonElement(raw).jsonObject
            require(record.keys == setOf("version", "scope", "canvas", "output", "safe"))
            require(integer(record.getValue("version")) == 1)
            if (record["scope"] != scope.json()) return ClusterLayoutRead(null, ClusterLayoutUnavailableReason.SCOPE_MISMATCH)
            fun rect(name: String): PixelRect {
                val a = record.getValue(name).jsonArray
                require(a.size == 4)
                return PixelRect(integer(a[0]),integer(a[1]),integer(a[2]),integer(a[3]))
            }
            val saved = SavedClusterLayout(scope,rect("canvas"),rect("output"),rect("safe"))
            if (saved.canvas != canvas) ClusterLayoutRead(null,ClusterLayoutUnavailableReason.CANVAS_CHANGED)
            else ClusterLayoutRead(saved,null)
        } catch (_: IllegalArgumentException) {
            ClusterLayoutRead(null,ClusterLayoutUnavailableReason.INVALID_RECORD)
        } catch (_: IllegalStateException) {
            ClusterLayoutRead(null,ClusterLayoutUnavailableReason.INVALID_RECORD)
        }
    }
    private fun integer(element: JsonElement): Int {
        val p = element.jsonPrimitive
        require(!p.isString && Regex("0|[1-9][0-9]*").matches(p.content))
        return p.content.toInt()
    }
}
