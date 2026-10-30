# Decision Additions — Stream A (Build)

## ADR-230 — Retain BouncyCastle Cryptographic Provider on Desktop (OPT-O22-03 Blocked)

### Context
Finding `OPT-O22-03` proposed removing the BouncyCastle library suite (`bcprov-jdk18on`, `bcpkix-jdk18on`, `bcutil-jdk18on`, ~10.3 MB) from the desktop distribution, where it is used in `FlashCertMaker.kt` for generating self-signed TLS certificates for local WebSocket servers.

### Decision
`OPT-O22-03` is **BLOCKED** and deferred to a dedicated owner-gated architecture review. BouncyCastle is retained on the desktop runtime classpath, but strictly pinned to version `1.81.1` across configurations (resolving `OPT-O21-07` and eliminating the 24-hour dynamic version check).

### Rationale
1. Removing BouncyCastle touches the active TLS 1.3 certificate generation and handshake path between Android and Desktop clients.
2. Under `docs/plans/OPTIMIZATION-PARALLEL-PLAN.md` §8, `OPT-O22-03` is explicitly classified as **gated**: "It generates the desktop TLS certificate; removal touches handshake code on both hosts and needs an ADR + a device test."
3. Handshake changes must be verified against physical Android hardware across multiple OS versions during a dedicated device session before removing the cryptographic provider.

### Status
BLOCKED — Gated on owner decision and physical device TLS handshake session.
