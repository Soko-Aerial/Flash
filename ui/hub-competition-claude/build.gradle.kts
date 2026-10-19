// Dev-only showcase launcher for the Hub UI/UX competition entries (packages
// `competition/novahub`, `competition/apex`, `competition/opencode`, all living in `:ui:chat`).
// The entries are design-only and are deliberately NOT reachable from the shipped app, so this
// module exists for exactly one job: open a desktop window that hosts them for judging.
//
// Shape copied from `:desktop` (a pure-JVM CONSUMER of the KMP library modules — KMP plugin with
// a single `jvm()` target, CMP plugin for the `compose.*` dependency DSL). It is never a
// dependency of anything and is not published.
//
// Run through the Gradle LOCK SCRIPT (docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md / AGENTS.md §36),
// never `gradlew` directly:
//
//   powershell -File tools\flash-gradle.ps1 :ui:hub-competition-claude:runShowcase   (entry picker)
//   powershell -File tools\flash-gradle.ps1 :ui:hub-competition-claude:runNova       (straight to NOVA)
//   powershell -File tools\flash-gradle.ps1 :ui:hub-competition-claude:runApex       (straight to Apex Zenith)
//   powershell -File tools\flash-gradle.ps1 :ui:hub-competition-claude:runDeck       (straight to Command Deck)

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        val jvmMain by getting {
            dependencies {
                // The three competition entries compile into this module's commonMain; their
                // public `HubCompetitionShowcaseScreen` entry points are what this launcher calls.
                implementation(project(":ui:chat"))
                // Flash design system for the picker window itself.
                implementation(project(":ui:theme"))

                // Desktop native windowing for the CURRENT OS — supplies
                // `androidx.compose.ui.window.Window`/`application` on a JVM (same as :desktop).
                implementation(compose.desktop.currentOs)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
            }
        }
    }
}

// JavaExec launchers, registered the same way `:desktop` registers its radio dev tools. No
// `compose.desktop.application {}` block is configured here, so there is no built-in `run` task
// to collide with and nothing packages this module.
val showcaseMainCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main")

fun registerShowcaseTool(taskName: String, entryId: String?, taskDescription: String) =
    tasks.register<JavaExec>(taskName) {
        group = "flash tools"
        description = taskDescription
        dependsOn("jvmMainClasses")
        classpath = files(showcaseMainCompilation.output.allOutputs, showcaseMainCompilation.runtimeDependencyFiles)
        mainClass.set("com.transfer.flash.ui.hub.competition.claude.ShowcaseLauncherAppKt")
        jvmArgs("-Xms64m", "-Xmx1g")
        if (entryId != null) {
            // The launcher opens this entry directly (Esc still returns to the picker).
            args(entryId)
        }
    }

registerShowcaseTool("runShowcase", null, "Opens the Hub competition picker window (NOVA / Apex Zenith / Command Deck).")
registerShowcaseTool("runNova", "nova", "Runs the NOVA entry showcase directly (Esc returns to the picker).")
registerShowcaseTool("runApex", "apex", "Runs the Apex Zenith entry showcase directly (Esc returns to the picker).")
registerShowcaseTool("runDeck", "deck", "Runs the Command Deck entry showcase directly (Esc returns to the picker).")
