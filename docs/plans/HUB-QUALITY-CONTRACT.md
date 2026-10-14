# Flash Hub plans — the quality contract (mandatory for every phase of every plan)

**Written:** 2026-10-14 by Claude Sonnet 5.5, at the owner's request: *"the implementing AI is lazy and will do the bare minimum, so make the plans
force it to go beyond and be as good as the author."* This file is the answer. It applies to **all** plans in `docs/plans/HUB-*.md` and to the roadmap.

**Read this before the first line of code and again before you write any phase report.** If you are a model that tends to stop at the first
version that compiles: that behaviour is the exact failure this contract exists to prevent. Here, **the minimum is a failure**. There is no
time pressure and no prize for finishing early; the owner would rather wait than receive something that works only on the happy path.

---

## 1. The five promises you make by accepting a phase

1. **I will prove, not claim.** Every statement "done / tested / works" in my report is backed by a file path, a test name, or pasted tool output (section 4).
2. **I will do the unglamorous 80 %.** Failure paths, hostile input, concurrency, lifecycle, scale, accessibility, desktop parity, migration, docs and logs are part of the feature, not extras (section 3).
3. **I will try to break my own work before I report it.** I will run the adversarial review (section 6) and list at least ten concrete findings.
4. **I will be honest about what I did not do.** An honest "not done, because X" is accepted. A false "done" is the only unforgivable outcome (section 7).
5. **I will leave the repository better than I found it.** Logs, ADRs, protocol docs, probes, backlog entries: written in the same session (`AGENTS.md` §6-10, §28, §35).

## 2. Before you code a phase: the explain-back (no code until this is written)

In the plan's "Implementation log", write for the phase, in your own words (about 10-20 lines):
- what the phase must achieve and why (one paragraph, no copying);
- the files you will touch, **and for each new class the three most similar existing classes you read** and what you reuse or deliberately do differently (reuse before write: do not duplicate a helper that exists);
- the five ways the phase is most likely to go wrong (races, wire compatibility, security, scale, platform differences);
- which tests you will write first (tests before code for pure logic and for every security rule).

A phase without an explain-back is not started. This is cheap for someone who understood the plan and impossible to fake for someone who skimmed it.

## 3. Beyond the minimum: the categories every phase must cover

For each phase, go through **every** line below and either (a) show the test or code that covers it, or (b) write one specific sentence saying why it does not apply. "N/A" without a reason is a failed line.

| # | Category | What "covered" means (examples, not a complete list) |
|---|---|---|
| 1 | **Failure paths** | Every network, database, parse, crypto and file operation has a tested failure path ending in a defined state and, where the user can notice, a sentence from the plan's string table. Server 500, timeout, truncated body, invalid JSON, 401/403/404/409/429, hub unreachable, DNS failure, TLS failure. |
| 2 | **Hostile input** | Every new input (request field, frame, statement, link, file, search string, announcement text) has a test for: oversize, empty, wrong type, malformed encoding, duplicate, replayed, wrong signer, wrong scope, Unicode tricks (bidi override, zero width, combining marks, surrogate pairs), injection strings. Decoders never throw on random bytes (fuzz at least 1,000 random/truncated inputs in a test). |
| 3 | **Concurrency and ordering** | Two events at once (sync while a revocation arrives, two admins editing, a retry racing the original, unenrol during sync). Use injected clocks and virtual time (`runTest`); no `Thread.sleep` in tests. State what is guarded and by what. |
| 4 | **Lifecycle** | Process death and restore, configuration change, backgrounding, airplane mode, hub restored from backup (epoch), device clock jumps backwards/forwards, enrol/unenrol/revoke while the feature is in use, upgrade from the previous app version with data on disk (migration test). |
| 5 | **Scale** | 0, 1, many, 5,000 and (where listed) 20,000 items. Name the complexity of every loop over a collection; no O(n²) on lists that can reach thousands; indexes for every new server query (assert the query plan or document why not). Record synthetic numbers in `logs/experiments.md` labelled "synthetic, not device". |
| 6 | **Security** | The plan's security expectations each have a positive test, a negative test and a **mutation check** (break the rule, watch the test fail, restore it). Fail closed: an exception inside a security decision means deny. Nothing secret in logs, probes, exceptions or notifications. |
| 7 | **Compatibility** | Old client against new hub and new client against old hub, both tested (capability strings, unknown frames ignored, unknown JSON fields ignored). A user who is not enrolled sees no change at all. |
| 8 | **Both hosts** | Android **and** desktop. A phase whose desktop half is missing is not done. Shared code first (`commonMain`); host code stays thin. |
| 9 | **Accessibility and honesty of UI** | Content descriptions, 48 dp targets, order for screen readers, 200 % font, dark mode, reduced motion, right-to-left, long names, empty/error/offline/stale states, every refusal says *why* in plain words. No dead buttons. |
| 10 | **Observability** | Structured log lines with the project tags and **evidence probes** from `docs/testing/PROBES.md` for every new decision point, so a device test can be judged from a log (ids shortened, no secrets). Add new probe names to PROBES.md. |
| 11 | **Documentation** | `docs/protocol.md`, `docs/protocol-routes.txt`, `docs/security.md` (threat rows with mitigation **and residual risk**), `docs/android-platform-notes.md` if a platform fact was used (with URL and date), the component doc, ADR statuses, `logs/progress.md`, `logs/handoff.md`, `AGENTS.md` §29, `logs/errors.md` for any defect, and the device tests in `docs/testing/TEST-BACKLOG.md`. |
| 12 | **Admin and support view** | Someone must be able to understand a problem from the outside: settings help text that says what a number means and what the risk is, error messages that name the next step, a line in the troubleshooting doc when a failure needs a human. |

## 4. Evidence rules (what a report may and may not say)

Fill the **evidence table** in the plan's Implementation log for every step ID of the phase:

| Step | Files changed (`path:line`) | Tests (full names) | Mutation (what I broke, which test failed) | Compile / test commands and result |
|---|---|---|---|---|

Rules:
- "Tests green" must come with the command and the last lines of its output: number of tests run, failures 0. A claim without pasted output is rejected by the verifier.
- A security-relevant step without a mutation entry is **not done**.
- A test that would still pass if the feature were deleted proves nothing. The mutation check exists to show your tests can fail.
- "Compiles" is not "works"; "unit tests green" is not "device verified". Never write "device-verified" (only the owner can, in the backlog).
- Do not paste secrets. Shorten ids to 8 characters.

## 5. Banned shortcuts (the verifier greps for these; each one found is a defect)

1. `TODO`, `FIXME`, `XXX`, `HACK`, "for now", "temporary", "stub", `NotImplementedError`, `TODO()` in production code or in tests you add. If something is genuinely deferred, it goes into the plan's log **and** the backlog with an owner-visible reason, not into a code comment.
2. `@Ignore`, `@Disabled`, commented-out tests, weakened assertions, tests that only assert `!= null` or "does not throw".
3. Empty `catch`, `catch (e: Exception) { }`, `runCatching { }` whose failure is discarded in a security decision, `!!` on data that came from the network or disk.
4. Hard-coded test values, ids or URLs in production code; feature flags left on "for testing".
5. A new helper that duplicates an existing one (you did not search). A copy-pasted block of more than ~10 lines.
6. A hand-copied list that a test is supposed to check against (a test must enumerate the real source of truth).
7. Widening visibility (`public`, removing `internal`) only to make a test compile, disabling a lint/detekt rule, suppressing a warning without a written reason.
8. An unrelated refactor, a reformatting of untouched code, or a drive-by dependency (a new dependency is on the stop-and-ask list).
9. "Happy path" tests only: for every new function at least one success, one boundary and one failure test; for every wire structure a golden vector **and** malformed inputs; for every list 0/1/many/5,000.
10. A user-facing string that is not exactly the plan's string (or, if the plan has none, a string you added to the plan's table in the same change).

## 6. The adversarial self-review (mandatory at the end of every phase)

Re-read your whole diff **as a hostile reviewer who dislikes you**. Write at least **ten specific findings** in the log. A finding has: the place (`path:line`), the concrete failure scenario ("when the hub answers 500 after the first page, the snapshot is half applied because ..."), and either the fix (with the test name) or the reason it is acceptable. Findings such as "could add more tests" are worthless and do not count. A report with fewer than ten real findings is treated as "no review happened".

Prompts you must answer in that review (at least these):
1. What happens if this code runs twice, concurrently, or in the wrong order?
2. What happens at the exact boundary (limit, lease end, first/last element, empty, max length, one over)?
3. What happens when the other side is an older or a malicious build?
4. What does the user see if it fails halfway, and can they recover without reinstalling?
5. What is the worst thing an attacker with a valid but low-privilege account could do with this?
6. What did I assume about the existing code without reading it? (open it now)
7. What would break on the other host (Android vs desktop), on a slow phone, with 5,000 items?
8. Which of my tests would still pass if I deleted the feature?
9. Did I log or notify anything I should not (keys, tokens, bodies, full ids)?
10. What will the next AI misunderstand about this code, and have I written it down?

Additionally list **at least five situations the plan does not spell out** that you found, handled and tested (the plans' "situations" sections are a floor, not a ceiling; roadmap rule 12).

## 7. The phase report (write it into the Implementation log and `logs/progress.md`; use this template)

```
### Phase <id> report — <date>
Done: <list with evidence-table references>
Done but only unit-tested (device checks added to the backlog as <ids>): <list>
Not done, and why: <list; "none" must be justified>
Surprises (things the plan got wrong or did not mention, files that had drifted): <at least 3, or say precisely why none>
Decisions I took that the plan did not make (with where they are recorded): <list>
Adversarial review findings (>= 10) and the status of each: <list>
Extra situations handled beyond the plan (>= 5): <list>
Tests: <N added, N total run, 0 failures, commands>
Mutation checks: <list>
Open risks the owner should know: <list>
Self-score against section 3 (12 lines: covered / not applicable with reason / not covered): <table>
```

An empty "Not done" and an empty "Surprises" together are treated as a red flag by the verifier: nobody finishes real work without either.

## 8. How the owner will check you

The owner will give your diff and your report to a second AI with `docs/plans/HUB-PLANS-PROMPTS.md` ("verifier prompt"). It runs the banned-pattern search, re-runs your test commands, spot-checks at least five claims per phase against the code, mutation-checks two security rules itself, and reads your findings for specificity. Plan for that: write as if every sentence will be checked.

## 9. When you finish early

If a phase is done and verified, do **not** start the next phase because you are bored. Instead, in this order: (1) re-run the adversarial review with fresh eyes on the hardest file; (2) add the tests you wished you had; (3) re-read the plan's "situations" and the security section line by line against your code; (4) improve the documentation for the admin and for the next AI; (5) only then move on, after the phase report is written.
