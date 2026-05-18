package com.openautolink.app.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Enumerate every VHAL property the calling UID can see via CarPropertyManager.
 *
 * On AAOS, `CarPropertyManager.getPropertyList()` returns one
 * `CarPropertyConfig` per property the caller has read access to. The
 * config tells us:
 *   - Property ID
 *   - Access (READ / WRITE / READ_WRITE)
 *   - Change mode (STATIC / ON_CHANGE / CONTINUOUS)
 *   - Area type (GLOBAL / WINDOW / SEAT / etc.)
 *   - Value type (BOOLEAN / INT / FLOAT / STRING / ...)
 *
 * This answers definitively "what can my app actually do on this car?"
 * without speculating about permission levels.
 *
 * All access is via reflection so the app does not need to link against
 * android.car at build time.
 */
object CarPropertyEnumerator {

    data class PropertyEntry(
        val propertyId: Int,
        val name: String,
        val access: String,      // READ / WRITE / READ_WRITE
        val changeMode: String,  // STATIC / ON_CHANGE / CONTINUOUS
        val areaType: String,    // GLOBAL / WINDOW / SEAT / DOOR / MIRROR / WHEEL / VENDOR
        val valueType: String,   // BOOLEAN / INTEGER / FLOAT / STRING / mixed / ...
        val isVendor: Boolean,
    )

    data class Result(
        val featureAutomotive: Boolean,
        val carApiPresent: Boolean,
        val carConnected: Boolean,
        val totalCount: Int,
        val readableCount: Int,
        val writableCount: Int,
        val vendorCount: Int,
        val entries: List<PropertyEntry>,
        val errorOrNull: String?,
    )

    suspend fun enumerate(context: Context): Result = withContext(Dispatchers.IO) {
        val featureAutomotive = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        if (!featureAutomotive) {
            return@withContext Result(false, false, false, 0, 0, 0, 0, emptyList(),
                "FEATURE_AUTOMOTIVE not available")
        }

        try {
            val carClass = Class.forName("android.car.Car")
            val createCar = carClass.getMethod("createCar", Context::class.java)
            val car = createCar.invoke(null, context)
                ?: return@withContext Result(true, true, false, 0, 0, 0, 0, emptyList(),
                    "Car.createCar returned null")

            // Connect (best-effort, 2s)
            try { carClass.getMethod("connect").invoke(car) } catch (_: Exception) { /* may already be auto */ }
            var connected = false
            repeat(20) {
                try {
                    if (carClass.getMethod("isConnected").invoke(car) as? Boolean == true) {
                        connected = true; return@repeat
                    }
                } catch (_: Exception) { /* ignore */ }
                Thread.sleep(100)
            }

            val propertyService = carClass.getField("PROPERTY_SERVICE").get(null) as String
            val pm = carClass.getMethod("getCarManager", String::class.java)
                .invoke(car, propertyService)
                ?: return@withContext Result(true, true, connected, 0, 0, 0, 0, emptyList(),
                    "CarPropertyManager is null (no read perms granted?)")

            val list = pm.javaClass.getMethod("getPropertyList").invoke(pm) as? List<*>
                ?: return@withContext Result(true, true, connected, 0, 0, 0, 0, emptyList(),
                    "getPropertyList returned null")

            val entries = list.mapNotNull { cfg -> decodeConfig(cfg) }
                .sortedBy { it.propertyId }

            val readable = entries.count { it.access != "WRITE" }
            val writable = entries.count { it.access == "WRITE" || it.access == "READ_WRITE" }
            val vendorCount = entries.count { it.isVendor }
            Result(true, true, connected, entries.size, readable, writable, vendorCount, entries, null)
        } catch (e: Exception) {
            Result(true, false, false, 0, 0, 0, 0, emptyList(),
                "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun decodeConfig(cfg: Any?): PropertyEntry? {
        if (cfg == null) return null
        return try {
            val cls = cfg.javaClass
            val propertyId = cls.getMethod("getPropertyId").invoke(cfg) as Int

            // Access: READ=1 / WRITE=2 / READ_WRITE=3 — try int constants
            val accessInt = try { (cls.getMethod("getAccess").invoke(cfg) as Int) } catch (_: Exception) { -1 }
            val access = when (accessInt) {
                1 -> "READ"
                2 -> "WRITE"
                3 -> "READ_WRITE"
                else -> "?"
            }

            val changeModeInt = try { (cls.getMethod("getChangeMode").invoke(cfg) as Int) } catch (_: Exception) { -1 }
            val changeMode = when (changeModeInt) {
                0 -> "STATIC"
                1 -> "ON_CHANGE"
                2 -> "CONTINUOUS"
                else -> "?"
            }

            // Property ID encoding (per android.car.VehiclePropertyType): high nibble of high byte
            // 0x00FF0000 mask = value type, 0xFF000000 = data type, 0x0F000000 = area type
            // Vendor flag: bit 16 of the high-byte category (0x20000000)
            val valueTypeNibble = (propertyId.toLong() and 0x00FF0000L).toInt() ushr 16
            val valueType = when (valueTypeNibble) {
                0x10 -> "STRING"
                0x20 -> "BOOLEAN"
                0x30 -> "INT32"
                0x31 -> "INT32_VEC"
                0x40 -> "INT64"
                0x41 -> "INT64_VEC"
                0x50 -> "FLOAT"
                0x51 -> "FLOAT_VEC"
                0x60 -> "BYTES"
                0xE0 -> "MIXED"
                else -> "type=0x${valueTypeNibble.toString(16)}"
            }

            val areaTypeNibble = (propertyId.toLong() and 0x0F000000L).toInt() ushr 24
            val areaType = when (areaTypeNibble) {
                0 -> "GLOBAL"
                1 -> "WINDOW"
                2 -> "MIRROR"
                3 -> "SEAT"
                4 -> "DOOR"
                5 -> "WHEEL"
                else -> "area=0x${areaTypeNibble.toString(16)}"
            }

            // Vendor properties have group nibble 0x20000000 set (Vehicle Property Group VENDOR)
            val groupNibble = (propertyId.toLong() and 0xF0000000L).toInt() ushr 28
            val isVendor = groupNibble == 0x2

            // Name: try to reflect VehiclePropertyIds.toString(int)
            val name = try {
                val vpi = Class.forName("android.car.VehiclePropertyIds")
                vpi.getMethod("toString", Int::class.javaPrimitiveType).invoke(null, propertyId) as? String
                    ?: "0x${propertyId.toString(16)}"
            } catch (_: Exception) { "0x${propertyId.toString(16)}" }

            PropertyEntry(propertyId, name, access, changeMode, areaType, valueType, isVendor)
        } catch (_: Exception) {
            null
        }
    }
}
