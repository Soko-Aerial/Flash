# Core Hub Protocol Module (`:core:hub-protocol`)

The Hub and its clients share this Kotlin Multiplatform module for REST and relay DTOs,
frame codecs, enrolment invites, certificate chains, revocation lists and signed
proofs. Its Maven artifact is `core-hub-protocol`. The module can be pulled in
transitively by the Hub client and is annotated `@FlashInternalApi` at its wire seams.

The documented [wire protocol](../../../protocol.md) and its version markers govern
interop. For a Kotlin app, start with the [enterprise module](core-enterprise.md)
or the [engine facade](core-engine.md).
