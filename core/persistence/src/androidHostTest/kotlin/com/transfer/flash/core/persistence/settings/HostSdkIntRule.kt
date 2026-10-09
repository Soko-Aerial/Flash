package com.transfer.flash.core.persistence.settings

import org.junit.rules.ExternalResource

/**
 * Test-harness fix for the 12 "Unable to rename preferences_pb.tmp" failures (sweep 2026-10-09).
 *
 * Root cause (reproduced with a 15-line Java program): DataStore's `File.atomicMoveTo` uses `Files.move(REPLACE_EXISTING)` only when
 * `Build.VERSION.SDK_INT >= 26`; otherwise it falls back to `File.renameTo`. In a plain-JVM host test the stub `android.jar` reports
 * `SDK_INT == 0`, so the fallback runs, and on JDK 25 / Windows (the Gradle test JVM here) `File.renameTo` no longer replaces an
 * existing file: the first write works, every later one returns false and DataStore throws "Unable to rename". JDK 21 still replaced.
 * This is not a product bug (on a device SDK_INT is real).
 *
 * Fix: report a modern SDK_INT for the test, which is what the production code path on a device takes. Restored afterwards.
 */
class HostSdkIntRule : ExternalResource() {
    private var previous: Int? = null

    override fun before() {
        val field = android.os.Build.VERSION::class.java.getField("SDK_INT")
        previous = field.getInt(null)
        setSdkInt(field, 34)
    }

    override fun after() {
        previous?.let { setSdkInt(android.os.Build.VERSION::class.java.getField("SDK_INT"), it) }
    }

    /**
     * `SDK_INT` is `static final`; since JDK 12 reflection cannot clear `final`, so the value is written through `sun.misc.Unsafe`
     * (test code only; present in the `jdk.unsupported` module of every JDK this project builds with).
     */
    private fun setSdkInt(field: java.lang.reflect.Field, value: Int) {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(unsafe, field)
        val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java).invoke(unsafe, field) as Long
        unsafeClass.getMethod("putInt", Any::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(unsafe, base, offset, value)
    }
}
