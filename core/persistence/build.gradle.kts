plugins {
    // `com.android.library` is INCOMPATIBLE with the Kotlin Multiplatform plugin under
    // AGP 9+. A converted module swaps it for these two rather than adding to it.
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.ksp)
    // Phase 09B-1: the Room Gradle plugin replaces `ksp { arg("room.schemaLocation", …) }`.
    // A single global KSP arg makes kspAndroidMain and kspKotlinJvm write the same schema path,
    // which can race under `--max-workers=2`; the plugin gives each KSP task its own staging
    // directory under `build/intermediates/room/schemas/<kspTaskName>/` and copies from there.
    alias(libs.plugins.androidx.room)
    `maven-publish`
}

kotlin {
    // Phase 3 Task 3.2, unchanged by the conversion: strict explicit-API mode.
    // See docs/publishing/PHASE-03-api-surface.md.
    //
    // NOTE (pre-existing, still true): the Room data layer (entities, DAOs, FlashDatabase) is
    // transitively forced public because :app wires the DB directly via
    // FlashDatabaseOpener → FlashDatabase → *Dao accessors. Gating it behind @FlashInternalApi
    // was Phase 4 work and did not happen; it is not 09B-1's job either (R1).
    explicitApi()

    // `db/FlashDatabaseConstructor` is an `expect` OBJECT: Room's KSP processor emits the body per
    // target, so the seam has to be a classifier rather than a function. expect/actual classifiers
    // are still Beta (KT-61573) and warn per declaration site, so the flag is set at the extension
    // level to cover every target — same reason as :core:common, :core:security and :core:discovery.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.transfer.flash.core.persistence"
        compileSdk = 35

        // NOT inside `defaultConfig { }` any more — the KMP Android target is variant-free.
        minSdk = 24

        // Was `defaultConfig { consumerProguardFiles("consumer-rules.pro") }`. Rules are dropped
        // in SILENCE if this is omitted. This module's file is comment-only, but it documents why
        // Room and SQLCipher need no first-party keep rules, so it must keep shipping.
        optimization {
            consumerKeepRules.apply {
                file("consumer-rules.pro")
                publish = true
            }
        }

        // Replaces `buildTypes { release { … } }`: the target publishes a single variant, so
        // instead of declaring build types it states which one local project deps resolve from.
        // Every dependency of this module (:core:common) is already KMP and variant-free, so this
        // is declared for template symmetry rather than because anything needs selecting.
        localDependencySelection {
            selectBuildTypeFrom.set(listOf("release"))
        }

        // Replaces `compileOptions { sourceCompatibility/targetCompatibility }`.
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }

        // Creates `androidHostTest` + the `testAndroidHostTest` task (the KMP replacement for
        // `testDebugUnitTest`). `isIncludeAndroidResources` is the replacement for
        // `testOptions { unitTests { isIncludeAndroidResources = true } }`, which has no KMP
        // equivalent outside this block — FlashDatabaseInvariantTest is Robolectric and needs it.
        withHostTest {
            isIncludeAndroidResources = true
        }

        // Was `defaultConfig { testInstrumentationRunner = … }`. No src/androidTest exists;
        // the runner is declared so the template is complete.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    // Desktop/Linux/CI target. Plain `jvm()`, never `jvm("desktop")` — CONVENTIONS.md R5.
    // `compileKotlinJvm` cannot see android.jar, which is what proves the 26 relocated entity/DAO
    // files are genuinely Android-free now that they live in commonMain.
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            // DAOs return kotlinx Flow, so coroutines must be `api` — `implementation` would keep
            // those return types off a downstream consumer's compile classpath.
            api(libs.kotlinx.coroutines.core)
            // `api`, NOT `implementation` (which is what the pre-KMP build file used): with
            // FlashDatabase, the 11 @Dao interfaces and the 11 @Entity classes all public, and
            // FlashDatabase extending RoomDatabase, a consumer cannot touch this module's surface
            // without androidx.room on their compile classpath. Verified by gate 7
            // (:sample:consumer), which is the same check Phase 08 used for coroutines.
            api(libs.androidx.room.runtime)
            // The MULTIPLATFORM driver tier (androidx.sqlite:sqlite). Room's generated `_Impl`
            // code already imports SQLiteConnection/SQLiteStatement from here — verified on disk
            // before this phase started. `implementation`: no androidx.sqlite type appears in this
            // module's own public signatures.
            implementation(libs.androidx.sqlite.core)
        }
        androidMain.dependencies {
            // sqlite-ktx is the Android-only SUPPORT layer (`androidx.sqlite.db.*`). It cannot go
            // in commonMain and it is not interchangeable with androidx.sqlite:sqlite above.
            // FlashMigrations overrides `migrate(db: SupportSQLiteDatabase)` and stays here (R8).
            implementation(libs.androidx.sqlite)
            // net.zetetic SQLCipher for Android — the production encryption path in
            // FlashDatabaseOpener. Android-only by construction (JNI .so).
            implementation(libs.sqlcipher.android)
            // The settings tier (FlashSettingsDataStore, DiscoveryModeSetting) stays in
            // androidMain until 09B-3: it uses java.io.File and androidx.datastore.
            implementation(libs.androidx.datastore.preferences)
            // room-ktx is Android-only (it carries the coroutine/Support glue). The KMP-safe part
            // of Room is room-runtime in commonMain above.
            implementation(libs.androidx.room.ktx)
        }
        // Runs on BOTH the Android host-test JVM and the desktop jvm() target, so RetentionPolicy
        // is executed on each, not merely compiled (CONVENTIONS.md R3.1).
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // Phase 09B-2 (D5 = C answered 2026-09-14): the ENCRYPTED file-backed JVM driver tier.
        // This is the source set PHASE-09B recorded as never created ("`jvmMain` was never created
        // for this module", amendment 8) — the one artifact 09B-2 was blocked on. `sqlite-jdbc-crypt`
        // is an encrypted SQLite build, so unlike `androidx-sqlite-bundled` (jvmTest, `:memory:`
        // only) this one may legally open a FILE. Adding it here is what makes D5 = C satisfiable
        // on desktop at all; the driver adapter over it lives in `src/jvmMain`.
        jvmMain.dependencies {
            implementation(libs.sqlite.jdbc.crypt)
        }
        // FlashDatabaseInvariantTest is Robolectric + JUnit 4 and drives the Android Support
        // stack; the two settings suites are Android-only for the same reason as their subjects.
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(libs.androidx.room.testing)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            // TEST-ONLY. BundledSQLiteDriver is UNENCRYPTED, so D5 = C's charter confines it to
            // in-memory (":memory:") use inside this source set. Declared here and NOWHERE else so
            // no product code can reach it; PHASE-09B verification gate 5 greps for exactly that.
            implementation(libs.androidx.sqlite.bundled)
        }
    }
}

// Per-target KSP. A bare `ksp(...)` does NOT reach a KMP target's compilation — the configuration
// names are derived from the target names, so Room's processor has to be added to each.
dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspJvm", libs.androidx.room.compiler)
}

// Replaces `ksp { arg("room.schemaLocation", …); arg("room.incremental", …) }`. The schema
// directory is versioned in-repo (C1.7) and must not move: PHASE-09B gate 6 requires the exported
// schema to stay byte-identical, because that is what discharges the narrow R8 exception taken for
// the single `@ConstructedBy` line on FlashDatabase.kt.
//
// HOW TO READ THIS TASK'S OUTPUT. The plugin passes Room the two INTERNAL options
// `room.internal.schemaInput` (this directory) and `room.internal.schemaOutput` (a per-KSP-task
// staging directory), and Room writes to the output only when the schema it computed DIFFERS from
// the input. So `> Task :core:persistence:copyRoomSchemas NO-SOURCE` and an empty
// `build/intermediates/room/schemas/` are the SUCCESS signal — they mean "no schema drift" — and are
// indistinguishable from "export was never configured". To get positive evidence instead, point KSP
// at a scratch directory (`ksp { arg("room.schemaLocation", "$projectDir/build/schema-probe") }`,
// which takes precedence), rerun `kspAndroidMain`/`kspKotlinJvm`, and `cmp` the result against the
// file here. Phase 09B-1 did exactly that for both targets; see the migration log.
room {
    schemaDirectory("$projectDir/schemas")
}

publishing {
    publications {
        // KMP generates the publications itself (root `kotlinMultiplatform`, plus one per target),
        // so a module must NOT `register<MavenPublication>("release")` any more. Their default
        // artifactIds derive from the project name (`persistence`, `persistence-android`,
        // `persistence-jvm`); rename in place to keep the `core-persistence` coordinate that 1.1.0
        // consumers already use. Version and group still come from the root build file.
        withType<MavenPublication>().configureEach {
            artifactId = artifactId.replace("persistence", "core-persistence")
        }
    }
}
