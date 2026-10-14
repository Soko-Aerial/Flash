# S2-server backlog additions (fragment; integrator appends to `docs/testing/TEST-BACKLOG.md` §4zzzzzzzzz)

## PRIV — Directory privacy, option C (Plan A2, S2 built 2026-10-14, unit-tested only)

### PRIV-01 — outsider cannot find a hidden department
- Setup: hub with departments Alpha (visible) and Executive (hidden); phone P enrolled in Alpha.
- Steps: on P, open Hub tab → People; search "Executive"; list departments.
- Pass: no Executive department, people or groups appear; search shows zero rows with the normal empty shape. Source: A2 §7. Status: TODO.

### PRIV-02 — insider can
- Setup: phone Q enrolled in Executive (hidden).
- Steps: on Q, open People; list departments and groups.
- Pass: Executive, its people and its groups are listed with names and online dots. Source: A2 §7. Status: TODO.

### PRIV-03 — hidden-department member can message an outsider and the outsider's phone accepts it
- Setup: Q (Executive) messages P (Alpha) by known id / directory lookup.
- Steps: Q sends a 1:1 message to P while both online (hub reachable).
- Pass: P receives and can reply; no "unknown sender" rejection. Check log for `directory.lookup.hit`. Source: A2 §7. Status: TODO.

### PRIV-04 — toggling hidden updates a phone's list after refresh
- Setup: P shows Beta department; admin hides Beta in the console.
- Steps: on P, refresh the directory (next update); admin un-hides; refresh again.
- Pass: Beta disappears after the first refresh and reappears after the second; existing conversations stay. Source: A2 §7. Status: TODO.

### PRIV-05 — old app + new hub
- Setup: old app (pre-A2) enrolled; hub runs A2.
- Steps: browse groups, read own-department members on the old app.
- Pass: old shapes work; new departments appear as names (old UI handles them); nothing crashes. Source: A2 §5/§7. Status: TODO.
