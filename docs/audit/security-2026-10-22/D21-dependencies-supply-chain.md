# Domain D21 — Dependencies and Supply Chain

## 1. Domain Overview
This domain audits external and vendored dependencies, Gradle repository configurations, lockfiles, dependency verification mechanisms, vendored forks (`third_party/webrtc-kmp`), publishing pipelines (`jitpack.yml`), build scripts, and CI/CD workflows (`.github/workflows/ci.yml`).

Audited commit: `75eda19b018948c00947bc08d7288c2d3a0ae6ce`.

---

## 2. Supply Chain Inventory

| Component | Origin / Source | Verification / Pinning | Security Implications |
|---|---|---|---|
| Repositories | `google()`, `mavenCentral()`, Google Maven Central mirror, `gradlePluginPortal()`, `mavenLocal()` | HTTPS enforced; NO `verification-metadata.xml` | `mavenLocal()` first in search order (`SEC-D21-01`). |
| Catalog (`libs.versions.toml`) | Curated list of Android, Jetpack Compose, Ktor, BouncyCastle, Room, Okio | Exact pinned versions (no `+` wildcards) | Pinned versions prevent accidental drift. |
| Vendored `webrtc-kmp` | Fork of `aschulz90/webrtc-kmp` (upstream `shepeliev/webrtc-kmp`) | In-tree source compilation via Gradle `includeBuild("third_party/webrtc-kmp")` | Changes documented in `MODIFICATIONS.md`. Native binaries pulled from Maven Central. |
| Native Dependencies | `jSerialComm:2.11.4`, `jna:5.17.0`, `sqlite-jdbc-crypt:3.50.1.0`, `webrtc-java:0.19.0` | Packaged inside JARs, unpacked to temporary filesystem at runtime | Unpacking to `/tmp` subject to symlink/tampering risks on multi-user systems. |
| Publishing Pipeline | `jitpack.yml` | Builds release artifacts on JitPack OpenJDK 17 runner | Runs `publishToMavenLocal` with `-x test -x lint`. |
| CI Pipeline | `.github/workflows/ci.yml` | GitHub Actions runner `ubuntu-latest` | Missing top-level token `permissions` (`SEC-D21-03`). |

---

## 3. Checklist & Audit Questions

| Item | Status | Notes |
|---|---|---|
| Cleartext repository URLs (`http://`) | PASS | None found. All repository and plugin URLs use HTTPS. |
| Dynamic/wildcard version specifications | PASS | All dependencies in `gradle/libs.versions.toml` use pinned, exact versions. |
| Global repository search order | DEFECT | `mavenLocal()` is configured as the first repository in `dependencyResolutionManagement` (`SEC-D21-01`). |
| Dependency verification / lockfiles | DEFECT | Gradle `verification-metadata.xml` is absent; dependency checksums/signatures are unverified (`SEC-D21-02`). |
| Vendored fork hygiene (`third_party/webrtc-kmp`) | PASS | Well-documented in `MODIFICATIONS.md`; source files kept in-tree; Apache-2.0 license preserved. |
| Build scripts with arbitrary downloads (`curl \| sh`) | PASS | No shell scripts download and pipe to interpreter. |
| Developer tooling secrets (`tools/tavily_search.py`) | PASS | API key read from ignored `.tavily_api_key` or environment; key was never committed to git. |
| CI workflow token permissions | DEFECT | `.github/workflows/ci.yml` lacks top-level `permissions` block, defaulting to broad write permissions (`SEC-D21-03`). |

---

## 4. Files Audited
- `settings.gradle.kts`
- `build.gradle.kts`
- `gradle/libs.versions.toml`
- `third_party/webrtc-kmp/MODIFICATIONS.md`
- `third_party/webrtc-kmp/build.gradle.kts`
- `jitpack.yml`
- `.github/workflows/ci.yml`
- `tools/build-release.ps1`
- `tools/tavily_search.py`
- `tools/.gitignore`

---

## 5. Findings

### SEC-D21-01 — Global `mavenLocal()` Repository Prioritized First Enables Local Dependency Poisoning
- **Severity:** Medium        **Confidence:** CONFIRMED
- **Attacker:** A10, A6 (Attacker or malicious script on local developer/CI host)
- **Component:** `settings.gradle.kts:26`
- **CWE / standard:** CWE-829: Inclusion of Functionality from Untrusted Control Sphere; CWE-427: Uncontrolled Search Path Element; CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:H/A:H (7.8)
- **What is wrong:** In `settings.gradle.kts`, `dependencyResolutionManagement.repositories` places `mavenLocal()` as the very first repository, preceding `google()` and `mavenCentral()`. Gradle searches repositories sequentially: the first repository containing an artifact satisfying coordinates satisfies the resolution. The local Maven cache (`~/.m2/repository`) is an unauthenticated, unverified local filesystem directory shared across all projects and processes for the user. Any unprivileged process, third-party build tool, or rogue dependency from another project running on the same machine can place poisoned JAR files into `~/.m2/repository/com/squareup/okio/` or `org/jetbrains/kotlinx/`. Gradle will pull these compromised artifacts instead of fetching legitimate ones from Maven Central.
- **Impact:** Supply-chain compromise: developer and CI builds can unknowingly compile and bundle malicious local code into released APKs and desktop binaries.
- **Evidence:**
  `settings.gradle.kts:20-27`:
  ```kotlin
  dependencyResolutionManagement {
      repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
      repositories {
          // Phase 24 Step 2: mavenLocal() is FIRST so `:sample:consumer-desktop` (the only module
          // that consumes published coordinates rather than project() edges) resolves the freshly
          // published `com.transfer.flash:*` tree. Harmless to every other module: local
          // publications only shadow a dependency if a version collides AND the cache prefers the
          // local one, and Gradle checks mavenLocal first only for coordinates that exist there.
          mavenLocal()
          google()
          mavenCentral()
          maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
      }
  ```
- **Why existing guards do not stop it:** The code comments erroneously assume `mavenLocal()` is "harmless". Gradle does not verify whether coordinates exist in Maven Central before checking `mavenLocal()`; it accepts the first hit.
- **Verifier result:** survived. Code verification confirmed `mavenLocal()` is at line 26 as the first repository in `dependencyResolutionManagement`.
- **Recommended fix:** Restrict `mavenLocal()` exclusively to the `:sample:consumer-desktop` project or declare repository content filtering so `mavenLocal()` only resolves group `com.transfer.flash.*`:
  ```kotlin
  mavenLocal {
      content {
          includeGroup("com.transfer.flash")
      }
  }
  ```
- **Related:** `ADR-043`, `SEC-D21-02`.

---

### SEC-D21-02 — Missing Gradle Dependency Verification (`verification-metadata.xml`) Leaves Build Vulnerable to Upstream Tampering
- **Severity:** Medium        **Confidence:** CONFIRMED
- **Attacker:** A10 (Supply chain attacker compromising an upstream mirror or dependency artifact)
- **Component:** `gradle/` (absence of `gradle/verification-metadata.xml`)
- **CWE / standard:** CWE-353: Missing Support for Integrity Check; CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:H/I:H/A:H (7.5)
- **What is wrong:** The Flash build system does not enable Gradle Dependency Verification. There is no `gradle/verification-metadata.xml` specifying SHA-256 checksums or trusted PGP signing keys for resolved dependencies and plugins. If an attacker performs DNS spoofing or compromises an upstream mirror (such as the configured Google Cloud Storage mirror `https://maven-central.storage-download.googleapis.com/maven2/`), or if a maintainer account of an upstream library is compromised, Gradle silently downloads and executes the tampered classes without error.
- **Impact:** Malicious code injection during build time or application execution without triggering any integrity check.
- **Evidence:** Repository search confirms `gradle/verification-metadata.xml` does not exist in the working directory or git history.
- **Why existing guards do not stop it:** HTTPS certificates validate server identity but do not prevent malicious or tampered artifacts served legitimately by compromised mirrors or repository accounts.
- **Verifier result:** survived. Confirmed `gradle/verification-metadata.xml` is absent.
- **Recommended fix:** Generate and commit a dependency verification configuration via `./gradlew --write-verification-metadata sha256 allTests` and enforce `checksums` verification mode in `gradle/verification-metadata.xml`.
- **Related:** `SEC-D21-01`.

---

### SEC-D21-03 — GitHub Actions Workflow Lacks Explicit Principle-of-Least-Privilege Token Permissions
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** A10 (Compromised dependency or third-party GitHub Action)
- **Component:** `.github/workflows/ci.yml:1-51`
- **CWE / standard:** CWE-276: Incorrect Default Permissions
- **What is wrong:** `.github/workflows/ci.yml` does not declare a top-level `permissions:` block. By default, unless configured otherwise at the organization level, GitHub Actions runs with read-and-write permissions for `GITHUB_TOKEN`. If any third-party Action used in the CI pipeline (`actions/checkout@v4`, `actions/setup-java@v4`, `gradle/actions/setup-gradle@v4`, `actions/upload-artifact@v4`) or a malicious dependency executed during test execution is compromised, it can access the environment's `GITHUB_TOKEN` to modify repository contents, push commits, or modify release tags.
- **Impact:** Potential repository compromise and unauthorized git modifications by compromised build actions.
- **Evidence:** `.github/workflows/ci.yml` contains `on:` and `jobs:`, but omits `permissions:`.
- **Why existing guards do not stop it:** GitHub Actions grants default repository write permissions when `permissions` is omitted.
- **Verifier result:** survived. Code verification confirmed absence of `permissions` declaration in `ci.yml`.
- **Recommended fix:** Explicitly restrict default workflow permissions at the top of `.github/workflows/ci.yml`:
  ```yaml
  permissions:
    contents: read
  ```
- **Related:** `SEC-D19-01`.

---

## 6. Rejected Hypotheses & Not Vulnerable Notes
- **REJ-D21-01: Cleartext HTTP Repositories (`http://`)**: Audited all `build.gradle.kts` and `settings.gradle.kts` files. All repositories and plugin management configurations exclusively use HTTPS URLs.
- **REJ-D21-02: Dynamic / Wildcard Dependency Versions**: Audited `gradle/libs.versions.toml`. All external libraries, plugins, and dependencies declare exact, fixed version strings (no `+` or dynamic ranges).
- **REJ-D21-03: Arbitrary Download-and-Execute Build Scripts**: Audited `tools/` and `gradlew`. No build scripts download shell scripts or binaries via `curl | sh` or `wget`.
- **REJ-D21-04: Leaked API Keys in Developer Search Tooling**: Audited `tools/tavily_search.py`. Local key file `tools/.tavily_api_key` is properly ignored in `tools/.gitignore` and has never been committed to git history.
