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
 *  - which methods does this dex *reference*?  (its `method_ids` table)
 *  - which methods does this dex *define*, and what does each class extend and implement?
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
    private val methodIdsSize = buf.getInt(0x58)
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

    /**
     * Every method this dex names — the ones it defines and the ones it calls alike. That is
     * exactly the set that has to resolve at load time, which is why no disassembly is needed:
     * a reference the code never reaches still has to exist for the class to verify.
     */
    fun methodReferences(): List<MethodRef> = (0 until methodIdsSize).map(::methodId)

    fun classDefs(): List<ClassDef> = (0 until classDefsSize).map { i ->
        val at = classDefsOff + i * 32
        val descriptor = type(buf.getInt(at))
        val superIdx = buf.getInt(at + 8)
        val interfacesOff = buf.getInt(at + 12)
        val classDataOff = buf.getInt(at + 24)

        val interfaces = if (interfacesOff == 0) emptyList() else {
            val size = buf.getInt(interfacesOff)
            (0 until size).map { type(buf.getShort(interfacesOff + 4 + it * 2).toInt() and 0xFFFF) }
        }

        val methods = mutableSetOf<String>()
        if (classDataOff != 0) {
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
                    cursor = uleb128(cursor).second      // code_off
                    methods += methodId(methodIdx).signature
                }
            }
        }
        ClassDef(
            descriptor = descriptor,
            superclass = if (superIdx == NO_INDEX) null else type(superIdx),
            interfaces = interfaces,
            methods = methods
        )
    }

    companion object {
        private const val NO_INDEX = -1  // 0xffffffff as a signed int

        private fun isDex(name: String) = name.startsWith("classes") && name.endsWith(".dex")

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
