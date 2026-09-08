package com.transfer.flash.core.persistence.db

import androidx.room.RoomDatabaseConstructor

/**
 * Generated-constructor seam for [FlashDatabase] (Phase 09B-1).
 *
 * Room has two ways to instantiate a database. The reflective one,
 * `Room.databaseBuilder(context, FlashDatabase::class.java, name)`, needs `Class` literals and an
 * Android `Context`, so it exists only on Android — that is why [FlashDatabaseOpener] stays in
 * `androidMain`. Every other target uses this route instead: Room's KSP processor sees
 * [androidx.room.ConstructedBy] on [FlashDatabase], finds this `expect object`, and emits the
 * matching `actual` per target. Without it the processor generates no initializer for the `jvm()`
 * target at all, so the desktop artifact would contain a database class that cannot be opened.
 *
 * **Nothing here is hand-written on the platform side by design.** The `actual` declarations are
 * generated, which is also why the compiler cannot see them while it checks this file — hence the
 * suppression, whose argument is the compiler diagnostic `NO_ACTUAL_FOR_EXPECT` (the name Room's own
 * KMP guide uses; `KotlinNoActualForExpect` is the IDE inspection id and does not silence a build).
 * If a target ever fails with "expected object has no actual declaration", the fix is to find out
 * why KSP did not run for that target, not to hand-write a stub.
 *
 * `public` because [FlashDatabase] is public and Room's contract requires the constructor object to
 * be at least as visible as the database it constructs.
 */
@Suppress("NO_ACTUAL_FOR_EXPECT")
public expect object FlashDatabaseConstructor : RoomDatabaseConstructor<FlashDatabase> {
    override fun initialize(): FlashDatabase
}
