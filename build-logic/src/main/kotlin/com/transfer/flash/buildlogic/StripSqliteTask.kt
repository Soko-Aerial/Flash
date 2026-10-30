package com.transfer.flash.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * OPT-O22-02: Strips foreign native binaries from sqlite-jdbc.jar for the desktop distribution.
 * Preserves classes, resources, and ONLY the native library matching the host target OS/arch.
 */
@CacheableTask
abstract class StripSqliteTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputJar: ConfigurableFileCollection

    @get:Input
    abstract val targetOs: Property<String>

    @get:Input
    abstract val targetArch: Property<String>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @TaskAction
    fun strip() {
        val inJar = inputJar.singleFile
        val outJar = outputJar.get().asFile
        outJar.parentFile.mkdirs()

        val os = targetOs.get().lowercase()
        val arch = targetArch.get().lowercase()
        val keepPrefix = when {
            os.startsWith("win") -> "org/sqlite/native/Windows/$arch/"
            os.startsWith("linux") -> "org/sqlite/native/Linux/$arch/"
            os.startsWith("mac") -> "org/sqlite/native/Mac/"
            else -> "org/sqlite/native/"
        }

        ZipFile(inJar).use { zipIn ->
            ZipOutputStream(FileOutputStream(outJar)).use { zipOut ->
                val entries = zipIn.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    if (name.startsWith("org/sqlite/native/")) {
                        if (!name.startsWith(keepPrefix) && name != "org/sqlite/native/") {
                            // Strip foreign native shared library
                            continue
                        }
                    }
                    val newEntry = ZipEntry(name)
                    newEntry.time = entry.time
                    zipOut.putNextEntry(newEntry)
                    zipIn.getInputStream(entry).use { input ->
                        input.copyTo(zipOut)
                    }
                    zipOut.closeEntry()
                }
            }
        }
    }
}
