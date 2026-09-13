# Phase 26 — Desktop identity persistence + pairing wiring (the P pick, P2)

**Status:** AUTHORIZED (2026-09-13 — the human picked **P2** over the scoped P1/P3),
**EXECUTION READY** — pairs with the Phase 16 gate: this is what unblocks **G2/G6** (pairing
gates) on hardware day, and the phone→desktop transfer direction (trust-gated on the phone).
**Risk:** MEDIUM-HIGH — this is the R8 security surface (crypto + trust state), extended to a
new platform. New at-rest encryption code is exactly the class of change AGENTS.md §19 and the
R8 rule say must be phase-filed and reviewed before any code exists.
**Decisions relied on:** P2 = persist a software keypair under `~/.flash/` with OS-key at-rest
encryption (this file records the design), D1 = B (strict commonMain), R5 (plain `jvm()`), R8
(security surface untouchable without review — this file IS the review), R10 (JNA is a new
dependency — see the dependency note below).

---

## What this phase is for

Close the desktop pairing gap scoped in
[`research/desktop-pairing-gap-scoping.md`](research/desktop-pairing-gap-scoping.md). Verified
state of that gap (2026-09-13, measured from the tree):

- **Pairing math is done and common.** `core/security/src/commonMain/.../pairing/` holds
  `FlashPairingFrames`, `FlashPairingProtocol`, `NumericComparisonCode`,
  `PairingSessionStateMachine` — target-free. Nothing about pairing is Android-only.
- **The ONE blocker is the desktop `FlashCrypto` identity.** `SoftwareFlashCrypto`
  (commonMain, `internal`) is stamped *"⚠️ NOT FOR PRODUCTION IDENTITY STORAGE ⚠️"*: its
  identity keypair is a lazy in-memory ECDH/ECDSA key that dies with the process. Every desktop
  restart mints a fresh identity, so TOFU trust can never survive — which is precisely why
  G2/G6 cannot pass today, and why the phone shows **Pair**, not **Chat**, for the desktop.
- `KeystoreFlashCrypto` (androidMain) is the hardware-backed Android actual and is untouched by
  this phase (R8).
- The desktop shell wires `FlashIdentityStore` + `FlashTrustStore` (file-backed,
  `~/.flash/identity.properties` / `trust.properties` — Phase 21) but **no `FlashCrypto`
  consumer and no pairing sessions at all** — `DesktopEngine` has no pairing member.
- `core/security`'s `jvmMain` already carries `PlatformCrypto.jvm.kt` — bit-identical JCA
  actuals for every primitive the crypto path needs. The key-handling primitives exist on
  desktop; only *persistence* and *wiring* are missing.

## Design (decided 2026-09-13, human-confirmed)

### The at-rest question, answered: **Windows DPAPI first**

Desktop has no hardware keystore in standard JCA. The options considered:

| Option | Verdict |
|---|---|
| **Windows DPAPI via JNA** | **PICKED.** `CryptProtectData`/`CryptUnprotectData` encrypt the PKCS#8 key bytes to the current Windows user's login. Copied to another machine or user, the blob is garbage. JNA ships the wrapper (`com.sun.jna.platform.win32.Crypt32Util`) — no custom JNI. Windows-only code behind a seam; macOS Keychain / Linux keyring are future actuals of the same seam. |
| Restrictive-permission plaintext PKCS#8 | Rejected — any malware running as the user reads the identity key outright; this is the tier P2 exists to improve on. |
| App-local wrapping key stored beside the file | Rejected — obfuscation, not protection. |
| Passphrase-derived key | Rejected for now — the desktop shell has no passphrase UX; would change the product's interaction model, which is not this phase's call. |

### Components

1. **`IdentityKeyVault` (new, `desktop` module or `core/security` jvmMain — see "Where it
   lives"):** a one-method seam — `protect(bytes): ByteArray` / `unprotect(bytes): ByteArray`.
   jvmMain actual = DPAPI via JNA. A test actual = identity (or XOR) so unit tests run without
   Windows API calls.
2. **`PersistedFlashCrypto` (new, implements `FlashCrypto`):** delegates all crypto to the
   existing shared machinery (`EcP256Ops` via the same path `SoftwareFlashCrypto` uses — the
   ephemeral-ECDH + HKDF session-key path is **identical code**, reused, not re-derived);
   differs only in identity-key handling:
   - On first run: generate P-256 via JCA, persist `PKCS#8` private + `X.509` public, DPAPI-
     protected, to `~/.flash/identity/id-key.bin`.
   - File format: `1 byte format-version (currently 0x01) || DPAPI blob` — the version byte
     exists so a future macOS/Linux actual (or DPAPI→Keychain migration) can be detected and
     migrated, never silently misparsed.
   - On load: DPAPI-unprotect, parse, zeroize the intermediate byte arrays after the
     `PrivateKey` object is built.
   - **Falls back to `SoftwareFlashCrypto` semantics (fresh in-memory identity) only if the
     vault is unreadable**, and logs the degradation loudly — never silently mints a new
     persisted key over a failure.
3. **Wiring — `DesktopEngine` gains a pairing path:** construct a pairing session coordinator
   over the commonMain protocol classes (`FlashPairingProtocol` + `PairingSessionStateMachine`)
   with `PersistedFlashCrypto`, the existing `DesktopTrustStore`, and the WS network; route
   `FLASH_PAIR` inbound frames (the same dispatch point the app's
   `DiscoveryEngineHolder.kt:1631–1634` uses) and expose trust confirmation to the desktop UI
   (numeric-comparison code display + accept/decline — parity with the phone's dialog).
   The app module's `PairingCoordinator` is Android UI orchestration and is NOT reused; the
   desktop equivalent is a thin adapter over the same common classes.

### Where it lives

`PersistedFlashCrypto` + `IdentityKeyVault` go in **`core/security`'s `jvmMain`** — the R8
surface is where identity crypto belongs, and a future non-desktop JVM consumer gets it for
free. The DPAPI/JNA bits are `jvmMain` + a runtime JNA dependency scoped to the JVM variant;
`DesktopEngine` just constructs it. (If the JNA edge proves awkward under the KMP Android
variant's dependency shaping, fall back to `desktop/src/jvmMain` — decided during execution,
noted in the log either way.)

### Dependency note (R10)

`net.java.dev.jna:jna` (+ `jna-platform`) is a NEW dependency — human-authorized by the P2/DPAPI
pick itself (recorded in ADR-035). Pin the current stable 5.x line in `gradle/libs.versions.toml`
with the version-catalog discipline; scope it to the JVM variant only, never Android.

## Honest security-tier statement (belongs in the shipped docs, not just here)

DPAPI-persisted software identity is a **middle tier**: strictly better than losing the identity
every restart, strictly weaker than Android's non-exportable hardware key (which cannot be
exported even by malware running as the user). DPAPI-protected bytes CAN be unprotected by code
running as the same Windows user. This tiering is stated in the desktop README section when
this phase ships — not glossed over.

## Execution order

1. **26-1:** `IdentityKeyVault` seam + DPAPI actual + test actual + unit tests (protect/unprotect
   round-trip, wrong-user simulation can't be tested on one box — covered by the format doc).
2. **26-2:** `PersistedFlashCrypto` (generate-once → persist → load → zeroize; fallback
   loud-degradation path) + tests: restart-survival (persist, new instance, same public key),
   corrupted-blob fallback, version-byte rejection of an unknown format.
3. **26-3:** wire into `DesktopEngine` + pairing session adapter + UI accept/decline surface;
   `FLASH_PAIR` routing; trust-store writes on accept.
4. **26-4:** gate —
   - `:core:security` (or `:desktop`) compile + test tasks both variants;
   - full R3 sweep (count must not regress; the 12 known NTFS `:core:persistence` failures
     excluded);
   - hardware-day script: G2 pairing desktop↔phone (numeric comparison both directions), G6
     re-pair after desktop restart (the actual point of P2), then phone→desktop file transfer
     (trust-gated send now reachable) — recorded in the Phase 16 log when run.

## Do NOT

- **Do NOT touch `SoftwareFlashCrypto` or `KeystoreFlashCrypto`** — R8. `PersistedFlashCrypto`
  is additive; the ephemeral path is reused, never edited.
- **Do NOT persist anything to plaintext**, "temporarily" included. The fallback path keeps the
  key in memory only.
- **Do NOT adopt a second crypto library** (BouncyCastle etc.) — JCA + the existing
  `PlatformCrypto` actuals cover P-256 sign/ECDH/HKDF; a new provider is a new ADR.
- **Do NOT auto-trust or auto-accept pairing** on desktop — numeric comparison with explicit
  accept, parity with the phone. The harness's auto-accept policy (`DesktopInteropHarness`) is
  explicitly a harness policy and must NOT leak into the shell.
- **Do NOT skip the version byte** in the file format — it is the migration contract.

## Rollback

Each sub-step is a separate commit (R1). 26-3 reverts to the Phase 21 shell (no pairing) with no
migration concern; 26-1/26-2 are additive classes. If `~/.flash/identity/id-key.bin` exists from
a test run and the phase is rolled back, the file is inert (nothing reads it) — note it in the
log rather than deleting user state from code.

## Log entry (mandatory)

The sub-steps run, the JNA version pinned, where the classes landed (security-jvmMain vs
desktop) and why, the security-tier statement's final wording, and — once hardware day happens —
the G2/G6 results referencing this phase.
