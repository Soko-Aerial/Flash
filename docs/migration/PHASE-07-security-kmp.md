# Phase 07 — KMP conversion: `core:security`

> **REWRITTEN 2026-09-05, after execution, for D1 = B.** The previous version of this file was
> written for D1 = Option A (`jvmAndAndroidMain`) and voided itself if the answer turned out to
> be B — *"That is weeks of R8-sensitive work, not an afternoon. Do not attempt it from this
> file."* D1 is answered **B** in `DECISIONS.md`, and the 2026-09-03 amendment in
> `CONVENTIONS.md` made B load-bearing rather than merely chosen. This file now documents the
> D1 = B design **as actually built and verified**, so Phases 08–12 copy a real pattern instead
> of re-deriving one. The A-era placement tables are gone; they described a source set that does
> not exist.

**Blocked by:** Phase 06 (the pilot: it discovered the Android-KMP DSL shape, the host-test task
name, and the publication rewrite this phase copies).

**Risk: HIGH** — not the MEDIUM the A-era version claimed. Under D1 = A this module needed zero
seams and zero content edits. Under D1 = B the entire JCA surface is unavailable to shared code,
so the primitives grow an `expect`/`actual` seam, **seven files are rewritten**, and one public
API is retyped. The module is **R8-sensitive** (cryptography plus one wire format), so every
rewrite below is argued to be *output-identical*, not merely "looks equivalent".

## What this phase is for

Convert `:core:security` from `com.android.library` to `kotlin.multiplatform` +
`com.android.kotlin.multiplatform.library`, with an `android` target and a desktop `jvm()`
target, such that:

- Flash's cryptography, pairing protocol and trust policy live in **`commonMain`** — pure Kotlin,
  no `java.*`/`javax.*` — and are therefore inheritable by a future Kotlin/Native target.
- The JCA calls that used to be inline survive **unchanged**, one level down, as `actual`s in
  `androidMain` and `jvmMain`.
- `:core:security:compileKotlinJvm` compiles: the crypto surface builds for desktop.
- `:core:security:jvmTest` **runs** the desktop `actual`s (compiling them is not enough).
- The 80 pre-existing Android host tests still run and still pass, at the same count.
- `:core:security` still publishes as `core-security`; `:app:assembleDebug` still resolves it.

`core:security` is the right first module after the pilot because it is a dependency leaf: its
only project dependency is `api(project(":core:common"))`, converted in Phase 06.

## Verified starting state

Confirmed by reading the module on 2026-09-05, immediately before the conversion.

- 17 production `.kt` under `src/main/java/com/transfer/flash/core/security/`; 13 test `.kt`
  under `src/test/java/...` (11 JUnit 4 classes + `testutil/FakeClock.kt` +
  `testutil/FakeSharedPreferences.kt`).
- **No** `AndroidManifest.xml`, no `src/main/res/`, no `src/main/assets/` — so `androidResources`
  does not need enabling (same as `core:common` in Phase 06).
- `consumer-rules.pro` exists and is **comment-only** ("No keep rules are needed").
- `androidx.core.ktx` and `androidx.lifecycle.runtime.ktx` have **zero source references**
  (grep of `src/main`, R11 exclusions applied). They are Android AARs.
- Pre-conversion test baseline, measured with `:core:security:testDebugUnitTest`:
  **`SECURITY_TEST_BASELINE = 80 tests / 0 failures / 0 skipped`**.

## The source-set strategy (D1 = B)

Three production source sets. **`jvmAndAndroidMain` is not created** (CONVENTIONS R5) — the
duplication between `androidMain` and `jvmMain` is the price of keeping `commonMain` clean enough
for Kotlin/Native, and it is intentional.

```
                    commonMain          (pure Kotlin: algorithms, protocol, policy, wire frames)
                         │  internal expect fun × 10  +  internal expect interface × 1
                 ┌───────┴───────┐
                 ▼               ▼
           androidMain        jvmMain    (identical JCA actual bodies; ~90 lines duplicated)
```

Placement rule under B — note it is the *inverse* of the A-era rule:

1. If a file names `android.*` → **androidMain** (whole classes only: keystore, SharedPreferences).
2. Otherwise the file belongs in **`commonMain`**, and any `java.*`/`javax.*` it used becomes a
   call to an `internal expect fun` whose `actual`s carry the original JCA code verbatim.
3. `jvmMain` holds nothing but `actual`s. It has exactly one file.

Under A, "uses `java.*`" was the trigger to move a file *out* of shared code. Under B it is the
trigger to *split* it. Nothing is moved out because of `java.*`.

## The seam: `crypto/PlatformCrypto.kt`

Ten `internal expect fun`s plus one `internal expect interface`, all in
`commonMain/.../crypto/`, with `actual`s in `PlatformCrypto.android.kt` and `PlatformCrypto.jvm.kt`
(Phase 06's `PlatformX.kt` / `PlatformX.<target>.kt` naming).

| `expect` declaration | JVM/Android `actual` — the pre-existing call, moved not changed |
|---|---|
| `sha256(data)` | `MessageDigest.getInstance("SHA-256").digest(data)` |
| `hmacSha256(key, data)` | `Mac.getInstance("HmacSHA256")` + `SecretKeySpec(key, "HmacSHA256")` |
| `secureRandomBytes(size)` | one process-wide `SecureRandom().nextBytes(ByteArray(size))` |
| `constantTimeBytesEqual(a, b)` | `MessageDigest.isEqual(a, b)` |
| `aesGcmSeal(key, nonce, aad, plaintext)` | `Cipher.getInstance("AES/GCM/NoPadding")`, `ENCRYPT_MODE`, `GCMParameterSpec(128, nonce)`, `updateAAD`, `doFinal` |
| `aesGcmOpen(key, nonce, aad, ciphertext)` | same, `DECRYPT_MODE` |
| `ecP256GenerateKeyPair()` | `KeyPairGenerator.getInstance("EC")` + `ECGenParameterSpec(FlashCrypto.EC_CURVE)` |
| `ecP256Sign(privateKey, data)` | `Signature.getInstance(FlashCrypto.ECDSA_SIGNATURE_ALGORITHM)` |
| `ecP256Verify(publicKeyEncoded, data, signature)` | `KeyFactory("EC")` + `X509EncodedKeySpec`, `catch → false` |
| `ecP256SharedSecret(privateKey, peerPublicKeyEncoded)` | `KeyAgreement.getInstance(FlashCrypto.KEY_AGREEMENT_ALGORITHM)` |
| `internal expect interface PlatformEcPrivateKey` | `internal actual typealias PlatformEcPrivateKey = java.security.PrivateKey` |

Three deliberate choices in that table, each of which a later reader might otherwise "fix":

- **`constantTimeBytesEqual` is a seam, not a hand-written loop.** A pure-Kotlin XOR-accumulate
  loop would return the same booleans, but `MessageDigest.isEqual` is the function that ships in
  the current build, and R8 asks for bit-identical *behaviour* including timing behaviour. A
  Kotlin/Native `actual` will need the loop; JVM targets keep the JDK's.
- **`PlatformEcPrivateKey` is an `expect interface`, not an `expect class`.** Both `actual`s are
  `actual typealias`es to `java.security.PrivateKey`, which is itself an interface, and Kotlin
  rejects a kind mismatch: *"'actual typealias PlatformEcPrivateKey = PrivateKey' has no
  corresponding expected declaration … class kinds are different (class, interface, …)"*. That
  error is what an `expect class` produces here — it is not a missing-actual problem, and the fix
  is the classifier kind, not the source-set wiring. Aliasing rather than wrapping is also what
  lets `KeystoreFlashCrypto` pass its non-exportable AndroidKeyStore `PrivateKey` straight through
  the seam with no unwrap step.
- **`aesGcmOpen` throws the platform's own exception type.** No common exception type was
  introduced, so on JVM targets a bad tag still surfaces as `javax.crypto.AEADBadTagException` and
  `E2eFrameCodecTest`'s three `assertThrows(AEADBadTagException::class.java)` tests pass unedited.
  `commonTest` asserts only *that* it throws (`assertFails`). When a native `actual` lands it must
  either throw a JVM-shaped equivalent or the codec needs a common exception type — a follow-up,
  recorded here so it is not discovered by surprise.

## The one public API change

`FlashCrypto` exposed `java.security` types. Those cannot appear in `commonMain`, so the
interface is **retyped to byte arrays**:

| Before | After |
|---|---|
| `val identityPublicKey: PublicKey` | `val identityPublicKeyEncoded: ByteArray` |
| `fun generateEphemeralEcdhKeyPair(): KeyPair` | `fun generateEphemeralEcdhKeyPair(): FlashEcKeyPair` |
| `fun ecdhSessionKey(selfEphemeral: KeyPair, peerEphemeralPublicKey: ByteArray)` | `fun ecdhSessionKey(selfEphemeral: FlashEcKeyPair, peerEphemeralPublicKey: ByteArray)` |

This is a **retype, not a deletion**, and therefore not the R2 violation it superficially
resembles:

- `PublicKey` → `publicKeyEncoded` loses nothing. Every call site in the repo already read
  `.encoded` (X.509 SubjectPublicKeyInfo), and every peer-facing API in the module —
  `FlashCrypto.verify`, `FlashFingerprint.fingerprint`, `FlashPairingFrame` — already spoke SPKI
  bytes. The public half is the half that goes on the wire.
- The private half is *less* exposed than before, not more: `FlashEcKeyPair.privateKey` is an
  `internal` opaque handle, so no consumer can reach key material that `java.security.KeyPair`
  handed out freely. This is why `ecdhSessionKey` takes the whole keypair rather than the private
  key — an `internal` type cannot appear in a `public` signature.
- No algorithm, curve, key size, tag length or parameter changed.
- The rename (`identityPublicKey` → `identityPublicKeyEncoded`) is deliberate: changing the type
  behind the same name would compile at some call sites and mean something different. A renamed
  member turns every stale call into a compile error. Exactly one consumer existed
  (`app/.../debug/DiscoveryEngineHolder.kt`, two lines) and both already appended `.encoded`.

`ecdhSessionKey` had no callers outside the module; `AEADBadTagException` appears nowhere outside
it. Both facts were re-checked by grep after the conversion.

## File placement as built

22 production files (17 before + 5 new) and 14 test files (13 before + 1 new). Paths relative to
`core/security/src/<sourceSet>/kotlin/com/transfer/flash/core/security/`.

### `commonMain` — 17 files

| File | State | Note |
|---|---|---|
| `crypto/PlatformCrypto.kt` | **new** | the seam (table above) |
| `crypto/FlashEcKeyPair.kt` | **new** | `expect interface PlatformEcPrivateKey` + `FlashEcKeyPair` |
| `crypto/Hex.kt` | **new** | `Byte.toHexLower/Upper`, `ByteArray.toHexLower` — replaces `String.format` |
| `crypto/FlashCrypto.kt` | rewritten | retyped interface + `internal object EcP256Ops` |
| `crypto/SoftwareFlashCrypto.kt` | rewritten | **R8.** now shared by every target |
| `crypto/E2eFrameCodec.kt` | rewritten | **R8 (crypto + wire).** format byte-identical |
| `crypto/Hkdf.kt` | edited | **R8.** `Mac` → `hmacSha256` |
| `crypto/FlashFingerprint.kt` | rewritten | **R8.** `MessageDigest`/`String.format` → seam + `Hex` |
| `pairing/NumericComparisonCode.kt` | edited | **R8.** SAS derivation → seam |
| `pairing/FlashPairingProtocol.kt` | edited | `UUID`/`SecureRandom` → `core:common` seam + `secureRandomBytes` |
| `pairing/PairingSessionStateMachine.kt` | moved | pure Kotlin already |
| `pairing/FlashPairingFrames.kt` | moved | **R8 (wire).** zero imports, zero edits |
| `trust/pinned/TofuPolicy.kt` | edited | **R8.** `MessageDigest.isEqual` → seam |
| `trust/pinned/LegacyTrustMigration.kt` | moved | pure Kotlin already |
| `trust/FlashTrustStore.kt` | moved | interface |
| `identity/FlashIdentity.kt`, `identity/FlashIdentityStore.kt` | moved | data class + interface |

The two files the A-era version flagged as the "single most likely mistake" —
`PairingSessionStateMachine.kt` and `LegacyTrustMigration.kt`, pure Kotlin but referencing
`NumericComparisonCode` / `TofuPolicy` — are a **non-issue under B**: their dependencies moved to
`commonMain` too, so the transitive rule that forced them down under A has nothing to bite on.

### `androidMain` — 4 files

`crypto/PlatformCrypto.android.kt` (**new**, the JCA `actual`s), `crypto/KeystoreFlashCrypto.kt`
(edited: three override signatures follow the retype; the AndroidKeyStore / StrongBox /
self-signed-certificate logic is untouched), `identity/AndroidPreferencesIdentityStore.kt` and
`trust/AndroidPreferencesTrustStore.kt` (moved unedited).

### `jvmMain` — 1 file

`crypto/PlatformCrypto.jvm.kt`. Produced from the `androidMain` file mechanically; `diff` shows
**two differing lines, both KDoc**. Do not "de-duplicate" these two files into a shared parent —
that parent is `jvmAndAndroidMain`, which R5 forbids.

### Tests — 13 in `androidHostTest`, 1 in `commonTest`

All 13 pre-existing files move to `androidHostTest` unchanged except for two files whose
assertions name the retyped API (`SoftwareFlashCryptoTest` — 7 call sites, `FlashFingerprintTest`
— 2). Same test count, same assertions. `FakeSharedPreferences` implements
`android.content.SharedPreferences`, which is why the suites cannot simply be promoted to
`commonTest` wholesale.

`commonTest/.../crypto/PlatformCryptoParityTest.kt` is **new and load-bearing**: it is the only
thing that executes the `jvmMain` `actual`s. `compileKotlinJvm` proves they *compile*; the two
`actual` files are separate compilation units, and Android's JCA provider (Conscrypt) is not
OpenJDK's (SunJCE), so "Android passes" transfers nothing to desktop. Being in `commonTest` it
runs under **both** `testAndroidHostTest` and `jvmTest`, and any future target inherits it. It
pins fixed vectors where a round-trip alone would pass under a substituted algorithm: SHA-256 of
`"abc"` (FIPS 180-4 B.1) and HMAC-SHA256 RFC 4231 case 2. 10 tests.

## Behaviour preservation (R8) — the argument for each rewrite

The seam moves JCA calls; three rewrites also changed *how* bytes reach those calls. Each is an
identity, and each is pinned by a pre-existing test:

- **`Hkdf.expand`**: three streamed `Mac.update(T)`, `update(info)`, `update(counter)` calls became
  one `hmacSha256(prk, block + info + byteArrayOf(counter))`. HMAC of a concatenation is the same
  function of the same byte sequence — streaming is an API convenience, not a different
  computation. Pinned by `HkdfTest`'s RFC 5869 Appendix A vectors (3 tests).
- **`String.format("%02x")` → `Hex.kt`**: `java.util.Formatter` renders a negative `Byte` for
  `%x`/`%X` as its value plus 2^8, i.e. `toInt() and 0xFF` — exactly what the table lookup does.
  The high-bit case is covered because `FlashFingerprintTest`'s fixed vector starts with `0x82`.
  `kotlin.text.HexFormat` was avoided on purpose: still `@ExperimentalStdlibApi` in Kotlin 2.2.10,
  and R10 forbids opting a shipped module into an experimental stdlib API opportunistically.
- **`String.toByteArray()` → `encodeToByteArray()`**: both are UTF-8. Every string fed to a digest
  in this module is either an ASCII literal (`"flash-e2e-v…"`, `"flash-pairing-confirm-v1:…"`) or
  the output of `NumericComparisonCode.normalizeHex`, which emits only `[0-9a-f]`, so UTF-8,
  US-ASCII and ISO-8859-1 encodings are byte-identical. Pinned by `NumericComparisonCodeTest` (8)
  and `TofuPolicyTest` (8).

Unchanged by construction: AES-256/GCM with a 12-byte random nonce and a 128-bit tag, the
`[nonce | ciphertext+tag]` frame layout, the `"flash-e2e-v${FlashProtocol.VERSION}"` AAD, P-256
(`secp256r1`), `SHA256withECDSA`, `ECDH`, the 32-byte session key, `EMPTY_SALT`, `SESSION_INFO`,
the 6-digit modulus `1_000_000` over 5 digest bytes, and every `FlashPairingFrame` field.

## Steps (as executed)

### Step 1 — Preconditions
`git status` clean; Phase 06 committed (`83232f4`); record `SECURITY_TEST_BASELINE` from
`core/security/build/test-results/testDebugUnitTest/` **before** touching the build file, because
that task and its results directory stop existing the moment the plugin changes. Delete the stale
results directory afterwards — see the trap in `logs/migration.md`.

### Step 2 — `core/security/build.gradle.kts`
Rewrite it alone (R4). Copy the Phase 06 `android { }` / `jvm()` shape, keep `explicitApi()`, add
`-Xexpect-actual-classes`, keep `optimization { consumerKeepRules }` and
`localDependencySelection`, add the publication `artifactId` rewrite. Move
`androidx.core.ktx` / `androidx.lifecycle.runtime.ktx` from the old `dependencies { }` block into
`androidMain.dependencies` — relocated, not deleted.

### Step 3 — `git mv` the sources
`src/main/java/…` → `src/commonMain/kotlin/…`, then move the four Android-only files on to
`src/androidMain/kotlin/…`. `src/test/java/…` → `src/androidHostTest/kotlin/…` wholesale.
Use `git mv` so the diff reads as renames and blame survives.

### Step 4 — Cut the seam
Write `commonMain/crypto/PlatformCrypto.kt` (10 `expect fun`s), then
`androidMain/crypto/PlatformCrypto.android.kt` and `jvmMain/crypto/PlatformCrypto.jvm.kt`.
Both `actual` files are near-identical JCA code; that duplication is required by D1 = B.
Add `commonMain/crypto/FlashEcKeyPair.kt` with `internal expect interface PlatformEcPrivateKey`
and `actual typealias`es on both platforms.

### Step 5 — Retype the public API and fix its one consumer
`identityPublicKey: PublicKey` → `identityPublicKeyEncoded: ByteArray`, `KeyPair` →
`FlashEcKeyPair`. Then `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt`
(two lines). This is the only edit outside the module.

### Step 6 — Write the parity suite
`commonTest/crypto/PlatformCryptoParityTest.kt`, using `kotlin.test` only. `commonTest` runs on
**both** the Android host JVM and the desktop `jvm()` target, which is the only way the `jvmMain`
`actual`s are ever executed rather than merely compiled.

### Step 7 — Run the six gates below, in order
Gates 1–2 are compile-only and fail fast; do not run the 11-minute repo-wide gate until they pass.

## Verification gates — six, all must pass

| # | Command | Pass condition | Result 2026-09-05 |
|---|---|---|---|
| 1 | `:core:security:compileKotlinJvm` | BUILD SUCCESSFUL — desktop crypto compiles | pass |
| 2 | `:core:security:compileAndroidMain` | BUILD SUCCESSFUL | pass |
| 3 | `:core:security:testAndroidHostTest` | total **≥ `SECURITY_TEST_BASELINE`** (80), never 0, all green | **90 / 0 / 0** |
| 4 | `:core:security:jvmTest` | desktop `actual`s **execute**, all green, never 0 | **10 / 0 / 0** |
| 5 | `commonMain` purity grep (CONVENTIONS R6.1) | no `java.*`/`javax.*`/`android*.*` outside comments | pass |
| 6 | repo-wide R3 command incl. `:app:assembleDebug` | ≥ `BASELINE_TEST_TOTAL` (863/12/0) | see log entry |

Gate 3 is `≥`, not `==`: the 10 new parity tests run on the Android host JVM too, so the correct
post-conversion count is 80 + 10 = 90. Verify the **per-class** counts still match baseline —
a total that matches by coincidence while a class silently drops out is the failure this catches.

Gate 5 is new in this phase, because Phase 07 measured that nothing in the build enforces R6 —
see CONVENTIONS **R6.1**. `compileKotlinJvm` certifies only the absence of `android.*`.

## Published coordinates

`:core:security:publishToMavenLocal` produces the Phase 06 shape — one root module plus one per
target, all keeping the `core-security` prefix:

```
com/transfer/flash/core-security/1.1.0/          core-security-1.1.0.{module,pom,jar,-sources.jar}
com/transfer/flash/core-security-android/1.1.0/  core-security-android-1.1.0.{module,pom,aar,-sources.jar}
com/transfer/flash/core-security-jvm/1.1.0/      core-security-jvm-1.1.0.{module,pom,jar,-sources.jar}
```

Read `files[].url` inside the `.module`, **not** `component.module`. The root module's
`available-at` entries point at `../../core-security-android/…` and `../../core-security-jvm/…`,
and the per-target `files[].url` values are `core-security-android-1.1.0.aar` /
`core-security-jvm-1.1.0.jar` — so a consumer asking for `com.transfer.flash:core-security`
resolves and gets routed by Gradle metadata. `component.module` in the *child* modules still reads
`core-security` (the artifactId rewrite runs after metadata generation); `core-common` publishes
the same way as of Phase 06, so this is the established pattern, not a Phase 07 regression. Nothing
in this repo consumes the published artifact — `:app` uses a project dependency — so **resolution
from a repository is unverified until Phase 24**.

## Do NOT

- **Do NOT change a byte of algorithm behaviour.** R8. If a rewrite cannot be argued to be
  output-identical and pinned by an existing test, it does not belong in this phase.
- **Do NOT create `jvmAndAndroidMain`** to de-duplicate the two `actual` files. R5. The ~90
  duplicated lines are the point.
- **Do NOT introduce a common exception type for AEAD failures** here. It would change what
  `E2eFrameCodec.decrypt` throws on Android today.
- **Do NOT reach for `kotlin.text.HexFormat`** — experimental in Kotlin 2.2.10 (R10).
- **Do NOT promote the JUnit 4 suites to `commonTest`.** `FakeSharedPreferences` implements
  `android.content.SharedPreferences`; the topology change would risk the count for no gain.
- **Do NOT delete `androidx.core.ktx` / `androidx.lifecycle.runtime.ktx`.** They are unused by
  grep, but this phase changes layout, not the runtime classpath. They are relocated to
  `androidMain.dependencies` so the Android artifact's classpath is unchanged and the desktop
  target never sees an AAR. Deletion is a later cleanup phase (R1).
- **Do NOT add a Kotlin/Native target here** even though the seam is designed for one. That is a
  new phase (see CONVENTIONS R6.1).

## Completion checklist

- [x] `build.gradle.kts` rewritten: KMP + KMP-Android plugins, `android { … withHostTest {} …
      withDeviceTest {} }`, `jvm()`, `explicitApi()` kept, `-Xexpect-actual-classes`,
      `optimization { consumerKeepRules }`, publication prefix rewrite.
- [x] 17 files in `commonMain`, 4 in `androidMain`, 1 in `jvmMain` — no `jvmAndAndroidMain`.
- [x] 13 test files in `androidHostTest` (`git mv`, only the 9 retype call sites edited);
      1 new `commonTest` parity suite.
- [x] The one public API change recorded above, with its single consumer updated.
- [x] Gates 1–6 pass; counts pasted into `logs/migration.md`.

## Rollback

```bash
git checkout -- core/security/ app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt
git clean -fd core/security/src
```

Unlike the A-era plan, this phase edits one file outside the module
(`DiscoveryEngineHolder.kt`, two lines) because the public API was retyped — revert it too.
Confirm with `./gradlew :app:assembleDebug --no-configuration-cache`.

## Known issues found by this phase (CONVENTIONS R1 — reported, not fixed)

1. **Nothing enforces D1 = B.** `java.*` in `commonMain` compiles green today; measured and
   written up as CONVENTIONS **R6.1**, with the grep gate that substitutes for the compiler.
2. **No phase in the plan ever adds a Kotlin/Native target**, so the stated "Linux and all
   platforms" goal has no phase that delivers the Native half, and issue 1 has no phase that
   closes it. Phases 13–16 and 21–22 are desktop-JVM only.
3. **Published-artifact resolution is unverified.** See "Published coordinates" above.
4. **A native `aesGcmOpen` `actual` will need an exception-type decision** that Phase 07
   deliberately deferred.
