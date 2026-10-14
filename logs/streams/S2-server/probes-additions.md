# S2-server probes additions (fragment; integrator folds into `docs/testing/PROBES.md`)

No new `dir.*` probe names are registered in this phase. Observable evidence for
device tests uses the existing server audit vocabulary (ids shortened, no
names, no secrets):

- `directory.people.list rows=<n>` — a people listing was served (count only).
- `directory.lookup.hit hit=1` — a known-id lookup resolved.
- `directory.lookup.missburst misses>=10/min` — lookup miss burst throttled.
- `directory.department.hidden department=<id> hidden=<bool>` — flag changed
  (epoch bumped; clients resync).

A device test passes PRIV-03 when the sender side logs `directory.lookup.hit`
and the message is accepted; PRIV-04 when a listing after refresh no longer
contains the hidden rows.
