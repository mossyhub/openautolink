package com.openautolink.carplaylab.intake

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.json.JSONObject

/** A typed projection of our own EV frames. No raw frame, location, or identity text is exported. */
object OalEvWireDiagnostics {
    enum class Kind { PREPARED_OFFER, WRITTEN_STATUS, WRITTEN_MODEL }

    class Receipt internal constructor(val kind: Kind, lines: List<String>) {
        val lines: List<String> = java.util.Collections.unmodifiableList(lines.toList())
    }

    private val scalars = mapOf(3 to 2, 4 to 2, 11 to 2, 15 to 1, 21 to 4, 22 to 4,
        23 to 4, 24 to 4, 25 to 1, 30 to 2)
    private val offerGroups = setOf(3, 4, 11, 15, 21, 30)
    private val connectorIds = setOf(0L, 1L, 2L, 4L, 5L, 6L)

    fun preparedOffer(frame: Iap2Frame): Receipt? {
        if (frame.messageId != 0x1D01) return null
        val body = Iap2BodyReader.of(frame)
        val status = body.optionalGroup(21)?.list().orEmpty().map { it.id }.filter { it in offerGroups }.sorted()
        if (status.none { it != 4 }) return null
        val info = body.group(20)
        val ids = info.list().filter { it.id == 11 && it.payload.size == 1 }
            .map { u(it.payload) }.filter { it in connectorIds }.joinToString(",").ifEmpty { "none" }
        fun watts(id: Int) = info.list().firstOrNull { it.id == id && it.payload.size == 4 }
            ?.let { u(it.payload).toString() } ?: "none"
        return Receipt(Kind.PREPARED_OFFER, listOf(
            "carplay-ev-offer stage=prepared connectors=$ids dcW=${watts(12)} acW=${watts(14)} groups=${status.joinToString(",")}"))
    }

    internal fun status(body: Iap2BodyReader, send: Long): String? {
        val fields = body.list().filter { scalars[it.id] == it.payload.size }
        if (fields.none { it.id != 4 }) return null
        val values = fields.joinToString(",") { tlv ->
            val n = u(tlv.payload)
            val value = if (tlv.id == 4) n.toShort().toLong() else n
            "${tlv.id}:$value"
        }
        return "carplay-ev-wire send=$send status=$values"
    }

    // Deliberately format finite numeric members only, not arbitrary JSON or string values.
    internal fun model(body: Iap2BodyReader, send: Long, field: Int): List<String> {
        val charging = field == 26
        val id = if (charging) 12582922 else 12582921
        val json = JSONObject(body.string(field))
        if (json.getInt("model_id") != id) return emptyList()
        val p = json.getJSONObject("vehicle_parameters")
        fun vector(key: String, length: Int): String {
            val a = p.getJSONArray(key)
            require(a.length() == length)
            return (0 until length).joinToString(",") { number(a.get(it)) }
        }
        val fields = if (charging) listOf(
            "energy=" + vector("vehicle_energy_axis_wh", 11),
            "watts=" + vector("vehicle_charge_axis_w", 11),
            "steps=" + vector("energy_w_per_h", 21),
            "eff=" + number(p.get("efficiency_factor")),
        ) else listOf(
            "consumption=" + vector("vehicle_consumption_values_wh_per_m", 10),
            "aux=" + number(p.get("vehicle_auxiliary_power_w")),
            "gain=" + number(p.get("vehicle_altitude_gain_consumption_wh_per_m")),
            "loss=" + number(p.get("vehicle_altitude_loss_consumption_wh_per_m")),
        )
        return fields.map { "carplay-ev-wire send=$send model=$field id=$id $it" }
    }

    private fun u(bytes: ByteArray) = bytes.fold(0L) { n, b -> (n shl 8) or (b.toLong() and 255L) }
    private fun number(value: Any): String {
        require(value is Number && value.toDouble().isFinite())
        return value.toString()
    }
}
