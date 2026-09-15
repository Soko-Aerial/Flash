# Palette's Journal - Flash UX & Accessibility Learnings

## 2026-09-12 - Reaction Chip Tactile Feedback & Design Token Compliance
Learning: Interactive chips (like `FlashReactionChip.kt`) using `combinedClickable` need a shared `MutableInteractionSource` passed to `.flashPressScale(interactionSource)` so both 1-tap toggle and long-press actions trigger tactile physical spring feedback smoothly. Additionally, replacing stock `material3.Text` with `FlashText` ensures consistent design token typography across custom interactive components.
Action: Whenever adding press feedback to clickable/combinedClickable Flash composables, remember and share `interactionSource` with `flashPressScale`, and use `FlashText` instead of `material3.Text`.

## 2026-09-11 - Interactive Reaction Chip Tactile Feedback & Design Token Compliance
Learning: Reaction chips (`FlashReactionChip.kt`) were using bare `material3.Text` instead of `FlashText` and lacked tactile press scale feedback. When adding `Modifier.flashPressScale(interactionSource)` to clickable surfaces that use custom `interactionSource` (like `combinedClickable`), passing the exact same `interactionSource` instance ensures the spring press scaling is perfectly synchronized with press events.
Action: Always pass a shared `remember { MutableInteractionSource() }` to both `flashPressScale` and `combinedClickable`/`clickable`, and replace any stock Material `Text` with `FlashText`.
