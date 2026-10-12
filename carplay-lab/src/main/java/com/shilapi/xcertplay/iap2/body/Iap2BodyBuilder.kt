package com.shilapi.xcertplay.iap2.body

import com.shilapi.xcertplay.iap2.catalog.Iap2Endpoint
import com.shilapi.xcertplay.iap2.catalog.Iap2FieldSpec
import com.shilapi.xcertplay.iap2.catalog.Iap2WireType
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.iap2.wire.Iap2ProtocolException
import com.shilapi.xcertplay.iap2.wire.Iap2WireCodec

/**
 * Ordered, schema-aware builder for one CSM body.
 *
 * The builder preserves insertion order and repeated IDs. When [endpoint] carries a schema, known
 * IDs and wire types are validated at [build]; schema-less/opaque endpoints remain fully usable.
 */
class Iap2BodyBuilder private constructor(
    private val endpoint: Iap2Endpoint?,
    private val explicitMessageId: Int?,
    private val fields: List<Iap2FieldSpec>,
    private val validateSchema: Boolean,
) {
    private data class Entry(val parameter: Iap2Parameter, val wireType: Iap2WireType)

    private val entries = ArrayList<Entry>()

    fun void(id: Int): Iap2BodyBuilder = add(id, Iap2WireCodec.void(), Iap2WireType.VOID)

    fun u8(id: Int, value: Int): Iap2BodyBuilder =
        add(id, Iap2WireCodec.u8(value), Iap2WireType.U8)

    fun bool(id: Int, value: Boolean): Iap2BodyBuilder =
        add(id, Iap2WireCodec.bool(value), Iap2WireType.U8)

    fun i8(id: Int, value: Int): Iap2BodyBuilder =
        add(id, Iap2WireCodec.i8(value), Iap2WireType.I8)

    fun u16(id: Int, value: Int): Iap2BodyBuilder =
        add(id, Iap2WireCodec.u16(value), Iap2WireType.U16)

    fun i16(id: Int, value: Int): Iap2BodyBuilder =
        add(id, Iap2WireCodec.i16(value), Iap2WireType.I16)

    fun u32(id: Int, value: Int): Iap2BodyBuilder = u32(id, value.toLong())

    fun u32(id: Int, value: Long): Iap2BodyBuilder =
        add(id, Iap2WireCodec.u32(value), Iap2WireType.U32)

    fun i32(id: Int, value: Int): Iap2BodyBuilder =
        add(id, Iap2WireCodec.i32(value), Iap2WireType.I32)

    fun u64(id: Int, value: Long): Iap2BodyBuilder =
        add(id, Iap2WireCodec.u64(value), Iap2WireType.U64)

    fun bytes(id: Int, value: ByteArray): Iap2BodyBuilder =
        add(id, Iap2WireCodec.bytes(value), Iap2WireType.BYTES)

    fun string(id: Int, value: String): Iap2BodyBuilder =
        add(id, Iap2WireCodec.string(value), Iap2WireType.STRING)

    fun strings(id: Int, values: Iterable<String>): Iap2BodyBuilder =
        add(id, Iap2WireCodec.strings(values), Iap2WireType.STRING_LIST)

    fun u16List(id: Int, values: Iterable<Int>): Iap2BodyBuilder =
        add(id, Iap2WireCodec.u16List(values), Iap2WireType.U16_LIST)

    fun raw(id: Int, payload: ByteArray): Iap2BodyBuilder =
        add(id, payload.copyOf(), Iap2WireType.OPAQUE)

    fun group(id: Int, block: Iap2BodyBuilder.() -> Unit): Iap2BodyBuilder {
        val child = Iap2BodyBuilder(
            endpoint = null,
            explicitMessageId = null,
            fields = schemaField(id)?.children.orEmpty(),
            validateSchema = validateSchema,
        ).apply(block)
        val encoded = child.encodeValidated()
        add(id, encoded, Iap2WireType.GROUP)
        return this
    }

    fun optionalVoid(id: Int, present: Boolean): Iap2BodyBuilder =
        if (present) void(id) else this

    fun optionalU8(id: Int, value: Int?): Iap2BodyBuilder =
        if (value == null) this else u8(id, value)

    fun optionalI8(id: Int, value: Int?): Iap2BodyBuilder =
        if (value == null) this else i8(id, value)

    fun optionalU16(id: Int, value: Int?): Iap2BodyBuilder =
        if (value == null) this else u16(id, value)

    fun optionalI16(id: Int, value: Int?): Iap2BodyBuilder =
        if (value == null) this else i16(id, value)

    fun optionalU32(id: Int, value: Long?): Iap2BodyBuilder =
        if (value == null) this else u32(id, value)

    fun optionalI32(id: Int, value: Int?): Iap2BodyBuilder =
        if (value == null) this else i32(id, value)

    fun optionalU64(id: Int, value: Long?): Iap2BodyBuilder =
        if (value == null) this else u64(id, value)

    fun optionalBytes(id: Int, value: ByteArray?): Iap2BodyBuilder =
        if (value == null) this else bytes(id, value)

    fun optionalString(id: Int, value: String?): Iap2BodyBuilder =
        if (value == null) this else string(id, value)

    fun build(): Iap2Frame {
        val target = endpoint?.id ?: explicitMessageId
            ?: throw IllegalStateException("A raw body builder needs an explicit message id")
        return build(target)
    }

    fun build(messageId: Int): Iap2Frame {
        return Iap2Frame(messageId, encodeValidated())
    }

    private fun encodeValidated(): ByteArray {
        if (validateSchema && fields.isNotEmpty()) validate(fields)
        return Iap2ParameterList.of(entries.map { it.parameter }).encode()
    }

    private fun add(id: Int, payload: ByteArray, wireType: Iap2WireType): Iap2BodyBuilder {
        require(id in 0..0xffff) { "CSM parameter id must fit in u16" }
        entries += Entry(Iap2Parameter(id, payload), wireType)
        return this
    }

    private fun schemaField(id: Int): Iap2FieldSpec? = fields.firstOrNull { it.id == id }

    private fun validate(specs: List<Iap2FieldSpec>) {
        val byId = specs.associateBy(Iap2FieldSpec::id)
        for (entry in entries) {
            val spec = byId[entry.parameter.id]
                ?: throw Iap2ProtocolException(
                    "Unknown body parameter 0x${entry.parameter.id.toString(16).padStart(4, '0')}",
                )
            if (!wireTypeMatches(spec.type, entry.wireType)) {
                throw Iap2ProtocolException(
                    "Body parameter 0x${spec.id.toString(16).padStart(4, '0')} expected ${spec.type}, " +
                        "got ${entry.wireType}",
                )
            }
            if (!spec.repeatable && entries.count { it.parameter.id == spec.id } > 1) {
                throw Iap2ProtocolException(
                    "Body parameter 0x${spec.id.toString(16).padStart(4, '0')} is not repeatable",
                )
            }
        }
        for (spec in specs.filter { it.required }) {
            if (entries.none { it.parameter.id == spec.id }) {
                throw Iap2ProtocolException(
                    "Missing required body parameter 0x${spec.id.toString(16).padStart(4, '0')} (${spec.name})",
                )
            }
        }
    }

    private fun wireTypeMatches(expected: Iap2WireType, actual: Iap2WireType): Boolean =
        expected == Iap2WireType.OPAQUE || expected == actual

    companion object {
        fun of(endpoint: Iap2Endpoint): Iap2BodyBuilder =
            Iap2BodyBuilder(endpoint, null, endpoint.fields, validateSchema = true)

        fun opaque(messageId: Int): Iap2BodyBuilder =
            Iap2BodyBuilder(null, messageId, emptyList(), validateSchema = false)
    }
}
