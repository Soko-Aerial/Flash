# Handoff — Stream E (`rvw22-e-platform`)

Fastest read after `progress.md`: where the work stands and what the owner must do.

## Current branch
`agent/rvw22-e-platform`, worktree `E:\FlashWork\rvw22-e-platform`, off `dev` (base `93212c7f`).
Nine commits, **not pushed**, `dev` and `main` untouched.

## Head
`85b0ed2b` (this docs commit). Last code commit: `79c6d002` (F74).

## Last verified build
`tools\flash-gradle.cmd :core:radio:jvmTest :ui:platform-shims:jvmTest :ui:platform-shims:testAndroidHostTest :core:discovery:jvmTest :core:discovery:testAndroidHostTest :app:compileDebugKotlin :desktop:compileKotlinJvm --console=plain`
→ BUILD SUCCESSFUL in 7m8s, on the exact code of the commits above.

## Where the nine findings ended
| Finding | Verdict | Commit |
|---|---|---|
| F50 camera capture lost on rotation/process death | FIXED | `0d6dec8c` |
| F51 concurrent permission requests hang / cross-answer | FIXED | `ff30c485` |
| F53 stale NSD advertise listener | FIXED (differs from the audit's fix) | `e7a021ed` |
| F61 missing `RECEIVER_NOT_EXPORTED` | NOT-A-BUG (flag would break Bluetooth) | `81398a57` |
| F62 slice-guard int overflow | FIXED | `894abf99` |
| F63 EXIF behind JPEG fill bytes | FIXED | `f202ca55` |
| F67 path traversal in `resolveLocalFile` | CANNOT-CONFIRM | — |
| F72 multicast lock skipped at API 34+ | DEFERRED (DISC-07) | `bd3ba618` |
| F74 no teardown in the audio shims | FIXED | `79c6d002` |

## What is proven
Unit/host tests green and a 5-mutation check where every reverted production change made a new
test fail (F50, F51, F62 ×2, F63). `:app:compileDebugKotlin` and `:desktop:compileKotlinJvm` green.

## What is NOT proven
Everything device-side. F53 has no test at all (no `NsdManager` seam — compile-verified only) and
F74 has none (needs native media classes). No phone was touched, nothing was deployed.

## Owed
`R22E-01` … `R22E-07` in `backlog-additions.md` (camera across rotation/death; two permission
dialogs; NSD re-advertise; fill-byte JPEG on Windows; repeated voice playback/recording; DISC-07 for
F72; an Android 14+ radio discovery regression watch for F61).

## Two things a merger must not "fix" from the audit
1. **F61** — do not add `RECEIVER_NOT_EXPORTED` to `AndroidRadioDiscovery`'s receivers; it would stop
   discovery and pairing. `RECEIVER_EXPORTED` would be the only safe flag if one ever becomes
   mandatory (URLs in `platform-notes-additions.md`).
2. **F53** — do not move `advertiseListener = listener` after `registerService`; the asynchronous
   `onRegistrationFailed` case needs the listener published before the call.

## Rule 8 note
`NsdResolveQueue.kt` and the NSD resolve path (`NsdTransport.kt` ~458-470) were not touched; the F53
edit is confined to `advertise` (~341). No other rule-8 file was edited.

## Recommended next task
Merge the branch into `dev` (nine commits, no conflicts expected — none of the rule-8 files moved),
then move `R22E-01..07` into `docs/testing/TEST-BACKLOG.md` and run `R22E-02` and `R22E-03` first:
they are the two fixes whose host tests cannot reach the real camera/permission launcher and the real
`NsdManager`.
