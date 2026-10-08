// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa.phone

import java.io.ByteArrayOutputStream

/**
 * The protobuf wire format, as far as the phone role needs it: varints and length-delimited
 * fields, written and read by field number.
 *
 * Deliberately not the generated classes. Those mark fields `required` that real head units leave
 * out (`build()` threw on one on 2026-10-08), and they miss fields units depend on, like the
 * display channel a protocol-1.3 unit wants in a native-focus request. And deliberately not
 * protobuf-java's CodedInput/OutputStream either: a module borrows that library from the app, R8
 * keeps only what the app itself calls, and a method this file would be the only caller of could
 * be missing at load. A page of code with no dependency avoids both.
 */
internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): ProtoWriter {
        tag(field, WIRE_VARINT)
        rawVarint(value)
        return this
    }

    fun varint(field: Int, value: Int): ProtoWriter = varint(field, value.toLong())

    fun bool(field: Int, value: Boolean): ProtoWriter = varint(field, if (value) 1L else 0L)

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, WIRE_LENGTH)
        rawVarint(value.size.toLong())
        out.write(value, 0, value.size)
        return this
    }

    fun message(field: Int, value: ProtoWriter): ProtoWriter = bytes(field, value.toByteArray())

    /** A packed repeated varint field: one tag, one length, the values back to back. */
    fun packedVarints(field: Int, values: IntArray): ProtoWriter {
        val body = ProtoWriter()
        for (v in values) body.rawVarint(v.toLong())
        return bytes(field, body.toByteArray())
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wire: Int) = rawVarint(((field shl 3) or wire).toLong())

    private fun rawVarint(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH = 2
        const val WIRE_FIXED32 = 5
    }
}

/**
 * One parsed message: every field by number, in the order it came. Repeated fields keep every
 * occurrence; [int] and friends return the last, as protobuf does for a scalar seen twice.
 */
internal class ProtoMessage private constructor(private val fields: List<Field>) {

    class Field(val number: Int, val wire: Int, val varint: Long, val bytes: ByteArray?)

    fun has(number: Int): Boolean = fields.any { it.number == number }

    fun long(number: Int, default: Long = 0L): Long =
        fields.lastOrNull { it.number == number && it.wire == ProtoWriter.WIRE_VARINT }?.varint ?: default

    fun int(number: Int, default: Int = 0): Int = long(number, default.toLong()).toInt()

    fun bytes(number: Int): ByteArray? =
        fields.lastOrNull { it.number == number && it.wire == ProtoWriter.WIRE_LENGTH }?.bytes

    fun string(number: Int): String? = bytes(number)?.let { String(it, java.nio.charset.StandardCharsets.UTF_8) }

    fun message(number: Int): ProtoMessage? = bytes(number)?.let { parseOrNull(it) }

    fun messages(number: Int): List<ProtoMessage> =
        fields.filter { it.number == number && it.wire == ProtoWriter.WIRE_LENGTH }
            .mapNotNull { f -> f.bytes?.let { parseOrNull(it) } }

    /**
     * A repeated varint field, whichever way the sender encoded it: packed (one length-delimited
     * blob) or one tag per value. Head units do both for the same field.
     */
    fun varints(number: Int): List<Long> {
        val result = ArrayList<Long>()
        for (f in fields) {
            if (f.number != number) continue
            when (f.wire) {
                ProtoWriter.WIRE_VARINT -> result.add(f.varint)
                ProtoWriter.WIRE_LENGTH -> f.bytes?.let { blob ->
                    val r = Reader(blob, 0, blob.size)
                    while (r.hasMore()) result.add(r.varint())
                }
            }
        }
        return result
    }

    companion object {
        fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ProtoMessage {
            val r = Reader(data, offset, offset + length)
            val list = ArrayList<Field>()
            while (r.hasMore()) {
                val key = r.varint()
                val number = (key ushr 3).toInt()
                val wire = (key and 7).toInt()
                when (wire) {
                    ProtoWriter.WIRE_VARINT -> list.add(Field(number, wire, r.varint(), null))
                    ProtoWriter.WIRE_LENGTH -> {
                        val len = r.varint().toInt()
                        list.add(Field(number, wire, 0L, r.take(len)))
                    }
                    ProtoWriter.WIRE_FIXED64 -> { r.skip(8); list.add(Field(number, wire, 0L, null)) }
                    ProtoWriter.WIRE_FIXED32 -> { r.skip(4); list.add(Field(number, wire, 0L, null)) }
                    else -> throw IllegalArgumentException("unsupported wire type $wire for field $number")
                }
            }
            return ProtoMessage(list)
        }

        fun parseOrNull(data: ByteArray): ProtoMessage? = try {
            parse(data)
        } catch (e: Exception) {
            null
        }
    }

    private class Reader(private val data: ByteArray, private var pos: Int, private val end: Int) {
        fun hasMore(): Boolean = pos < end

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(pos < end) { "truncated varint" }
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                require(shift < 64) { "varint too long" }
            }
        }

        fun take(len: Int): ByteArray {
            require(len >= 0 && pos + len <= end) { "truncated field of $len bytes" }
            val out = data.copyOfRange(pos, pos + len)
            pos += len
            return out
        }

        fun skip(len: Int) {
            require(pos + len <= end) { "truncated fixed field" }
            pos += len
        }
    }
}
