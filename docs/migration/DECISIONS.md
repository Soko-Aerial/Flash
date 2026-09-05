# Open Decisions — HUMAN INPUT REQUIRED

**Blocking map (which phase each decision gates):**

| Decision | Blocks | Notes |
|---|---|---|
| **D1** | **Phase 06** | The KMP pilot cannot start until the source-set strategy is chosen. Highest-leverage decision in the migration. |
| **D2** | **Phase 06** | Only if you choose to rename; if D2 = B, the rename is its own phase run **before** Phase 06. If D2 = A, non-blocking. |
| **D3** | **Phase 18** | Compose dependency source. Does **not** block Phase 06. **Bound Phase 17 as well** (2026-09-05): 17 could not add `composeResources/` without the CMP plugin, so it enacted D3 = A a phase early and discharged D3's outstanding CMP-version verification. |
| **D4** | **Phase 18** | Dynamic-color replacement. Does **not** block Phase 06. |
| **D5** | **Phase 09** | Desktop persistence strategy. |
| **D6** | **Phase 14** | Desktop discovery implementation. |
| **D7** | **Phase 19** | UI platform shims. **Executed 2026-09-05 (`94a60a4`); D7b overridden on evidence** — a and c as written, but FileKit was **not** adopted. See the note under D7 below. |
| **D8** | **Phase 22** | Whether the §15 desktop screens exist. |
| **D9** | Phase 24 | Sample consumers; agent may proceed on the recommendation. |
| **D10** | **Phases 13B-2, 13B-3, 15, 16** | What replaces `java.io.InputStream` in a `commonMain` signature. Added 2026-09-05 by the agent that reached Phase 13. **Answered 2026-09-05 = Option A**, with an explicit R8 authorisation for 13B-3's `ChunkFrame` rewrite (byte-identical output required). 13B-2 is now executable. |
| **D11** | **a new phase, number TBD** | Is the calling stack (`:core:calling` + `:ui:callui`) in desktop scope? Added 2026-09-05 — no phase file has ever mentioned `:core:calling`. **Answered 2026-09-05 = in scope, research first.** Does not block any existing phase. |

**As of 2026-09-05 every decision D1–D11 is answered.** No phase in this plan is blocked on a decision
any more. What remains blocked is blocked on a *predecessor phase*, and two sub-items are blocked on
narrower human input that is not a decision: 09B-2 needs D5=C's three implementation sub-answers (which
encrypted desktop driver, is a commercial licence acceptable, SQLCipher file-format parity) and 09B-3
needs the settings-tier ABI option (a) or (b).

Earlier drafts said "D1–D4 block Phase 06." That was wrong: D3 and D4 are Compose
decisions and cannot affect a pilot that converts a Compose-free module (`core:common`).
Phase 06's own header states this; this table is the source of truth.

Each decision lists options, consequences, and a recommendation. An agent must
**not** pick for the human on D1, D2, D5, D8, D10 or **D11** — those change the shape of the
project. For D3, D4, D6, D7 an agent may proceed with the recommendation if the human
has not answered, but must record that it did so in the phase log. **All of D1–D11 now carry a human
answer**, so this rule is currently a rule about *future* decisions and about not re-litigating the
existing ones: if a phase seems to need a decision changed, add a new numbered decision, do not edit an
answered one.

Record answers by editing this file: replace `**ANSWER:** _pending_` with the choice
and the date.

---

## D1 — `jvmAndAndroidMain` or a strict stdlib-only `commonMain`?

This is the highest-leverage decision in the entire migration.

**Option A — introduce a `jvmAndAndroidMain` intermediate source set (RECOMMENDED)**

Android and desktop are both JVM targets, so an intermediate source set between
`commonMain` and the two platform sets can use `java.*` and `javax.*` freely.

- TLS/TOFU pinning (5 files), blocking sockets (10 files), the whole JCA crypto
  surface (~9 files), and `ConcurrentHashMap`/`ReentrantLock` (~8 files) all move
  **unchanged**.
- Security-critical code is not rewritten, so its behaviour cannot silently change.
- Cost: closes the door on iOS/Kotlin-Native. Adding iOS later means paying the
  rewrite then, plus re-testing everything.
- `commonMain` still holds models, protocol, state machines, chunk planning — the
  parts that genuinely benefit from being platform-neutral.

**Option B — strict `commonMain`: stdlib + coroutines only**

- Requires rewriting the transport onto Ktor or okio, replacing the JCA crypto layer
  with a multiplatform crypto library, and re-verifying every security property.
- Realistically weeks of work with a real risk of introducing a subtle crypto or
  pinning bug.
- Buys iOS/Native capability that is not currently a goal.

**Question for the human: is iOS or any Kotlin/Native target ever plausible for
Flash, or is Android + desktop the end state?**

If the answer is "Android + desktop", Option A is strictly better. If iOS is a real
roadmap item, Option B is the honest choice and the schedule must grow accordingly.

**ANSWER:** Option B (chosen 2026-08-31) — strict `commonMain`: stdlib + coroutines only. Rewrites the transport onto Ktor/okio and replaces the JCA crypto layer with a multiplatform crypto library; re-verifies every security property. Buys iOS/Native capability. This materially grows the schedule for Phase 06 and the security-critical phases.

**REAFFIRMED 2026-09-03** — the human's stated target is *"linux and all platforms"*. That
settles the question this decision asks, and it settles it for B:

- **Linux desktop alone would not have required B.** Linux, Windows and macOS desktop all run
  the JVM, so a single `jvm()` target covers all three and Option A would have sufficed for
  them. It is **iOS / Kotlin-Native** — the "all platforms" half of the instruction — that makes
  a strict `commonMain` mandatory, because `java.*` and `javax.*` do not exist there at all.
- Therefore the JCA crypto rewrite, the Ktor/okio transport rewrite, and the
  `kotlin.concurrent` / `Mutex` concurrency rewrite are all **in scope and required**, not
  optional hardening. PHASE-05's "Recommend D1 = A" assessment was written for an
  Android-plus-desktop-JVM product and no longer describes the product being built; its
  **cost** analysis stays valid and is the schedule input.
- Consequence for source-set layout: **`jvmAndAndroidMain` is not created.** CONVENTIONS.md R2
  step 2 and the R5 table row naming it are void — see the amendment recorded at the top of
  CONVENTIONS.md.
- Consequence for D5: Option C's encrypted-desktop-storage requirement now extends past the
  JVM. A Kotlin/Native SQLCipher story must be evaluated in Phase 09, not assumed.

---

## D2 — Rename `core:*` modules to `flash-*`?

Old plan §5 proposed renaming every module (`core-common` → `flash-common`, etc.).

**Option A — keep `core:*` names (RECOMMENDED).** Zero functional gain from renaming.
A rename touches every Gradle file, every import, and the JitPack/Maven artifact IDs
that were just set up in the publishing work merged as PR #1. Adding source sets does
not require renaming anything.

**Option B — rename now.** Only worth it if you also want to restructure artifact
coordinates, and it should then be its own isolated phase with its own commit.

**ANSWER:** Option A (chosen 2026-08-31) — keep `core:*` names.

---

## D3 — Compose dependency source after KMP conversion

Currently every Compose dependency resolves through `platform(libs.androidx.compose.bom)`
version `2025.12.00`, with no individual artifact versions pinned.

**Option A — switch `ui:*` to the `org.jetbrains.compose` plugin and its artifacts
(RECOMMENDED).** This is the standard CMP setup. The Compose BOM is dropped for the
shared UI modules; `compose.runtime`, `compose.foundation`, `compose.material3`,
`compose.ui`, `compose.components.resources` come from the CMP plugin instead.
`org.jetbrains.kotlin.plugin.compose` is still required separately and still tracks
the **Kotlin** version, not the CMP version.

**Option B — keep the Android BOM and add CMP only for desktop.** Produces two
divergent Compose dependency trees. Not recommended.

⚠️ **Phase 06 must verify** which CMP release lines up with this project's Kotlin
2.2.10. Per JetBrains, the latest CMP is always compatible with the latest Kotlin, and
anything from CMP 1.8.0 onward requires Kotlin ≥ 2.1.0 — so 2.2.10 is fine, but the
exact CMP version must be resolved against the published compatibility table rather
than guessed. CMP 1.12.0 corresponds to Jetpack Compose 1.12.0.
Source: [Compose Multiplatform compatibility and versioning](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html)

**ANSWER:** Option A (chosen 2026-08-31) — switch `ui:*` to the `org.jetbrains.compose`
plugin and its artifacts, drop the Android BOM for shared UI modules. The custom Flash
design system (`FlashColors`, `FlashTypography`, `FlashShapes`, `FlashIcons`) is
Flash-owned and survives unchanged — only the dependency source changes, not the visual
identity. Phase 06 must still verify the exact CMP version against Kotlin 2.2.10.

**VERIFICATION DISCHARGED — Phase 17, 2026-09-05 (`a8d9d0d`).** Phase 06 never did it (it
converted `:core:common`, which has no Compose). Result: **CMP 1.9.3**, and the ⚠️ above is
wrong on the key point — *"the latest CMP is always compatible with the latest Kotlin"* is true
but useless here, because this repo's Kotlin is **frozen at 2.2.10 by R10**, not latest. Read
from each release's `components-resources-<v>.module` on Maven Central:

| CMP | declares `kotlin-stdlib` | usable at Kotlin 2.2.10? |
|---|---|---|
| **1.9.3** | **2.1.0** | **yes — chosen** |
| 1.10.3 | 2.2.20 | no — would raise stdlib above the compiler |
| 1.11.1 | 2.3.20 | no — built with Kotlin 2.3 |
| 1.12.0 | 2.3.20 | no — built with Kotlin 2.3 |

A 2.2.10 compiler cannot read metadata emitted by 2.3, so **CMP 1.12.0 — the version this
decision's text names — is unusable**, and so is 1.11.x. 1.9.3 also happens to leave every
Android consumer untouched: it maps to Jetpack Compose 1.9.4, *below* the 1.10.0 that
`composeBom = "2025.12.00"` pins (material3 1.4.0), so Gradle keeps 1.10.0 and `:ui:chat`,
`:ui:callui` and `:app` see no version change at all. CMP 1.10.3 would have dragged
androidx.compose to 1.10.5.

Two mechanical facts D3's Option A text omits, both mandatory and both discovered by build
failure rather than by reading docs:

- A CMP module applies **both** `org.jetbrains.kotlin.plugin.compose` (the compiler, tracks the
  Kotlin version) **and** `org.jetbrains.compose` (the `compose` DSL and `Res` generation,
  versioned independently). They are not alternatives.
- `compose.components.resources` does **not** put the Compose runtime on the compile classpath.
  `implementation(compose.runtime)` is required or every Kotlin compilation in the module fails
  with `IncompatibleComposeRuntimeVersionException` — including a source set with zero
  `@Composable`, because the compiler plugin checks unconditionally.

**Consequence for the human decision queue: current CMP is unreachable without a Kotlin bump.**
Staying on 1.9.3 is fine today. If any phase from 20 onward wants a newer CMP API, the Kotlin
version has to move first, and R10 makes that an explicit authorisation, not an agent's call.

---

## D4 — What replaces Android dynamic color (Monet)?

`ui/theme/Theme.kt` and `ui/theme/FlashTheme.kt` call `dynamicDarkColorScheme` /
`dynamicLightColorScheme`. These are Android 12+ only and have no CMP common
equivalent.

**Option A — `expect fun` seam (RECOMMENDED).** `expect fun flashDynamicColorScheme(dark: Boolean): ColorScheme?`
returning the Monet scheme on Android and `null` on desktop, with the existing static
Flash palette as the fallback. Preserves current Android behaviour exactly.

**Option B — drop dynamic color entirely** and use the static Flash palette on both
platforms. Simpler, one fewer `expect`/`actual` pair, but changes Android behaviour
for existing users.

**ANSWER:** Option A (chosen 2026-08-31) — `expect fun
flashDynamicColorScheme(dark: Boolean): ColorScheme?` returning the Monet scheme on
Android and `null` on desktop with the static Flash palette as fallback. Android
behaviour for existing users is preserved exactly.

---

## D5 — Persistence strategy for desktop

Today: Room `androidx.room` 2.8.4 + SQLCipher `net.zetetic:sqlcipher-android` 4.17.0,
plus `androidx.datastore` for settings. 28 of 34 files in `core/persistence` are
coupled to these. Phase 4 of the publishing work (ADR-024) already inverted this
behind ports/adapters, so the seam exists.

**Option A — desktop runs without persistence at first (RECOMMENDED for Phase 1).**
Implement the existing persistence ports with in-memory adapters on desktop. Transfers
work; history and resume-across-restart do not. This is what old plan §12 prescribes
and it keeps Phase 16 reachable quickly.

**Option B — migrate to Room 3 (`androidx.room3` 3.0.2) with the bundled SQLite
driver.** Room 3 supports KMP including JVM desktop, generates Kotlin only, and
requires KSP per target. This is a **group rename and a major version bump**
(`androidx.room` → `androidx.room3`, `room-runtime` → `room3-runtime`) plus an
`expect object ... : RoomDatabaseConstructor` and a per-platform
`getDatabaseBuilder`. Encryption on desktop is then an open problem — the bundled
driver is not SQLCipher.
Source: [Set up Room database for KMP](https://developer.android.com/kotlin/multiplatform/room)

**Option C — Option B plus encrypted desktop storage.** Candidates, none free:
`s0d3s/SQLCipherMultiplatform` (KMP Android+JVM SQLCipher), `bloomberg/selekt`
(Android + JDBC with encryption), Zetetic's own SQLCipher for JDBC (commercial
licence). All need evaluation for maintenance status and licence before adoption.

**Constraint from old plan §13 that still stands: do not disable encryption to make
the desktop port easier.** Under Option A that constraint is satisfied trivially,
because nothing is persisted on desktop. Under Option B it is **violated** unless
Option C follows. Decide A→C as a sequence, or B as a knowing, documented tradeoff.

**ANSWER:** Option C (chosen 2026-08-31) — migrate to Room 3 KMP AND add encrypted
desktop storage (evaluate `s0d3s/SQLCipherMultiplatform`, `bloomberg/selekt`, Zetetic
SQLCipher-for-JDBC for maintenance status + licence before adoption). Desktop gets full
persistence and encryption, so resume-across-restart and transfer history work on
desktop. Phase 09 schedule grows accordingly.

---

## D6 — Desktop discovery implementation

Android uses `android.net.nsd.NsdManager` (see `core/discovery/nsd/`, 4 files).
Desktop needs an mDNS/DNS-SD implementation speaking the same service type and TXT
records, decoded by the existing `TxtCodec`.

**Option A — JmDNS (`org.jmdns:jmdns`) (RECOMMENDED).** Apache-2.0, pure Java,
documented as fully interoperable with Bonjour, and Android NSD is itself
Bonjour-compatible — so the two should meet on the wire.
Known constraint: the standard examples bind via `InetAddress.getLocalHost()`, which
is single-interface. On a multi-homed desktop (Wi-Fi + Ethernet + VPN + Hyper-V
adapters, all common on Windows) that picks the wrong interface. Phase 14 must
enumerate interfaces explicitly and bind deliberately.

**Option B — write a minimal DNS-SD implementation over `MulticastSocket`.** Full
control, no dependency, but re-implementing mDNS correctly is a large task with subtle
failure modes.

⚠️ **This is the highest-risk area of the migration, and the plan previously
scheduled it late.** Per the `nsd-hotspot-discovery` findings, NSD asymmetry over a
Wi-Fi hotspot has already cost this project a debugging cycle once. Cross-platform
mDNS will resurface that class of bug. Phase 14 therefore begins with a **throwaway
spike** before any refactoring is committed.

**ANSWER:** Option A (chosen 2026-08-31) — JmDNS (`org.jmdns:jmdns`). Apache-2.0,
pure Java, Bonjour-interoperable, so it meets Android NSD on the wire. Phase 14 must
enumerate desktop interfaces explicitly and bind deliberately (multi-homed Windows),
and must begin with a throwaway spike before any refactoring is committed.

---

## D7 — UI platform shims: build or adopt?

Three capabilities in `ui:chat` have no common equivalent.

**a) `Toast` — 13 call sites, all in `FlashConversationScreen.kt`.**
Recommended: replace with Material 3 `SnackbarHost` + `SnackbarHostState`, which is
already available in common and already used elsewhere in the app's Scaffolds. This
is a genuine behaviour change on Android (snackbar, not toast) and needs sign-off.
Alternative: an `expect fun showTransientMessage(String)` shim that keeps `Toast` on
Android. Preserves behaviour, adds a platform seam.

**b) File picking — `rememberLauncherForActivityResult(OpenDocument)`.**
Recommended: **FileKit** (`vinceglb/FileKit`) — supports Android, JVM, iOS, macOS,
web, uses native pickers per platform, and covers the save-dialog case too.
Alternative: hand-rolled `expect`/`actual` — `ActivityResultContracts` on Android,
`JFileChooser`/`FileDialog` on desktop. No dependency, more code, worse UX.

**c) Runtime permissions — `RequestPermission` launcher, `ContextCompat`.**
Recommended: `expect suspend fun ensurePermission(...)` returning granted on desktop
unconditionally, since desktop has no runtime permission model. Do **not** pull in a
permissions library for one call site.

**ANSWER:** Recommendation adopted (chosen 2026-08-31) — make all three platform shims **shared**:
- **a)** Replace `Toast` (12 call sites in `FlashConversationScreen.kt`) with Material 3 `SnackbarHost` + `SnackbarHostState` (already available in common). Accepted: Android behaviour change from toast overlay to snackbar (bottom bar, dismissible, queueable).
- **b)** Adopt **FileKit** (`vinceglb/FileKit`) for file picking/saving — cross-platform shared library using native pickers. Android keeps the system document picker; no UX change there.
- **c)** Add `expect suspend fun ensurePermission(...)` — Android `actual` uses existing `RequestPermission` + `ContextCompat` internally; desktop returns granted unconditionally. No permissions library.

**EXECUTED — Phase 19, 2026-09-05 (`94a60a4`). (a) and (c) as answered; (b) overridden on evidence.**

- **a) done as answered**, with two facts the answer did not have. It is **13** call sites, not the 12
  this ANSWER says (the problem statement above says 13; grep confirms 13). And the `SnackbarHost`
  could not go in the `Scaffold`'s `snackbarHost` slot: six of the 13 messages are raised from inside
  the focus overlay and the media viewer, which are emitted *after* the Scaffold and paint over
  anything it owns. It is the last sibling of the screen body instead, and every `showSnackbar` is
  preceded by `currentSnackbarData?.dismiss()` so the newest message wins the way a Toast did.
- **c) done as answered**, except that `ensureGranted` cannot be a *bare* `expect suspend fun`: the
  Android `actual` needs an `ActivityResultLauncher`, which only `rememberLauncherForActivityResult`
  can create, inside a composition. So the seam is `@Composable expect fun
  rememberFlashPermissionRequester(): FlashPermissionRequester`, whose interface carries the
  `suspend fun ensureGranted(FlashPermission): Boolean` the answer asks for.
- **b) FileKit was NOT adopted.** Read at source level, not judged from its README, and ruled out on
  three counts — each a silent behaviour change rather than a compile error:
  1. **Toolchain.** FileKit 0.15.0 needs Kotlin 2.4.10 and CMP 1.11.1; this repo is frozen at 2.2.10 /
     1.9.3 (CONVENTIONS R10), so the newest usable release is 0.11.0. The coordinates named above
     (`com.vinceglb:filekit-compose`) do not exist at **any** version — the group is
     `io.github.vinceglb` and the module is `filekit-dialogs-compose`.
  2. **`audio/*` is not expressible.** `FileKitType.File(extensions)` maps each extension through
     `MimeTypeMap.getMimeTypeFromExtension` and falls back to an all-files wildcard when the set is
     empty. There is no wildcard-MIME path, so the composer's Audio filter would silently become
     device-dependent or all-files.
  3. **Gallery would lose its persistable grant.** `FileKitType.ImageAndVideo` routes to
     `PickVisualMedia` — the Android photo picker, not SAF `OpenDocument`. Photo-picker URIs reject
     `takePersistableUriPermission`, which Flash needs so the transfer engine can keep streaming a
     picked file after the chat screen dies. This is a functional regression in the transfer path, not
     a UX preference — and it contradicts this ANSWER's own *"Android keeps the system document
     picker; no UX change there."*

  What shipped instead is the ANSWER's stated alternative: a hand-rolled `expect`/`actual` —
  `OpenDocument` on Android, `JFileChooser` on desktop (**not** AWT `FileDialog`, whose only filter
  hook is `setFilenameFilter`, which Windows ignores outright — the Gallery and Audio filters would
  become all-files with no warning). No dependency was added, so PHASE-19's Step 14 (`libs.versions.toml`
  FileKit alias) is a no-op and R10 is untouched.

  One objection to FileKit **was** cleared and is recorded so it is not re-raised: it auto-initialises
  from `LocalActivityResultRegistryOwner`, so adopting it would not have required an `:app` change.
  **Revisit after a Kotlin bump** — at a current CMP, only objections 2 and 3 remain, and both are
  about Flash's specific picker contract rather than about FileKit's quality.

---

## D8 — Do the desktop screens in old plan §15 exist?

Old plan §14 lists "device cards, transfer cards, progress indicators, status
indicators" as components to share, and §15 mocks up a two-pane desktop
devices+transfers layout.

The shipped UI is a **chat application** (`ui:chat` 46 files + `ui:theme` 18 files).
The devices-and-transfers UI in those mockups is not obviously present in shareable
form; `app/` holds `MainActivity`, a dev console, and a stress-test screen.

**Option A — desktop ships the existing chat UI, adaptively laid out (RECOMMENDED).**
Honest about what exists. Phase 22 becomes "arrange existing composables for wide
windows", which per AUDIT.md CORRECTION 9 is largely already possible.

**Option B — build the devices+transfers desktop UI from the §15 mockups.** This is
**new feature work**, not migration. It should be its own project after Phase 23.

**Do not let an agent silently pick B and report it as "sharing the UI".**

**ANSWER:** Option A (chosen 2026-08-31) — desktop ships the existing chat UI, adaptively laid out.

---

## D9 — What happens to `sample/consumer` and `sample/consumer-granular`?

These reproduce a downstream Android compile classpath (publishing Phase 2 Task 2.3).
Once `core/*` is KMP, they validate only the Android artifact.

Recommended: keep them Android-only as-is through Phase 23, then add a
`sample/consumer-desktop` in Phase 24 to validate the JVM artifact. Not a blocker.

**ANSWER:** Option A (chosen 2026-08-31) — keep `sample/consumer` and
`sample/consumer-granular` Android-only as-is through Phase 23; add
`sample/consumer-desktop` (pure JVM) in Phase 24 to validate the desktop artifact.

---

## D10 — How does the desktop reach the `:core:transfer` pipeline?

Added 2026-09-05, by the agent that reached Phase 13 and could not execute it. **This is a new
decision, not a re-litigation of D1.** D1 chose strict `commonMain`; D10 is the consequence nobody
costed at the time.

**Today (measured, repo at `d8af05c`):** `:core:transfer` is 5 `commonMain` + 15 `androidMain` files.
**Not one of the 15 references `android.*` or `androidx.*`.** They are `androidMain` because they use
`java.io` byte streams, `java.nio.ByteBuffer` framing, `java.security.MessageDigest`,
`java.util.concurrent` atomics and maps, `java.util.UUID` and `java.util.BitSet` — and under D1 = B
there is no shared JVM tier to hold them. `androidMain` and `jvmMain` are siblings, so **nothing in
`jvmMain` can see any of it**: a compile probe of Phase 13's own proposed adapters produced 11
`Unresolved reference` errors, including the `chunked` and `policy` **packages** themselves.

The blocker is specifically that `java.io.InputStream` appears in *published* `public` signatures:
`ChunkSource.open(): InputStream`, `FileSourceOpener.open(String): InputStream`,
`RandomAccessSinkHandle : Closeable`, `FileRandomAccessSinkHandle(File, Long)`.

**Option A — adopt a multiplatform I/O library and re-type the seams (RECOMMENDED).**
`kotlinx-io` (`kotlinx.io.Source`/`Sink`/`RawSource`) or Okio. One new dependency. ABI change on
four `public` types, of which three have **zero** first-party consumers; the three `RandomAccess*`
types have exactly two callers each (`core/engine/…/Flash.kt`, `app/…/DiscoveryEngineHolder.kt`),
both constructing over a `java.io.File`, so an Android-side `File` overload keeps consumer edits at
zero. The only option under which a Kotlin/Native target can ever compile this module — which is the
same hole R6.1 records.

**Option B — in-repo `expect`/`actual` typealiases to `java.*`.** `public expect class
PlatformInputStream` with `actual typealias PlatformInputStream = java.io.InputStream` in both
`androidMain` and `jvmMain`. No new dependency, no consumer edits, Android JVM signatures unchanged.
But it is JVM-shaped multiplatform: a native target has nothing to alias to, and `java.nio.ByteBuffer`
has no native analogue at all, so this defers the problem rather than solving it. Cheapest path to a
working desktop transfer; dead end for "all platforms".

**Option C — duplicate the pipeline in `jvmMain`.** No ABI change, no dependency, Android
`ChunkFrame` stays byte-identical. Rejected on principle: two independent implementations of a wire
format is exactly what R8 exists to prevent, and the one duplicate the repo already has
(`AutoConnectGate`) has been filed as a Known issue twice. Listed for completeness.

**Option D — desktop gets no transfer pipeline.** `:core:transfer`'s desktop artifact stays
contract-only (15 interface/data classes, no chunker, no sink). Phase 13 becomes a documented no-op,
Phase 15's desktop transport carries bytes for a pipeline that does not exist on the desktop side,
and Phase 16's headless interop gate cannot pass. This is the honest description of doing nothing.

**Constraint that applies to A, B and C alike:** `chunked/ChunkFrame.kt` builds the CHUNK wire frame
with `java.nio.ByteBuffer`, and `ChunkFrame` is named in R8's untouchable list. Whichever option is
chosen, the framing rewrite needs an explicit authorisation from the human, with byte-identical
output as the acceptance criterion. See `PHASE-13B-desktop-fileio.md` §13B-3.

**Recommendation:** **A**, staged. Run 13B-1 now (decision-free, no dependency, no ABI change),
then adopt the I/O library in 13B-2, then do the framing/hashing/atomics port in 13B-3 under an
explicit R8 authorisation.

**ANSWER:** **Option A (chosen 2026-09-05)** — adopt a multiplatform I/O library and re-type the four
seams. Staged exactly as recommended: 13B-1 (already done, `fafd450`) → 13B-2 adopts the library and
re-types `ChunkSource.open()`, `FileSourceOpener.open(String)`, `RandomAccessSinkHandle` and
`FileRandomAccessSinkHandle` → 13B-3 ports the framing, hashing and atomics. An Android-side `File`
overload keeps first-party consumer edits at zero (`core/engine/…/Flash.kt`,
`app/…/DiscoveryEngineHolder.kt`). This is the only option under which a Kotlin/Native target can ever
compile `:core:transfer`, which is the same hole CONVENTIONS.md R6.1 has recorded since Phase 07.

**R8 AUTHORISATION FOR 13B-3 (granted 2026-09-05):** the human has explicitly authorised the rewrite of
`chunked/ChunkFrame.kt`, which builds the CHUNK wire frame with `java.nio.ByteBuffer` and is named on
R8's untouchable list. **The acceptance criterion is byte-identical output** against the current Android
implementation: golden vectors captured from the existing frames before the rewrite, asserted after it,
and **13B-3 does not ship if any byte differs**. This authorisation covers `ChunkFrame` only. It does
**not** extend to `FlashEnvelope`, `FlashProtocol`, `MessageWireFrame`, `WsTransferMessages`, `TxtCodec`
or `FlashPairingFrames`, which remain untouchable under R8 without a separate authorisation.

The choice between `kotlinx-io` and Okio is left to 13B-2 as an implementation detail, to be decided on
evidence and recorded in its log entry. It is a new dependency, so it needs a new
`gradle/libs.versions.toml` alias — permitted because R10 allows a phase that explicitly adds a
dependency to add one, and 13B-2 is that phase.

---

## D11 — Is the calling stack (`:core:calling` + `:ui:callui`) in desktop scope?

Added 2026-09-05, by the agent that finished Phase 20 and audited the phase index. **This decision
exists because the plan never had one.** Grepping every file in `docs/migration/` for `core:calling`
returns hits in `CONVENTIONS.md` and `README.md` only — **no phase file mentions `:core:calling` at
all**, and none converts or gates `:ui:callui` either.

**Today (measured 2026-09-05):** both modules are still `com.android.library`. `:ui:callui` depends on
`:ui:theme` (`ui/callui/build.gradle.kts:62`) and names `FlashIconSpec` (`FlashCallScreen.kt:486`), so it
sits inside the blast radius of Phases 17, 18 and 19 — all three of which are done. The verification runs
for 09B-1, 17, 18 and 19 each added `:ui:callui:compileDebugKotlin` **by hand**, because nothing in the
plan compiles it. Since Phase 18 it has been compiling against a `:ui:theme` that is multiplatform, so
the gap widens with every UI phase instead of holding still. `:core:calling` is the WebRTC module and is
the substantive half of the problem.

**Option A — out of scope, documented.** Both stay Android-only permanently. Cheap and honest, but leaves
`:ui:callui` structurally exposed to every future `:ui:theme` change with only a hand-added compile task
as a net.

**Option B — in scope, research first (RECOMMENDED, and chosen).** A new phase file whose **first step is
a research step**, not a conversion step: which WebRTC implementation exists for desktop JVM, what it
would pin against the frozen toolchain (R10: Gradle 9.5.0 / AGP 9.3.1 / Kotlin 2.2.10 / JVM_11), whether
the existing signalling and `:core:messaging` seams survive it, and what the ABI consequences are. It
reports before proposing any conversion. **WebRTC on desktop is not a small assumption to make
silently** — that is exactly why this is a decision and not an agent judgement call.

**Option C — defer, but wire the gate.** Add `:core:calling` and `:ui:callui` compile tasks to the
documented R3 command line so the widening gap is *measured* rather than assumed, and decide later.

**ANSWER:** **Option B (chosen 2026-09-05)** — **in desktop scope, research first.** The phase must open
with the WebRTC-for-desktop-JVM investigation and report its findings before proposing any conversion
work; it must not begin by converting either module. Option C's gate wiring should be folded in as a
cheap side-effect regardless of what the research concludes, since it costs one line in the R3 command
and turns an assumption into a measurement.

**This decision blocks nothing that already exists.** It authorises a *new* phase, whose number is TBD
and which must not be inserted ahead of 13B-2/15/16 — D10 = A has just unblocked the critical path, and
the Phase 16 interop gate outranks calling.
