# PHASE-29 — Desktop conversation (the chat repository actually has three blockers, not one)

**Status:** AUTHORED (2026-09-13, planning only — **no code written**).
**BLOCKED ON A HUMAN DECISION** (D5 = C's three sub-answers, restated and narrowed below).
**Risk:** HIGH — this is the phase that unblocks the desktop's only missing *screen*, and it crosses
persistence (R8-adjacent: the encrypted DB), a D1 cleanup, and a test migration.
**Decisions relied on:** D1 = B (strict `commonMain`), D5 = C (encrypted DB, 09B charter), R2, R4,
R6, R7.

---

## What this phase is for

Desktop has no conversation screen. Verified today:

- `DesktopShell.kt:227–240` — `FlashDestination.Conversation` renders **`FlashChatListScreen`** with
  a comment saying the shell "never routes here". Clicking a chat on desktop navigates and then
  shows the chat list again.
- `DesktopEngine.kt:153` — `public val chats: FlashChatRepository = EmptyFlashChatRepository`.
- The phone calls the **same** `FlashConversationScreen` (`MainActivity.kt:962`) that desktop would;
  nothing about the screen is platform-shaped. Only the repository is missing.

The project record (README row 23, PHASE-21's notes, PHASE-26) says this is "blocked on 09B-2 — the
encrypted file-backed opener". **That is true but incomplete, and reading the code narrows it
usefully.** There are **three** separate blockers, only one of which is 09B-2.

## Verified blocker census (2026-09-13, from the tree)

### Blocker 1 — the encrypted desktop driver (this *is* 09B-2, and it needs the human)

**Much more of this is already done than the record implies.** `:core:persistence` has a `jvm()`
target (`build.gradle.kts:81`), 26 entity/DAO files already live in `commonMain`, and
`core/persistence/src/jvmTest/.../FlashDatabaseJvmTest.kt` **proves the generated Room tier
actually runs on the JVM**: all eleven tables open, `OnConflictStrategy.IGNORE` honours the
annotation, `InvalidationTracker` re-emits after a write, read cursors and group-delivery
aggregates work. Room's `kspJvm` processor emits a working multiplatform `FlashDatabase_Impl`.
That is 09B-1, and it is green.

What is missing is exactly one thing, stated by that test's own KDoc:

> **The driver is `BundledSQLiteDriver`, which is UNENCRYPTED.** PHASE-09B permits it here and
> nowhere else, and only in memory: `inMemoryDatabaseBuilder` passes `name = null`, so no file path
> — and no `":memory:"` string literal — is involved at all. Putting this driver in `jvmMain`, or
> giving it a path, is "B without C" under D5's charter and is forbidden. **The encrypted desktop
> driver is 09B-2's problem and needs a human decision first.**

So the decision is narrower than "which desktop DB". It is: **what encrypts a file-backed SQLite
database on the JVM, and is the licence acceptable.** SQLCipher for Android
(`net.zetetic.database.sqlcipher.SupportOpenHelperFactory`, wired at
`core/persistence/src/androidMain/.../FlashDatabaseOpener.kt:39`) has no JVM sibling in the current
dependency set. The three sub-answers D5 = C is waiting on map one-to-one onto this.

**This phase cannot start until that is answered.** Everything below is scoped so it can be
executed the day it is.

### Blocker 2 — `RealFlashChatRepository` is Android-only by *residence*, not by content

`core/messaging/src/androidMain/.../RealFlashChatRepository.kt` (2,600+ lines) imports only
`:core:common`, `:core:persistence`'s DAOs/entities and coroutines — **no `android.*` at all**. It
could move to `commonMain` today except for five `java.*` imports:

| Import (line) | Uses | Shared replacement |
|---|---|---|
| `java.util.UUID` (62) | 8× `UUID.randomUUID()` (707, 708, 767, 824, 895, 951, 1026, 1185…) | `UuidIdGenerator` — `core/common/.../id/FlashIdGenerator.kt:30` |
| `java.util.concurrent.ConcurrentHashMap` (63) | `groupTitleCache` (198), `typingStates` (249, nested) | `SyncMap` — see below |
| `java.text.SimpleDateFormat` (59) | date-separator formatting | **no shared equivalent found** |
| `java.util.Date` (60) | paired with the above | `SystemTimeSource` — `core/common/.../time/FlashTimeSource.kt:20` |
| `java.util.Locale` (61) | paired with the above | **no shared equivalent found** |

`SyncMap`/`SyncList` **exist** but are `internal` to `:core:calling`
(`core/calling/src/commonMain/.../PlatformMonitor.kt:28`/`:58`) — Phase 25 S2e put them there. This
phase must either **promote them to `:core:common`** (they are generic, non-`internal` in spirit,
and this is the second module to need them) or duplicate them, which R2's spirit forbids. Promoting
is the right call and is a one-file move plus two visibility edits.

The `SimpleDateFormat`/`Locale` pair is the only genuinely new seam. `:ui:chat` already formats date
separators for display (`FlashDateSeparatorLogicTest`), so the question is whether the repository
should format at all or emit a timestamp and let the UI format — **a design decision this phase must
make explicitly, not by accident.**

### Blocker 3 — the test suite lives in `androidHostTest` and constructs a real DB

`core/messaging/src/androidHostTest/.../RealFlashChatRepositoryTest.kt` is ~2,600 lines with ~20
`RealFlashChatRepository(` construction sites, plus `GroupLateJoinDiagnosticTest.kt:397`. Today they
run on the Android host JVM.

Two consequences:

- Moving the repository to `commonMain` **breaks these tests' compilation** unless they move to
  `commonTest` — which puts them on the `jvm()` target too, where they will need a database. That is
  fine *only after* Blocker 1, because `FlashDatabaseJvmTest`'s KDoc forbids giving
  `BundledSQLiteDriver` a file path. **So the tests are gated on the same decision as the feature.**
- The move is therefore not "relocate a file"; it is "relocate a file and a 2,600-line suite and
  re-point ~20 construction sites at a JVM-capable database factory". Budget accordingly.

## Design sketch (to be finalised after the decision)

1. **A database-open seam.** The Android opener is `FlashDatabaseOpener.openEncrypted(context, …)`
   — `Context`-shaped, so it cannot be the shared abstraction. Introduce a target-free
   `FlashDatabaseProvider` (or equivalent) in `:core:persistence/commonMain`, with the Android
   actual delegating to today's `openEncrypted` and a new JVM actual delegating to whichever driver
   the decision picks. The `Context` never crosses the seam.
2. **Promote `SyncMap`/`SyncList`** to `:core:common`.
3. **D1 cleanup** of the five `java.*` imports in `RealFlashChatRepository`.
4. **Move** `RealFlashChatRepository` + its suite to `commonMain`/`commonTest`.
5. **Wire** `DesktopEngine.chats` to the real repository (`DesktopEngine.kt:153`) and delete the
   `FlashDestination.Conversation` fallback in `DesktopShell.kt:227–240`.
6. **Conversation-specific desktop work** — which is *not* nothing, but is small: the desktop needs
   the same pointer-idiom treatment Phase 28 builds (right-click context menu at the cursor,
   hover, keyboard), plus **a detail-pane host** rather than a full-screen push, which Phase 22
   deliberately deferred (its C3: "conversation pane deferred: desktop binds
   `EmptyFlashChatRepository` until 09B-2").

## Do NOT

- **Do NOT ship `BundledSQLiteDriver` with a file path.** The KDoc above is an explicit prohibition
  from the 09B charter, not a suggestion. An unencrypted desktop database is a security regression
  against `docs/security.md`.
- **Do NOT move the repository before the D1 cleanup.** A `java.*` import in `commonMain` is an R6
  violation and `compileKotlinJvm` will not see `android.jar` to hide it — it will fail loudly at
  least, but only after the move is half-applied.
- **Do NOT delete the `Conversation` fallback** until the repository is actually bound; the fallback
  is what keeps the `when` exhaustive and the shell honest.
- **Do NOT touch `FlashDatabaseOpener`'s Android behaviour or `FlashMigrations`** — R8 surface, and
  the migrations are shared with shipped installs.
- **Do NOT re-derive the message/outbox logic.** The repository is 2,600 lines of load-bearing
  behaviour with a suite behind it; this phase *moves* it, it does not rewrite it.

## Sub-step plan

| # | Sub-step | Content |
|---|---|---|
| 29-0 | **Decision (human)** | D5 = C's three sub-answers: which JVM encrypted driver, is its licence acceptable, is file-format parity with Android's SQLCipher required? Nothing below runs until this is answered. |
| 29-1 | Open seam | `FlashDatabaseProvider` in `:core:persistence/commonMain`; Android actual delegates to today's `openEncrypted` unchanged. |
| 29-2 | Promote sync types | `SyncMap`/`SyncList` `:core:calling` → `:core:common`, public, `explicitApi()` KDoc. `:core:calling` repointed. |
| 29-3 | D1 cleanup | Five `java.*` imports replaced; the date-format question answered explicitly and recorded. |
| 29-4 | JVM driver actual | Per 29-0. A `FlashDatabaseJvmTest` companion proving open + encrypt + reopen, in `jvmTest`. |
| 29-5 | Move repository + suite | `commonMain`/`commonTest`; ~20 construction sites re-pointed. |
| 29-6 | Desktop wiring | `DesktopEngine.chats` real; shell fallback deleted; conversation hosted as a detail pane (Phase 22's deferred C3) with Phase 28's pointer idioms. |
| 29-7 | Verification | R3 sweep; `:core:persistence:jvmTest` + `:core:messaging:jvmTest`; `:app:assembleDebug`; R6 scan. **Plus a human run**: send a message phone→desktop and desktop→phone, kill the desktop app, relaunch, confirm history survived. |
| 29-8 | Log + README | Honest entry; row 23's "DEFERRED" cells updated. |

## Why this file leads with a decision

Phase 21 recorded the conversation fallback, Phase 22 deferred the detail pane, and row 23 marked the
desktop cells DEFERRED — all three pointing at "09B-2". None of them records that **09B-1 already
made the JVM Room tier work**, so the remaining gap is one driver choice away rather than a module
away. Writing that down is the point of this phase file; executing it is a day's work after the
answer.
