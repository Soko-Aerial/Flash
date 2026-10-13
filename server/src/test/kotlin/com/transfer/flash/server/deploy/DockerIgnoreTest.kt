package com.transfer.flash.server.deploy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * REV-12: Docker matches a `.dockerignore` pattern without a leading `**&#47;` only at the ROOT of the build context, so a key in a
 * sub-directory slipped into the image build. This test reads the real file and checks, with a small matcher that follows Docker's rules
 * for the pattern shapes we use, that every kind of secret is ignored wherever it sits.
 */
class DockerIgnoreTest {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return requireNotNull(dir) { "repository root not found from ${System.getProperty("user.dir")}" }
    }

    private fun patterns(): List<Regex> = File(repoRoot(), ".dockerignore").readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
        .map { toRegex(it.removeSuffix("/")) }

    /** Docker's matching for our shapes: `**&#47;` = any directories (or none), `*` = within one path segment, otherwise from the root. */
    private fun toRegex(pattern: String): Regex {
        val out = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            when {
                pattern.startsWith("**/", i) -> { out.append("(?:.*/)?"); i += 3 }
                pattern[i] == '*' -> { out.append("[^/]*"); i++ }
                else -> { out.append(Regex.escape(pattern[i].toString())); i++ }
            }
        }
        // A pattern that names a directory also covers everything below it.
        return Regex("^$out(?:/.*)?$")
    }

    private fun ignored(path: String) = patterns().any { it.matches(path) }

    @Test
    fun secretsAreIgnoredAtTheRootAndInEverySubdirectory() {
        val secrets = listOf(
            "keystore.properties", "local.properties", "release.jks", "upload.keystore", "bundle.p12", "pkcs8.pk8", "root.pem",
            "root-identity.pk8", "root-identity.json", "setup-token.txt", "data.key", "server.key", "delegation.cert", "tools/.tavily_api_key",
            ".env", ".env.production",
        )
        for (name in secrets) {
            for (prefix in listOf("", "server/", "core/security/", "a/b/c/", "server/data/")) {
                assertTrue(ignored(prefix + name), "'$prefix$name' would enter the Docker build context: add a **/ pattern to .dockerignore")
            }
        }
    }

    @Test
    fun buildOutputAndToolStateAreIgnored() {
        for (path in listOf(".gradle-user-home/caches/x", ".gradle/x", "build/x", "server/build/x", ".git/config", ".claude/settings.json")) {
            assertTrue(ignored(path), "$path should not be sent to the Docker daemon")
        }
    }

    @Test
    fun sourcesTheImageBuildNeedsAreNotIgnored() {
        for (path in listOf("server/src/main/kotlin/Main.kt", "core/hub-protocol/src/commonMain/kotlin/A.kt", "settings.gradle.kts", "gradle/libs.versions.toml", "server/Dockerfile")) {
            assertTrue(!ignored(path), "$path is needed by `docker build` and must not be ignored")
        }
    }
}
