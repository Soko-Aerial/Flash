package com.transfer.flash.core.security.crypto

/**
 * Opaque handle to an EC P-256 private key held in whatever form the platform provides
 * (`java.security.PrivateKey` on JVM targets).
 *
 * An `expect` *classifier* rather than an `expect fun` because this seam must carry per-platform
 * *state* — the same exception CONVENTIONS.md R2 grants to `core:common`'s `PlatformLock`. It is
 * deliberately opaque and `internal`: private key material must never be exportable through
 * Flash's own API, which is why [FlashEcKeyPair] exposes only the public half and why
 * [FlashCrypto.ecdhSessionKey] takes the whole keypair instead of the private key.
 *
 * It is an `expect interface`, not an `expect class`, because both JVM `actual`s are
 * `actual typealias`es to `java.security.PrivateKey` — which is itself an interface — and Kotlin
 * requires the `expect` declaration to be the same kind of classifier as whatever the
 * `actual typealias` expands to ("class kinds are different (class, interface, …)"). Aliasing the
 * platform type instead of wrapping it is what lets `KeystoreFlashCrypto` hand its non-exportable
 * AndroidKeyStore `PrivateKey` straight to the seam, with no unwrapping step that could copy key
 * material. Nothing outside this module can implement it: the declaration is `internal`.
 *
 * `-Xexpect-actual-classes` is enabled in this module's `build.gradle.kts` to suppress the
 * Beta warning (KT-61573), which covers all `expect`/`actual` classifiers, interfaces included.
 */
internal expect interface PlatformEcPrivateKey

/**
 * An EC P-256 keypair: the public half as wire-ready bytes, the private half as an opaque
 * platform handle.
 *
 * Replaces `java.security.KeyPair` in [FlashCrypto]'s signature. The public half is exposed in
 * X.509 SubjectPublicKeyInfo encoding because that is the only form that ever leaves the
 * process — both call sites outside this module already read `keyPair.public.encoded`, and every
 * peer-facing API in `core:security` (`FlashCrypto.verify`, [FlashFingerprint.fingerprint],
 * `FlashPairingFrame`) already speaks SPKI bytes.
 */
public class FlashEcKeyPair internal constructor(
    /** Public key in X.509 SubjectPublicKeyInfo encoding — the wire form. */
    public val publicKeyEncoded: ByteArray,
    internal val privateKey: PlatformEcPrivateKey,
)
