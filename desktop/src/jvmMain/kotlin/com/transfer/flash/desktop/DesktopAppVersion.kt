package com.transfer.flash.desktop

/**
 * The desktop application version shown in Settings → About.
 *
 * Two sources, both derived from the same `desktopAppVersion` in `desktop/build.gradle.kts`:
 * - a packaged MSI/EXE launcher sets `jpackage.app-version` from `packageVersion`;
 * - the application jar carries `Implementation-Version` in its manifest (used by `:desktop:run`).
 *
 * The packaged value wins when present. A development launch from a classes directory has neither,
 * so it reports `dev` rather than inventing a release number. This replaces the hardcoded
 * `1.0.0-desktop` that shipped in 2.2.0 while its installers said 2.2.0.
 */
internal object DesktopAppVersion {
    const val UNKNOWN = "dev"

    fun resolve(jpackageVersion: String?, manifestVersion: String?): String =
        jpackageVersion?.trim()?.takeIf { it.isNotEmpty() }
            ?: manifestVersion?.trim()?.takeIf { it.isNotEmpty() }
            ?: UNKNOWN

    fun current(): String = resolve(
        jpackageVersion = System.getProperty("jpackage.app-version"),
        manifestVersion = DesktopAppVersion::class.java.`package`?.implementationVersion,
    )
}
