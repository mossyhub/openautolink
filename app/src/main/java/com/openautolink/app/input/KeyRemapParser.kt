package com.openautolink.app.input

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Parses the persisted Android-keycode → Android Auto-keycode map. */
internal object KeyRemapParser {
    fun parse(serialized: String): Map<Int, Int> {
        if (serialized.isBlank()) return emptyMap()
        return runCatching {
            Json.parseToJsonElement(serialized).jsonObject.map { (hardwareKey, aaKey) ->
                hardwareKey.toInt() to aaKey.jsonPrimitive.int
            }.toMap()
        }.getOrDefault(emptyMap())
    }
}
