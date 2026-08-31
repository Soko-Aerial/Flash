# Phase Log Entry Template

Append one filled-in copy of this to `logs/migration.md` at the end of every phase.
Newest entry at the bottom. Do not edit previous entries.

```markdown
## Phase NN — <phase title>

- **Date:** YYYY-MM-DD
- **Agent/model:** <name>
- **Commit:** <short sha, or "not committed — blocked">
- **Decisions relied on:** D1=A, D3=A (unanswered, proceeded with recommendation)

### Change
<2–4 sentences. What changed and why. Reference the phase file's step numbers.>

### Files changed
<Explicit list. Group by add / modify / delete / move.>

### Verification
Command run:
```
./gradlew :app:assembleDebug testDebugUnitTest --no-configuration-cache
```
Result: <PASS / FAIL>
<Paste the tail of the output. If tests ran, paste the test summary line.
If you could not run it, say exactly why.>

Additional checks specific to this phase:
- <e.g. "grep for android.util.Log in core/network returns 0 matches" — with result>

### Deviations from the phase file
<Anything you did differently, and why. "None" is a valid answer.>

### Known issues
<Problems you noticed but did NOT fix because they are out of scope.
Include file:line. These become future phase input. "None" is valid.>

### Next step
<The next phase number, or the specific blocker that must be resolved first.>
```

## Rules for filling this in

- **Never** write "PASS" without pasting output. Per CONVENTIONS.md R9, an
  unsubstantiated pass is worse than a failure.
- If the phase was blocked, still write an entry. Set **Commit** to
  `not committed — blocked` and put the reason under **Known issues**.
- If you deviated from the phase file in any way, it goes under **Deviations**, not
  buried in prose. Later phases assume the file was followed exactly.
- Convert relative dates to absolute. "Yesterday" is meaningless to the next agent.
