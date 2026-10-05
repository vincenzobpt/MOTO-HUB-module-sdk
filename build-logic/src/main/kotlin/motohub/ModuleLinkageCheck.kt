// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package motohub

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Fails the build when a loadable module calls something the app it will be loaded into does not
 * have.
 *
 * ### Why this exists
 *
 * A module is dexed on its own and every dependency it compiles against is `compileOnly`: at
 * runtime its class loader's parent is the app's, so kotlin-stdlib, coroutines, Compose, protobuf,
 * Conscrypt and MOTO-HUB's own contracts all come from the APK. The APK is minified. R8 decides
 * what to keep by looking at what the *app* uses, and it cannot see the module — so a stdlib
 * method only the module calls is dead code to R8, and R8 removes it.
 *
 * `proguard-rules.pro` answers this with `-keepnames`, which preserves names and lets members go,
 * and its own comment claims the failure would then be "loud (NoClassDefFoundError in the module
 * load, named in the log)". It is not. On 2026-09-20 seven riders' phones were killing MOTO-HUB
 * mid-ride on `NoSuchMethodError: ArraysKt.lastOrNull([Ljava/lang/Object;)`, thrown from the
 * Android Auto receiver's *logger* on the transport poll thread — months after release, nowhere
 * near module load, and only when the receiver had an error to report. The module had loaded
 * perfectly.
 *
 * A `-keep` for that one method would have fixed that one method. This task instead states the
 * rule the module system actually depends on and enforces it before a build leaves the machine:
 * **every method a module names must exist in the APK it will be loaded into, or on the
 * platform.** The calls are read from the `invoke-*` instructions of the module classes that
 * actually load (see [Dex.uses]); a call the code never reaches still has to resolve for its
 * class to verify, so reachability is not asked.
 *
 * ### What it does not check
 *
 * Fields, and classes the module names without calling into. Both fail loudly at load, which is
 * the case the existing rules already cover; methods were the silent ones.
 *
 * ### The allow list
 *
 * `module-linkage-allowed.txt` beside `proguard-rules.pro` takes one `Lowner;name(args)ret` per
 * line, `#` for comments. It is for a reference that provably cannot be reached — not for one
 * that is merely believed to be cold. A wrong entry here is a crash on a motorcycle.
 */
@CacheableTask
abstract class CheckModuleLinkage : DefaultTask() {

    /** The minified APK (or a bare dex) the modules will be loaded into. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apk: RegularFileProperty

    /** Every module that ships with, or for, that APK: `.mhm`, `.jar` or `.dex`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val modules: ConfigurableFileCollection

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val allowList: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    /** Set false to report without failing, for looking at an APK that is already published. */
    @get:Input
    @get:Optional
    abstract val failOnUnresolved: Property<Boolean>

    @TaskAction
    fun check() {
        val apkFile = apk.get().asFile
        val moduleFiles = modules.files.filter(File::isFile).sortedBy(File::getName)
        if (moduleFiles.isEmpty()) {
            throw GradleException(
                "checkModuleLinkage was given no modules. A release that loads modules must be " +
                    "checked against them; wire them in or remove the task."
            )
        }

        val app = AppSurface(Dex.readAll(apkFile))
        val allowed = allowList.orNull?.asFile
            ?.takeIf(File::isFile)
            ?.readLines()
            ?.map { it.substringBefore('#').trim() }
            ?.filter(String::isNotEmpty)
            ?.toSet()
            .orEmpty()

        val lines = StringBuilder()
        val failures = mutableListOf<String>()
        moduleFiles.forEach { moduleFile ->
            val dexes = Dex.readAll(moduleFile)
            val uses = dexes.flatMap { it.uses().entries }.associate { it.key to it.value }
            // What the module's own loader serves: the classes it carries and the app does not.
            // DexClassLoader asks the app's loader first, so a class both carry is the APK's at
            // runtime and the module's copy is never read: dashcam 0.4.1 packs its own
            // kotlin-stdlib (pulled in by media3), its copy of kotlin.text.Regex had
            // Regex(String, Set), the APK's did not, and this check trusted the copy that never
            // loads (NoSuchMethodError on a rider's phone, 2026-10-05).
            val own = uses.keys.filterNot(app::knows).toSet()
            val borrowed = loadedFrom(Dex.moduleEntry(moduleFile), own, uses).asSequence()
                .flatMap { uses.getValue(it).calls.asSequence() }
                .filter { it.owner !in own }
                .filterNot { isPlatform(it.owner) }
                // A method named on an array type resolves against java.lang.Object, which is
                // never in the APK to begin with.
                .filterNot { it.owner.startsWith("[") }
                .distinct()
                .sortedBy(Dex.MethodRef::toString)
                .toList()

            val unresolved = borrowed.filterNot { app.resolves(it) }
                .filterNot { it.toString() in allowed }

            lines.append(moduleFile.name)
                .append(": ").append(borrowed.size).append(" borrowed method reference(s), ")
                .append(unresolved.size).append(" unresolved\n")
            unresolved.forEach { reference ->
                val reason = if (app.knows(reference.owner)) {
                    "the class is in the APK but this method was shrunk out of it"
                } else {
                    "the class is not in the APK at all"
                }
                lines.append("    ").append(reference).append("  — ").append(reason).append('\n')
                failures += "${moduleFile.name}: $reference — $reason"
            }
        }

        report.get().asFile.apply { parentFile.mkdirs() }.writeText(lines.toString())
        logger.lifecycle(lines.toString().trimEnd())

        if (failures.isNotEmpty() && failOnUnresolved.getOrElse(true)) {
            throw GradleException(
                buildString {
                    append(failures.size)
                    append(" method(s) a module calls are missing from ")
                    append(apkFile.name)
                    append(". Loaded modules link against the app's libraries, and R8 shrinks ")
                    append("what the app itself never calls — so these throw NoSuchMethodError ")
                    append("on a rider's phone, at whatever moment the module happens to reach ")
                    append("them.\n\n")
                    failures.forEach { append("  ").append(it).append('\n') }
                    append(
                        "\nFix the module to stop calling it, or keep it whole in " +
                            "proguard-rules.pro. Prefer the former for anything on an error or " +
                            "logging path: that code runs when something has already gone wrong."
                    )
                }
            )
        }
    }

    /**
     * The module's own classes that can load: everything reachable from [entry] through the
     * classes their code names. The rest of what a module carries - most of a bundled library -
     * never loads, and its calls are not this module's calls. Without a manifest to start from,
     * every own class counts.
     */
    private fun loadedFrom(entry: String?, own: Set<String>, uses: Map<String, Dex.Uses>): Set<String> {
        if (entry == null || entry !in own) return own
        val loaded = mutableSetOf<String>()
        val queue = ArrayDeque(listOf(entry))
        while (queue.isNotEmpty()) {
            val descriptor = queue.removeFirst().trimStart('[')
            if (descriptor !in own || !loaded.add(descriptor)) continue
            uses[descriptor]?.classes?.forEach(queue::addLast)
        }
        return loaded
    }

    /** Descriptors that come from the boot classpath, so no APK is expected to carry them. */
    private fun isPlatform(descriptor: String): Boolean = PLATFORM_PREFIXES.any(descriptor::startsWith)

    /** Everything the APK defines, indexed for resolution through supers and interfaces. */
    private class AppSurface(dexes: List<Dex>) {
        private val classes: Map<String, Dex.ClassDef> =
            dexes.flatMap { it.classDefs() }.associateBy { it.descriptor }

        fun knows(descriptor: String) = descriptor in classes

        /**
         * Resolution the way the runtime does it: the class, then its superclasses, then the
         * interfaces they declare. Skipping the last step would condemn every default method a
         * module calls through an interface the app happens not to implement itself.
         */
        fun resolves(reference: Dex.MethodRef): Boolean {
            // A constructor is never inherited: `new Regex(String, Set)` needs that exact <init>
            // on Regex itself. Walking up from it reached java.io.Serializable, which Regex
            // implements, and dashcam 0.4.1 passed this check with a constructor R8 had removed
            // (NoSuchMethodError on a rider's phone, 2026-10-05).
            if (reference.signature.startsWith("<init>(")) {
                return classes[reference.owner]?.methods?.contains(reference.signature) == true
            }
            val seen = mutableSetOf<String>()
            val queue = ArrayDeque(listOf(reference.owner))
            while (queue.isNotEmpty()) {
                val descriptor = queue.removeFirst()
                if (!seen.add(descriptor)) continue
                // java.lang.Object is where every chain ends, so treating it as "resolved"
                // would make this check answer yes to everything — which is exactly what it did
                // on the first run, reporting the crash that prompted it as fine. Only the nine
                // methods Object actually declares resolve here.
                if (descriptor == OBJECT) {
                    if (reference.signature in OBJECT_METHODS) return true
                    continue
                }
                // Any other boot-classpath ancestor is not in the APK to be read, and is not
                // ours to shrink. A java.* one is asked of the JDK this build runs on, which
                // carries the same types: java.io.Serializable declares nothing, so reaching it
                // proves nothing. One the JDK does not have (android.*) cannot be checked, so it
                // is not blamed.
                if (PLATFORM_PREFIXES.any(descriptor::startsWith)) {
                    if (platformDeclares(descriptor, reference.signature) != false) return true
                    continue
                }
                val definition = classes[descriptor] ?: continue
                if (reference.signature in definition.methods) return true
                definition.superclass?.let(queue::addLast)
                definition.interfaces.forEach(queue::addLast)
            }
            return false
        }
    }

    private companion object {
        const val OBJECT = "Ljava/lang/Object;"

        /**
         * Whether a java.* type, or anything it extends or implements, declares [signature]:
         * null when the type is not one this JVM can answer for.
         */
        fun platformDeclares(descriptor: String, signature: String): Boolean? {
            if (!descriptor.startsWith("Ljava/")) return null
            val name = descriptor.substring(1, descriptor.length - 1).replace('/', '.')
            val root = runCatching { Class.forName(name, false, null) }.getOrNull() ?: return null
            val seen = mutableSetOf<Class<*>>()
            val queue = ArrayDeque<Class<*>>(listOf(root))
            while (queue.isNotEmpty()) {
                val type = queue.removeFirst()
                if (!seen.add(type)) continue
                if (type.declaredMethods.any { signatureOf(it) == signature }) return true
                type.superclass?.let(queue::addLast)
                type.interfaces.forEach(queue::addLast)
            }
            return false
        }

        private fun signatureOf(method: java.lang.reflect.Method): String =
            method.name + method.parameterTypes.joinToString("", "(", ")", transform = ::descriptorOf) +
                descriptorOf(method.returnType)

        private fun descriptorOf(type: Class<*>): String = when {
            type.isArray -> type.name.replace('.', '/')
            type == Void.TYPE -> "V"
            type == java.lang.Boolean.TYPE -> "Z"
            type == java.lang.Byte.TYPE -> "B"
            type == java.lang.Character.TYPE -> "C"
            type == java.lang.Short.TYPE -> "S"
            type == Integer.TYPE -> "I"
            type == java.lang.Long.TYPE -> "J"
            type == java.lang.Float.TYPE -> "F"
            type == java.lang.Double.TYPE -> "D"
            else -> "L" + type.name.replace('.', '/') + ";"
        }

        val OBJECT_METHODS = setOf(
            "<init>()V",
            "toString()Ljava/lang/String;",
            "hashCode()I",
            "equals(Ljava/lang/Object;)Z",
            "getClass()Ljava/lang/Class;",
            "clone()Ljava/lang/Object;",
            "finalize()V",
            "notify()V",
            "notifyAll()V",
            "wait()V",
            "wait(J)V",
            "wait(JI)V"
        )

        val PLATFORM_PREFIXES = listOf(
            "Ljava/", "Ljavax/", "Landroid/", "Ldalvik/", "Lorg/w3c/", "Lorg/xml/", "Lorg/xmlpull/",
            "Lorg/json/", "Lorg/apache/http/", "Ljunit/", "Lsun/"
        )
    }
}
