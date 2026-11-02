# Radio stream (R) — errors and fixes

Fragment for `logs/errors.md`. ERROR-971..980 are reserved for this stream (plan §7).

---

## ERROR-971 — `port.writeBytes(buffer, count, offset)` in jSerialComm 2.11.4 is `(byte[], int, int)`, not `(byte[], long, long)`

### Date
2026-11-02

### Area
`:core:radio` / desktop serial link (`OPT-O26-04`)

### Symptoms
`:core:radio:compileKotlinJvm` failed while replacing the duplicate outgoing-frame copy in `JvmSerialLink.write`:

```text
e: .../core/radio/src/jvmMain/kotlin/com/transfer/flash/core/radio/link/JvmSerialPortCatalog.kt:78:30
Cannot access 'fun writeBytes(p0: Long, p1: ByteArray!, p2: Int, p3: Int, p4: Int): Int':
it is private in 'com/fazecast/jSerialComm/SerialPort'.
```

### Environment
- Windows 11, 8 logical cores; JBR 21; Gradle 9.5.0.
- jSerialComm 2.11.4 (`gradle/libs.versions.toml`), resolved from
  `~/.gradle/caches/modules-2/files-2.1/com.fazecast/jSerialComm/2.11.4/...`.
- Compile target JVM 11 (`core/radio/build.gradle.kts`).

### Root cause
The public overloads of `com.fazecast.jSerialComm.SerialPort` in 2.11.4 are

```text
public final int  readBytes(byte[], int)
public final int  readBytes(byte[], int, int)
public final int  writeBytes(byte[], int)         // (buffer, bytesToWrite)      offset 0
public final int  writeBytes(byte[], int, int)    // (buffer, bytesToWrite, offset)
private native int writeBytes(long, byte[], int, int, int)
```

The parameters of the three-argument form are **`int`**, and the order is `(buffer, bytesToWrite, offset)`
(verified with `javap -c`: the two-argument form calls the three-argument one with `0` as the third argument, and the
method first rejects `bytesToWrite > buffer.length - offset`). Passing `Long` arguments made Kotlin resolve against the
private native five-argument overload and fail with "it is private", which reads like an access problem rather than a
signature problem.

### Failed attempts
1. `port.writeBytes(data.copyOfRange(off, data.size), data.size - off)` — the original code. Correct, but it copies the
   whole remaining buffer on every iteration, including the first (where `off == 0`), which is the defect `OPT-O26-04`
   names.
2. `port.writeBytes(data, (data.size - off).toLong(), off.toLong())` — the compile error above.

### Working fix
`val n = port.writeBytes(data, data.size - off, off)`, with the same `n < 0` / `n == 0` handling as before. No copy, and
the third argument is the byte offset as the library documents.

### Verification
`:core:radio:jvmTest` and `:app:compileDebugKotlin` green (see `progress.md` for the exact run). The write path itself is
**never exercised against a real serial port in any test in this repository** (`JvmSerialPortCatalog`'s KDoc says so), so
this fix is compile- and review-verified only: the desktop serial write is owed as part of `PERF-R05`/`BT-00`.

### Related files
- `core/radio/src/jvmMain/kotlin/com/transfer/flash/core/radio/link/JvmSerialPortCatalog.kt`

### Status
RESOLVED (compile-verified; runtime unverified on hardware)

---

## ERROR-972 — K2 backend crashes ("No override for IR_EXTERNAL_JAVA_DECLARATION_STUB name:get") on an anonymous `LinkedHashMap` subclass

### Date
2026-11-02

### Area
`:core:radio` build / Kotlin 2.x compiler (`OPT-O26-03`, `OPT-O26-07`)

### Symptoms
Both `:core:radio:compileKotlinJvm` and `:core:radio:compileAndroidMain` failed with an **internal compiler error**,
not a source diagnostic:

```text
e: java.lang.IllegalStateException: No override for FUN IR_EXTERNAL_JAVA_DECLARATION_STUB name:get visibility:public
modality:OPEN <> (<this>:java.util.LinkedHashMap<K..., V...>, p0:@[EnhancedNullability] K...) returnType:@[EnhancedNullability] V?... [operator]
in CLASS CLASS name:<no name provided> modality:FINAL visibility:local
superTypes:[java.util.LinkedHashMap<com.transfer.flash.core.radio.wire.JdkRadioSignatureScheme.SpkiKey, java.security.PublicKey>]
  at org.jetbrains.kotlin.backend.common.actualizer.SpecialFakeOverrideSymbolsResolver.getReferencedSimpleFunction(...)
```

### Environment
- JBR 21, Gradle 9.5.0, Kotlin 2.x K2 compiler, `explicitApi()`; `:core:radio` JVM 11 target.

### Root cause
A `val map = object : LinkedHashMap<K, V>() { override fun removeEldestEntry(...) = size > max }` property, later
indexed with `map[key]`, makes the K2 backend resolve the `get` fake override against the *local anonymous* class and
crash while actualising the module. It is a compiler bug, not a source error, and it names a file that may be unrelated
to the real trigger - the report named `JdkRadioSignatureScheme` while `PendingAckTable` had the same construct.

### Failed attempts
1. Rebuilding after fixing a genuine source error: the internal error persisted, so it is not caused by the first failure.
2. Reading the message as an `explicitApi()` visibility problem: it is not; the visibility in the message is `public`.

### Working fix
Use a plain `LinkedHashMap` and evict by hand:

```kotlin
private val parsedKeys = LinkedHashMap<SpkiKey, PublicKey>()
...
parsedKeys[key] = parsed
while (parsedKeys.size > MAX_PARSED_KEYS) {
    val eldest = parsedKeys.keys.firstOrNull() ?: break
    parsedKeys.remove(eldest)
}
```

Same behaviour, no anonymous subclass. `RadioSession`'s pre-existing `object : LinkedHashMap<...>()` (completed-segment
memory) still compiles, so the pattern is only a hazard where the map is also read through an index on the anonymous type;
either way, avoid the construct in new code.

### Verification
`:core:radio:jvmTest` and `:core:radio:compileAndroidMain` green (see `progress.md`).

### Related files
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/chat/PendingAckTable.kt`
- `core/radio/src/jvmMain/kotlin/com/transfer/flash/core/radio/wire/JdkRadioCrypto.kt`
- `core/radio/src/androidMain/kotlin/com/transfer/flash/core/radio/wire/JdkRadioCrypto.kt`

### Status
RESOLVED

---

## Note (not a product error) — the first version of the measurement harness asserted the wrong byte count

`RadioAllocationBenchTest.kissDecoderAllocationOnANoisyLine` first asserted `statistics.bytesIn == 1 048 576` after
16 measured iterations. The harness's own warm-up loop feeds the decoder too, so the counter was `1 179 648`. The
assertion was replaced by a `> 0` sanity check plus a separate, cleanly scoped measurement; no product code was involved.
Recorded only so the next agent does not read the failed run in the build log as a defect.
