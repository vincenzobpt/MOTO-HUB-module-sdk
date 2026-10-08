// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa.phone

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProtoWireTest {

    private fun parse(w: ProtoWriter) = ProtoMessage.parse(w.toByteArray())

    private fun assertRejected(data: ByteArray) {
        try {
            ProtoMessage.parse(data)
            fail("expected the parse to throw")
        } catch (expected: IllegalArgumentException) {
            // the reader rejects with require(), nothing else
        }
        assertNull(ProtoMessage.parseOrNull(data))
    }

    @Test
    fun aSmallVarintIsOneTagByteAndOneValueByte() {
        assertArrayEquals(byteArrayOf(0x08, 0x7F), ProtoWriter().varint(1, 127).toByteArray())
    }

    @Test
    fun aMultiByteVarintCarriesSevenBitsPerByte() {
        assertArrayEquals(byteArrayOf(0x08, 0x80.toByte(), 0x01), ProtoWriter().varint(1, 128).toByteArray())
        assertArrayEquals(byteArrayOf(0x08, 0xAC.toByte(), 0x02), ProtoWriter().varint(1, 300).toByteArray())
    }

    @Test
    fun varintsRoundTripAcrossTheirWidths() {
        val values = listOf(0L, 1L, 127L, 128L, 16_383L, 16_384L, 1L shl 40, Long.MAX_VALUE)
        for (v in values) {
            assertEquals(v, parse(ProtoWriter().varint(3, v)).long(3))
        }
    }

    @Test
    fun aNegativeIntTakesTenVarintBytesAndComesBackNegative() {
        val data = ProtoWriter().varint(1, -1).toByteArray()
        assertEquals(1 + 10, data.size)

        val msg = ProtoMessage.parse(data)
        assertEquals(-1, msg.int(1))
        assertEquals(-1L, msg.long(1))
        assertEquals(-12345, parse(ProtoWriter().varint(1, -12345)).int(1))
    }

    @Test
    fun longMinValueSurvivesTheTenthByte() {
        assertEquals(Long.MIN_VALUE, parse(ProtoWriter().varint(1, Long.MIN_VALUE)).long(1))
    }

    @Test
    fun boolIsAVarintOfZeroOrOne() {
        val msg = parse(ProtoWriter().bool(1, true).bool(2, false))
        assertEquals(1, msg.int(1))
        assertEquals(0, msg.int(2))
        assertTrue(msg.has(2))
    }

    @Test
    fun aMissingFieldGivesTheDefault() {
        val msg = parse(ProtoWriter().varint(1, 7))
        assertFalse(msg.has(2))
        assertEquals(0, msg.int(2))
        assertEquals(42, msg.int(2, 42))
        assertEquals(42L, msg.long(2, 42L))
        assertNull(msg.bytes(2))
        assertNull(msg.string(2))
        assertNull(msg.message(2))
        assertTrue(msg.messages(2).isEmpty())
        assertTrue(msg.varints(2).isEmpty())
    }

    @Test
    fun bytesAndStringsRoundTrip() {
        val blob = byteArrayOf(0, 1, 2, 0xFF.toByte())
        val msg = parse(ProtoWriter().bytes(1, blob).bytes(2, "Mötorrad €".toByteArray(Charsets.UTF_8)))
        assertArrayEquals(blob, msg.bytes(1))
        assertEquals("Mötorrad €", msg.string(2))
    }

    @Test
    fun anEmptyLengthDelimitedFieldIsPresentButEmpty() {
        val msg = parse(ProtoWriter().bytes(1, ByteArray(0)))
        assertTrue(msg.has(1))
        assertEquals(0, msg.bytes(1)!!.size)
        assertEquals("", msg.string(1))
    }

    @Test
    fun aLongPayloadNeedsAMultiByteLength() {
        val blob = ByteArray(300) { it.toByte() }
        assertArrayEquals(blob, parse(ProtoWriter().bytes(1, blob)).bytes(1))
    }

    @Test
    fun nestedMessagesRoundTrip() {
        val inner = ProtoWriter().varint(1, 11).bytes(2, "inner".toByteArray())
        val outer = ProtoWriter().message(5, inner).varint(6, 99)

        val msg = parse(outer)
        val nested = msg.message(5)!!
        assertEquals(11, nested.int(1))
        assertEquals("inner", nested.string(2))
        assertEquals(99, msg.int(6))
    }

    @Test
    fun repeatedMessagesKeepTheirOrder() {
        val w = ProtoWriter()
        for (i in 1..3) w.message(1, ProtoWriter().varint(1, i * 10))
        assertEquals(listOf(10, 20, 30), parse(w).messages(1).map { it.int(1) })
    }

    @Test
    fun aMalformedNestedMessageIsLeftOutNotThrown() {
        // 0x08 starts a varint field and the blob ends there.
        val w = ProtoWriter()
            .bytes(1, byteArrayOf(0x08))
            .message(1, ProtoWriter().varint(1, 5))
        val msg = parse(w)
        assertNull(msg.message(2))
        assertEquals(listOf(5), msg.messages(1).map { it.int(1) })
        // last occurrence of the field is the well-formed one
        assertEquals(5, msg.message(1)!!.int(1))
    }

    @Test
    fun packedRepeatedVarintsAreRead() {
        val msg = parse(ProtoWriter().packedVarints(1, intArrayOf(1, 128, 300, 70_000)))
        assertEquals(listOf(1L, 128L, 300L, 70_000L), msg.varints(1))
    }

    @Test
    fun oneTagPerValueRepeatedVarintsAreRead() {
        val msg = parse(ProtoWriter().varint(1, 1).varint(1, 128).varint(1, 300))
        assertEquals(listOf(1L, 128L, 300L), msg.varints(1))
    }

    @Test
    fun packedAndUnpackedFormsOfOneFieldAddUp() {
        // Head units do both for the same field, even within one message.
        val w = ProtoWriter()
            .varint(1, 4)
            .packedVarints(1, intArrayOf(5, 6))
            .varint(1, 7)
            .varint(2, 99)
        assertEquals(listOf(4L, 5L, 6L, 7L), parse(w).varints(1))
    }

    @Test
    fun anEmptyPackedBlobGivesNoValues() {
        assertTrue(parse(ProtoWriter().packedVarints(1, IntArray(0))).varints(1).isEmpty())
    }

    @Test
    fun aNegativePackedValueReadsBackNegative() {
        val msg = parse(ProtoWriter().packedVarints(1, intArrayOf(-3, 3)))
        assertEquals(listOf(-3, 3), msg.varints(1).map { it.toInt() })
    }

    @Test
    fun aScalarSeenTwiceTakesTheLastValue() {
        val msg = parse(
            ProtoWriter()
                .varint(1, 5).varint(1, 9)
                .bytes(2, "first".toByteArray()).bytes(2, "second".toByteArray())
        )
        assertEquals(9, msg.int(1))
        assertEquals(9L, msg.long(1))
        assertEquals("second", msg.string(2))
    }

    @Test
    fun aFieldOfTheWrongWireTypeIsNotReadAsTheRightOne() {
        val msg = parse(ProtoWriter().bytes(1, "x".toByteArray()).varint(2, 3))
        assertEquals(0, msg.int(1))
        assertNull(msg.bytes(2))
        assertTrue(msg.has(1))
    }

    @Test
    fun fixed32AndFixed64FieldsAreSkippedWithoutLosingLaterFields() {
        val fixed32 = byteArrayOf(0x15, 1, 2, 3, 4) // field 2, wire 5
        val fixed64 = byteArrayOf(0x19, 1, 2, 3, 4, 5, 6, 7, 8) // field 3, wire 1
        val data = ProtoWriter().varint(1, 10).toByteArray() +
            fixed32 +
            fixed64 +
            ProtoWriter().varint(4, 20).bytes(5, "after".toByteArray()).toByteArray()

        val msg = ProtoMessage.parse(data)
        assertEquals(10, msg.int(1))
        assertEquals(20, msg.int(4))
        assertEquals("after", msg.string(5))
        // seen, but there is no value to read
        assertTrue(msg.has(2))
        assertTrue(msg.has(3))
        assertEquals(0, msg.int(2))
        assertEquals(0, msg.int(3))
    }

    @Test
    fun parseHonoursOffsetAndLength() {
        val inner = ProtoWriter().varint(1, 7).toByteArray()
        val padded = byteArrayOf(0x7F, 0x7F) + inner + byteArrayOf(0x7F)
        assertEquals(7, ProtoMessage.parse(padded, offset = 2, length = inner.size).int(1))
    }

    @Test
    fun anEmptyBodyIsAnEmptyMessage() {
        assertFalse(ProtoMessage.parse(ByteArray(0)).has(1))
    }

    @Test
    fun aLengthDelimitedFieldCutShortThrows() {
        // field 1, 5 bytes announced, 2 present
        assertRejected(byteArrayOf(0x0A, 0x05, 0x61, 0x62))
    }

    @Test
    fun aTagWithoutItsValueThrows() {
        assertRejected(byteArrayOf(0x08))
    }

    @Test
    fun aVarintThatNeverEndsThrows() {
        assertRejected(byteArrayOf(0x08, 0x80.toByte(), 0x80.toByte()))
    }

    @Test
    fun aVarintLongerThanTenBytesThrows() {
        val tooLong = ByteArray(11) { 0x80.toByte() }
        assertRejected(byteArrayOf(0x08) + tooLong)
    }

    @Test
    fun aFixedFieldCutShortThrows() {
        assertRejected(byteArrayOf(0x15, 1, 2)) // fixed32 with 2 bytes
        assertRejected(byteArrayOf(0x19, 1, 2, 3, 4, 5, 6, 7)) // fixed64 with 7 bytes
    }

    @Test
    fun aNegativeLengthThrows() {
        // length varint 0xFFFFFFFF.. reads as -1 once narrowed to Int
        val negative = ProtoWriter().varint(1, -1).toByteArray()
        assertRejected(byteArrayOf(0x0A) + negative.copyOfRange(1, negative.size))
    }

    @Test
    fun groupsAndOtherWireTypesThrow() {
        assertRejected(byteArrayOf(0x0B)) // field 1, start-group (3)
        assertRejected(byteArrayOf(0x0C)) // field 1, end-group (4)
        assertRejected(byteArrayOf(0x0E)) // field 1, wire type 6
    }

    @Test
    fun aGoodMessageIsNotRejectedByParseOrNull() {
        assertEquals(3, ProtoMessage.parseOrNull(ProtoWriter().varint(1, 3).toByteArray())!!.int(1))
    }
}
