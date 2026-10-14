# S2-server stream errors (fragment; integrator folds into `logs/errors.md`)

## ERROR-430 — `tools/flash-gradle.ps1` cannot take bare `:module:task` args

### Date
2026-10-14

### Area
Parallel-agent tooling (`tools/flash-gradle.ps1`, all streams)

### Symptoms
`tools\flash-gradle.cmd :server:test ...` fails before any build:
`Cannot process argument transformation on parameter 'WaitMinutes'. Cannot
convert value ":server:test" to type "System.Int32".`
With `-WaitMinutes 45` supplied, the task then binds to `-Runner` positionally
and the script tries to execute `:server:test` as a cmdlet:
`The term ':server:test' is not recognized...`.

### Root cause
`param([int]$WaitMinutes = 45, [string]$Runner = "", [ValueFromRemainingArguments]$GradleArgs)`:
bare Gradle args bind positionally to `WaitMinutes`/`Runner` instead of falling
into `GradleArgs`. The protocol doc says the script was "tested with a stand-in
command only" — a real `:module:task` was never tried.

### Working fix (workaround, no script edit — shared file, not my stream's)
Invoke with the task list as an explicit array via direct invocation (same lock,
same env, same worktree root):
`& .\tools\flash-gradle.ps1 -WaitMinutes 45 -GradleArgs ':server:test','--tests','X'`
(colon args quoted). Via `cmd`, `-GradleArgs 'a','b'` collapses to one
comma-joined arg — do NOT use the `.cmd` form for multi-arg runs.

### Suggested real fix (integrator/owner)
Make `-WaitMinutes`/`-Runner` non-positional (`[Parameter(Position=-1)]` is not
a thing; use `ValueFromRemainingArguments` only on `GradleArgs` and mark the
others `Position` explicitly unused... simplest: `[Parameter(Mandatory=$false,
Position=0)]` removed by giving `GradleArgs` `Position=0`? Actually simplest:
change signature to `param([Parameter(Position=0)][string[]]$GradleArgs,
[int]$WaitMinutes = 45, [string]$Runner = "")` — GradleArgs takes positionals
first). Needs owner yes (shared tool).

### Verification
All A2 gate runs in this stream used the workaround (see progress.md commands).

### Status
OPEN (workaround in use; real fix proposed)
