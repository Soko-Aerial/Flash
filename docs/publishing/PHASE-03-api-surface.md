# Phase 3 — API Surface Curation

**Goal:** stop shipping the entire implementation as public API. Turn on
`explicitApi()`, generate a checked‑in API dump, and demote internals so the
committed ABI is small and intentional. The dump also feeds Phase 2.2.

**Prereq:** Phase 2 (Task 2.1 at least).

---

## The problem

No module enables Kotlin `explicitApi()`, so **everything** with no visibility
modifier is `public`. Consumers currently "see" — and could compile against, then
break on — internal machinery:

- `ChaosNetworkHarness` / `ChaosSession` — a **test/fault‑injection** harness.
- Wire codecs & framing: `DataChannelFraming`, `WebSocketCodec`,
  `DiscoveryRouteBinder`, `SecureSocketUpgrader`, `FlashTlsContextFactory`,
  `NsdTxtCodec`, `TxtCodec`.
- Transfer internals: `MultiStreamDispatcher`, `StreamChannel`, `ChunkFrame`,
  `Chunker`, `SendPipeline`, `ReceivePipeline`, `ResumeBitVector`, `Sha256`.
- Room `@Dao`/`@Entity` types and `FlashSettingsDataStore`.
- Public `MutableSharedFlow`/`MutableStateFlow` fields exposed for read.

Every one of these becomes a compatibility obligation the day you publish.

---

## Task 3.1 — Add the binary‑compatibility‑validator (generates the API dump)

> **WITHDRAWN — superseded by ADR‑023 (2026‑08‑26).** BCV v0.18.1 registers no
> `apiDump`/`apiCheck` tasks under this project's AGP 9.3.1 built‑in Kotlin (no
> classic `kotlin-android`/JVM/MPP plugin), so it is inert here. The plugin,
> the root `apiValidation {}` block, and the `libs.versions.toml` entry were
> removed. Published‑ABI enforcement is `explicitApi()` (strict) per module
> instead — see ADR‑023. The steps below are kept for historical context only.

This gives a reviewable text snapshot of the exact public ABI per module — the
source of truth for Phase 2.2 and for spotting leaks.

1. Add to `libs.versions.toml` under `[plugins]` (pick the current version at
   apply time; verify it resolves):
   ```toml
   binary-compat-validator = { id = "org.jetbrains.kotlinx.binary-compatibility-validator", version = "0.18.1" }
   ```
2. Apply in the **root** `build.gradle.kts` plugins block with `apply false`, then
   `apply(plugin = ...)` in each `core/*` module, **or** apply it once at root and
   configure `apiValidation { }`. Simplest: apply at root:
   ```kotlin
   plugins { alias(libs.plugins.binary.compat.validator) }
   apiValidation {
       // The sample consumer and app are not part of the library ABI.
       ignoredProjects.addAll(listOf("app", "consumer", "theme", "chat"))
   }
   ```
3. Generate the baseline dump:
   ```bash
   ./gradlew apiDump
   ```
   Commit the produced `core/*/api/*.api` files.

**Acceptance:** each published `core/*` module has a committed `.api` file.

## Task 3.2 — Enable `explicitApi()` per module

With `explicitApi()`, the compiler **errors** on any top‑level/member declaration
that lacks an explicit visibility modifier — forcing a deliberate `public` /
`internal` decision on every symbol.

1. In each `core/*/build.gradle.kts`, add a Kotlin config block. **Verify where
   the Kotlin DSL lives** first — this project uses AGP 9.3.1 built‑in Kotlin, so
   confirm whether a top‑level `kotlin { }` extension is available or whether it
   must go through `androidComponents`/`compilerOptions`. Try, in order:
   ```kotlin
   kotlin {
       explicitApi()
   }
   ```
   If that DSL isn't resolvable with the built‑in Kotlin, fall back to:
   ```kotlin
   tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
       compilerOptions.freeCompilerArgs.add("-Xexplicit-api=strict")
   }
   ```
2. Do **one module at a time**, starting with `core:common`. Expect a wave of
   compile errors — that is the point.

## Task 3.3 — Resolve each error: `public`, `internal`, or `@FlashInternalApi`

For every symbol the compiler flags, choose:

- **Part of the documented contract** (matches `docs/architecture/public-api.md`):
  mark it explicitly `public`. This is the interfaces and models: `FlashEngine`,
  `FlashNetwork`, `FlashDiscovery`, `FlashSession`, `FlashTransferRepository`,
  `FlashChatRepository`, `FlashDevice`, `FlashDeviceId`, `FlashResult`,
  `FlashError`, `FlashTransportType`, `FlashPeerPresence`, the `Default*`/`Ws*`
  factory‑relevant entry points consumers must construct.
- **Implementation detail** (everything in the problem list above): mark
  `internal`. Prefer `internal` over annotations wherever nothing outside the
  module needs it — `internal` removes it from the ABI entirely.
- **Cross‑module‑internal** (a type another `core` module needs but consumers must
  not use): keep it `public` but annotate `@FlashInternalApi` (already defined in
  `core:common`, `RequiresOptIn` ERROR). Use sparingly.

Specific dispositions:
- `ChaosNetworkHarness`/`ChaosSession` → move to `src/test`/`src/androidTest` if
  they are test‑only, otherwise `internal`. They must not ship as public API.
- All codecs/framing/pipeline/`Chunker`/`Sha256`/`ResumeBitVector` → `internal`.
- Room `@Dao`/`@Entity`, `FlashSettingsDataStore` → `internal` (also see Phase 4).
- Public `MutableSharedFlow`/`MutableStateFlow` fields → expose the read‑only
  `SharedFlow`/`StateFlow` supertype publicly and keep the mutable backing field
  `private`.

## Task 3.3 — AS‑BUILT classification (2026‑08‑26)

The aspirational "Specific dispositions" above were written before checking what
`app/` and `ui/` actually import. Ground truth (verified by grepping consumer
imports) overrides them. **Decision rule, in priority order:**

1. **Transitive‑forced public wins over everything.** If a plain‑`public`
   app‑facing signature returns/accepts/extends a type, that type is `public`
   regardless of intent (e.g. `FlashIdentityStore.getIdentity(): FlashIdentity`
   forces `FlashIdentity` public; `DefaultFlashPairingProtocol(… timeouts:
   PairingTimeouts = …)` forces `PairingTimeouts` public).
2. **Imported by `app/` or `ui/` → plain `public`.** Those modules are out of
   scope and cannot receive `@file:OptIn`, so `internal`/`@FlashInternalApi`
   would break their compile.
3. **Imported by another `core:*` module but NOT app/ui → `@FlashInternalApi`**
   (Kotlin‑public, opt‑in ERROR) + `@file:OptIn(… FlashInternalApi::class)` on the
   consuming file(s).
4. **Referenced only inside its own module, not leaked through any public
   signature → `internal`.** (Unit tests keep friend‑path access.)

Modules **common, messaging, engine, discovery** are DONE (green under strict
`explicitApi()`).

### persistence — entire data layer stays plain `public` (Phase‑3 reality)
`app` wires the DB directly (`FlashDatabaseOpener.openEncrypted` → `FlashDatabase`
→ `*Dao` accessors → entities/query‑result types), and `FlashSettingsDataStore` is
app/engine‑referenced. So every entity, DAO, `FlashDatabase`, opener, migration,
`RetentionPolicy`, and settings type is plain `public`. Gating this behind
`@FlashInternalApi` is **Phase 4** (persistence‑decoupling) work, not Phase 3.

### security
- **public** — identity: `FlashIdentityStore`, `FlashIdentity` (forced),
  `AndroidPreferencesIdentityStore`; trust: `FlashTrustStore` (app+engine+transfer),
  `AndroidPreferencesTrustStore` (app+transfer); crypto: `FlashFingerprint` (app),
  `KeystoreFlashCrypto` (app), `FlashCrypto` (contract, implemented by the app‑facing
  `KeystoreFlashCrypto`); pairing: `FlashPairingFrame`, `PairingPhase`,
  `PairingSessionState`, `FlashPairingEvent`, `DefaultFlashPairingProtocol` (all app),
  `FlashPairingProtocol` (contract), `PairingTimeouts` (forced).
- **internal** — crypto: `SoftwareFlashCrypto`, `Hkdf`, `E2eFrameCodec`, `EcP256Ops`
  (already); pairing: `NumericComparisonCode`, `PairingSessionStateMachine`,
  `PairingSessionEvent`; trust/pinned: `RoomTrustedStore`, `TofuPolicy`,
  `LegacyTrustMigration`. (All used only inside impl bodies; verify no public leak.)
- **@FlashInternalApi** — none (every cross‑core security type is also app‑facing →
  already public).

### transfer
- **public** — `ChunkFrame`, `ReceivePipeline`, `Sha256`, `StreamChannel` are all
  imported by `app/…/debug/DiscoveryEngineHolder.kt`, so they are `public`
  (**correcting** the "→ internal" disposition above). Plus the documented repo
  contract (`FlashTransferRepository`, `RealFlashTransferRepository`, progress/model
  types consumers see).
- **internal** — module‑only impl: `MultiStreamDispatcher`, `Chunker`, `SendPipeline`,
  `ResumeBitVector`, and the multistream receiver internals — subject to
  "public‑exposes‑internal" checks at compile (promote if a public repo signature
  leaks them).
- **@FlashInternalApi** — none confirmed yet (network↔transfer only crosses in the
  transfer→network direction; the `DataChannelFraming`→`ChunkFrame` grep hit was a
  comment, not a code dep).

### network
- **@FlashInternalApi** — `WebSocketCodec`: imported cross‑core by
  `core/transfer/…/wslegacy/WsTransferManager.kt` → gate with `@FlashInternalApi`
  and add `@file:OptIn(… FlashInternalApi::class)` to `WsTransferManager.kt`
  (**correcting** the "→ internal" disposition; `internal` is invisible cross‑module).
- **internal** — network‑only: `SecureSocketUpgrader`, `FlashTlsContextFactory`,
  `ChaosNetworkHarness`, `ChaosSession` (main‑source but referenced only by
  `ChaosResilienceTest`), `DataChannelFraming` (used by `DataChannelClient`/`Server`
  within network). None are imported by app/ui.
- **public** — the documented network contract (`FlashNetwork`,
  `DefaultFlashNetwork`, and `WsTransferClient`/`WsTransferServer` **iff** app/ui
  construct them — verify consumer imports during execution).

## Task 3.4 — Re‑dump and reconcile with Phase 2.2

> **N/A under ADR‑023** — there is no `.api` dump to re‑generate. Foreign‑type
> leaks are now surfaced by explicitApi's "public‑exposes‑internal"
> (`EXPOSED_*`) compile errors, which force the promote‑to‑`api` decision at the
> leak site. Remaining Phase 2.2 dep‑scope reconciliation is tracked there.

After demotions, run `./gradlew apiDump` again. The `.api` files should shrink to
the intended surface. Now revisit **Phase 2.2**: any foreign `core:*` type still
present in a module's `.api` is a real leak → promote that module dep to `api`.
Anything that became `internal` no longer leaks → its dep stays `implementation`.

## Acceptance

> **Restated by ADR‑023.** BCV `apiDump`/`apiCheck` are removed; the acceptance
> is the explicitApi criterion below (met — all 8 `core/*` modules green under
> strict `explicitApi()`).

- `explicitApi()` (strict) active in every published `core/*` module; build is
  green.
- ~~`.api` dumps contain only the `public-api.md` contract + minimal
  `@FlashInternalApi` surface — no Chaos/codec/pipeline/DAO types.~~ *(no dumps;
  enforced at compile instead)*
- ~~`./gradlew apiCheck` passes (dumps match committed baseline).~~ *(N/A)*

## Verification (hand off)

```bash
./gradlew apiCheck :core:engine:compileReleaseKotlin :sample:consumer:assembleDebug
```

