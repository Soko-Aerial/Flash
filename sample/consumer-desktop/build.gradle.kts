// Throwaway desktop consumer — NOT published (no `maven-publish`, no publishing block).
// Phase 24 Step 2 / D9 = Option A: prove variant-aware resolution by depending on the ROOT
// coordinates (the same `com.transfer.flash:core-…:1.1.0` string an Android consumer writes)
// from mavenLocal() and showing a `kotlin("jvm")` build picks the `-jvm` artifact and compiles
// against the public API. The sibling `:sample:consumer` (umbrella) and `:sample:consumer-granular`
// (single-artifact) do the same for the Android variant and stay Android-only per D9 = A.
//
// Deliberately NO `implementation(project(":core:…"))` lines anywhere: a project dependency
// would resolve source-set-to-source-set and prove nothing about published metadata. Everything
// below must come from `mavenLocal()`.
plugins {
    kotlin("jvm")
}

kotlin {
    compilerOptions {
        // JVM_11, matching every other module in the repo (R10-adjacent consistency; also
        // keeps the Java/Kotlin target pair consistent — the build's JBR 21 toolchain can
        // compile down to 11).
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

// The toolchain pins the JDK (21, what this build runs on) for both compile tasks, and
// `compileJavaTaskOptions` pins the Java bytecode release to 11 to match Kotlin's jvmTarget —
// the same effective pair every other module in the repo uses (JBR 21 compiling down to
// JVM_11), eliminating the default-Java-target mismatch.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
}

dependencies {
    // Mirror of :sample:consumer's umbrella shape, resolved from the published tree:
    // core-engine's api exposure must make the whole public vocabulary compilable.
    implementation("com.transfer.flash:core-engine:2.0.0-beta")
    // Mirror of :sample:consumer-granular's single-artifact shape for the desktop tier:
    // core-network alone must re-expose core-common's FlashDevice through its api edge.
    implementation("com.transfer.flash:core-network:2.0.0-beta")
}
// No project-level `repositories {}`: the root settings' FAIL_ON_PROJECT_REPOS mode governs,
// and its mavenLocal()-first order (added for this module in Phase 24 Step 2) is what makes
// the published tree resolve.
