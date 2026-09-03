package com.transfer.flash.core.common.id

/**
 * A fresh random (v4) UUID in canonical 8-4-4-4-12 hex form.
 *
 * Phase 06 seam for [UuidIdGenerator]. `java.util.UUID` exists on Android and desktop JVM
 * but not on Kotlin/Native, and `kotlin.uuid.Uuid` is still `@ExperimentalUuidApi` in
 * Kotlin 2.2.10, so the stable `expect`/`actual` is used. The generated string format is
 * part of the wire contract (peer ids, envelope ids), so an `actual` must produce the same
 * canonical form — not an arbitrary unique token.
 */
internal expect fun randomUuidString(): String
