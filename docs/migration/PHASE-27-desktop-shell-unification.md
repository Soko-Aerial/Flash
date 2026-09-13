# PHASE-27 — Desktop shell unification (one shell, two hosts)

**Status:** AUTHORED (2026-09-13, planning only — **no code written**). **NOT execution-ready:**
see the open decision at the bottom. This is a *prerequisite* phase — Phases 28–33 each assume it,
because without it every per-screen desktop change lands twice and the two frames drift further.
**Risk:** MEDIUM-HIGH — it refactors the composable that is the Android app's entire navigation.
No new features, no crypto, no protocol. Behaviour must be **byte-for-byte identical on Android**.
**Decisions relied on:** D8 = A ("desktop ships the existing chat UI, adaptively laid out"),
D11 = B, R2 (never move code to `commonMain` to make something compile — this phase moves code for
a *reason* and says what it is), R5 (plain `jvm()`), R6 (no `java.*`/non-Compose `androidx.*` in
`commonMain`), R7 (`explicitApi()`).

---

## What this phase is for

**Read this before the rest: the screens are already shared.** The per-screen work you expect to do
for desktop is *not* a port. Verified 2026-09-13 against the tree:

| Surface | Android | Desktop | Shared? |
|---|---|---|---|
| Chat list | `FlashChatListScreen` — `MainActivity.kt:1422` | same composable — `DesktopShell.kt:207` | **yes** |
| Transfers | `FlashTransfersScreen` — `MainActivity.kt:1304` | same — `DesktopShell.kt:241` | **yes** |
| Nearby | `FlashNearbyScreen` — `MainActivity.kt:1346` | same — `DesktopShell.kt:292` | **yes** |
| Settings | `FlashSettingsScreen` — `MainActivity.kt:1376` | same — `DesktopShell.kt:327` | **yes** |
| Pairing dialog | via `FlashNearbyScreen` | via `FlashNearbyScreen` | **yes** |
| Conversation | `FlashConversationScreen` — `MainActivity.kt:962` | **absent** (falls back to the chat list) | no |
| Call screen | `FlashCallScreen` overlay — `MainActivity.kt:1594` | **absent** | no |

All four tab screens live in `:ui:chat/commonMain` and are called by both hosts today. The pairing
dialog is shared the same way: **neither** shell calls `FlashPairingDialog` directly — both go
through `FlashNearbyScreen.kt:164–173`, which calls it. There is nothing to port.

**What is duplicated is the FRAME**, and it is duplicated expensively:

- **`:app` — `FlashShell`**, `app/src/main/java/com/transfer/flash/MainActivity.kt:583–1635`:
  a **~1,052-line composable inside a 2,016-line `MainActivity.kt`**. It renders all five
  `FlashDestination`s (Conversation 962, Transfers 1304, Nearby 1346, Settings 1376, ChatList 1422),
  owns `FlashBottomNav` (1525), and hosts the call screen as a topmost overlay (1594). A comment at
  line 642 still calls it "~750-line composable" — stale.
- **`:desktop` — `DesktopShell.kt`**, ~460 lines: renders four of the five destinations, owns
  `DesktopSideBar` + the Phase‑22 two-pane arrangement, and substitutes a fallback for
  `FlashDestination.Conversation` (`DesktopShell.kt:227–240`).

Phase 22 already made the *desktop* frame adaptive (sidebar on Expanded, `FlashBottomNav` on
Compact/Medium). The Android frame does not adapt at all — `FlashAdaptiveMath`
(`ui/chat/.../adaptive/FlashAdaptiveLayouts.kt`) is tested but has **no production consumer**.

So there are two jobs here, and they are the same job:

1. There must be **one** shell, not two, or every later screen phase is written twice.
2. The adaptive math that already exists and is already tested must become the shell's own
   behaviour on both hosts — which is exactly the "the UI transforms as it grows" shape you asked
   about, and it is the arrangement Material's window size classes prescribe.

## The prerequisite nobody has paid yet: the shells read different engines

This is the reason this phase cannot be a pure move. Merging the shells without first putting a
common facade in front of both means the shared shell gets a platform-shaped data layer and the
next divergence is worse than today's.

- `:app`'s shell reads `AppEngine` (`app/.../engine/AppEngine.kt`) → `DiscoveryEngineHolder`,
  a large Android composition root: `engine.chats` (`MainActivity.kt:610`), `engine.calls`,
  `engine.settingsStore` (`:417`), transfers, discovery, trust, identity.
- `:desktop`'s shell reads `DesktopEngine` (`desktop/.../DesktopEngine.kt`), which the Phase 21
  record is explicit about: **it is NOT a `FlashEngine`.** It assembles the Phase‑16-proven stack
  by hand — `CompositeDiscovery`/`JmdnsTransport` (223–231), `JvmWsFlashNetwork` (217),
  `RealFlashTransferRepository` (235), `EmptyFlashChatRepository` (153).

They are two independent composition roots that happen to expose overlapping members with
overlapping-but-not-identical types. A shared shell needs a **facade interface in `commonMain`**
that both satisfy, and each host keeps its own composition root behind it.

> **The honest risk:** if the facade is drawn from *neither* host's real shape — drawn from the
> union of what both happen to have today — it will be wrong in a way that only shows up in
> Phase 33 (calls need members desktop has no equivalent for: `FlashCallService`,
> `FlashCallRinger`, `FlashCallAudioRouter` are all `:app` classes). Draw the facade from the
> **screen call sites** (`FlashShell`'s own reads), not from either engine's surface.

## Open decision (blocks execution, needs a human)

**Where does the shared shell live, and what is its dependency edge?**

| Option | Shape | Verdict |
|---|---|---|
| **A — `:ui:chat`** | Shell moves into `:ui:chat` beside `FlashBottomNav` (`ui/chat/.../ui/shell/`). Zero new modules. | Cheapest. But `:ui:chat` would gain a dependency on the facade's module, and the facade needs `:core:calling` types for Phase 33 — dragging calling into the chat module's graph. |
| **B — new `:ui:shell`** | New KMP module above `:ui:chat`, `:ui:callui`, `:ui:theme`, owning the frame + facade. `:app` and `:desktop` both depend on it. | **Recommended.** Keeps `:ui:chat` free of a calling edge, and gives the frame a home whose name says what it is. Costs one module in `settings.gradle.kts` + a Phase‑24 publishing row. |
| C — facade in `:core:engine` | Shell keeps reading a core type. | Rejected — `:core:engine` is a JVM-thin module carrying six `api()` cores; a UI facade there inverts the dependency direction and R2/R6 pressure would be immediate. |

**This phase is NOT execution-ready until A/B/C is picked.** (Precedent: D10/D11 were picked by the
human before their phases ran, and Phase 22 was authored with a correction block *before* coding.
This is a smaller version of the same fork.)

## Do NOT

- **Do NOT change Android behaviour.** Not the nav animation, not the bottom bar's visibility
  rules, not the back handling, not the call overlay's z-order. This phase's only observable
  Android change should be **none**. If a screen looks different on the phone afterwards, the
  phase failed.
- **Do NOT delete `FlashBottomNav` or `DesktopSideBar`** — both are load-bearing depending on the
  window class; the unified shell chooses between them via `FlashAdaptiveMath`.
- **Do NOT implement Conversation or Calls here.** They are Phases 29 and 33; this phase builds the
  frame that will host them and leaves today's fallbacks exactly as they are.
- **Do NOT move `RealFlashChatRepository`** (androidMain) to satisfy the facade. Phase 29 owns that;
  forcing it here is precisely the R2 violation ("never move code to `commonMain` to make something
  compile").
- **Do NOT touch `:core,*` build files** (R4).

## Sub-step plan

| # | Sub-step | Content |
|---|---|---|
| 27-1 | Facade interface | Define the shell's facade in the module chosen above, `commonMain`, `explicitApi()`. Derive it from `FlashShell`'s actual reads, enumerated as a list in the file's KDoc — not from either engine's surface. Every member is a `Flow`/`StateFlow` + a suspend/listener action, no platform types. |
| 27-2 | Desktop adapter | `DesktopEngine` implements the facade. Pure delegation; no behaviour change to `DesktopShell` yet. |
| 27-3 | Android adapter | `AppEngine`/`DiscoveryEngineHolder` implements it. Pure delegation. `:app:assembleDebug` must stay green with `FlashShell` untouched — this proves the facade is satisfiable from the Android side before anything moves. |
| 27-4 | Extract the shell | Move `FlashShell` (584–1635) out of `MainActivity.kt` into the shell module, taking the facade as its parameter. Mechanical move: **no logic rewrites in this step.** `MainActivity.kt` shrinks to ~960 lines and keeps its Android-only pieces (permissions, intents, `onSettingsChange`, the call overlay host). |
| 27-5 | Point `:desktop` at it | `DesktopShell` becomes a thin host: build the facade, hand it to the shared shell, keep the desktop-only extras (side-bar width, detail-pane selection) as parameters. Delete the duplicated destination `when`. |
| 27-6 | Adaptive on both | The shared shell consumes `FlashAdaptiveMath` for nav-frame choice. Android gains the adaptive frame it never had (tablet/desktop-mode). Must be verified **identical** in phone portrait. |
| 27-7 | Verification | `:app:assembleDebug`, `:desktop:compileKotlinJvm`, `:ui:chat:compileKotlinJvm` + `:ui:chat:compileAndroidMain`, `:ui:chat:jvmTest`, full R3 sweep; R6 scan on the new module; and a **manual phone launch** diffing the five destinations against the pre-phase build. |
| 27-8 | Log + README | Honest entry; README rows 27–33 added together (they are one plan). |

## Verification gates

- R3 (Android builds **and** tests pass) — non-negotiable, this phase touches the app's main screen.
- `:ui:chat:jvmTest` — the Phase‑22 adaptive math tests must stay green; they are the shared
  breakpoint contract the new frame now depends on.
- **Manual, human-run:** launch `:app` on the phone and `:desktop:run` on Windows, step through all
  five destinations on each. This phase's whole claim is "no visible change", and that claim is only
  checkable by eye. Do not mark it DONE on compile evidence alone — Phase 21's record is explicit
  that compile-verified is not gate-verified.
