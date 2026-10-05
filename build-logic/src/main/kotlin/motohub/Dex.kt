// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package motohub

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Just enough of the Dalvik executable format to answer two questions:
 *
 *  - which methods does this dex *define*, and what does each class extend and implement?
 *  - what does each class's code *use*: the methods it invokes and the classes it names?
 *
 * Read directly rather than through `dexdump`, so the check runs wherever the build runs and
 * needs nothing from `build-tools` beyond what is already there to build with. The format is
 * documented at https://source.android.com/docs/core/runtime/dex-format; only the tables named
 * above are parsed, and everything else in the file is skipped.
 */
internal class Dex(bytes: ByteArray) {
    private val buf: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private val stringIdsSize = buf.getInt(0x38)
    private val stringIdsOff = buf.getInt(0x3C)
    private val typeIdsOff = buf.getInt(0x44)
    private val protoIdsOff = buf.getInt(0x4C)
    private val fieldIdsOff = buf.getInt(0x54)
    private val methodIdsOff = buf.getInt(0x5C)
    private val classDefsSize = buf.getInt(0x60)
    private val classDefsOff = buf.getInt(0x64)

    private val strings = arrayOfNulls<String>(stringIdsSize)

    /** One method, named the way this file compares them: `name(paramDescriptors)returnType`. */
    data class MethodRef(val owner: String, val signature: String) {
        override fun toString(): String = "$owner$signature"
    }

    /** A class this dex defines, with what it inherits from and the methods it carries. */
    data class ClassDef(
        val descriptor: String,
        val superclass: String?,
        val interfaces: List<String>,
        val methods: Set<String>
    )

    private fun uleb128(at: Int): Pair<Int, Int> {
        var offset = at
        var result = 0
        var shift = 0
        while (true) {
            val byte = buf.get(offset++).toInt()
            result = result or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
        }
        return result to offset
    }

    private fun string(index: Int): String {
        strings[index]?.let { return it }
        val dataOff = buf.getInt(stringIdsOff + index * 4)
        // The uleb128 here is the UTF-16 length, which is not the byte length; the bytes that
        // follow are MUTF-8 and NUL-terminated, so the terminator is what bounds the read.
        var at = uleb128(dataOff).second
        val start = at
        while (buf.get(at).toInt() != 0) at++
        val raw = ByteArray(at - start)
        for (i in raw.indices) raw[i] = buf.get(start + i)
        // Descriptors and member names are compared, never displayed to a user or re-encoded, so
        // a byte-faithful decoding is worth more here than a strictly correct MUTF-8 one.
        return String(raw, Charsets.ISO_8859_1).also { strings[index] = it }
    }

    private fun type(index: Int): String = string(buf.getInt(typeIdsOff + index * 4))

    private fun protoSignature(index: Int): String {
        val at = protoIdsOff + index * 12
        val returnType = type(buf.getInt(at + 4))
        val parametersOff = buf.getInt(at + 8)
        val params = StringBuilder()
        if (parametersOff != 0) {
            val size = buf.getInt(parametersOff)
            for (i in 0 until size) {
                params.append(type(buf.getShort(parametersOff + 4 + i * 2).toInt() and 0xFFFF))
            }
        }
        return "($params)$returnType"
    }

    private fun methodId(index: Int): MethodRef {
        val at = methodIdsOff + index * 8
        val owner = type(buf.getShort(at).toInt() and 0xFFFF)
        val proto = protoSignature(buf.getShort(at + 2).toInt() and 0xFFFF)
        val name = string(buf.getInt(at + 4))
        return MethodRef(owner, "$name$proto")
    }

    fun classDefs(): List<ClassDef> = (0 until classDefsSize).map { i ->
        val at = classDefsOff + i * 32
        val descriptor = type(buf.getInt(at))
        val superIdx = buf.getInt(at + 8)
        val interfacesOff = buf.getInt(at + 12)

        val interfaces = if (interfacesOff == 0) emptyList() else {
            val size = buf.getInt(interfacesOff)
            (0 until size).map { type(buf.getShort(interfacesOff + 4 + it * 2).toInt() and 0xFFFF) }
        }

        ClassDef(
            descriptor = descriptor,
            superclass = if (superIdx == NO_INDEX) null else type(superIdx),
            interfaces = interfaces,
            methods = methodsOf(i).map { (methodIdx, _) -> methodId(methodIdx).signature }.toSet()
        )
    }

    /** What one class's code reaches for: the methods it invokes and every class it names. */
    class Uses {
        val calls = mutableSetOf<MethodRef>()
        val classes = mutableSetOf<String>()
    }

    /**
     * For every class this dex defines, what its code uses - read from the instructions, whether
     * or not they are ever reached: a call site that does not resolve fails its class's
     * verification either way.
     *
     * The method table alone is not enough once a module carries copies of libraries. Those
     * copies hold thousands of calls their own code makes, and most of that code never loads:
     * a copy the app also has is shadowed by the app's (the module's loader asks the app first),
     * and the rest only loads if something the module runs names it. Which class makes which
     * call is only in the bytecode.
     */
    fun uses(): Map<String, Uses> = (0 until classDefsSize).associate { i ->
        val at = classDefsOff + i * 32
        val uses = Uses()
        val superIdx = buf.getInt(at + 8)
        if (superIdx != NO_INDEX) uses.classes += type(superIdx)
        val interfacesOff = buf.getInt(at + 12)
        if (interfacesOff != 0) {
            repeat(buf.getInt(interfacesOff)) {
                uses.classes += type(buf.getShort(interfacesOff + 4 + it * 2).toInt() and 0xFFFF)
            }
        }
        methodsOf(i).forEach { (_, codeOff) -> if (codeOff != 0) usesIn(codeOff, uses) }
        type(buf.getInt(at)) to uses
    }

    /** (method_idx, code_off) for each method class_def [index] defines, direct then virtual. */
    private fun methodsOf(index: Int): List<Pair<Int, Int>> {
        val classDataOff = buf.getInt(classDefsOff + index * 32 + 24)
        if (classDataOff == 0) return emptyList()
        val out = mutableListOf<Pair<Int, Int>>()
        var cursor = classDataOff
        val (staticFields, a1) = uleb128(cursor); cursor = a1
        val (instanceFields, a2) = uleb128(cursor); cursor = a2
        val (directMethods, a3) = uleb128(cursor); cursor = a3
        val (virtualMethods, a4) = uleb128(cursor); cursor = a4
        repeat(staticFields + instanceFields) {
            cursor = uleb128(cursor).second      // field_idx_diff
            cursor = uleb128(cursor).second      // access_flags
        }
        // Method indices are stored as deltas within each of the two lists, and each list
        // restarts from zero.
        listOf(directMethods, virtualMethods).forEach { count ->
            var methodIdx = 0
            repeat(count) {
                val (diff, afterDiff) = uleb128(cursor)
                methodIdx += diff
                cursor = uleb128(afterDiff).second   // access_flags
                val (codeOff, afterCode) = uleb128(cursor)
                cursor = afterCode
                out += methodIdx to codeOff
            }
        }
        return out
    }

    /** Walks one code_item's instructions, adding what each one names to [out]. */
    private fun usesIn(codeOff: Int, out: Uses) {
        val insnsSize = buf.getInt(codeOff + 12)
        val insns = codeOff + 16
        var pc = 0
        while (pc < insnsSize) {
            val unit = buf.getShort(insns + pc * 2).toInt() and 0xFFFF
            val opcode = unit and 0xFF
            if (opcode == 0x00 && unit != 0) {
                // A switch or array payload sitting in the instruction stream, not an instruction.
                val size = buf.getShort(insns + (pc + 1) * 2).toInt() and 0xFFFF
                pc += when (unit) {
                    0x0100 -> size * 2 + 4
                    0x0200 -> size * 4 + 2
                    0x0300 -> {
                        val elementWidth = size
                        val count = buf.getInt(insns + (pc + 2) * 2).toLong()
                        ((count * elementWidth + 1) / 2 + 4).toInt()
                    }
                    else -> 1
                }
                continue
            }
            val operand = if (WIDTHS[opcode] > 1) buf.getShort(insns + (pc + 1) * 2).toInt() and 0xFFFF else 0
            when (opcode) {
                in 0x6E..0x72, in 0x74..0x78, 0xFA, 0xFB -> methodId(operand).let { call ->
                    out.calls += call
                    out.classes += call.owner
                }
                // const-class, check-cast, instance-of, new-instance, new-array, filled-new-array*
                0x1C, 0x1F, 0x20, 0x22, 0x23, 0x24, 0x25 -> out.classes += type(operand)
                in 0x52..0x6D -> out.classes += type(buf.getShort(fieldIdsOff + operand * 8).toInt() and 0xFFFF)
            }
            pc += WIDTHS[opcode]
        }
    }

    companion object {
        private const val NO_INDEX = -1  // 0xffffffff as a signed int

        /** Each opcode's length in 16-bit code units, from the Dalvik instruction formats. */
        private val WIDTHS = IntArray(256) { 1 }.apply {
            fun width(range: IntRange, units: Int) = range.forEach { this[it] = units }
            width(0x02..0x02, 2); width(0x03..0x03, 3)          // move/from16, move/16
            width(0x05..0x05, 2); width(0x06..0x06, 3)          // move-wide
            width(0x08..0x08, 2); width(0x09..0x09, 3)          // move-object
            width(0x13..0x13, 2); width(0x14..0x14, 3)          // const/16, const
            width(0x15..0x16, 2); width(0x17..0x17, 3)          // const/high16, const-wide/16, /32
            width(0x18..0x18, 5); width(0x19..0x19, 2)          // const-wide, const-wide/high16
            width(0x1A..0x1A, 2); width(0x1B..0x1B, 3)          // const-string, /jumbo
            width(0x1C..0x1C, 2)                                // const-class
            width(0x1F..0x20, 2)                                // check-cast, instance-of
            width(0x22..0x23, 2)                                // new-instance, new-array
            width(0x24..0x26, 3)                                // filled-new-array*, fill-array-data
            width(0x29..0x29, 2); width(0x2A..0x2C, 3)          // goto/16, goto/32, switches
            width(0x2D..0x3D, 2)                                // cmp*, if-*
            width(0x44..0x6D, 2)                                // aget/aput, iget/iput, sget/sput
            width(0x6E..0x72, 3); width(0x74..0x78, 3)          // invoke-*, invoke-*/range
            width(0x90..0xAF, 2)                                // binop
            width(0xD0..0xE2, 2)                                // binop/lit16, binop/lit8
            width(0xFA..0xFB, 4)                                // invoke-polymorphic*
            width(0xFC..0xFD, 3)                                // invoke-custom*
            width(0xFE..0xFF, 2)                                // const-method-handle, -type
        }

        private fun isDex(name: String) = name.startsWith("classes") && name.endsWith(".dex")

        /**
         * The entry class a module's manifest names, as a descriptor; null for a bare dex, or a
         * file that carries no manifest.
         */
        fun moduleEntry(file: File): String? {
            val json = when {
                file.name.endsWith(".mhm") -> ZipFile(file).use { outer ->
                    val jar = outer.getEntry("module.jar") ?: return null
                    val bytes = outer.getInputStream(jar).use(java.io.InputStream::readBytes)
                    java.util.zip.ZipInputStream(bytes.inputStream()).use { inner ->
                        generateSequence { inner.nextEntry }
                            .firstOrNull { it.name == MANIFEST }
                            ?.let { inner.readBytes().toString(Charsets.UTF_8) }
                    }
                }
                file.name.endsWith(".jar") -> ZipFile(file).use { zip ->
                    zip.getEntry(MANIFEST)?.let { entry ->
                        zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                    }
                }
                else -> null
            } ?: return null
            val name = Regex("\"entryClass\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                ?: return null
            return "L" + name.replace('.', '/') + ";"
        }

        private const val MANIFEST = "META-INF/motohub-module.json"

        /**
         * Every dex inside an APK, a module `.mhm`, a module `.jar` or a bare `.dex`.
         *
         * A `.mhm` is a zip holding `module.jar` (itself a zip holding the dex) beside the
         * signature covering it, so it is opened one layer deeper than the rest.
         */
        fun readAll(file: File): List<Dex> = when {
            file.name.endsWith(".dex") -> listOf(Dex(file.readBytes()))
            file.name.endsWith(".mhm") -> ZipFile(file).use { outer ->
                val jar = outer.getEntry("module.jar")
                    ?: error("${file.name} carries no module.jar.")
                val staged = File.createTempFile("motohub-module", ".jar")
                try {
                    outer.getInputStream(jar).use { input ->
                        staged.outputStream().use { input.copyTo(it) }
                    }
                    readAll(staged)
                } finally {
                    staged.delete()
                }
            }
            else -> ZipFile(file).use { zip ->
                zip.entries().asSequence()
                    .filter { isDex(it.name) }
                    .sortedBy { it.name }
                    .map { Dex(zip.getInputStream(it).use(java.io.InputStream::readBytes)) }
                    .toList()
            }
        }
    }
}
