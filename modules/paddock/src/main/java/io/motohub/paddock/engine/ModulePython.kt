// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.engine

import android.system.Os
import com.chaquo.python.Python
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Boots Chaquopy's CPython from files this module carries, with no APK assets: AndroidPlatform
 * reads the app's AssetManager, which holds nothing of ours, so this replaces it. The Python
 * assets travel as jar resources under [PREFIX] and are unpacked into plain directories; the
 * native libraries come from the directory the app unpacked (contract 39), found by
 * System.loadLibrary through this class's loader.
 */
internal object ModulePython {
    private const val PREFIX = "paddock-py/chaquopy/"

    /** Unpacks once per module file; returns the Python home with app/requirements/stdlib/bootstrap. */
    /**
     * The Python files go to [noBackupDir], not the module's storage: MOTO-HUB's .mhb backup copies
     * every module's storage, and these ~60 MB come back from the module file anyway.
     */
    fun unpack(storage: File, noBackupDir: File, log: (String) -> Unit): File {
        val jar = moduleJar()
        // Where 0.1.0 and 0.1.1 unpacked them: inside the backup.
        File(storage, "py").takeIf { it.isDirectory }?.deleteRecursively()
        val home = File(noBackupDir, "paddock-py")
        val stamp = File(home, ".stamp")
        val expected = "${jar.length()}-${jar.lastModified()}"
        if (stamp.isFile && stamp.readText() == expected) {
            log("Python files already unpacked")
            return home
        }
        home.deleteRecursively()
        home.mkdirs()
        var files = 0
        ZipFile(jar).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory || !entry.name.startsWith(PREFIX)) continue
                val rel = entry.name.removePrefix(PREFIX)
                require(!rel.contains("..")) { "Unsafe entry ${entry.name}" }
                when {
                    rel.endsWith(".imy") -> {
                        // Chaquopy splits trees by ABI (stdlib-common + stdlib-arm64-v8a): plain
                        // directories cannot share a package across two sys.path entries, so merge.
                        val target = File(home, mergedName(rel.removeSuffix(".imy")))
                        ZipInputStream(zip.getInputStream(entry)).use { inner ->
                            while (true) {
                                val e = inner.nextEntry ?: break
                                if (e.isDirectory) continue
                                require(!e.name.contains("..")) { "Unsafe entry ${e.name}" }
                                val out = File(target, e.name)
                                out.parentFile?.mkdirs()
                                out.outputStream().use { inner.copyTo(it) }
                                files++
                            }
                        }
                    }
                    // bootstrap-native/<abi>/java/chaquopy.so belongs with bootstrap.imy's java/.
                    rel.startsWith("bootstrap-native/") -> {
                        val out = File(home, "bootstrap/" + rel.substringAfter('/').substringAfter('/'))
                        out.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                        files++
                    }
                    else -> {
                        val out = File(home, rel)
                        out.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                        files++
                    }
                }
            }
        }
        // Android warns, and will refuse, native code loaded from a writable file.
        home.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.forEach { it.setReadOnly() }
        stamp.writeText(expected)
        log("Unpacked $files Python files")
        return home
    }

    private fun mergedName(base: String): String = when {
        base.startsWith("stdlib") -> "stdlib"
        base.startsWith("requirements") -> "requirements"
        base.startsWith("app") -> "app"
        else -> base
    }

    /** The module file: the class loader serves our resources from it as jar:file:...!/ URLs. */
    private fun moduleJar(): File {
        val url = ModulePython::class.java.classLoader!!.getResource(PREFIX + "build.json")
            ?: error("build.json is not among the module's resources")
        val path = url.toString().substringAfter("file:").substringBefore("!/")
        return File(path).also { require(it.isFile) { "Module file not found from $url" } }
    }

    /** Same order as Chaquopy's AndroidPlatform.loadNativeLibs. */
    fun loadNativeLibraries(home: File, log: (String) -> Unit): String {
        val version = JSONObject(File(home, "build.json").readText()).getString("python_version")
        for (suffix in listOf("chaquopy", "python")) {
            System.loadLibrary("crypto_$suffix")
            System.loadLibrary("ssl_$suffix")
            System.loadLibrary("sqlite3_$suffix")
        }
        System.loadLibrary("python$version")
        System.loadLibrary("python" + version.substringBefore('.'))
        System.loadLibrary("chaquopy_java")
        // Libraries pip wheels carry beside their modules (libffi for cffi). Chaquopy's importer
        // would preload them; without it the linker only finds them if already loaded by soname.
        File(home, "requirements/chaquopy/lib").listFiles { f -> f.name.endsWith(".so") }
            ?.forEach { System.load(it.absolutePath) }
        log("Native libraries loaded (Python $version)")
        return version
    }

    fun start(home: File, storage: File) {
        val tmp = File(storage, "tmp").apply { mkdirs() }
        Os.setenv("HOME", storage.absolutePath, true)
        Os.setenv("TMPDIR", tmp.absolutePath, true)
        Os.setenv("SSL_CERT_FILE", File(home, "cacert.pem").absolutePath, true)
        val path = listOf("app", "requirements", "stdlib", "bootstrap")
            .joinToString(":") { File(home, it).absolutePath }
        if (Python.isStarted()) return
        // A second CPython in one process crashes it (SIGSEGV in Py_Initialize). An older copy of
        // the module, replaced while its Python ran, is invisible from this class loader: a
        // process-wide system property is what both copies see.
        val other = System.getProperty(RUNNING_PROPERTY)
        if (other != null) throw IllegalStateException("Python already started by another copy of Paddock ($other)")
        System.setProperty(RUNNING_PROPERTY, moduleJar().length().toString())
        Python.start(ModulePlatform(path))
    }

    private const val RUNNING_PROPERTY = "io.motohub.paddock.python"

    private class ModulePlatform(private val path: String) : Python.Platform() {
        override fun getPath(): String = path
    }
}
