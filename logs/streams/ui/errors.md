# Error & Diagnostic Log — Stream U (UI)

## ERROR-901 — IME Resize Jitter from Nested Spring Animation

### Area
Compose UI / WindowInsets / Soft Keyboard (Android)

### Symptoms
When tapping the message input box, the composer bar rubber-bands, jitters, and visually lags behind the hardware-drawn soft keyboard during its 250 ms opening transition.

### Root Cause
In `FlashComposer.kt:177-178`, `.animateContentSize()` was placed on the root `Column` immediately following `.navigationBarsPadding().imePadding()`. Edge-to-edge Android IME animations update `WindowInsets.ime` frame-by-frame (15 to 30 animation ticks across 250 ms). Because `animateContentSize()` treats every incremental inset delta as a layout size change requiring an oscillating spring animation, the Compose spring is continuously interrupted and restarted on every vsync frame, oscillating out-of-phase with the platform keyboard.

### Working Fix
Removed `.animateContentSize()` from the root `Column` so the composer follows the hardware keyboard 1:1 via `.imePadding()`. Scoped `.animateContentSize()` exclusively to the internal expanding input pill `Box` for text expansion.

### Status
RESOLVED (`d51e5c5e`)

---

## ERROR-902 — Slot Reuse Hitch Across Calendar Boundaries in Message List

### Area
LazyColumn Layout / Slot Table Recycling

### Symptoms
Micro-stutter and dropped frames when scrolling across calendar day boundaries in long chat histories.

### Root Cause
`flashMessageContentType` categorized messages purely by content type (`"text_in"`, `"text_out"`, etc.) without checking `message.daySeparator`. Items with day headers (~40 dp taller) shared the identical `contentType` as headerless items, causing Compose to recycle an inadequate slot table and force an unexpected remeasure pass.

### Working Fix
Appended `"_sep"` to the content type string when `message.daySeparator != null`.

### Status
RESOLVED (`907900a8`)
