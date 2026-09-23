import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Turns AboutLibraries' `aboutlibraries.json` into `THIRD_PARTY_NOTICES.txt` (audit C1, ADR-043).
 *
 * The file is both the human-readable notice that ships in the APK and the installers, and the input
 * of the in-app "Open-source licences" screen (`ThirdPartyNotices.parse` in :ui:chat). The format is
 * therefore a contract. Change it only together with that parser and its test:
 *
 * ```
 * <intro lines>
 * COMPONENTS
 * - <name> <version> (<group:artifact>)
 *   License: <title>; <title>
 *   <website, optional>
 * LICENSE TEXTS
 * ==== <title> ====
 * <text>
 * ```
 *
 * **Compliance gate:** every component must reference at least one licence whose full text is
 * present. When a new dependency arrives with a licence we hold no text for, the build fails with its
 * name, rather than shipping a notice that silently omits it. The fix is in
 * `tools/licenses/fetch_license_texts.py`.
 */
@CacheableTask
abstract class ThirdPartyNoticesTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val libraryDefinitions: RegularFileProperty

    /** Shown in the header, e.g. "Android". */
    @get:Input
    abstract val platformName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parse(libraryDefinitions.get().asFile) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val libraries = root["libraries"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val licenses = (root["licenses"] as Map<String, Map<String, Any?>>?).orEmpty()

        fun text(hash: String): String? = (licenses[hash]?.get("content") as String?)?.trim()?.takeIf { it.isNotEmpty() }

        // Titles must be unique: the parser keys licence texts by title.
        val byName = licenses.entries.groupBy { (it.value["name"] as String?)?.takeIf(String::isNotBlank) ?: it.key }
        val titles = licenses.keys.associateWith { hash ->
            val name = (licenses[hash]?.get("name") as String?)?.takeIf(String::isNotBlank) ?: hash
            if (byName.getValue(name).size > 1) "$name ($hash)" else name
        }

        val sorted = libraries.sortedWith(
            compareBy({ (it["name"] as String? ?: it["uniqueId"] as String).lowercase() }, { it["uniqueId"] as String }),
        )
        val missing = sorted.filter { lib -> lib.licenseHashes().none { text(it) != null } }
            .map { "${it["uniqueId"]} (licences: ${it.licenseHashes().ifEmpty { listOf("none") }.joinToString()})" }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Third-party notices: no licence text for\n  " + missing.joinToString("\n  ") +
                    "\nAdd the text or an override with tools/licenses/fetch_license_texts.py (see ADR-043).",
            )
        }

        val used = sorted.flatMap { it.licenseHashes() }.filter { text(it) != null }.distinct()
            .sortedBy { titles.getValue(it).lowercase() }
        val out = StringBuilder()
        out.appendLine("Flash: third-party notices (${platformName.get()})")
        out.appendLine()
        out.appendLine("Flash is licensed under the Apache License 2.0 (see LICENSE and NOTICE in the source repository).")
        out.appendLine("It includes the third-party components below. This file is generated at build time from the")
        out.appendLine("resolved dependencies and config/aboutlibraries/. Native code carries no licence metadata of its")
        out.appendLine("own, so its notices are attached to the artifact that ships it.")
        out.appendLine()
        out.appendLine("COMPONENTS")
        for (lib in sorted) {
            val name = lib["name"] as String? ?: lib["uniqueId"] as String
            val version = (lib["artifactVersion"] as String?)?.let { " $it" }.orEmpty()
            out.appendLine("- $name$version (${lib["uniqueId"]})")
            out.appendLine("  License: " + lib.licenseHashes().map { titles[it] ?: it }.distinct().joinToString("; "))
            (lib["website"] as String?)?.takeIf { it.isNotBlank() }?.let { out.appendLine("  $it") }
        }
        out.appendLine()
        out.appendLine("LICENSE TEXTS")
        for (hash in used) {
            out.appendLine("==== ${titles.getValue(hash)} ====")
            out.appendLine(text(hash))
            out.appendLine()
        }

        val file = outputDir.get().file(FILE_NAME).asFile
        file.parentFile.mkdirs()
        file.writeText(out.toString())
    }

    private fun Map<String, Any?>.licenseHashes(): List<String> =
        (this["licenses"] as List<*>?).orEmpty().map { it.toString() }

    companion object {
        const val FILE_NAME: String = "THIRD_PARTY_NOTICES.txt"
    }
}
