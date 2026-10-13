# Plan: extract the enterprise hub client into `:core:enterprise` (ADR-128)

**Status:** ACCEPTED by the owner on 2026-10-13. NOT STARTED when this file was written.
**Audience:** an AI coding agent (Gemini or another) that must be told every step. Do exactly what is written. Where this plan
says STOP, stop and report instead of improvising.
**Effort:** about half a day, mostly waiting for Gradle.
**Behaviour change:** NONE. This is a pure move + rename + rewiring. No feature, no bug fix, no cleanup.

---

## 0. Rules for whoever executes this plan (read first, obey literally)

1. Read `AGENTS.md` sections 1, 4, 6, 7, 8, 11, 12, 27, 28, 35 before touching anything. Read `logs/handoff.md` (top) and
   `docs/decisions.md` ADR-128.
2. **Do not change behaviour.** If you notice a bug while moving a file, do NOT fix it in this task. Write it into `logs/errors.md`
   as a new `ERROR-NNN` (status OPEN) and keep going. (Known already: ERROR-396 in `HubChatBridge`, not part of this task.)
3. **Never delete, skip, `@Ignore`, loosen or rewrite a test to make it pass.** Tests move with their code. A test that fails after
   the move means the move is wrong. The ONLY edits allowed in a moved test file are the `package` line and `import` lines (and the
   one line described in step 5 for the lock helper).
4. **Use `git mv`**, never copy+delete, so history follows the files.
5. **Run every command in this plan.** Do not say "should work". Paste real results into the progress log.
6. **Do not run two Gradle commands at the same time**: concurrent builds corrupt each other's `build/` directories (symptom:
   random "class not found" or "file in use" errors; fix: re-run alone).
7. **Gradle only works on this machine with this exact prefix** (Windows, Git Bash). Use it for every Gradle command in this
   plan; the plan writes it as `GRADLE`:

   ```bash
   cd "C:/Users/KaliOxygen/Downloads/Flash"
   export JAVA_HOME="/c/Users/KaliOxygen/.gradle/jdks/jetbrains_s_r_o_-21-amd64-windows.2"
   export JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Users\KaliOxygen\.gradle\afunix'
   ./gradlew <tasks>
   ```
   Do NOT add `--offline`. The message `Daemon compilation failed: null` followed by `BUILD SUCCESSFUL` is harmless noise.
8. Do not commit secrets. Do not touch `keystore.properties`, `*.jks`, `*.pk8`, `data.key`.
9. Do not edit files under `docs/` or `logs/` other than the ones listed in section 9. Old docs keep the old package names on purpose
   (they are history; AGENTS.md section 27).
10. If anything in this plan contradicts what you see in the repository, STOP and report the contradiction. Do not guess.

---

## 1. Goal

Create a new Kotlin Multiplatform library module **`:core:enterprise`** and move into it everything in `:core:network` that talks
to the enterprise hub (enrolment, directory, relay client, trust stores, sealing-key manager, hub session, hub status). After the
move:

* `:core:network` contains only the LAN / radio mesh transports and no longer depends on `:core:hub-protocol`.
* A consumer that wants only the LAN mesh (`sample/consumer-granular`) no longer pulls the hub client or its wire types.
* `:core:enterprise` is published as artifact `core-enterprise` (`core-enterprise-android`, `core-enterprise-jvm`) with the same
  group and version as every other module.
* Every existing test passes unchanged, with the same total number of tests as before.
* `:server` (main code) does **not** depend on `:core:enterprise`; only `:server` tests do.

Why: ADR-128 (`docs/decisions.md`). The two halves have no code dependency on each other (verified 2026-10-13), the split lets the
hub client evolve (multi-device, FO-16) without touching the LAN transport, and it keeps the consumer-granular artifact small.

## 2. Non-goals (do NOT do these)

* Do not change any wire format, protocol document, ADR other than recording status, or the server.
* Do not move `EnrollmentCredentials`, `EnterpriseTrustStore`, `MemoryEnterpriseTrustStore`, `HubClientAuth`,
  `HubSealingKeyStore`, `PendingJoinsCodec`, `FlashCrypto` or anything else living in `:core:security`. They stay.
* Do not move anything from `core/messaging/.../hub/` (the chat bridge and sealing glue stay in `:core:messaging`).
* Do not rename classes or functions. Only packages change.
* Do not add dependencies other than the ones in section 4.3. Do not upgrade versions.
* Do not "improve" `explicitApi`, visibility, KDoc or formatting of moved files.
* Do not fix ERROR-396 or any other bug here.
* Do not start multi-device work (FO-16) or hub-only group roster (FO-17).

## 3. Current state (facts verified 2026-10-13; re-verify in step 0)

* The hub client code lives in `core/network/src/**/{enterprise,relay}/` in the Kotlin packages
  `com.transfer.flash.core.network.enterprise` and `com.transfer.flash.core.network.relay`: **35 files**, listed in section 4.1.
* Nothing else inside `:core:network` references those two packages, and nothing outside them references
  `com.transfer.flash.core.hubprotocol` or `com.transfer.flash.core.security.enterprise`.
* **The only back-reference** from the moved code into the rest of `:core:network` is
  `FlashRelayClient.kt` importing `com.transfer.flash.core.network.radio.withRadioLock` (an `internal expect fun`, used twice, in
  `start()` and `stop()`). The new module gets its own tiny helper (step 5). `:core:network` keeps its `withRadioLock` (used by the
  radio code).
* Moved code imports: `:core:common` (annotation `FlashInternalApi`, `protocol.Base64`, `FlashEnvelope`, time, result, logging),
  `:core:hub-protocol`, `:core:security` (`security.enterprise.*`, `security.crypto.*`), Ktor client (core, cio, websockets),
  kotlinx coroutines, and in `androidMain` `android.content.*` (one file), in `jvmMain` `java.io` / `java.nio` (one file). No
  `@Serializable` in the moved code (so no serialization plugin is needed).
* Consumers of the moved packages (files containing `core.network.enterprise` or `core.network.relay`): `app` (3 files), `desktop`
  (2), `core/messaging` (10: main and tests), `ui/chat` (6), `server` (11, all tests). Nothing else.
* Gradle edges to `:core:network` today: `app` (L54), `desktop` (L40), `core/messaging` (L88), `ui/chat` (L93), `core/engine`
  (`api`, L110), `core/transfer` (L116, a live edge for something unrelated to the hub: leave it), `sample/consumer-granular`
  (L27, must keep compiling) and `server` (`testImplementation`, L64).
* `core/network/build.gradle.kts` exposes `api(project(":core:hub-protocol"))` with the comment "Relay/enterprise wire types ...
  appear in public signatures: ADR-107". After the move that comment is no longer true for `:core:network`.

## 4. Target state and fixed parameters

### 4.1 Files to move (old path -> new path)

Package mapping (the only two rules):

| Old package | New package |
|---|---|
| `com.transfer.flash.core.network.enterprise` | `com.transfer.flash.core.enterprise` |
| `com.transfer.flash.core.network.relay` | `com.transfer.flash.core.enterprise.relay` |

Directory mapping: replace `core/network/src/<set>/kotlin/com/transfer/flash/core/network/enterprise/` by
`core/enterprise/src/<set>/kotlin/com/transfer/flash/core/enterprise/`, and `.../network/relay/` by
`.../core/enterprise/relay/`. `<set>` stays the same (`commonMain`, `commonTest`, `androidMain`, `jvmMain`, `jvmTest`).

**commonMain / enterprise (12):** `EnrollmentResult`, `EnterpriseCredentialCodec`, `EnterpriseDirectoryClient`,
`EnterpriseEnrollmentClient`, `EnterpriseHub`, `EnterpriseModels`, `HubCredentialVerifier`, `HubPeerDirectory`,
`HubSealingKeyManager`, `HubSession`, `HubStatus`, `HubUrlPolicy` (each `.kt`).
**commonMain / relay (6):** `FlashRelayClient`, `KtorRelayTransport`, `RelayFailure`, `RelayIdentity`, `RelaySessionState`,
`RelayTransport`.
**androidMain / enterprise (1):** `AndroidPreferencesEnterpriseTrustStore`.
**jvmMain / enterprise (1):** `JvmFileEnterpriseTrustStore`.
**commonTest / enterprise (3):** `EnterpriseEnrollmentAndDirectoryTest`, `HubStatusMathTest`, `HubUrlPolicyTest`.
**commonTest / relay (3):** `FlashRelayClientRecoveryTest`, `FlashRelayClientTest`, `ScriptedRelayTransport` (fixture).
**jvmTest / enterprise (8):** `EnrollmentTrustTest`, `EnterpriseHubSealingLifecycleTest`, `EnterpriseHubTest`,
`HubSealingKeyManagerTest`, `HubSessionTest`, `HubTestKit` (fixture), `JvmFileEnterpriseTrustStoreAtomicTest`,
`JvmFileEnterpriseTrustStoreTest`.
**jvmTest / relay (1):** `FlashRelayClientStartRaceTest`.

Total moved: 12 + 6 + 1 + 1 + 3 + 3 + 8 + 1 = **35**.

New files (3, one expect and two actuals, section step 5):
`core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/relay/RelayLock.kt`,
`core/enterprise/src/androidMain/kotlin/com/transfer/flash/core/enterprise/relay/RelayLock.android.kt`,
`core/enterprise/src/jvmMain/kotlin/com/transfer/flash/core/enterprise/relay/RelayLock.jvm.kt`.

Other new files: `core/enterprise/build.gradle.kts`, `core/enterprise/consumer-rules.pro`.

### 4.2 Module parameters (use these exact values)

| Parameter | Value |
|---|---|
| Gradle path | `:core:enterprise` (directory `core/enterprise`) |
| Published artifact ids | `core-enterprise`, `core-enterprise-android`, `core-enterprise-jvm` (via `artifactId.replace("enterprise", "core-enterprise")`) |
| Android namespace | `com.transfer.flash.core.enterprise` |
| compileSdk / minSdk | 35 / 24 (same as `:core:network`) |
| JVM target | `JVM_11` for android and jvm |
| Kotlin API mode | `explicitApi()` |
| Plugins | `kotlin.multiplatform`, `android.kotlin.multiplatform.library`, `maven-publish` (NO `kotlin.serialization`, NO `com.android.library`) |
| Targets | `android { withHostTest { } }` and `jvm { }`. Do NOT add `withDeviceTest` (there is no androidTest) |

### 4.3 Dependencies of `:core:enterprise`

| Source set | Dependency | Kind | Why |
|---|---|---|---|
| commonMain | `project(":core:common")` | `api` | public signatures use `FlashInternalApi`, `Base64`, result types |
| commonMain | `project(":core:hub-protocol")` | `api` | wire DTOs, certificates and relay frames appear in public signatures |
| commonMain | `project(":core:security")` | `api` | public signatures use `EnterpriseTrustStore`, `EnrollmentCredentials`, `HubSealingKeyStore`, etc. (an `api` can never break a consumer; `implementation` could) |
| commonMain | `libs.kotlinx.coroutines.core` | `api` | `Flow` / `StateFlow` are public |
| commonMain | `libs.ktor.client.core`, `libs.ktor.client.cio`, `libs.ktor.client.websockets` | `implementation` | the HTTP and WebSocket clients |
| commonTest | `kotlin("test")`, `libs.kotlinx.coroutines.test` | `implementation` | as in `:core:network` |
| androidHostTest | `libs.junit` | `implementation` | as in `:core:network` |
| jvmTest | `libs.junit` | `implementation` | as in `:core:network` |

Nothing in `androidMain` or `jvmMain` dependencies (the two platform files use only `android.content` / `java.*`).
**Do NOT** depend on `:core:network`, `:core:discovery`, `:core:messaging`, `:core:persistence` or `:core:engine`. A dependency on
`:core:network` would defeat the whole move.

### 4.4 Dependency rules that must hold at the end (checked in section 8)

* `:core:network` has no dependency on `:core:enterprise` and none on `:core:hub-protocol`.
* `:core:enterprise` has no dependency on `:core:network`.
* `:server` main classpath has no `:core:enterprise`.
* `:core:engine` re-exports it with `api(project(":core:enterprise"))` (it already re-exports every core module).

---

## 5. Step by step

Each step ends with a **Check** you must run before the next step.

### Step 0. Baseline (before any edit)

```bash
git status --short | head        # MUST be empty. If not: STOP (a dirty tree makes this move unreviewable).
git rev-parse --short HEAD       # write this hash down: it is your rollback point
git switch -c refactor/core-enterprise-extraction   # only if the owner's workflow wants a branch; otherwise stay on dev
```

Record baseline test counts. Run, one after another (never in parallel):

```
GRADLE :core:network:jvmTest :core:network:testAndroidHostTest :core:messaging:jvmTest :ui:chat:jvmTest :server:test
```

Then count tests per module (Git Bash):

```bash
count() { grep -ho 'tests="[0-9]*"' "$@" 2>/dev/null | awk -F'"' '{s+=$2} END{print s+0}'; }
echo "network jvm:      $(count core/network/build/test-results/jvmTest/*.xml)"
echo "network android:  $(count core/network/build/test-results/testAndroidHostTest/*.xml)"
echo "messaging jvm:    $(count core/messaging/build/test-results/jvmTest/*.xml)"
echo "ui-chat jvm:      $(count ui/chat/build/test-results/jvmTest/*.xml)"
echo "server:           $(count server/build/test-results/test/*.xml)"
```

Write the five numbers into the working notes. **After the move the sum of `network jvm` + `enterprise jvm` must equal the old
`network jvm` number, and likewise for the android host tests; the other three numbers must be unchanged.**
(Reference, 2026-10-13: network jvm 1088, server 411, hub-protocol 122. Your numbers may differ slightly if the tree changed;
what matters is that you use your own baseline.)

If any baseline test fails: STOP. Known flaky tests (do not weaken, just re-run once): `GroupJoinTest` refused-join,
`FlashStressLogicTest` 2000-message timing, server `RevocationAndEnrolmentTest`, `RelayLoadTest`,
`RelayDeliveryContractTest.anOversizedWebSocketFrameClosesTheConnection`,
`E2eRelayContractTest.aReconnectStormLeavesExactlyOneLiveSession` (only under concurrent builds).

Verify the facts in section 3:

```bash
# 35 files:
find core/network/src -type f \( -path '*/enterprise/*' -o -path '*/relay/*' \) | wc -l
# nothing else in network refers to them (must print nothing):
grep -rlE "core\.network\.(enterprise|relay)|hubprotocol|security\.enterprise" core/network/src \
  | grep -vE "/(enterprise|relay)/"
# the one back-reference (must print exactly the FlashRelayClient import line):
grep -rn "withRadioLock" core/network/src/commonMain/kotlin/com/transfer/flash/core/network/relay
```

**Check:** 35 files; the first grep prints nothing; the second prints the import and two uses in `FlashRelayClient.kt`.

### Step 1. Create the module skeleton and register it

1. In `settings.gradle.kts`, add `include(":core:enterprise")` on the line directly after `include(":core:network")`.
2. Create `core/enterprise/consumer-rules.pro` with exactly:

   ```
   # Consumer ProGuard/R8 rules for :core:enterprise
   #
   # No keep rules are needed. The hub client has no first-party reflection and no serialization plugin of its own
   # (the @Serializable wire types live in :core:hub-protocol, which ships its own consumer rules).
   # Transitive libraries that require keep rules ship their own consumer rules.
   ```
3. Create `core/enterprise/build.gradle.kts` with exactly:

   ```kotlin
   // ADR-128: the enterprise hub client (enrolment, directory, relay client, trust stores, sealing keys, hub session),
   // extracted from :core:network. It depends on :core:hub-protocol, :core:security and :core:common, NEVER on :core:network.
   plugins {
       alias(libs.plugins.kotlin.multiplatform)
       alias(libs.plugins.android.kotlin.multiplatform.library)
       `maven-publish`
   }

   kotlin {
       explicitApi()

       android {
           namespace = "com.transfer.flash.core.enterprise"
           compileSdk = 35
           minSdk = 24

           optimization {
               consumerKeepRules.apply {
                   file("consumer-rules.pro")
                   publish = true
               }
           }

           localDependencySelection {
               selectBuildTypeFrom.set(listOf("release"))
           }

           compilerOptions {
               jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
           }

           // Creates `androidHostTest` and the `testAndroidHostTest` task. Without it the commonTest files stop running on the
           // Android host while the build still reports SUCCESS.
           withHostTest { }
       }

       jvm {
           compilerOptions {
               jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
           }
       }

       sourceSets {
           commonMain.dependencies {
               api(project(":core:common"))
               api(project(":core:hub-protocol"))
               api(project(":core:security"))
               api(libs.kotlinx.coroutines.core)
               implementation(libs.ktor.client.core)
               implementation(libs.ktor.client.cio)
               implementation(libs.ktor.client.websockets)
           }
           commonTest.dependencies {
               implementation(kotlin("test"))
               implementation(libs.kotlinx.coroutines.test)
           }
           getByName("androidHostTest").dependencies {
               implementation(libs.junit)
           }
           jvmTest.dependencies {
               implementation(libs.junit)
           }
       }
   }

   publishing {
       publications {
           withType<MavenPublication>().configureEach {
               artifactId = artifactId.replace("enterprise", "core-enterprise")
           }
       }
   }
   ```
4. Create the source directories (they get filled in step 2):
   `mkdir -p core/enterprise/src/{commonMain,commonTest,androidMain,jvmMain,jvmTest}`.

**Check:** `GRADLE :core:enterprise:tasks --all -q` prints tasks without a configuration error (an empty module is fine).
If it says the module is not found, the `include` line is wrong.

### Step 2. Move the 35 files with `git mv`

Run exactly this (Git Bash, repository root):

```bash
SRC=core/network/src
DST=core/enterprise/src
for set in commonMain commonTest androidMain jvmMain jvmTest; do
  for kind in enterprise relay; do
    from="$SRC/$set/kotlin/com/transfer/flash/core/network/$kind"
    [ -d "$from" ] || continue
    if [ "$kind" = enterprise ]; then
      to="$DST/$set/kotlin/com/transfer/flash/core/enterprise"
    else
      to="$DST/$set/kotlin/com/transfer/flash/core/enterprise/relay"
    fi
    mkdir -p "$to"
    for f in "$from"/*.kt; do git mv "$f" "$to/"; done
  done
done
find core/network/src -type d -empty -delete
```

**Check:**

```bash
find core/enterprise/src -type f -name '*.kt' | wc -l          # 35
find core/network/src -type f \( -path '*/enterprise/*' -o -path '*/relay/*' \) | wc -l   # 0
```

### Step 3. Rewrite the packages and imports (mechanical)

One command rewrites `package` lines, `import` lines, fully qualified names in code and KDoc references, in every Kotlin and Gradle
source of the repository outside `build/` directories:

```bash
grep -rlE "com\.transfer\.flash\.core\.network\.(enterprise|relay)" \
    --include=*.kt --include=*.kts --exclude-dir=build --exclude-dir=.gradle --exclude-dir=.git \
    app desktop core ui server sample \
  | xargs sed -i -E \
      -e 's/com\.transfer\.flash\.core\.network\.enterprise/com.transfer.flash.core.enterprise/g' \
      -e 's/com\.transfer\.flash\.core\.network\.relay/com.transfer.flash.core.enterprise.relay/g'
```

Do NOT run it over `docs/` or `logs/` (history keeps the old names).

**Check:**

```bash
# must print nothing (no old name left in code):
grep -rnE "core\.network\.(enterprise|relay)" --include=*.kt --include=*.kts --exclude-dir=build \
    app desktop core ui server sample
# every moved main file now declares the new package (must print nothing):
grep -L "^package com.transfer.flash.core.enterprise" $(find core/enterprise/src -name '*.kt')
```

### Step 4. The one back-reference: replace `withRadioLock` inside the moved code

In `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/relay/FlashRelayClient.kt`:

1. Delete the line `import com.transfer.flash.core.network.radio.withRadioLock`.
2. Rename the two calls `withRadioLock(` to `withRelayLock(` (in `start()` and `stop()`). Nothing else in the file changes.

Create the three new files, with exactly this content.

`core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/relay/RelayLock.kt`:

```kotlin
package com.transfer.flash.core.enterprise.relay

/**
 * Runs [block] while holding the monitor of [lock]. `kotlin.synchronized` is JVM-only and `commonMain` is kept strict, so this
 * is a one-line seam with identical `actual`s on the two JVM-family targets (android, jvm). Same shape as the radio code's
 * helper in `:core:network`, copied so that this module never depends on `:core:network`. An `expect fun` needs no
 * `-Xexpect-actual-classes` flag.
 */
internal expect fun <T> withRelayLock(lock: Any, block: () -> T): T
```

`core/enterprise/src/androidMain/kotlin/com/transfer/flash/core/enterprise/relay/RelayLock.android.kt` and
`core/enterprise/src/jvmMain/kotlin/com/transfer/flash/core/enterprise/relay/RelayLock.jvm.kt` (identical content):

```kotlin
package com.transfer.flash.core.enterprise.relay

internal actual fun <T> withRelayLock(lock: Any, block: () -> T): T = synchronized(lock, block)
```

Do NOT touch `core/network/.../radio/RadioLock*.kt`; the radio code still uses it.

**Check:** `grep -rn "withRadioLock" core/enterprise` prints nothing.

### Step 5. Compile the new module on its own

```
GRADLE :core:enterprise:compileKotlinJvm :core:enterprise:compileAndroidMain
```

(If the second task name is not found, run `GRADLE :core:enterprise:tasks --all -q | grep -i compile` and use the Android compile
task that exists. Do not skip the Android compile: `AndroidPreferencesEnterpriseTrustStore` only compiles there.)

If compilation fails with *unresolved reference*: read the error. The cause is one of
(a) a type that lived in the root `com.transfer.flash.core.network` package and was used without an import (the same-package
trap): STOP and report, do not copy `:core:network` classes into the new module;
(b) a missing dependency from the table in 4.3: add only what the table lists.

**Check:** both tasks `BUILD SUCCESSFUL`.

### Step 6. Run the moved tests inside the new module

```
GRADLE :core:enterprise:jvmTest :core:enterprise:testAndroidHostTest
```

Compare with the baseline (step 0): `enterprise jvm` count must equal the number of tests that used to come from those files. Use
section 8 criterion A3 for the exact sum rule.

**Check:** 0 failures, 0 skipped tests more than before.

### Step 7. Slim down `:core:network`

Edit `core/network/build.gradle.kts`:

1. Remove the two lines (and only these):
   ```kotlin
   // Relay/enterprise wire types (RelayFrame, DTOs, certs) appear in public signatures: ADR-107.
   api(project(":core:hub-protocol"))
   ```
2. Leave everything else (ktor, security, discovery, bouncycastle, jserialcomm) untouched, even if a dependency now looks unused.
   Cleaning those is a separate task.

Then:

```
GRADLE :core:network:compileKotlinJvm :core:network:compileAndroidMain :core:network:jvmTest :core:network:testAndroidHostTest
```

If a compile error says a `hubprotocol` type is unresolved inside `:core:network`, STOP and report (section 3 says that cannot
happen). If another module (e.g. `:core:transfer`) breaks because it used hub-protocol through `:core:network`'s `api`, do NOT
re-add the line to `:core:network`; instead add `implementation(project(":core:hub-protocol"))` to that module, and record it in the
progress log.

**Check:** `:core:network` tests pass; `network jvm` count = old count minus the moved tests.

### Step 8. Rewire the consumers

Add the dependency line next to the existing `:core:network` line in each file (same indentation, same `implementation` kind unless
stated):

| File | Add |
|---|---|
| `app/build.gradle.kts` (after L54) | `implementation(project(":core:enterprise"))` |
| `desktop/build.gradle.kts` (after L40) | `implementation(project(":core:enterprise"))` |
| `core/messaging/build.gradle.kts` (after L88) | `implementation(project(":core:enterprise"))` |
| `ui/chat/build.gradle.kts` (after L93) | `implementation(project(":core:enterprise"))` |
| `core/engine/build.gradle.kts` (after L110) | `api(project(":core:enterprise"))` (the engine re-exports every core module) |
| `server/build.gradle.kts` (after L64) | `testImplementation(project(":core:enterprise"))`  **test only; NOT `implementation`** |

Do NOT add it to `core/transfer`, `sample/*`, `core/network`, `core/security`, `core/persistence`, `core/discovery`.

Then check each consumer still needs `:core:network`: leave the existing `:core:network` lines in place in all files above even if
a consumer now uses nothing from it (removing edges is a separate task). The only exception that must hold: `sample/consumer-granular`
keeps compiling with `:core:network` alone.

**Check (compile everything, one command at a time):**

```
GRADLE :core:messaging:compileKotlinJvm :core:messaging:compileAndroidMain
GRADLE :ui:chat:compileKotlinJvm :ui:chat:compileAndroidMain
GRADLE :core:engine:compileKotlinJvm :core:engine:compileAndroidMain
GRADLE :desktop:compileKotlinJvm
GRADLE :app:compileDebugKotlin
GRADLE :sample:consumer-granular:compileKotlin    # if this exact task name does not exist, run `GRADLE :sample:consumer-granular:tasks` and use its compile task
```

### Step 9. Packaging and docs wiring

1. `jitpack.yml`: in the `install:` line insert ` :core:enterprise:publishToMavenLocal` directly after
   ` :core:network:publishToMavenLocal`. Update the header comment sentence "all fifteen" to "all sixteen" and add one sentence:
   "`core:enterprise` (artifact `core-enterprise`, ADR-128) holds the hub client; `core:engine` api-depends on it".
2. `README.md`: in the "Published modules" table add a row after `core-network`:
   `| core-enterprise | Supported (optional) | No | Enterprise hub client: enrolment, directory, relay client, trust stores. Depends on core-hub-protocol and core-security, not on core-network. |`
   Change the text on the `core-hub-protocol` row from "pulled in by `core-security` and `core-network`" to "pulled in by
   `core-security` and `core-enterprise`". Change any "fifteen" modules wording to "sixteen" (search: `grep -n "fifteen" README.md jitpack.yml docs/publishing/*.md`).
3. `docs/architecture.md` (module list near L28): add the line
   `  :core:enterprise  (enterprise hub client: enrolment, directory, relay client, trust stores; depends on hub-protocol + security, not on network)`.
4. `docs/publishing/PHASE-06-jitpack-publishing.md`: do not edit (history); the jitpack.yml header covers it.

### Step 10. Add the boundary test for the server

Create `server/src/test/kotlin/com/transfer/flash/server/deploy/ModuleBoundaryTest.kt`:

```kotlin
package com.transfer.flash.server.deploy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-128: the hub client lives in `:core:enterprise`. The SERVER must never depend on it in main code (it would couple the
 * hub to its own client); only its tests may, because they drive a real client against a real hub.
 */
class ModuleBoundaryTest {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return requireNotNull(dir) { "repository root not found" }
    }

    private fun lines(path: String) = File(repoRoot(), path).readLines().map { it.trim() }.filter { !it.startsWith("//") }

    @Test
    fun theServerMainCodeDoesNotDependOnTheHubClient() {
        val deps = lines("server/build.gradle.kts").filter { it.contains("project(\":core:enterprise\")") }
        assertTrue(deps.isNotEmpty(), "the server tests need :core:enterprise")
        assertTrue(deps.all { it.startsWith("testImplementation") }, "only testImplementation may name :core:enterprise, found: $deps")
    }

    @Test
    fun theLanTransportDoesNotDependOnTheHubClientOrTheHubProtocol() {
        val deps = lines("core/network/build.gradle.kts")
        assertFalse(deps.any { it.contains("project(\":core:enterprise\")") }, ":core:network must not depend on :core:enterprise")
        assertFalse(deps.any { it.contains("project(\":core:hub-protocol\")") }, ":core:network must not depend on :core:hub-protocol")
    }

    @Test
    fun theHubClientDoesNotDependOnTheLanTransport() {
        val deps = lines("core/enterprise/build.gradle.kts")
        assertFalse(deps.any { it.contains("project(\":core:network\")") }, ":core:enterprise must not depend on :core:network")
    }
}
```

**Check:** `GRADLE :server:test --tests "*ModuleBoundaryTest*"` passes (3 tests). Prove the test works: temporarily change
`testImplementation(project(":core:enterprise"))` in `server/build.gradle.kts` to `implementation(...)`, run the test, see it FAIL,
change it back, run again, see it pass. Record that you did this ("mutation check") in the progress log.

### Step 11. Full verification

Run, one by one, never in parallel. Every command must end `BUILD SUCCESSFUL`:

```
GRADLE :core:hub-protocol:jvmTest
GRADLE :core:security:jvmTest
GRADLE :core:enterprise:jvmTest :core:enterprise:testAndroidHostTest
GRADLE :core:network:jvmTest :core:network:testAndroidHostTest
GRADLE :core:messaging:jvmTest :core:messaging:testAndroidHostTest
GRADLE :ui:chat:jvmTest
GRADLE :server:test
GRADLE :server:installDist
GRADLE :desktop:compileKotlinJvm
GRADLE :app:compileDebugKotlin
GRADLE :sample:consumer-granular:compileKotlin
```

Server main isolation, proves section 4.4:

```
GRADLE :server:dependencies --configuration runtimeClasspath > /tmp/server-deps.txt   # use the scratchpad dir if /tmp is not allowed
grep -c "core:enterprise" /tmp/server-deps.txt      # MUST print 0
GRADLE :core:network:dependencies --configuration jvmRuntimeClasspath > /tmp/net-deps.txt
grep -c "hub-protocol\|core:enterprise" /tmp/net-deps.txt   # MUST print 0
```

(If `jvmRuntimeClasspath` is not a configuration name, run `GRADLE :core:network:dependencies | grep -E "^[a-zA-Z]+Classpath"` to
find the right one; do not skip the check.)

Publish dry run (publishes into the local Maven repository `~/.m2`, harmless):

```
GRADLE :core:common:publishToMavenLocal :core:hub-protocol:publishToMavenLocal :core:security:publishToMavenLocal :core:network:publishToMavenLocal :core:enterprise:publishToMavenLocal -x test -x lint
find ~/.m2/repository -maxdepth 6 -type d -name 'core-enterprise*'      # expect core-enterprise, core-enterprise-android, core-enterprise-jvm
```

Then open the generated `core-enterprise-jvm*.pom` and check that it lists `core-hub-protocol`, `core-security`, `core-common` and
does NOT list `core-network`.

Finally run the exact install line from `jitpack.yml` as the owner's release dry run (see memory note "Release dry-run": release-only
ProGuard and publication problems are invisible to debug builds). It is long; run it once at the end:

```bash
sed -n 's/^  - \(\.\/gradlew .*\)$/\1/p' jitpack.yml    # prints the line; run what it prints through GRADLE-style env
```

### Step 12. Docs and logs (see section 9 for the exact entries), then commit

Commits (two, in this order, after everything above is green):

```
refactor(enterprise): extract the hub client into :core:enterprise (ADR-128)
docs(enterprise): record the :core:enterprise extraction
```

Commit message trailer: `Co-Authored-By: <the agent that did the work>`. Record both commit hashes in `logs/handoff.md`.

---

## 6. Acceptance criteria (all must be true; each one is checkable)

| ID | Criterion | How to check |
|---|---|---|
| A1 | `core/enterprise` exists, is registered in `settings.gradle.kts`, builds for android and jvm. | step 5 |
| A2 | 35 files moved with `git mv`; 3 new lock files; 2 new module files; nothing left under `core/network/src/**/{enterprise,relay}`. | step 2 checks; `git status` shows renames (`R`), not delete+add |
| A3 | Test counts: `enterprise jvm + network jvm (after) = network jvm (before)`; same for android host tests; `messaging`, `ui-chat`, `server` unchanged, plus exactly 3 new server tests (`ModuleBoundaryTest`). | step 0 numbers vs step 11 |
| A4 | Zero test failures, zero tests newly ignored, no `@Ignore`/`@Disabled` added, no assertion edited. | `git diff -- '*Test*.kt'` shows only `package`/`import` line changes |
| A5 | No `com.transfer.flash.core.network.enterprise` / `.relay` string left in any `.kt` or `.kts` file. | step 3 check |
| A6 | `:core:network` has no dependency on `:core:hub-protocol` or `:core:enterprise`; `:core:enterprise` has none on `:core:network`; server main has none on `:core:enterprise`. | `ModuleBoundaryTest` + step 11 dependency greps |
| A7 | `:app:compileDebugKotlin`, `:desktop:compileKotlinJvm`, `:server:installDist`, `:sample:consumer-granular` compile. | step 11 |
| A8 | `core-enterprise`, `-android`, `-jvm` publish to the local Maven repo; the jvm POM lists hub-protocol, security, common and not network. | step 11 |
| A9 | `jitpack.yml` install line, README table, `docs/architecture.md` updated; ADR-128 marked IMPLEMENTED; progress, handoff, backlog entries written. | section 9 |
| A10 | The diff contains NO behaviour change: every changed line in a moved file is a `package`/`import` line, a fully qualified name rewritten by the sed command, or the `withRelayLock` rename. | review `git diff -M --stat` and `git diff -M` of the moved files |

## 7. Pitfalls (each one has bitten this repository before)

* **Concurrent Gradle runs** corrupt `build/` folders. Run one command at a time. If you see strange class-not-found errors, re-run
  the same command alone before investigating.
* **`internal` visibility is per module.** Six `internal` declarations move with their tests (`EnterpriseEnrollmentClient.mapHttpFailure`,
  `EnterpriseModels` `toDirectoryGroup` / `toDirectoryMember` / `toDepartment`, `KtorRelayTransport.kindForCode`). Tests in `:core:messaging` or
  `:server` that touch an `internal` member of a moved class will now fail to compile, because they were never in the same module;
  if that happens, STOP and report (do not make the member public).
* **KDoc cannot contain the character sequence `**/`** inside a comment (it closes the comment). Do not paste such text into KDoc.
* **Line endings:** Kotlin and Gradle files here are LF or CRLF as they already were; `sed -i` keeps them. Do not run a formatter or
  convert line endings. Docs under `docs/` and `logs/` are mostly CRLF: when you edit them use an editor that preserves CRLF, and
  APPEND new entries (the rest of the file must not show up in `git diff`).
* **Empty `androidMain` in a KMP module:** fine, but the module must still compile the Android target (step 5).
* **`-Xexpect-actual-classes`:** not needed (an `expect fun` is not an `expect class`). Do not add it.
* **Do not "fix" a failing known-flaky test by editing it.** Re-run once alone; if it still fails on a clean baseline and after, it is
  the known flake; report it.
* **Gradle task name differences (AGP 9 KMP):** Android compile tasks and host-test tasks are named differently from classic AGP
  (`compileAndroidMain`, `testAndroidHostTest`, no `testDebugUnitTest`). When a name is not found, list tasks; do not invent.
* **Kotlin import order is not enforced**, so the sed rewrite leaving imports unsorted is fine. Do not reorder.

## 8. Rollback

Nothing is destructive until you commit. To abandon: `git restore --staged . && git restore . && git clean -fd core/enterprise` and
confirm `git status` is clean (only do this for a tree that was clean at step 0). If already committed and something breaks later:
`git revert <the refactor commit>`; the docs commit stays and its ADR-128 status goes back to ACCEPTED with a note of why.

## 9. Documentation and log updates (required, AGENTS.md sections 6, 8, 28, 35)

Append or edit exactly these (CRLF files: append, keep CRLF):

1. `docs/decisions.md`: change ADR-128 "Status" to `IMPLEMENTED <date>, commits <hashes>`; add a "Result" line with the final
   test counts. Do not delete anything else.
2. `logs/progress.md`: new entry at the TOP (after the first header) with the six standard answers (worked on, changed, why,
   verified, remaining, next AI). Put the REAL command outputs summary (counts before/after, the mutation check from step 10,
   the dependency greps) in "Verification".
3. `logs/handoff.md`: update the "Update 2026-10-13" note at the top: extraction done or not, commit hashes, next task.
4. `docs/testing/TEST-BACKLOG.md`: `EXTR-01` already exists (status TODO, device check). Do NOT mark it PASS (only the owner's device
   run does that). Add a line under it: "Automated part done <date>, commit <hash>".
5. `AGENTS.md` section 29, "In Progress": add one bullet: "**Hub client extracted into `:core:enterprise` (ADR-128, <date>):** moved
   from `:core:network`, packages renamed to `com.transfer.flash.core.enterprise[.relay]`; no behaviour change; device check `EXTR-01`."
6. `docs/FUTURE-OPTIMIZATION.md` FO-16: replace the sentence saying extraction is pending by "extraction done (ADR-128)". Do not
   change the rest.
7. `logs/errors.md`: only if you hit a real problem. Use the next free `ERROR-NNN` (check the file; numbers up to 396 are used),
   with the format in AGENTS.md section 7.

## 10. Final report the executor must give

1. Commit hashes.
2. A table: before/after test counts per module (step 0 vs step 11).
3. The outputs (or their last lines) of the dependency greps and the mutation check.
4. Any STOP condition hit, any deviation from this plan (with the reason), any new `ERROR-NNN`.
5. A statement of what was NOT verified (device behaviour: enrolment and relay chat on a phone, `EXTR-01`).

## 11. Checklist (tick while working)

- [ ] Step 0 baseline recorded (5 numbers) and tree was clean
- [ ] Step 1 skeleton + `settings.gradle.kts`
- [ ] Step 2 35 files moved with `git mv`
- [ ] Step 3 sed rewrite, no old name left
- [ ] Step 4 `withRelayLock` helper (3 files) and `FlashRelayClient` edited
- [ ] Step 5 new module compiles (jvm + android)
- [ ] Step 6 new module tests pass
- [ ] Step 7 `:core:network` slimmed, tests pass
- [ ] Step 8 six consumer build files, all compile
- [ ] Step 9 jitpack.yml, README, architecture.md
- [ ] Step 10 `ModuleBoundaryTest` + mutation check
- [ ] Step 11 every verification command green, dependency greps 0, publish dry run
- [ ] Step 12 docs/logs updated, two commits, hashes in handoff
