package com.shilapi.xcertplay.iap2.body

import com.shilapi.xcertplay.iap2.catalog.Iap2Endpoint
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.iap2.wire.Iap2ProtocolException
import com.shilapi.xcertplay.iap2.wire.Iap2WireCodec

/**
 * Typed reader over an ordered CSM body.
 *
 * The reader does not discard unknown or repeated parameters. Callers can use the scalar helpers
 * for known fields and [raw] or [all] for forward-compatible values.
 */
class Iap2BodyReader private constructor(
    private val parameters: Iap2ParameterList,
) {
    val size: Int get() = parameters.size

    fun isEmpty(): Boolean = parameters.isEmpty()

    fun has(id: Int): Boolean = parameters.has(id)

    fun first(id: Int): Iap2Parameter? = parameters.first(id)

    fun require(id: Int, description: String = defaultDescription(id)): Iap2Parameter =
        parameters.require(id, description)

    fun all(id: Int): List<Iap2Parameter> = parameters.all(id)

    fun raw(id: Int): ByteArray = require(id).payload

    fun optionalRaw(id: Int): ByteArray? = first(id)?.payload

    fun void(id: Int): Boolean {
        val parameter = require(id)
        requireEmpty(parameter, id)
        return true
    }

    fun optionalVoid(id: Int): Boolean = first(id)?.let {
        requireEmpty(it, id)
        true
    } ?: false

    fun u8(id: Int): Int = Iap2WireCodec.readU8(scalar(id, 1))

    fun optionalU8(id: Int): Int? = first(id)?.let {
        requireSize(it, id, 1)
        Iap2WireCodec.readU8(it.payload)
    }

    fun i8(id: Int): Int = Iap2WireCodec.readI8(scalar(id, 1))

    fun optionalI8(id: Int): Int? = first(id)?.let {
        requireSize(it, id, 1)
        Iap2WireCodec.readI8(it.payload)
    }

    fun bool(id: Int): Boolean = u8(id) != 0

    fun optionalBool(id: Int): Boolean? = optionalU8(id)?.let { it != 0 }

    fun u16(id: Int): Int = Iap2WireCodec.readU16(scalar(id, 2))

    fun optionalU16(id: Int): Int? = first(id)?.let {
        requireSize(it, id, 2)
        Iap2WireCodec.readU16(it.payload)
    }

    fun i16(id: Int): Int = Iap2WireCodec.readI16(scalar(id, 2))

    fun optionalI16(id: Int): Int? = first(id)?.let {
        requireSize(it, id, 2)
        Iap2WireCodec.readI16(it.payload)
    }

    fun u32(id: Int): Long = Iap2WireCodec.readU32(scalar(id, 4))

    fun optionalU32(id: Int): Long? = first(id)?.let {
        requireSize(it, id, 4)
        Iap2WireCodec.readU32(it.payload)
    }

    fun i32(id: Int): Int = Iap2WireCodec.readI32(scalar(id, 4))

    fun optionalI32(id: Int): Int? = first(id)?.let {
        requireSize(it, id, 4)
        Iap2WireCodec.readI32(it.payload)
    }

    fun u64(id: Int): Long = Iap2WireCodec.readU64(scalar(id, 8))

    fun optionalU64(id: Int): Long? = first(id)?.let {
        requireSize(it, id, 8)
        Iap2WireCodec.readU64(it.payload)
    }

    fun bytes(id: Int): ByteArray = raw(id)

    fun optionalBytes(id: Int): ByteArray? = optionalRaw(id)

    fun string(id: Int): String = try {
        Iap2WireCodec.readString(raw(id))
    } catch (failure: Iap2ProtocolException) {
        throw malformed(id, failure)
    }

    fun optionalString(id: Int): String? = first(id)?.let {
        try {
            Iap2WireCodec.readString(it.payload)
        } catch (failure: Iap2ProtocolException) {
            throw malformed(id, failure)
        }
    }

    fun strings(id: Int): List<String> = try {
        Iap2WireCodec.readStrings(raw(id))
    } catch (failure: Iap2ProtocolException) {
        throw malformed(id, failure)
    }

    fun optionalStrings(id: Int): List<String>? = first(id)?.let {
        try {
            Iap2WireCodec.readStrings(it.payload)
        } catch (failure: Iap2ProtocolException) {
            throw malformed(id, failure)
        }
    }

    fun u16List(id: Int): List<Int> = try {
        Iap2WireCodec.readU16List(raw(id))
    } catch (failure: Iap2ProtocolException) {
        throw malformed(id, failure)
    }

    fun optionalU16List(id: Int): List<Int>? = first(id)?.let {
        try {
            Iap2WireCodec.readU16List(it.payload)
        } catch (failure: Iap2ProtocolException) {
            throw malformed(id, failure)
        }
    }

    fun group(id: Int): Iap2BodyReader =
        Iap2BodyReader(Iap2ParameterList.parse(raw(id)))

    fun optionalGroup(id: Int): Iap2BodyReader? = optionalRaw(id)?.let {
        Iap2BodyReader(Iap2ParameterList.parse(it))
    }

    fun list(): List<Iap2Parameter> = parameters.asList()

    private fun scalar(id: Int, expectedBytes: Int): ByteArray {
        val parameter = require(id)
        requireSize(parameter, id, expectedBytes)
        return parameter.payload
    }

    private fun requireSize(parameter: Iap2Parameter, id: Int, expectedBytes: Int) {
        if (parameter.payload.size != expectedBytes) {
            throw Iap2ProtocolException(
                "Parameter 0x${hex(id)} expected $expectedBytes bytes, got ${parameter.payload.size}",
            )
        }
    }

    private fun requireEmpty(parameter: Iap2Parameter, id: Int) {
        if (parameter.payload.isNotEmpty()) {
            throw Iap2ProtocolException("Parameter 0x${hex(id)} must be void")
        }
    }

    private fun malformed(id: Int, failure: Iap2ProtocolException): Iap2ProtocolException =
        Iap2ProtocolException("Malformed parameter 0x${hex(id)}", failure)

    private fun defaultDescription(id: Int): String = "parameter 0x${hex(id)}"

    private fun hex(id: Int): String = id.toString(16).padStart(4, '0')

    companion object {
        fun of(frame: Iap2Frame): Iap2BodyReader =
            Iap2BodyReader(Iap2ParameterList.parse(frame.payload))

        fun of(body: Iap2ParameterList): Iap2BodyReader = Iap2BodyReader(body)

        /** Validates the endpoint name without requiring a caller-side cast or map lookup. */
        fun of(endpoint: Iap2Endpoint, frame: Iap2Frame): Iap2BodyReader {
            require(frame.messageId == endpoint.id) {
                "Frame 0x${frame.messageId.toString(16)} is not ${endpoint.name}"
            }
            return of(frame)
        }
    }
}
