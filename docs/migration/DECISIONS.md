# Open Decisions — HUMAN INPUT REQUIRED

**Blocking map (which phase each decision gates):**

| Decision | Blocks | Notes |
|---|---|---|
| **D1** | **Phase 06** | The KMP pilot cannot start until the source-set strategy is chosen. Highest-leverage decision in the migration. |
| **D2** | **Phase 06** | Only if you choose to rename; if D2 = B, the rename is its own phase run **before** Phase 06. If D2 = A, non-blocking. |
| **D3** | **Phase 18** | Compose dependency source. Does **not** block Phase 06. |
| **D4** | **Phase 18** | Dynamic-color replacement. Does **not** block Phase 06. |
| **D5** | **Phase 09** | Desktop persistence strategy. |
| **D6** | **Phase 14** | Desktop discovery implementation. |
| **D7** | **Phase 19** | UI platform shims. |
| **D8** | **Phase 22** | Whether the §15 desktop screens exist. |
| **D9** | Phase 24 | Sample consumers; agent may proceed on the recommendation. |

Earlier drafts said "D1–D4 block Phase 06." That was wrong: D3 and D4 are Compose
decisions and cannot affect a pilot that converts a Compose-free module (`core:common`).
Phase 06's own header states this; this table is the source of truth.

Each decision lists options, consequences, and a recommendation. An agent must
**not** pick for the human on D1, D2, D5, or D8 — those change the shape of the
project. For D3, D4, D6, D7 an agent may proceed with the recommendation if the human
has not answered, but must record that it did so in the phase log.

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

**ANSWER:** _pending_

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
