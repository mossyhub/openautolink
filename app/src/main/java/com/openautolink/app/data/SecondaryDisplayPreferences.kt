package com.openautolink.app.data

import com.openautolink.app.secondary.*
import kotlinx.serialization.json.*

/** Bounded, fail-closed durable intent. Does not discover, qualify or launch displays. */
internal object SecondaryDisplayPreferences {
    const val MAX_OUTPUTS = 64
    const val MAX_BYTES = 65536
    private val integer = Regex("0|[1-9][0-9]*")
    private val json = Json { isLenient = false; ignoreUnknownKeys = false }

    /** Raw DataStore value: absence is distinct from a present record with the wrong type. */
    fun read(raw: Any?, legacy: ClusterOutputPreferences): SecondaryPreferencesSnapshot {
        if (raw == null) return SecondaryPreferencesSnapshot(0, emptyMap(), legacy)
        if (raw !is String) return malformed(legacy)
        return try {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            checkStructure(raw)
            val root = json.parseToJsonElement(raw).obj(setOf("version", "revision", "outputs"))
            require(root.getValue("version").number() == 1L) { "Unsupported secondary display preferences version" }
            val revision = root.getValue("revision").number()
            val outputs = root.getValue("outputs") as? JsonObject ?: error("outputs must be an object")
            require(outputs.size <= MAX_OUTPUTS)
            val decoded = outputs.map { (key, value) ->
                val id = SecondaryOutputId(key)
                val fields = value.obj(setOf("enabled", "aaRole", "androidAuto", "carPlay"), setOf("layout", "aaDensityDpi"))
                val roleValue = fields.getValue("aaRole")
                val role = if (roleValue == JsonNull) null else AaSecondaryRole.valueOf(roleValue.text())
                val layout = fields["layout"]?.takeUnless { it == JsonNull }?.let(::readLayout)
                val density = fields["aaDensityDpi"]?.takeUnless { it == JsonNull }?.number()?.also {
                    require(it in 80L..320L)
                }?.toInt()
                require(layout == null || layout.scope.outputId == id)
                id to SecondaryOutputPreference(fields.getValue("enabled").bool(), role,
                    fields.getValue("androidAuto").bool(), fields.getValue("carPlay").bool(), layout, density)
            }.toMap()
            SecondaryPreferencesSnapshot(revision, decoded, legacy)
        } catch (_: IllegalArgumentException) {
            malformed(legacy)
        } catch (_: IllegalStateException) {
            malformed(legacy)
        }
    }

    private fun malformed(legacy: ClusterOutputPreferences) = SecondaryPreferencesSnapshot(
        0, emptyMap(), legacy, "Secondary display preferences are malformed or unsupported; stored record retained")

    fun encode(snapshot: SecondaryPreferencesSnapshot): String {
        require(snapshot.error == null)
        require(snapshot.outputs.size <= MAX_OUTPUTS) { "At most 64 secondary identities may be stored" }
        val record = buildJsonObject {
            put("version", 1); put("revision", snapshot.revision)
            putJsonObject("outputs") {
                snapshot.outputs.forEach { (id, value) ->
                    require(value.layout == null || value.layout.scope.outputId == id) { "Layout scope must match output identity" }
                    putJsonObject(id.value) {
                        put("enabled", value.enabled)
                        put("aaRole", value.aaRole?.let { JsonPrimitive(it.name) } ?: JsonNull)
                        put("androidAuto", value.androidAuto); put("carPlay", value.carPlay)
                        put("layout", value.layout?.let(::writeLayout) ?: JsonNull)
                        value.aaDensityDpi?.let { put("aaDensityDpi", it) }
                    }
                }
            }
        }.toString()
        require(record.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Secondary display record exceeds 64 KiB" }
        return record
    }

    private fun readLayout(value: JsonElement): SecondaryLayoutPreference {
        val fields = value.obj(setOf("version", "scope", "output", "safe"), setOf("directSafe", "directSafeAutomatic", "aaDirectSafe"))
        require(fields.getValue("version").number() == 1L)
        val scope = fields.getValue("scope").obj(setOf("outputId", "vehicle", "buildFingerprint", "panelWidth", "panelHeight", "densityDpi"))
        fun dimension(key: String) = scope.getValue(key).number().also { require(it in 1..Int.MAX_VALUE.toLong()) }.toInt()
        return SecondaryLayoutPreference(SecondaryLayoutScope(SecondaryOutputId(scope.getValue("outputId").text()),
            scope.getValue("vehicle").text(), scope.getValue("buildFingerprint").text(),
            dimension("panelWidth"), dimension("panelHeight"), dimension("densityDpi")),
            readBox(fields.getValue("output")), readBox(fields.getValue("safe")),
            fields["directSafe"]?.takeUnless { it == JsonNull }?.let(::readBox), fields["directSafeAutomatic"]?.bool() ?: false,
            fields["aaDirectSafe"]?.takeUnless { it == JsonNull }?.let(::readBox))
    }
    private fun readBox(value: JsonElement): SecondaryLayoutBox {
        val fields = value.obj(setOf("left", "top", "right", "bottom"))
        fun coordinate(key: String): Double {
            val p = fields.getValue(key) as? JsonPrimitive ?: error("Expected number")
            require(!p.isString && p != JsonNull)
            return p.content.toDouble()
        }
        return SecondaryLayoutBox(coordinate("left"), coordinate("top"), coordinate("right"), coordinate("bottom"))
    }
    private fun writeLayout(layout: SecondaryLayoutPreference) = buildJsonObject {
        put("version", 1)
        putJsonObject("scope") {
            val s = layout.scope
            put("outputId", s.outputId.value); put("vehicle", s.vehicle); put("buildFingerprint", s.buildFingerprint)
            put("panelWidth", s.panelWidth); put("panelHeight", s.panelHeight); put("densityDpi", s.densityDpi)
        }
        put("output", writeBox(layout.output)); put("safe", writeBox(layout.safe))
        layout.aaDirectSafe?.let { put("aaDirectSafe", writeBox(it)) }
        if (layout.directSafe != null || layout.directSafeAutomatic) {
            put("directSafe", layout.directSafe?.let(::writeBox) ?: JsonNull)
            put("directSafeAutomatic", layout.directSafeAutomatic)
        }
    }
    private fun writeBox(box: SecondaryLayoutBox) = buildJsonObject {
        put("left", box.left); put("top", box.top); put("right", box.right); put("bottom", box.bottom)
    }
    private fun JsonElement.obj(required: Set<String>, optional: Set<String> = emptySet()): JsonObject {
        val fields = this as? JsonObject ?: error("Expected object")
        require(fields.keys.containsAll(required) && fields.keys.all { it in required || it in optional })
        return fields
    }
    private fun JsonElement.text(): String {
        val p = this as? JsonPrimitive ?: error("Expected string")
        require(p.isString)
        return p.content
    }
    private fun JsonElement.bool(): Boolean {
        val p = this as? JsonPrimitive ?: error("Expected boolean")
        require(!p.isString && p.content in setOf("true", "false"))
        return p.content == "true"
    }
    private fun JsonElement.number(): Long {
        val p = this as? JsonPrimitive ?: error("Expected integer")
        require(!p.isString && integer.matches(p.content))
        return p.content.toLong()
    }

    /** Reject duplicate keys (including escaped aliases) and nesting before JSON materialization. */
    private fun checkStructure(raw: String) {
        var index = 0
        val objects = ArrayList<MutableSet<String>?>()
        while (index < raw.length) {
            when (raw[index]) {
                '{' -> { require(objects.size < 16); objects.add(HashSet()); index++ }
                '[' -> { require(objects.size < 16); objects.add(null); index++ }
                '}', ']' -> { require(objects.isNotEmpty()); objects.removeAt(objects.lastIndex); index++ }
                '"' -> {
                    val start = index++
                    var closed = false
                    while (index < raw.length) {
                        val c = raw[index++]
                        if (c == '\\') { require(index < raw.length); index++ }
                        else if (c == '"') { closed = true; break }
                    }
                    require(closed)
                    var next = index
                    while (next < raw.length && raw[next].isWhitespace()) next++
                    if (next < raw.length && raw[next] == ':') {
                        val keys = objects.lastOrNull() ?: error("Key outside object")
                        require(keys.add(json.parseToJsonElement(raw.substring(start, index)).jsonPrimitive.content))
                    }
                }
                else -> index++
            }
        }
        require(objects.isEmpty())
    }
}
