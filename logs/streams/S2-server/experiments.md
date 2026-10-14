# S2-server experiments (fragment; integrator folds into `logs/experiments.md`)

## EXP-030 — Directory people search at 5,000 members (synthetic, not device)

### Setup
In-memory `OrganizationDirectory`, 50 departments, 5,000 members
(`DirectoryPrivacyTest.fiveThousandMembersPageAndSearch`), JVM unit test on
this PC, no phone, no network.

### Result
- Exact search ("member 4999") returns 1 row (`dev-4999`).
- Empty-query page returns 100 rows.
- 1,000-cycle cursor/query fuzz: no throw.

### Complexity
`searchPeople` is O(n log n) in-memory (filter + sort); no O(n²); the SQL
index `idx_dir_members_dept_name` serves no current query (in-memory design) —
kept for future SQL use.

### Conclusion
No performance concern at 5,000 for the in-memory design; device numbers still
owed (MEAS-style, owner schedule).
