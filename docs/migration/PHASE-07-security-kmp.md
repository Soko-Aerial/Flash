# Phase 07 — KMP conversion: `core:security`

**Blocked by:** Phase 06 (the pilot — it discovers the DSL shape, the test-task name,
and the publication rewrite that this phase copies). **Decisions: D1 must already be
answered** (it gated Phase 06). This phase is written for **D1 = A**
(`jvmAndAndroidMain`). If D1 = B, see the box in "The source-set strategy" before doing
anything — the work is several times larger and different.
**Risk: MEDIUM.** More files than the pilot (17 production, 13 test), and the module is
**R8-sensitive** (crypto + one wire format). But there is a decisive simplification: under
D1 = A this module needs **zero `expect`/`actual` seams**. Every file either is pure
Kotlin, or uses only `java.*`/`javax.*` (which live happily in `jvmAndAndroidMain`), or is
an Android-only class that simply moves to `androidMain`. Nothing is rewritten.

## What this phase is actually for

Convert `:core:security` from `com.android.library` to the KMP plugin with an Android
target and a desktop `jvm()` target, **moving files into source sets without editing their
contents**. When it is done:

- `:core:security:compileKotlinJvm` compiles — meaning the entire JCA crypto surface
  (ECDH, AES-256-GCM, HKDF, SHA-256 fingerprints, the SAS numeric-comparison code, the
  pairing protocol and its pure state machine) is proven to build for **desktop**. This is
  the first time Flash's cryptography compiles off Android.
- The Android unit tests still run and still pass **at the same count** as before.
- `:core:security` still publishes as `core-security`.
- `:app:assembleDebug` still resolves it.

`core:security` is the right first module after the pilot because it is a **dependency
leaf**: its only project dependency is `api(project(":core:common"))`, which Phase 06
already converted. So its `jvm()` target has everything it needs the moment Phase 06 lands
— nothing downstream has to exist yet. (Verified in `core/security/build.gradle.kts`:
the only `project(...)` dependency is `:core:common`.)

## Preconditions — do not start until all are true

1. **Phase 06 is merged and logged.** Open `docs/migration/logs/migration.md` and confirm
   there is a Phase 06 entry marked complete. If there is none, STOP — this phase copies
   Phase 06's discovered facts and cannot run first.
2. **D1 is answered `A` in `docs/migration/DECISIONS.md`.** Find the line
   `## D1 — ...` and read its `**ANSWER:**`. If it still says `_pending_`, STOP and tell
   the human: "Phase 06 could not have completed without D1; the log and DECISIONS.md
   disagree." You may **not** pick D1 yourself (CONVENTIONS.md; DECISIONS.md preamble).
   If the answer is `B`, jump to the **D1 = B box** below before doing anything else.
3. **The working tree is clean** on the migration branch (`git status` shows nothing you
   did not intend). Converting a module touches its whole file layout; start from a known
   state so `git mv` history is legible and rollback is a single `git checkout`.
4. **Phase 06's log records these five facts.** You will read the exact strings from the
   Phase 06 log in Step 1; this phase never re-derives them:
   - `KMP_ANDROID_DSL_SNIPPET` — the exact `androidLibrary { }` (or `kotlin { androidLibrary { } }`) block shape that compiled in Phase 06, including the host-test opt-in.
   - `KMP_HOST_TEST_BLOCK` — the sub-block that turned Android host (unit) tests on.
   - `KMP_JVM_TARGET_DSL` — how the desktop target was declared (expected: `jvm()`).
   - `KMP_LOCAL_DEP_SELECTION` — the `localDependencySelection`/variant-selection lines, if Phase 06 needed any.
   - `ANDROID_UNIT_TEST_TASK` — the exact Gradle task name that runs Android unit tests (e.g. `:core:common:testDebugUnitTest` **or**, under the KMP Android plugin, `:core:common:testAndroidHostTest` — Phase 06 recorded which).

> ### D1 = B box — read only if D1 answered `B`
> Under D1 = B (**strict `commonMain`, stdlib + coroutines only**) this phase is a
> different, far larger job. Every file listed under **jvmAndAndroidMain** below cannot
> use `java.*`/`javax.*` from shared code. Each one must instead be split into a
> `commonMain` declaration plus **`androidMain` + `jvmMain` `actual`s** that wrap the
> *identical* JCA calls — i.e. the entire crypto surface (`FlashCrypto`,
> `SoftwareFlashCrypto`, `E2eFrameCodec`, `Hkdf`, `FlashFingerprint`,
> `NumericComparisonCode`, `TofuPolicy`) grows an `expect`/`actual` seam, and every
> security property must be re-verified on both platforms. That is weeks of R8-sensitive
> work, not an afternoon. **Do not attempt it from this file.** Confirm with the human
> that they truly chose B (it closes iOS in, but pays a large up-front cost here), then
> derive the seams from PHASE-06's D1 = B guidance. The rest of this document assumes D1 = A.

## Verified starting state (read this, do not assume)

These facts were confirmed by reading the module on 2026-08-30. If any is no longer true
when you run the phase, stop and re-inventory — a later phase or a merge may have moved a
file.

**Current `core/security/build.gradle.kts`:**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.transfer.flash.core.security"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    buildTypes { release { /* no minify at library level */ } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    publishing { singleVariant("release") { withSourcesJar() } }
}

kotlin { explicitApi() }

dependencies {
    api(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "core-security"
            afterEvaluate { from(components["release"]) }
        }
    }
}
```

**Directory facts:**

- Production sources live under `core/security/src/main/java/com/transfer/flash/core/security/`
  — 17 `.kt` files (enumerated in the placement table). Despite the `java/` directory name
  they are all Kotlin; KMP wants them under `.../src/<sourceSet>/kotlin/...`.
- Tests live under `core/security/src/test/java/com/transfer/flash/core/security/` — 13
  `.kt` files (11 test classes + `testutil/FakeSharedPreferences.kt` +
  `testutil/FakeClock.kt`). They are JUnit4.
- There is **no** `AndroidManifest.xml`, no `src/main/res/`, and no `src/main/assets/` in
  this module — the same situation as `core:common` in Phase 06, so `androidResources` do
  **not** need enabling.
- `consumer-rules.pro` exists but is **empty of rules** (comment-only: "No keep rules are
  needed"). `proguard-rules.pro` exists (library-consumer file, not applied at library
  build time).
- **Dependency-usage findings (grep of `src/main`, excluding `media-downloader-main/`,
  `build/`, `.git/`, `docs/` per CONVENTIONS R11):**
  - `kotlinx.coroutines` **is used** — `pairing/FlashPairingProtocol.kt` imports
    `kotlinx.coroutines.flow.*` (and `channels.BufferOverflow`). All of these are in the
    coroutines **common** artifact, so they compile for `jvm()` unchanged.
  - `androidx.core.ktx` and `androidx.lifecycle.runtime.ktx` have **zero references** in
    `src/main`. They are Android AARs. This phase **relocates** them to
    `androidMain.dependencies` (see the build rewrite) rather than deleting them, so
    Android's runtime classpath is byte-identical and the desktop target never sees an
    AAR it cannot consume. (A later cleanup phase may delete `core.ktx` if it stays
    unused; deleting is out of scope here — this phase changes layout, not behaviour.)

## The source-set strategy (D1 = A)

Four source sets, wired exactly as Phase 06 established:

```
commonMain  ──────────────┐  (pure Kotlin only; no java/javax/android)
                          ▼
                 jvmAndAndroidMain   (java.*/javax.* allowed; shared by both JVM targets)
                    ┌─────┴─────┐
                    ▼           ▼
               androidMain    jvmMain   (platform-specific; android.* only in androidMain)
```

Wiring (goes in the `kotlin { sourceSets { … } }` block — see Step 2):

```kotlin
val jvmAndAndroidMain = create("jvmAndAndroidMain")
jvmAndAndroidMain.dependsOn(getByName("commonMain"))
getByName("androidMain").dependsOn(jvmAndAndroidMain)
getByName("jvmMain").dependsOn(jvmAndAndroidMain)
```

**Placement rule (memorise — it is the one thing a weak model gets wrong):**

1. If a file imports `android.*` → **androidMain**.
2. Else if a file imports `java.*` or `javax.*` → **jvmAndAndroidMain**.
3. Else (pure Kotlin/stdlib/coroutines) it *could* be **commonMain** — **but only if every
   symbol it references also lives in commonMain.** A file with no `java`/`javax`/`android`
   import that calls into a `jvmAndAndroidMain` type must itself go to **jvmAndAndroidMain**
   (a `commonMain` file cannot see a `jvmAndAndroidMain` symbol; the compile would fail).
   This **transitive rule** is why two import-clean files below are *not* in commonMain.

## The verified file-placement table

Paths below are relative to `core/security/src/main/java/com/transfer/flash/core/security/`.
Every file **moves unedited** (`git mv`). "R8" marks files that are crypto or wire formats
protected by keep-rules elsewhere — you may relocate them but must **not** change a byte of
their contents, package, or public shape (see "Do NOT").

### → `commonMain` (4 files) — pure Kotlin, all referenced symbols are in commonMain

| File | Why commonMain is safe |
|---|---|
| `identity/FlashIdentity.kt` | `data class FlashIdentity(deviceId, friendlyName)`; references only `FlashDeviceId` — a `core:common` **commonMain** type. No `java`/`javax`/`android`. |
| `identity/FlashIdentityStore.kt` | Interface; returns `FlashIdentity` and `FlashResult<Unit>` — both commonMain (`FlashResult` is `core:common` commonMain). No platform import. |
| `trust/FlashTrustStore.kt` | Interface over `FlashDeviceId` / `FlashResult` — both commonMain. No platform import. |
| `pairing/FlashPairingFrames.kt` | **R8 (wire).** `sealed interface FlashPairingFrame` + `PairRequest`/`PairAccept`/`PairConfirm`/`Paired`. Zero imports. Move only. |

### → `jvmAndAndroidMain` (10 files) — use `java.*`/`javax.*`, or transitively depend on a file that does

| File | Trigger |
|---|---|
| `crypto/FlashCrypto.kt` | `java.security.*` + `javax.crypto.KeyAgreement` in its interface signatures (KeyPair, PrivateKey, PublicKey, Signature, KeyFactory, KeyPairGenerator, ECGenParameterSpec, X509EncodedKeySpec). |
| `crypto/SoftwareFlashCrypto.kt` | **R8 (crypto).** The **desktop** `FlashCrypto` provider. `java.security.*` (KeyPair/PrivateKey/PublicKey …). |
| `crypto/E2eFrameCodec.kt` | **R8 (crypto + wire).** `javax.crypto.Cipher` / `GCMParameterSpec` / `SecretKeySpec` + `java.security.SecureRandom`. |
| `crypto/Hkdf.kt` | **R8 (crypto).** `javax.crypto.Mac` / `SecretKeySpec`. |
| `crypto/FlashFingerprint.kt` | **R8 (crypto).** `java.security.MessageDigest`. |
| `pairing/NumericComparisonCode.kt` | **R8 (crypto).** `java.security.MessageDigest` (SAS numeric comparison). |
| `pairing/FlashPairingProtocol.kt` | `java.security.SecureRandom` + `java.util.UUID` + `kotlinx.coroutines.flow.*`. |
| `pairing/PairingSessionStateMachine.kt` | **Transitive.** No `java`/`javax`/`android` import — but references `NumericComparisonCode` (jvmAndAndroidMain). Rule 3 ⇒ jvmAndAndroidMain. |
| `trust/pinned/TofuPolicy.kt` | **R8 (crypto).** `java.security.MessageDigest`. |
| `trust/pinned/LegacyTrustMigration.kt` | **Transitive.** No platform import — but references `TofuPolicy.LEGACY_UNBOUND_FINGERPRINT` (jvmAndAndroidMain). Rule 3 ⇒ jvmAndAndroidMain. |

### → `androidMain` (3 files) — import `android.*`

| File | Trigger |
|---|---|
| `crypto/KeystoreFlashCrypto.kt` | `class KeystoreFlashCrypto(context: Context) : FlashCrypto`; `android.security.keystore.*`, `android.os.Build`. The Android hardware-backed crypto provider. |
| `identity/AndroidPreferencesIdentityStore.kt` | `android.content.Context` / `SharedPreferences`, `android.os.Build`, `java.util.UUID`. |
| `trust/AndroidPreferencesTrustStore.kt` | `android.content.Context` / `SharedPreferences`. |

> **Trap — `crypto/FlashCrypto.kt:21` mentions `android.security.keystore` in a KDoc
> sentence, not in an `import`.** A grep for `android` will "hit" that comment line. The
> file has **no** `android.*` import and is a platform-neutral interface → it belongs in
> **jvmAndAndroidMain**, not androidMain. Read the import block, not comments.

> **The two transitive files are the single most likely mistake.**
> `PairingSessionStateMachine.kt` and `LegacyTrustMigration.kt` look "pure" (no
> `java`/`javax`/`android` import) so a grep-only heuristic drops them in `commonMain`.
> They then fail to compile because `commonMain` cannot see `NumericComparisonCode` /
> `TofuPolicy`, which are in `jvmAndAndroidMain`. Both files go to **jvmAndAndroidMain**.
> If `:core:security:compileCommonMainKotlinMetadata` (or the metadata compile) complains
> about an "unresolved reference: NumericComparisonCode / TofuPolicy / LEGACY_UNBOUND_…",
> you placed one of these in commonMain — move it down one level.

### Tests → `androidHostTest` (all 13, unmodified)

Every test stays an Android host (unit) test — moved, never rewritten — so the pass count
can be compared **equal** to the pre-conversion baseline (Phase 06's #1 silent-failure trap
was a green build running **zero** tests; the count check is how you catch it). They move
from `src/test/java/.../security/` to `src/androidHostTest/kotlin/.../security/`:

`FlashTrustStoreTest`, `FlashIdentityStoreTest`, `HkdfTest`, `SoftwareFlashCryptoTest`,
`FlashFingerprintTest`, `TofuPolicyTest`, `LegacyTrustMigrationTest`,
`NumericComparisonCodeTest`, `E2eFrameCodecTest`, `PairingSessionStateMachineTest`,
`DefaultFlashPairingProtocolTest`, plus `testutil/FakeSharedPreferences.kt` (implements
`android.content.SharedPreferences` — needs the Android stubs, which `androidHostTest`
has) and `testutil/FakeClock.kt`.

They stay in `androidHostTest` (not a shared `commonTest`) because `FakeSharedPreferences`
depends on `android.*`. Sharing crypto tests to a `jvmTest` set is a **later, optional**
improvement — do not attempt it here; it changes the test topology and risks the count.

## Why this module needs no `expect`/`actual`

Phase 06 introduced one `expect fun platformLogSink()` because `core:common` had a genuine
platform fork (Android `Log` vs desktop stdout). **`core:security` has no such fork under
D1 = A.** Reason: everything that differs by platform is a *whole class* that is already
Android-only (`KeystoreFlashCrypto`, `AndroidPreferencesIdentityStore`,
`AndroidPreferencesTrustStore`) and simply lives in `androidMain`. The desktop side does
not need an `actual` for these because the **desktop provider already exists as ordinary
JVM code**: `SoftwareFlashCrypto` is a complete `FlashCrypto` implementation using only
`java.security`/`javax.crypto`, so it sits in `jvmAndAndroidMain` and serves the `jvm()`
target directly. There is nothing to declare `expect` for.

Consequence: **`jvmMain` gets zero files in this phase.** The `jvm()` target compiles
entirely from `commonMain + jvmAndAndroidMain`. That is expected and correct — do not
invent placeholder `jvmMain` files to "balance" it.

> Desktop implementations of `FlashIdentityStore` and `FlashTrustStore` (a
> filesystem/DataStore-free desktop identity + trust persistence) are **out of scope for
> Phase 07** and are handled when desktop persistence is designed (Phase 09 / the desktop
> phases, per D5). Phase 07 only proves the crypto + protocol **compile** for desktop; it
> does not wire a runnable desktop security stack.

## Steps

Do them in order. Each step ends with a check; if a check fails, fix it before moving on.
All Gradle commands include `--no-configuration-cache` (the KMP Android plugin is not
config-cache-clean mid-migration; Phase 06 established this).

### Step 1 — Pull Phase 06's discovered facts

Open `docs/migration/logs/migration.md`, find the Phase 06 entry, and copy out the five
recorded strings (`KMP_ANDROID_DSL_SNIPPET`, `KMP_HOST_TEST_BLOCK`, `KMP_JVM_TARGET_DSL`,
`KMP_LOCAL_DEP_SELECTION`, `ANDROID_UNIT_TEST_TASK`). You will paste the DSL snippets into
Step 3 verbatim and use `ANDROID_UNIT_TEST_TASK` in Step 7. **Do not re-invent the DSL** —
if the log lacks any of these, stop and finish Phase 06's logging first; guessing the
Android-KMP DSL is the fastest way to a broken module.

### Step 2 — Record the baseline test count (before you touch anything)

While the module is still `com.android.library`, run its current unit tests and record how
many ran. This number is the contract for Step 7.

```bash
./gradlew :core:security:testDebugUnitTest --no-configuration-cache
```

Open the HTML report it prints
(`core/security/build/reports/tests/testDebugUnitTest/index.html`) or the XML under
`core/security/build/test-results/testDebugUnitTest/` and record the **total tests** count
as `SECURITY_TEST_BASELINE`. Write it into your working notes now — you will assert the
post-conversion count equals it. (If `testDebugUnitTest` is not the right task name in this
repo, use whatever Android unit-test task exists today; the point is a number to match.)

### Step 3 — Rewrite `core/security/build.gradle.kts`

Replace the whole file. Paste the Phase-06 snippets where marked. The structure below is
fixed; the `androidLibrary { }` interior and the publication rewrite come **verbatim from
Phase 06**.

**Part 1 — plugins, `kotlin { }` targets, source-set wiring:**

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library) // exact alias Phase 06 used
    `maven-publish`
}

kotlin {
    explicitApi()   // preserved from the old build; ADR-023 keeps strict public API

    // ── Android target ── paste KMP_ANDROID_DSL_SNIPPET from the Phase 06 log verbatim.
    //    It declares namespace = "com.transfer.flash.core.security", compileSdk = 35,
    //    minSdk = 24, includes KMP_HOST_TEST_BLOCK (turns Android host tests ON), and
    //    carries the consumer-proguard wiring Phase 06 used. Do not paraphrase it.
    androidLibrary {
        namespace = "com.transfer.flash.core.security"
        compileSdk = 35
        minSdk = 24
        // <<PASTE KMP_HOST_TEST_BLOCK HERE>>
        // <<PASTE Phase 06's consumer-proguard line here (consumer-rules.pro is empty,
        //    but keep the wiring identical to Phase 06 so nothing regresses)>>
    }

    jvm()   // desktop target == KMP_JVM_TARGET_DSL (plain jvm(), never jvm("desktop"))

    sourceSets {
        val jvmAndAndroidMain = create("jvmAndAndroidMain")
        jvmAndAndroidMain.dependsOn(getByName("commonMain"))
        getByName("androidMain").dependsOn(jvmAndAndroidMain)
        getByName("jvmMain").dependsOn(jvmAndAndroidMain)
        // dependencies go in Part 2
    }
}
```

**Part 2 — dependencies (inside the same `sourceSets { }`) and publishing:**

```kotlin
        // …inside kotlin { sourceSets { …the wiring from Part 1… } }

        getByName("commonMain").dependencies {
            api(project(":core:common"))          // the only project dep; commonMain-safe
            api(libs.kotlinx.coroutines.core)      // multiplatform; used by FlashPairingProtocol
        }

        getByName("androidMain").dependencies {
            // Relocated from the old top-level `dependencies {}`. Grep found ZERO uses of
            // either in src/main, but they are Android AARs kept for parity (Main
            // dispatcher / ktx). They must NOT be visible to the jvm() target. Do not
            // delete here — layout change only.
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }

        getByName("androidHostTest").dependencies {
            implementation(libs.junit)                     // JUnit4; tests are Android host tests
            implementation(libs.kotlinx.coroutines.test)
        }

        // <<PASTE KMP_LOCAL_DEP_SELECTION HERE if Phase 06 recorded one>>
```

```kotlin
publishing {
    publications {
        // The KMP plugin auto-creates one publication per target (root "kotlinMultiplatform"
        // + "android" + "jvm"). Apply the SAME artifactId rewrite Phase 06 recorded so the
        // artifact keeps the `core-security` prefix across all variants:
        withType<MavenPublication>().configureEach {
            artifactId = artifactId.replace(project.name, "core-security")
        }
    }
}
```

**What changed vs the old file, and why it is safe:**

- `com.android.library` → `kotlin.multiplatform` + `com.android.kotlin.multiplatform.library`.
  Forced: AGP 9 makes the old library plugin incompatible with KMP in one module
  (AUDIT CORRECTION 1).
- `android { … buildTypes/compileOptions/publishing.singleVariant … }` is **gone** — the
  KMP Android plugin has no build variants and no `singleVariant`. Host tests replace it
  via `KMP_HOST_TEST_BLOCK`. The old `publishing.singleVariant("release"){withSourcesJar()}`
  is not portable; sources jars are handled by the KMP publication (Phase 06's rewrite).
- The old single `register<MavenPublication>("release"){ artifactId="core-security" }`
  becomes the prefix rewrite, because KMP emits several publications, not one.
- `explicitApi()` stays. Coroutines stays `api` (public `Flow`s in the pairing API).
- No `expect`/`actual`, no new dependency, no version bump (CONVENTIONS R10).

### Step 4 — Move the production sources (`git mv`, zero content edits)

Use `git mv` so history follows the file and the diff shows a pure rename. **Create the
destination package directories first** (`git mv` will not create deep parents on every
platform). Run these from the **repo root**. Nothing in these files changes — same package
declaration, same bytes — so no import anywhere (in this module or its consumers) needs
touching.

```bash
cd core/security/src

# destination package roots
CM=commonMain/kotlin/com/transfer/flash/core/security
JA=jvmAndAndroidMain/kotlin/com/transfer/flash/core/security
AM=androidMain/kotlin/com/transfer/flash/core/security
SRC=main/java/com/transfer/flash/core/security

mkdir -p "$CM/identity" "$CM/trust" "$CM/pairing"
mkdir -p "$JA/crypto" "$JA/pairing" "$JA/trust/pinned"
mkdir -p "$AM/crypto" "$AM/identity" "$AM/trust"
```

```bash
# ── commonMain (4) ──
git mv "$SRC/identity/FlashIdentity.kt"        "$CM/identity/FlashIdentity.kt"
git mv "$SRC/identity/FlashIdentityStore.kt"   "$CM/identity/FlashIdentityStore.kt"
git mv "$SRC/trust/FlashTrustStore.kt"         "$CM/trust/FlashTrustStore.kt"
git mv "$SRC/pairing/FlashPairingFrames.kt"    "$CM/pairing/FlashPairingFrames.kt"
```

```bash
# ── jvmAndAndroidMain (10) — includes the two TRANSITIVE files ──
git mv "$SRC/crypto/FlashCrypto.kt"                  "$JA/crypto/FlashCrypto.kt"
git mv "$SRC/crypto/SoftwareFlashCrypto.kt"          "$JA/crypto/SoftwareFlashCrypto.kt"
git mv "$SRC/crypto/E2eFrameCodec.kt"                "$JA/crypto/E2eFrameCodec.kt"
git mv "$SRC/crypto/Hkdf.kt"                         "$JA/crypto/Hkdf.kt"
git mv "$SRC/crypto/FlashFingerprint.kt"             "$JA/crypto/FlashFingerprint.kt"
git mv "$SRC/pairing/NumericComparisonCode.kt"       "$JA/pairing/NumericComparisonCode.kt"
git mv "$SRC/pairing/FlashPairingProtocol.kt"        "$JA/pairing/FlashPairingProtocol.kt"
git mv "$SRC/pairing/PairingSessionStateMachine.kt"  "$JA/pairing/PairingSessionStateMachine.kt"
git mv "$SRC/trust/pinned/TofuPolicy.kt"             "$JA/trust/pinned/TofuPolicy.kt"
git mv "$SRC/trust/pinned/LegacyTrustMigration.kt"   "$JA/trust/pinned/LegacyTrustMigration.kt"
```

```bash
# ── androidMain (3) ──
git mv "$SRC/crypto/KeystoreFlashCrypto.kt"               "$AM/crypto/KeystoreFlashCrypto.kt"
git mv "$SRC/identity/AndroidPreferencesIdentityStore.kt" "$AM/identity/AndroidPreferencesIdentityStore.kt"
git mv "$SRC/trust/AndroidPreferencesTrustStore.kt"       "$AM/trust/AndroidPreferencesTrustStore.kt"
```

After this, `main/java/.../security/` should contain **no `.kt` files** (only now-empty
package dirs, which git ignores). Confirm with `git status` — every line should be a
rename `R`, and there should be exactly **17** of them. If any file is listed as deleted +
added instead of renamed, that is fine functionally, but check you did not accidentally
edit it.

### Step 5 — Move the tests to `androidHostTest` (unmodified)

```bash
cd core/security/src   # from repo root

HT=androidHostTest/kotlin/com/transfer/flash/core/security
TST=test/java/com/transfer/flash/core/security
mkdir -p "$HT/crypto" "$HT/pairing" "$HT/trust" "$HT/trust/pinned" "$HT/identity" "$HT/testutil"

git mv "$TST/trust/FlashTrustStoreTest.kt"            "$HT/trust/FlashTrustStoreTest.kt"
git mv "$TST/identity/FlashIdentityStoreTest.kt"      "$HT/identity/FlashIdentityStoreTest.kt"
git mv "$TST/crypto/HkdfTest.kt"                       "$HT/crypto/HkdfTest.kt"
git mv "$TST/crypto/SoftwareFlashCryptoTest.kt"        "$HT/crypto/SoftwareFlashCryptoTest.kt"
git mv "$TST/crypto/FlashFingerprintTest.kt"           "$HT/crypto/FlashFingerprintTest.kt"
git mv "$TST/crypto/E2eFrameCodecTest.kt"              "$HT/crypto/E2eFrameCodecTest.kt"
git mv "$TST/trust/pinned/TofuPolicyTest.kt"           "$HT/trust/pinned/TofuPolicyTest.kt"
git mv "$TST/trust/pinned/LegacyTrustMigrationTest.kt" "$HT/trust/pinned/LegacyTrustMigrationTest.kt"
git mv "$TST/pairing/NumericComparisonCodeTest.kt"     "$HT/pairing/NumericComparisonCodeTest.kt"
git mv "$TST/pairing/PairingSessionStateMachineTest.kt" "$HT/pairing/PairingSessionStateMachineTest.kt"
git mv "$TST/pairing/DefaultFlashPairingProtocolTest.kt" "$HT/pairing/DefaultFlashPairingProtocolTest.kt"
git mv "$TST/testutil/FakeSharedPreferences.kt"        "$HT/testutil/FakeSharedPreferences.kt"
git mv "$TST/testutil/FakeClock.kt"                    "$HT/testutil/FakeClock.kt"
```

> The exact sub-package of each test above is the best-known layout as of 2026-08-30. If a
> `git mv` fails with "no such file", run `git -C core/security ls-files 'src/test/**/*.kt'`
> to see the real path and adjust — do **not** create a new file, just fix the source path.
> The invariant that matters: **all 13 test files end up under `androidHostTest/kotlin/`
> with their contents unchanged**, and `test/java/` is left empty.

### Step 6 — Compile the desktop target (the real prize)

```bash
./gradlew :core:security:compileKotlinJvm --no-configuration-cache
```

This must succeed. It proves the full JCA crypto + pairing surface builds for desktop from
`commonMain + jvmAndAndroidMain` with **no `jvmMain` files**. Likely failure modes and
their fixes:

- `Unresolved reference: NumericComparisonCode` / `TofuPolicy` / `LEGACY_UNBOUND_FINGERPRINT`
  → you placed `PairingSessionStateMachine.kt` or `LegacyTrustMigration.kt` in `commonMain`.
  Move it to `jvmAndAndroidMain` (the transitive rule).
- `Unresolved reference: java` / `javax` in a `commonMain` file → that file was
  mis-placed; it belongs in `jvmAndAndroidMain`. Move it down.
- `Unresolved reference: android` anywhere the `jvm()` target can see → an `androidMain`
  file leaked into `commonMain`/`jvmAndAndroidMain`. Move it to `androidMain`.
- `Expecting a top level declaration` / package errors → a file's `package` line was
  altered during the move. Restore it; moves must not edit contents.

### Step 7 — Run the Android host tests and assert the count equals baseline

Use the `ANDROID_UNIT_TEST_TASK` you copied from the Phase 06 log (under the KMP Android
plugin this is typically `:core:security:testAndroidHostTest`, **not** the old
`testDebugUnitTest`).

```bash
./gradlew :core:security:testAndroidHostTest --no-configuration-cache
```

Open the generated report and read the **total tests** count. It must equal
`SECURITY_TEST_BASELINE` from Step 2.

- **Count == baseline, all green** → correct.
- **Count == 0 but "BUILD SUCCESSFUL"** → the #1 trap: host tests were never turned on.
  `KMP_HOST_TEST_BLOCK` is missing or wrong in `build.gradle.kts`. Fix the Android block;
  do not proceed on a green-but-empty run.
- **Count < baseline** → some tests were left behind in `src/test/` or landed in a source
  set the test task does not see. Check `git status` for stragglers under `test/java/`.
- **Compile error in a test** → a test moved to a set without its dependency (e.g.
  `FakeSharedPreferences` needs Android stubs → it must be in `androidHostTest`, not a
  hypothetical `commonTest`).

### Step 8 — Publish locally and confirm the artifact coordinates

```bash
./gradlew :core:security:publishToMavenLocal --no-configuration-cache
```

Then inspect `~/.m2/repository/com/transfer/flash/` (group per the root build). You should
see the KMP publications with the **`core-security` prefix** preserved by the rewrite in
Step 3 — the root module (`core-security`), plus per-target variants
(`core-security-android`, `core-security-jvm`) and a `.module` Gradle-metadata file. The
important invariant: **the coordinate a consumer already uses (`core-security`) still
resolves**; Gradle metadata routes Android consumers to the android variant and desktop
consumers to the jvm variant. If the prefix is missing (artifacts named `security*`), the
publication rewrite did not run — re-check Step 3 Part 2.

### Step 9 — Prove the Android app still builds against it

```bash
./gradlew :app:assembleDebug --no-configuration-cache
```

This is the cross-module gate: `:app` (still a normal Android app) must resolve the newly
KMP `:core:security` and compile. Because every file kept its package and public API, and
`explicitApi()` is retained, no consumer source changes. If `:app` fails with an
"unresolved reference" into `com.transfer.flash.core.security.*`, a public symbol changed
visibility or moved packages during the move — it must not have; diff the offending file
against its pre-move version (`git show HEAD:<old-path>`).

## Verification gate — all four must pass (copy results into the log)

| # | Command | Pass condition |
|---|---|---|
| 1 | `:core:security:compileKotlinJvm --no-configuration-cache` | BUILD SUCCESSFUL — desktop crypto compiles |
| 2 | `:core:security:<ANDROID_UNIT_TEST_TASK> --no-configuration-cache` | total tests **== `SECURITY_TEST_BASELINE`**, all green (never 0) |
| 3 | `:core:security:publishToMavenLocal --no-configuration-cache` | artifacts published under the `core-security` prefix (+ `.module`) |
| 4 | `:app:assembleDebug --no-configuration-cache` | BUILD SUCCESSFUL — consumer still resolves the module |

If any fail, this phase is **not done**. Do not log it as complete and do not start
Phase 08.

## Do NOT

- **Do NOT edit the contents of any moved file.** This is a layout change. Crypto and wire
  files (`SoftwareFlashCrypto`, `E2eFrameCodec`, `Hkdf`, `FlashFingerprint`,
  `NumericComparisonCode`, `TofuPolicy`, `FlashPairingFrames`, `FlashPairingProtocol`) are
  R8/keep-rule protected and security-critical — a one-character change can silently alter a
  cryptographic result. `git mv` only.
- **Do NOT move any file to `commonMain` "because grep found no `java` import."** Apply the
  transitive rule; when unsure, `jvmAndAndroidMain` is always safe under D1 = A.
- **Do NOT create `jvmMain` files.** Zero is correct for this phase.
- **Do NOT add a desktop `FlashIdentityStore`/`FlashTrustStore`.** Out of scope (D5 / later).
- **Do NOT add `expect`/`actual`.** None is needed under D1 = A.
- **Do NOT delete `androidx.core.ktx` / `androidx.lifecycle.runtime.ktx`** in this phase —
  relocate them to `androidMain`. Deletion is a separate, later cleanup.
- **Do NOT bump any version, add any dependency, or enable `androidResources`** (no
  manifest/res exists). CONVENTIONS R10.
- **Do NOT rewrite tests into `commonTest`/`jvmTest`** — keep all 13 in `androidHostTest`
  so the count is comparable.
- **Do NOT drop `explicitApi()`** or change any symbol's visibility (ADR-023).

## Completion checklist

- [ ] Phase 06 confirmed complete in `logs/migration.md`; D1 confirmed `A` in DECISIONS.md.
- [ ] `SECURITY_TEST_BASELINE` recorded from the pre-conversion `testDebugUnitTest` run.
- [ ] `build.gradle.kts` rewritten: KMP + KMP-Android plugins, `androidLibrary { }` from
      Phase 06, `jvm()`, four source sets wired, deps relocated, publication prefix rewrite,
      `explicitApi()` kept.
- [ ] 17 production files moved — 4 commonMain, 10 jvmAndAndroidMain (incl. the two
      transitive files), 3 androidMain — all as `git mv`, contents unchanged.
- [ ] 13 test files moved to `androidHostTest`, contents unchanged; `src/test/` empty.
- [ ] `jvmMain` has zero files.
- [ ] Gate 1 `compileKotlinJvm` — pass.
- [ ] Gate 2 host tests — total **== baseline**, all green (not 0).
- [ ] Gate 3 `publishToMavenLocal` — `core-security` prefix present.
- [ ] Gate 4 `:app:assembleDebug` — pass.
- [ ] Log entry appended to `docs/migration/logs/migration.md`.

## Rollback

The whole phase is one module's file moves plus one build file. To abandon:

```bash
git checkout -- core/security/
git clean -fd core/security/src/commonMain core/security/src/jvmAndAndroidMain \
               core/security/src/androidMain core/security/src/androidHostTest
```

That restores `build.gradle.kts` and returns every file to `src/main/java` /
`src/test/java`. Because nothing downstream was edited (consumers never changed), no other
module needs reverting. Confirm with `./gradlew :app:assembleDebug --no-configuration-cache`.

## Log entry (mandatory — a phase with no entry is "not done")

Append one entry to `docs/migration/logs/migration.md` using the format in
`TEMPLATE-phase-log.md`. It must record:

- The five Phase-06 facts you reused (so the next phase sees they were consistent).
- `SECURITY_TEST_BASELINE` and the post-conversion count (proving they match).
- The four gate results (paste the `BUILD SUCCESSFUL` lines / test totals).
- The two decisions this phase embodies: **zero `expect`/`actual`**, and **`jvmMain`
  empty** — with one sentence each so a later reader does not "fix" them.
- Confirmation that `androidx.core.ktx` / `lifecycle.runtime.ktx` were **relocated, not
  deleted**, and remain grep-unused (a pointer for the future cleanup phase).
- The published artifact coordinates observed in `~/.m2`.

Do not edit earlier log entries (CONVENTIONS R1/R9); append newest at the bottom.
