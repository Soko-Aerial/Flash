# C-hub-tab experiments additions (merge into logs/experiments.md)

## EXP-C1 - Hub tab view maths at 5,000 people (SYNTHETIC, not a phone)

**What this is.** `FlashHubScaleTest` (ui/chat jvmTest, HUBT-U95) builds 5,000 synthetic people (40 departments, accented names, mixed
presence/paired/verified) and 1,000 synthetic announcements and times the pure presentation code in `FlashHubMath` on the cloud build
machine's JVM (OpenJDK 21 run by Gradle, noisy shared container). **It is not a measurement of the screen, of scrolling, of Compose, of memory,
or of any phone.** The owner's measurement stays HUBT-03 (TODO). The values below are medians of 15 runs after 5 warm-up runs.

| Operation | N | Median |
|---|---|---|
| `peopleItems` (sticky-header list build) | 5,000 people | 5.07 ms (7 headers) |
| `personAccessibility` for every person | 5,000 people | 0.95 ms |
| `matchNews` (folded substring over title, body, author) | 1,000 announcements | 5.67 ms |
| `orderNews` + `unreadCount` | 1,000 announcements | 0.57 ms |
| `peopleItems` scaling 5,000 -> 10,000 people | ratio of medians | 1.68 (a linear algorithm gives about 2) |

**Conclusion (limited).** Nothing in this layer is super-linear, and building the list for 5,000 colleagues costs single-digit milliseconds on
a desktop-class JVM; on a phone it is expected to be several times slower but still far below a frame budget when run off the main thread, which
is where the screen runs it. The facade (`HubFacade`) does its own sorting and filtering once per snapshot (HubFacadeTest has the 5,000-row
tests, correctness only). Not measured: Compose layout and scroll cost of 5,000 rows (LazyColumn, only visible rows compose), memory, battery,
and any real device. The test's thresholds (250 ms, ratio < 8) are loose on purpose so a loaded CI machine does not flake; they only catch an
accidental quadratic.
