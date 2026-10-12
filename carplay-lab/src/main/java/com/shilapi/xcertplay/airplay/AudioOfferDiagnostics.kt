// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

/** Numeric allowlisted receipt of the actual /info object; never serialize arbitrary plist fields. */
object AudioOfferDiagnostics {
    const val MAX_ROWS = 16
    private val audioTypes = setOf("compatibility", "default", "alert", "media", "telephony", "speechRecognition")
    private fun integer(value: Any?): Long? = when (value) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }?.takeIf { it >= 0 }

    fun lines(info: Map<String, Any?>): List<String> {
        val rows = info["audioFormats"] as? List<*> ?: return emptyList()
        // Bound examination before mapping; malformed rows do not grant an unbounded search budget.
        return rows.take(MAX_ROWS).mapIndexedNotNull { index, value ->
            val row = value as? Map<*, *> ?: return@mapIndexedNotNull null
            val type = integer(row["type"])?.takeIf { it in 0..255 } ?: return@mapIndexedNotNull null
            val kind = row["audioType"] as? String ?: return@mapIndexedNotNull null
            if (kind !in audioTypes) return@mapIndexedNotNull null
            val output = integer(row["audioOutputFormats"]) ?: return@mapIndexedNotNull null
            val input = if (!row.containsKey("audioInputFormats")) "absent"
                else integer(row["audioInputFormats"])?.toString() ?: return@mapIndexedNotNull null
            "BENCH_DIAG audioOfferRow index=$index type=$type audioType=$kind output=$output input=$input"
        }
    }
}
