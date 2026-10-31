# Transfer Stream (T) Errors Log

Reserved range: ERROR-891 to ERROR-900.

## ERROR-891 — OkioRandomAccessSinkHandleTest Preallocation Size Assertion Failure on Windows

### Date
2026-10-30

### Area
Storage / File Preallocation (O23-03)

### Symptoms
`OkioRandomAccessSinkHandleTest.openingHandleDoesNotGrowFileWhenSmaller` failed with `AssertionError: expected:<0> but was:<10000>` and subsequent `IOException: failed to delete temp file` on Windows.

### Root cause
The test had historically asserted that opening a handle did not grow a small file (an old workaround for ERROR-067 where size remained 0). With optimization O23-03, `platformPreallocate` properly sets the initial disk capacity using OS preallocation (`setLength` on Windows / `fallocate` on Android), growing the allocated length to the requested size. Because the test failed before closing the handle, the open file handle on Windows prevented deleting the temporary file in `@AfterTest`.

### Working fix
Updated the unit test to verify that `openingHandlePreallocatesExpectedSizeSafely` preallocates the expected capacity, and wrapped assertions in a `try-finally` block to guarantee `handle.close()` executes before temp file cleanup.

### Status
RESOLVED

---

## ERROR-892 — Golden Vector Zero-Byte Chunk Parsing Failure with Buffer Pool

### Date
2026-10-31

### Area
Chunk Framing / Buffer Pooling (O02-01)

### Symptoms
`ChunkFrameGoldenVectorTest > every golden vector parses and reserializes to its own bytes` failed with `AssertionError: V6 chunk empty data: golden bytes did not parse`.

### Root cause
`ChunkBufferPool.acquire(n)` had a precondition `require(size > 0)`. When parsing golden vector V6 (an empty chunk with 0-byte payload), `Reader.bytesPooled(0)` called `ChunkBufferPool.acquire(0)`, which threw `IllegalArgumentException`, causing frame parsing to fail.

### Working fix
Updated `bytesPooled(n)` in `ChunkFrame.kt` to return `ByteArray(0)` immediately if `n == 0`, and added a guard in `ChunkBufferPool.release(buffer)` to ignore buffers of size <= 0.

### Status
RESOLVED
