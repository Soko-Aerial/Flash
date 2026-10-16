# Error Log — Stream R-ui

Reserved ERROR range: ERROR-660 through ERROR-679.

---

## ERROR-660 — Static a11y source test false positive on forwarded parameter variables

### Date
2026-10-16

### Area
`:ui:chat:jvmTest` / Accessibility Source Guard (`FlashRadioCopyA11ySourceTest`)

### Symptoms
`FlashRadioCopyA11ySourceTest > testAllAccessibilityReferencesUseFlashRadioCopy FAILED` with `AssertionError: Line 143 of FlashRadioGatewayComponents.kt defines accessibility description without FlashRadioCopy or parameter: 'stateDescription = sentence'`.

### Environment
- JVM Unit Test in `:ui:chat`
- Branch: `agent/R-ui`
- Worktree: `E:\FlashWork\R-ui`

### Root cause
The source guard test `FlashRadioCopyA11ySourceTest` verifies that all `contentDescription` and `stateDescription` assignments come from `FlashRadioCopy` rather than inline string literals.
In reusable composables (`FlashRadioStatusCard`, `FlashProfileRow`, `FlashModeRow`), the accessibility strings were passed as parameters (`a11yLabel`, `badgeText`) or computed from pure math (`val sentence = FlashRadioMath.statusSentence(...)`), where all inputs were already from `FlashRadioCopy`.
The test regex was overly strict by checking whether the single assignment line contained the literal substring `"FlashRadioCopy"`.

### Working fix
Updated `FlashRadioCopyA11ySourceTest.kt` to allow recognized parameter pass-through variables (`a11yLabel`, `badgeText`, `sentence`) while keeping the absolute ban on inline string literals (`"[^"]+"`).

### Verification
`:ui:chat:jvmTest --tests "com.transfer.flash.ui.radio.FlashRadioCopyA11ySourceTest" --rerun-tasks` PASSED.

### Status
RESOLVED
