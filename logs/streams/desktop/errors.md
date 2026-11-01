# Stream E (Desktop) — Errors & Fixes Log

## ERROR-941 — Kotlin Function Type Prohibits Named Arguments in DesktopShell

### Date
2026-11-01

### Area
Desktop Compose UI / Drag-and-Drop Optimization (OPT-O19-01)

### Symptoms
Kotlin compiler error in `DesktopShell.kt:935`:
`Named arguments are prohibited for function types. No parameter with name 'peerId' found.`

### Environment
- Kotlin 2.1.0 / Compose Multiplatform
- Module: `:desktop`
- Target: JVM Desktop

### Error
```text
e: file:///E:/FlashWork/opt-desktop/desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt:935:25
Named arguments are prohibited for function types. No parameter with name 'peerId' found.
```

### Root cause
`val currentSendFileToPeer by rememberUpdatedState(::sendFileToPeer)` coerced the local member function reference into a Kotlin `Function2<...>` lambda type. In Kotlin, invoking a function type variable using named parameters (`currentSendFileToPeer(peerId = ...)` is prohibited by the language specification because parameter names are not preserved on function interfaces.

### Failed attempts
1. Invoking `currentSendFileToPeer(peerId = peerId, paths = paths)`.

### Working fix
Removed the `rememberUpdatedState` wrapper for the stable local function and called `sendFileToPeer(peerId = peerId, paths = paths)` directly.

### Verification
`:desktop:compileKotlinJvm` compiled successfully.

### Status
RESOLVED

---

## ERROR-942 — Unresolved Hub Telemetry and Security Types During Shell Decoupling

### Date
2026-11-01

### Area
Desktop Compose UI / StateFlow Decoupling (OPT-O03-10)

### Symptoms
Compilation error during extraction of `DesktopSettingsDestination`:
`Unresolved reference 'hubStatus'` and `Unresolved reference 'EnterpriseCredentials'`.

### Environment
- Kotlin 2.1.0 / Compose Multiplatform
- Module: `:desktop`
- Target: JVM Desktop

### Error
```text
e: file:///E:/FlashWork/opt-desktop/desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt:1714:21
Unresolved reference 'hubStatus'.
```

### Root cause
`hubStatus` was needed by `FlashDestination.Hub` in the main shell body as well as in settings. Additionally, enterprise credentials in `DesktopEngine` use type `com.transfer.flash.core.security.enterprise.EnrollmentCredentials?` rather than `EnterpriseCredentials`.

### Working fix
Retained `hubStatus` StateFlow collection at the top of `DesktopShell` for `FlashDestination.Hub` and forwarded it to `DesktopSettingsDestination`; imported `EnrollmentCredentials` from `com.transfer.flash.core.security.enterprise`.

### Verification
`:desktop:compileKotlinJvm` and all desktop tests compiled and passed.

### Status
RESOLVED
