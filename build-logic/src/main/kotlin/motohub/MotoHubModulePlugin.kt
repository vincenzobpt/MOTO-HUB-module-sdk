// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see LICENSE.
package motohub

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.api.provider.Property
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import java.io.File
import java.security.Signature
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What a module says about itself. Written into `META-INF/motohub-module.json`, which the app
 * reads out of the file before it runs a single class of it.
 */
abstract class MotoHubModuleExtension {
    /** Stable id: letters, digits, `-` and `_`. The app files the module under it on disk. */
    abstract val id: Property<String>

    /** The module's own version. The app compares it as text, it does not order it. */
    abstract val version: Property<String>

    /** Fully qualified class implementing `MotoHubModuleEntry`, with a public no-arg constructor. */
    abstract val entryClass: Property<String>

    /** `MotoHubModuleContract.CONTRACT_VERSION` of the module-api this module was built against. */
    abstract val contractVersion: Property<Int>

    /** What the rider sees. Defaults to [id]. */
    abstract val displayName: Property<String>

    /** One sentence under the name. */
    abstract val description: Property<String>
}

/**
 * `id("motohub.module")`, applied next to `com.android.library`.
 *
 * Tasks, all in the `motohub` group:
 * - `moduleJar` - the module's classes dexed, with its manifest: the file the app loads.
 * - `modulePackage` - that file signed with your developer key, as `<id>-<version>.mhm`.
 * - `pushModule` - the package copied to the phone's Download folder over adb.
 * - `checkModuleLinkage -PmotohubApk=<apk>` - every method the module calls, resolved against a
 *   released MOTO-HUB APK. See docs/borrowed-libraries.md for why this is not optional.
 */
class MotoHubModulePlugin : Plugin<Project> {

    override fun apply(project: Project): Unit = with(project) {
        val module = extensions.create<MotoHubModuleExtension>("motohubModule")
        module.displayName.convention(module.id)
        module.description.convention("")
        val execs = providers

        val manifest = tasks.register("moduleManifest") {
            group = GROUP
            description = "Writes META-INF/motohub-module.json."
            val output = layout.buildDirectory.file("module/manifest/META-INF/motohub-module.json")
            inputs.property("id", module.id)
            inputs.property("version", module.version)
            inputs.property("entryClass", module.entryClass)
            inputs.property("contractVersion", module.contractVersion)
            inputs.property("displayName", module.displayName)
            inputs.property("description", module.description)
            outputs.file(output)
            doLast {
                val id = module.id.get()
                if (id.isEmpty() || !id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
                    throw GradleException("motohubModule.id '$id' may only hold letters, digits, '-' and '_'.")
                }
                if (module.contractVersion.get() < 1) {
                    throw GradleException("motohubModule.contractVersion must be set.")
                }
                output.get().asFile.apply { parentFile.mkdirs() }.writeText(
                    """
                    {
                      "id": ${json(id)},
                      "version": ${json(module.version.get())},
                      "contractVersion": ${module.contractVersion.get()},
                      "entryClass": ${json(module.entryClass.get())},
                      "displayName": ${json(module.displayName.get())},
                      "description": ${json(module.description.get())}
                    }
                    """.trimIndent() + "\n"
                )
            }
        }

        // d8 rather than the library's own AAR: what the app loads is a dex container, and an AAR
        // carries java classes the phone would have to dex itself.
        val moduleJar = tasks.register<Zip>("moduleJar") {
            group = GROUP
            description = "Dexes the module and packs it with its manifest: the file MOTO-HUB loads."
            dependsOn("assembleRelease", manifest)
            val dexDir = layout.buildDirectory.dir("module/dex")
            val classesJar = layout.buildDirectory.file(
                "intermediates/aar_main_jar/release/syncReleaseLibJars/classes.jar"
            )
            // Everything on the compile classpath is compileOnly - the contract and the libraries
            // the app lends. d8 only has to see them to resolve references, never copy them.
            val classpathFiles = configurations.getByName("releaseCompileClasspath").incoming
                .artifactView {
                    attributes.attribute(Attribute.of("artifactType", String::class.java), "android-classes-jar")
                    lenient(true)
                }.files

            doFirst {
                val sdk = androidSdk(rootDir)
                val d8 = d8(sdk)
                val androidJar = androidJar(sdk)
                val out = dexDir.get().asFile.apply { deleteRecursively(); mkdirs() }
                val command = buildList {
                    add(d8.absolutePath)
                    add("--release")
                    add("--min-api"); add(MIN_API.toString())
                    add("--lib"); add(androidJar.absolutePath)
                    classpathFiles.filter { it.exists() }.forEach { add("--classpath"); add(it.absolutePath) }
                    add("--output"); add(out.absolutePath)
                    add(classesJar.get().asFile.absolutePath)
                }
                execs.exec { commandLine(command) }.result.get()
            }

            from(dexDir)
            from(layout.buildDirectory.dir("module/manifest"))
            // d8 copies classes only: a module's src/main/resources travel in the zip at the same
            // relative paths, where the app's class loader serves them to getResourceAsStream.
            from(layout.projectDirectory.dir("src/main/resources"))
            archiveFileName.set(module.id.zip(module.version) { id, version -> "$id-$version.jar" })
            destinationDirectory.set(layout.buildDirectory.dir("module/out"))
        }

        val modulePackage = tasks.register("modulePackage") {
            group = GROUP
            description = "Signs the module with your developer key and packs it as <id>-<version>.mhm."
            val jar = moduleJar.flatMap { it.archiveFile }
            val output = layout.buildDirectory.file(
                module.id.zip(module.version) { id, version -> "module/out/$id-$version.mhm" }
            )
            val keyDir = DeveloperKey.directory(providers.gradleProperty("motohub.moduleKeyDir").orNull)
            inputs.file(jar)
            // Signing takes milliseconds, and the key lives outside the project where Gradle would
            // not notice it change - so this always runs rather than risk a stale signature.
            outputs.file(output)
            outputs.upToDateWhen { false }
            doLast {
                val key = DeveloperKey.loadOrCreate(keyDir, logger)
                val moduleBytes = jar.get().asFile.readBytes()
                val signature = Signature.getInstance("Ed25519").run {
                    initSign(key.private)
                    update(moduleBytes)
                    sign()
                }
                val target = output.get().asFile.apply { parentFile.mkdirs() }
                ZipOutputStream(target.outputStream()).use { zip ->
                    zip.put("module.jar", moduleBytes)
                    zip.put("module.sig", signature)
                    zip.put("module.pub", key.public.encoded)
                }
                logger.lifecycle(
                    "Packed ${target.name}, developer key ${DeveloperKey.fingerprint(key.public.encoded)}.\n" +
                        "Install it from MOTO-HUB > Modules > Install from a file, with developer mode on."
                )
            }
        }

        tasks.register("pushModule") {
            group = GROUP
            description = "Builds the .mhm and copies it to the phone's Download folder over adb."
            dependsOn(modulePackage)
            val packageFile = modulePackage.map { it.outputs.files.singleFile }
            doLast {
                val adb = File(androidSdk(rootDir), "platform-tools/" + if (isWindows) "adb.exe" else "adb")
                if (!adb.isFile) throw GradleException("adb is missing at $adb.")
                val file = packageFile.get()
                execs.exec { commandLine(adb.absolutePath, "push", file.absolutePath, "/sdcard/Download/${file.name}") }
                    .result.get()
                logger.lifecycle("Pushed ${file.name} to Download. Open MOTO-HUB > Modules > Install from a file.")
            }
        }

        // Its own task so the missing-property case fails with instructions instead of Gradle's
        // "property 'apk' doesn't have a configured value".
        // Relative to where ./gradlew was run, which is what a path typed on that command line means.
        val invokedFrom = gradle.startParameter.currentDir
        val apkPath = providers.gradleProperty("motohubApk").map { invokedFrom.resolve(it).absolutePath }
        val requireApk = tasks.register("requireMotohubApk") {
            doLast {
                val path = apkPath.orNull ?: throw GradleException(
                    "checkModuleLinkage needs the MOTO-HUB APK your module will run in: download it " +
                        "from the app's releases page and run\n  ./gradlew checkModuleLinkage -PmotohubApk=/path/to/app.apk"
                )
                if (!File(path).isFile) throw GradleException("No APK at $path.")
            }
        }
        tasks.register<CheckModuleLinkage>("checkModuleLinkage") {
            group = "verification"
            description = "Fails if the module calls a method the released MOTO-HUB APK does not have."
            dependsOn(requireApk)
            apk.set(layout.file(apkPath.map(::File)))
            modules.from(moduleJar.flatMap { it.archiveFile })
            projectDir.resolve("module-linkage-allowed.txt").takeIf(File::isFile)?.let(allowList::set)
            report.set(layout.buildDirectory.file("reports/module-linkage.txt"))
            failOnUnresolved.set(true)
        }
    }

    private fun ZipOutputStream.put(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private companion object {
        const val GROUP = "motohub"

        /** MOTO-HUB's own minSdk: a module is never loaded anywhere older. */
        const val MIN_API = 34

        val isWindows = System.getProperty("os.name").startsWith("Windows")

        fun json(value: String): String = buildString {
            append('"')
            value.forEach { c ->
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c == '\n' -> append("\\n")
                    c < ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }

        /** ANDROID_HOME, ANDROID_SDK_ROOT, or the sdk.dir Android Studio writes to local.properties. */
        fun androidSdk(rootDir: File): File {
            System.getenv("ANDROID_HOME")?.let { return File(it) }
            System.getenv("ANDROID_SDK_ROOT")?.let { return File(it) }
            val local = File(rootDir, "local.properties")
            if (local.isFile) {
                Properties().apply { local.inputStream().use(::load) }
                    .getProperty("sdk.dir")?.let { return File(it) }
            }
            throw GradleException("The Android SDK was not found: set ANDROID_HOME or sdk.dir in local.properties.")
        }

        /** The newest build-tools that has d8. */
        fun d8(sdk: File): File {
            val name = if (isWindows) "d8.bat" else "d8"
            return File(sdk, "build-tools").listFiles().orEmpty()
                .filter { File(it, name).isFile }
                .maxWithOrNull(compareBy({ it.versionPart(0) }, { it.versionPart(1) }, { it.versionPart(2) }))
                ?.let { File(it, name) }
                ?: throw GradleException("No build-tools with d8 under $sdk/build-tools. Install one with the SDK manager.")
        }

        /** The newest platform's android.jar, only used by d8 to resolve framework references. */
        fun androidJar(sdk: File): File =
            File(sdk, "platforms").listFiles().orEmpty()
                .filter { File(it, "android.jar").isFile }
                .maxByOrNull { it.name.removePrefix("android-").toIntOrNull() ?: 0 }
                ?.let { File(it, "android.jar") }
                ?: throw GradleException("No platform under $sdk/platforms. Install one with the SDK manager.")

        fun File.versionPart(index: Int): Int =
            name.split('.', '-').getOrNull(index)?.toIntOrNull() ?: 0
    }
}
