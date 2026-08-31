# Phase 01 — Repo Hygiene

**Blocked by:** Phase 00.
**Risk:** low. No Kotlin source changes.
**Decisions needed:** none.

## Why this phase exists

`git config core.autocrlf` is converting LF→CRLF on this checkout. Once `commonMain`
files are shared between two targets and edited by multiple agents on different
platforms, line-ending churn produces diffs where every line appears changed. That
makes review of the real migration diffs impossible. Fix it before the churn starts,
not after.

## Preconditions

- Phase 00 logged and passing.
- Working tree clean, or only containing changes you intend to keep.

## Steps

### Step 1 — Confirm the problem

```bash
git config --get core.autocrlf
```

Record the value. Then:

```bash
ls .gitattributes
```

If `.gitattributes` already exists, **read it first** and merge rather than
overwrite.

### Step 2 — Create `.gitattributes` at the repo root

Create the file with exactly this content:

```gitattributes
# Normalize line endings for all text files. Prevents CRLF churn once source is
# shared across commonMain/androidMain/jvmMain and edited on multiple platforms.
* text=auto eol=lf

# Windows-only scripts must keep CRLF
*.bat text eol=crlf
*.cmd text eol=crlf

# Gradle wrapper
gradlew text eol=lf
gradlew.bat text eol=crlf

# Explicit binary types — never touch these
*.png binary
*.webp binary
*.jpg binary
*.jpeg binary
*.jar binary
*.keystore binary
*.jks binary
*.so binary
*.db binary
```

### Step 3 — Renormalize the existing checkout

This rewrites line endings in the index for every tracked text file. It produces a
large, mechanical diff. That is expected and is exactly why it gets its own commit.

```bash
git add --renormalize .
```

Then inspect what changed:

```bash
git status --short | head -50
```

```bash
git diff --cached --stat | tail -5
```

### Step 4 — Verify no content actually changed

This is the important check. Renormalization must change **only** line endings.

```bash
git diff --cached --ignore-all-space --stat
```

Expected output: **empty**, or listing only `.gitattributes`. If any source file
appears here, something changed beyond whitespace — investigate before committing.

### Step 5 — Commit

```bash
git add .gitattributes && git commit -m "chore(migration): normalize line endings via .gitattributes"
```

## Verification

```bash
./gradlew :app:assembleDebug testDebugUnitTest --no-configuration-cache
```

Must pass. Line-ending normalization should not affect compilation, but Kotlin
multiline string literals and any test asserting on exact file bytes could in
principle be affected, so this is a real check, not a formality.

## Do NOT

- Do not change `core.autocrlf` in the user's global git config. `.gitattributes` is
  repo-scoped and is the correct mechanism. Per project rules, leave git config alone.
- Do not combine this with any other change. The renormalize diff is huge; anything
  committed alongside it becomes invisible in review.
- Do not run `git add --renormalize` a second time in a later phase.

## Completion checklist

- [ ] `.gitattributes` created at repo root with the content above
- [ ] `git add --renormalize .` run
- [ ] Step 4 whitespace-ignoring diff confirmed empty
- [ ] Build + tests pass
- [ ] Committed as a single commit touching nothing else
- [ ] Log entry appended

## Rollback

```bash
git revert <commit-sha>
```

Safe and complete — this phase adds one file and rewrites line endings, both of which
revert cleanly.
