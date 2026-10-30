# Domain O05 — Media Pipeline Performance Audit
**Commit:** `4dcc7fad133a5a97b1de082ab7fdd94638bbbeae`  
**Date:** 2026-10-23  
**Domain:** O05 — Media Pipeline  
**Target Runtimes:** Android (ART), Desktop (JVM 21 / Skia)

---

## 1. Files Read & Audited

The following implementation files across `:ui:platform-shims`, `:ui:chat`, `:ui:callui`, `:core:transfer`, `:core:messaging`, and `:app` were read and audited in detail:

### Image & Video Decoding Shims
- `ui/platform-shims/src/commonMain/kotlin/com/transfer/flash/ui/shims/FlashImageDecoder.kt`
- `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashImageDecoder.android.kt`
- `ui/platform-shims/src/jvmMain/kotlin/com/transfer/flash/ui/shims/FlashImageDecoder.jvm.kt`
- `ui/platform-shims/src/androidHostTest/kotlin/com/transfer/flash/ui/shims/FlashMediaDecoderSecurityTest.kt`
- `ui/platform-shims/src/androidHostTest/kotlin/com/transfer/flash/ui/shims/FlashMediaCacheTrimTest.kt`
- `ui/platform-shims/src/jvmTest/kotlin/com/transfer/flash/ui/shims/FlashImageDecoderJvmTest.kt`

### Video Surface & Playback Controls
- `ui/platform-shims/src/commonMain/kotlin/com/transfer/flash/ui/shims/FlashVideoSurface.kt`
- `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashVideoSurface.android.kt`
- `ui/platform-shims/src/jvmMain/kotlin/com/transfer/flash/ui/shims/FlashVideoSurface.jvm.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashVideoPlayer.kt`
- `ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/chat/FlashVideoPlayerMathTest.kt`

### Voice Recording & Playback
- `ui/platform-shims/src/commonMain/kotlin/com/transfer/flash/ui/shims/FlashVoiceRecorder.kt`
- `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashVoiceRecorder.android.kt`
- `ui/platform-shims/src/jvmMain/kotlin/com/transfer/flash/ui/shims/FlashVoiceRecorder.jvm.kt`
- `ui/platform-shims/src/jvmTest/kotlin/com/transfer/flash/ui/shims/FlashVoiceRecorderJvmTest.kt`
- `ui/platform-shims/src/commonMain/kotlin/com/transfer/flash/ui/shims/FlashAudioPlayer.kt`
- `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashAudioPlayer.android.kt`
- `ui/platform-shims/src/jvmMain/kotlin/com/transfer/flash/ui/shims/FlashAudioPlayer.jvm.kt`
- `ui/platform-shims/src/jvmTest/kotlin/com/transfer/flash/ui/shims/FlashAudioPlayerJvmTest.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashVoiceRecording.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashVoiceMessageCard.kt`
- `ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/chat/FlashVoiceLogicTest.kt`
- `ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/chat/FlashVoiceRecordingLogicTest.kt`

### Attachment Staging, Capture & Transfer Pipeline
- `ui/platform-shims/src/commonMain/kotlin/com/transfer/flash/ui/shims/FlashFilePicker.kt`
- `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashFilePicker.android.kt`
- `ui/platform-shims/src/jvmMain/kotlin/com/transfer/flash/ui/shims/FlashFilePicker.jvm.kt`
- `ui/platform-shims/src/commonMain/kotlin/com/transfer/flash/ui/shims/FlashCameraCapture.kt`
- `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashCameraCapture.android.kt`
- `ui/platform-shims/src/jvmMain/kotlin/com/transfer/flash/ui/shims/FlashCameraCapture.jvm.kt`
- `ui/platform-shims/src/androidHostTest/kotlin/com/transfer/flash/ui/shims/FlashCameraCaptureRestoreTest.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashAttachmentSheet.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashAttachmentStagingTray.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashComposer.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashConversationScreen.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashImageGrid.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashMediaViewer.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/Chunker.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/SendPipeline.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/ReceivePipeline.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/multistream/MultiStreamDispatcher.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt`
- `app/src/main/java/com/transfer/flash/MainActivity.kt`

---

## 2. Hot Paths with Execution Frequency & Dispatchers

The media pipeline executes across UI, background I/O, and native multimedia layers:

1. **In-Bubble Image Thumbnail Decoding (`Dispatchers.IO`)**
   - *Path:* `FlashImageGrid.kt:487` -> `produceState` -> `withContext(Dispatchers.IO)` -> `FlashMediaDecoder.decode` -> `BitmapFactory.decode*` (Android) / `ImageIO.read` (JVM).
   - *Frequency:* Once per media item on scroll into viewport. Memoized in `LruCache` (Android, 4–24 MB) and `LinkedHashMap` (JVM, 32 items).
   - *Cost & Dispatcher:* Disk read + native codec decode on `Dispatchers.IO`. ~1.16 MB (ARGB_8888 720px) or ~345 KB (RGB_565 480px).

2. **Attachment Send Pipeline File Reading (`Dispatchers.IO` / Worker)**
   - *Path:* `RealFlashTransferRepository.executeSend` -> `MultiStreamDispatcher.send()` -> `chunker.hashOnly` -> `openChunkStream` -> `ChunkFrame.serialize`.
   - *Frequency:* On every file send in 1:1 and group transfers.
   - *Cost & Dispatcher:* Executes on `workerDispatcher` (`Dispatchers.Default` / `IO`). Unconditionally reads the **entire file twice**: Pass 1 for whole-file SHA-256 pre-hash; Pass 2 for chunking and network dispatch.

3. **Voice Note Playback Progress Ticking (`Dispatchers.Main`)**
   - *Path:* `FlashVoiceMessageCard.kt:209` -> `LaunchedEffect(isPlaying)` -> `delay(100ms)` -> `elapsedMs = audioPlayer.positionMs()`.
   - *Frequency:* 10 Hz continuously while audio is playing.
   - *Cost & Dispatcher:* Executes on Compose Main thread (`Dispatchers.Main`). Mutates state variable `elapsedMs`, triggering whole-card recomposition 10 times per second during playback.

4. **Desktop Voice Note AAC-in-MP4 Demux & Decode (`Dispatchers.Main` / UI Thread)**
   - *Path:* `FlashVoiceMessageCard` tap Play -> `FlashAudioPlayer.jvm.kt:117` -> `ensurePrepared()` -> `decodeAacM4aToPcm(file)`.
   - *Frequency:* Once per play tap on received `.m4a` voice note on Desktop.
   - *Cost & Dispatcher:* Runs synchronously on Main UI thread. Executes JCodec pure-Java software AAC demux and decode into up to 32 MB PCM, blocking desktop frame rendering for 300 ms – 1,500 ms.

5. **Voice Recording Microphone Ingest & Waveform Sampling (`Dispatchers.Main` & Capture Thread)**
   - *Path:* User holds mic button -> `FlashComposer.kt:157` -> `delay(100ms)` -> `voiceAmplitudeProvider.maxAmplitude()` -> `recordAmplitudes` append.
   - *Frequency:* 10 Hz during active voice recording.
   - *Cost & Dispatcher:* Android uses `MediaRecorder.maxAmplitude` on main thread; Desktop uses daemon worker `flash-voice-capture` draining `TargetDataLine` into raw file with atomic peak tracking.

6. **Conversation State Message Mapping & Voice Meta Unpacking (`Dispatchers.IO` -> Main)**
   - *Path:* `RealFlashChatRepository.openConversation` -> `applyAttachment` -> `decodeVoiceMeta(entity.text)` -> `FlashVoiceAttachmentUi`.
   - *Frequency:* Every database mutation (new message, receipt, reaction, typing status, transfer progress tick).
   - *Cost & Dispatcher:* Parses packed CSV string `vmsg:<duration>:<csv>` for every voice message in the active chat, splitting strings and allocating boxed Integers.

---

## 3. Checklist Verification Table

| # | Checklist Item | Status | Verification Summary & Evidence |
|---|---|---|---|
| 1 | Image decode sampling to displayed size, inSampleSize calculation, off-main-thread execution, cache keys, memory footprint per thumbnail | **Problem** (`OPT-O05-09`, `OPT-O02-08`) | Decoding is downsampled via `inSampleSize` to `maxLongEdge` (720px standard, 480px minimal, 160px ultra-low). Off-main-thread execution via `withContext(Dispatchers.IO)` in `produceState`. Cache keys include source, video flag, budget, and color depth. Android cache is bounded (4–24 MB) with trim callbacks. However, corrupt images trigger a multi-iteration spin up to `sample = 32` (`OPT-O05-09`), and desktop retains 32 uncompressed bitmaps (~64 MB) without trim integration (`OPT-O02-08`). |
| 2 | EXIF orientation handling and bitmap rotation overhead | **Problem** (`OPT-O05-04`, `OPT-O05-05`) | Both Android and Desktop open the image file/stream a second time *after* decode has already completed to parse EXIF metadata (`OPT-O05-04`). Applying EXIF rotation allocates a full duplicate Bitmap/BufferedImage via `Bitmap.createBitmap(matrix)` / `Graphics2D.drawRenderedImage` (`OPT-O05-05`), doubling peak memory footprint and executing an expensive CPU/GPU pixel blit. |
| 3 | Video thumbnails generation and video playback architecture (surface/player lifecycle, preparing cost, multiple players alive) | **Problem** (`OPT-O05-10`) | Playback uses `FlashVideoSurface.android.kt` (`VideoView`), which is strictly lifecycle-managed (`onRelease { view.stopPlayback() }`) and active only on the current viewer page; multiple players are never alive simultaneously. On Desktop, playback stubs cleanly to system player. Android thumbnails use `MediaMetadataRetriever`. However, Desktop JVM thumbnails use pure-Java JCodec, decoding full frames into multi-megabyte uncompressed RGB byte arrays and converting colorspaces in pure software on the CPU (`OPT-O05-10`). |
| 4 | Voice notes: recording format, bitrate, waveform extraction cost and storage, playback progress ticks | **Problem** (`OPT-O05-02`, `OPT-O05-03`, `OPT-O05-06`, `OPT-O05-07`) | Android records AAC/MPEG-4 at 64 kbps 44.1 kHz mono (.m4a); Desktop records 44.1 kHz 16-bit mono WAV, but uses a wasteful double-pass disk write (`.pcm` -> `.wav`) (`OPT-O05-03`). Desktop playback of Android `.m4a` notes runs pure-Java software AAC decode synchronously on the UI thread, causing severe 300ms–1.5s freezes (`OPT-O05-02`). Waveform extraction runs at 10 Hz during recording; storing waveform in the text column causes string allocation churn during message mapping (`OPT-O05-07`). Card recomposes at 10 Hz during playback for a 1 Hz label (`OPT-O05-06`). |
| 5 | Attachment staging and copying: are files duplicated into private storage before send? Number of full-file reads per send (hash pass, send pass, thumbnail pass) | **Problem** (`OPT-O05-01`) | Picked files are NOT duplicated into private storage; SAF read permissions are persisted via `takePersistableUriPermission` and streamed directly. However, every file send performs **at least two full-file read passes**: pass 1 is an unconditional whole-file `chunker.hashOnly` pre-pass because `fileSha256Hex` is null in `RealFlashTransferRepository` (`OPT-O05-01`), followed by pass 2 for chunking. If the picker provided no size, `measureSourceBytes` performs a third read pass. If media, thumbnail generation adds a fourth. |
| 6 | Image compression / "send smaller" pipeline (re-encoding cost, quality, memory spike) | **No problem** | Chat attachments are sent bit-for-bit verbatim. There is NO image re-compression, transcoding, or "send smaller" pipeline for chat media attachments. Consequently, there is zero CPU re-encoding overhead and zero memory spike from JPEG/WebP compressors during chat send. ("Send smaller" in Flash exclusively refers to ADR-053/ADR-098 for capping WebRTC group video calls at 360p). |
| 7 | Gallery picker thumbnails and camera capture handling (orientation, temp files, process death) | **Problem** (`OPT-O05-08`) | Camera capture properly survives process death and configuration changes via `rememberSaveable` (`currentUri`, `currentPath`) and restores safely via `restoredCapture`. However, captured camera photos (`camera_captures/flash_photo_*.jpg`, 3–10 MB each) and voice recordings (`flash-voice-*.m4a`) accumulate indefinitely in `context.cacheDir` with no eviction policy, quota, or deletion when discarded (`OPT-O05-08`). |
| 8 | Animated content handling (GIF / animated WebP decode, memory, frame loops) | **No problem** | Neither Android nor Desktop executes multi-frame animated decoding loops or retains frame caches for GIFs or animated WebPs. `BitmapFactory` on Android and `ImageIO.read(0)` on Desktop strictly decode frame 0 as a static still image. There are no background animation coroutines, no ticker loops, and no frame-buffer allocations. (Feature limitation: animated stickers and GIFs are rendered as static images). |

---

## 4. Detailed Performance Findings

### OPT-O05-01 — Multi-Pass Full-File Disk I/O on Attachment Send via Unconditional `hashOnly` Pre-Pass
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** S, T, C   **Runtime:** Android, Desktop
- **Component:** `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/multistream/MultiStreamDispatcher.kt:205`, `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt:423,504,545`
- **Bottleneck class (transfer/media only):** storage
- **What is wasteful:** When any file is sent via 1:1 or group transfer, `RealFlashTransferRepository.executeSend` instantiates `MultiStreamDispatcher` without providing `fileSha256Hex` (it is passed as `null` by default). In `MultiStreamDispatcher.send()`, line 205 checks `fileSha256Hex?.let(Sha256::normalizeHex) ?: chunker.hashOnly(source)`. Because the digest is null, `chunker.hashOnly` is unconditionally executed: it streams the **entire file from start to finish** through a 64 KB scratch buffer to compute the SHA-256 digest before sending starts. Immediately afterwards, the materializer coroutine opens `ChunkStream(source)` and streams the **entire file a second time** from byte 0 to EOF to slice chunks and transmit them over the wire. If the file picker did not supply an explicit size (common with document providers and ContentResolvers), `measureSourceBytes` streams the **entire file a third time** just to count bytes.
- **Cost model:** frequency x unit cost x multiplier:
  - *Frequency:* Every file transfer initiated by the user.
  - *Unit cost:* Reading the entire file from mobile flash storage / disk twice (or 3x with unknown size) at flash read speeds (30–80 MB/s on budget eMMC storage).
  - *Multiplier:* On a 1 GB video transfer: `1,000 MB read (hashOnly) + 1,000 MB read (ChunkStream) = 2,000 MB disk read`. `1,000 MB / 50 MB/s = 20 seconds` of pure, blocking flash I/O and battery drain before the first network chunk is even placed on the socket.
- **User-visible effect:** Severe delay before transfer begins; 100% higher flash storage read wear; battery heating and throttled I/O on phones during file sharing.
- **Expected gain:** 50% reduction in total disk I/O and I/O time on the send path. Eliminates 10–60+ seconds of pre-send stalling on files >500 MB.
- **Proposed change:** Eliminate the pre-pass `hashOnly`. Compute the whole-file SHA-256 hash incrementally inside `ChunkStream` / `MultiStreamDispatcher` as chunks are read and dispatched, or send `FILE_START` without pre-computed whole-file hash when per-chunk SHA-256 integrity verification is already enforced on every chunk.
- **Invariants touched (5.3):** Invariant 1 (Security: per-chunk and whole-file integrity verification). The receiver already verifies each chunk via `ChunkFrame.chunkSha256`. The whole-file hash can be transmitted in a trailing frame or verified at completion.
- **Risk / effort:** Medium risk (requires transfer wire protocol handshake coordination); M effort.
- **Prove it:** In a JVM test with a 200 MB file, measure total bytes read from the underlying `ChunkSource`. Before: 400 MB read. After: exactly 200 MB read.
- **Verifier result:** Survived. Traced `RealFlashTransferRepository.kt:545`: parameter `fileSha256Hex` is omitted and defaults to `null`. Traced `MultiStreamDispatcher.kt:205`: `chunker.hashOnly(source)` is executed unconditionally. Traced `SendPipelineTest.kt:136`: explicit repository test comment confirms: *"One open for hashOnly pre-pass + one for the chunking pass."*
- **Related:** OPT-O07 transfer domain; EXP-001; ADR-016.

---

### OPT-O05-02 — Synchronous Pure-Java AAC Audio Decoding on Main/UI Thread in Desktop Voice Player
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** T, J, C, M   **Runtime:** Desktop
- **Component:** `ui/platform-shims/src/jvmMain/kotlin/com/transfer/flash/ui/shims/FlashAudioPlayer.jvm.kt:81,205-244`, `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashVoiceMessageCard.kt:208`
- **Bottleneck class (transfer/media only):** CPU
- **What is wasteful:** When a desktop user clicks "Play" on a voice note recorded on an Android device (which is encoded as AAC in an MP4 container, `.m4a`), the desktop player attempts JDK playback, fails with `UnsupportedAudioFileException`, and falls back to Tier 2: `decodeAacM4aToPcm(file)`. This method demuxes the MP4 container and executes a pure-Java software AAC decoder (`org.jcodec.codecs.aac.AACDecoder`) frame-by-frame, allocating byte arrays and expanding up to 32 MB of PCM in a `ByteArrayOutputStream`. Crucially, `audioPlayer.play()` is invoked directly from Compose's `LaunchedEffect(isPlaying)` on `Dispatchers.Main` (the AWT Event Dispatch Thread). The entire software AAC decoding loop executes synchronously on the UI thread before `clip.open(stream)` is reached.
- **Cost model:** frequency x unit cost x multiplier:
  - *Frequency:* Every time a user taps "Play" on an Android voice note on Desktop.
  - *Unit cost:* Software AAC decoding in pure Java averages ~5x–20x slower than hardware/native decoders. For a 30-second voice note (~2.6 MB PCM), decoding takes 300 ms – 750 ms of 100% CPU time. For a 60-second voice note (~5.3 MB PCM), it takes 600 ms – 1,500 ms.
  - *Multiplier:* 100% UI freeze (0 FPS, dropped frames, non-responsive window events) for 300 ms – 1.5 seconds.
- **User-visible effect:** Desktop application completely hangs / freezes when clicking to play a voice note; window shows "(Not Responding)" on Windows if playback is started on a longer note.
- **Expected gain:** 100% elimination of UI thread freeze during voice note preparation.
- **Proposed change:** Decouple audio preparation from the main thread. Run `ensurePrepared()` and `decodeAacM4aToPcm` asynchronously on `Dispatchers.IO`, reporting a loading/buffering state to the UI until `Clip.open` is ready.
- **Invariants touched (5.3):** None. Preserves all audio playback contracts.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Execute `decodeAacM4aToPcm` in a JVM microbenchmark with a 60-second `.m4a` file. Assert execution time on `Dispatchers.Main` is 0 ms by offloading to `Dispatchers.IO`.
- **Verifier result:** Survived. Traced `FlashVoiceMessageCard.kt:208`: `audioPlayer.play()` is called inside `LaunchedEffect(isPlaying, ...)`. `LaunchedEffect` executes on the Compose UI dispatcher (`Dispatchers.Main`). In `FlashAudioPlayer.jvm.kt:117`, `play()` calls `ensurePrepared()`, which calls `decodeAacM4aToPcm(file)` synchronously on the calling thread.
- **Related:** OPT-O12, OPT-O19.

---

### OPT-O05-03 — Double-Pass Disk I/O and Intermediate Temp File Churn in Desktop Voice Recording
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** S, T, C   **Runtime:** Desktop
- **Component:** `ui/platform-shims/src/jvmMain/kotlin/com/transfer/flash/ui/shims/FlashVoiceRecorder.jvm.kt:86-98,134-142`
- **Bottleneck class (transfer/media only):** storage
- **What is wasteful:** When capturing microphone audio on Desktop, `JvmVoiceRecorder` drains `TargetDataLine` into a raw `.pcm` temporary file on disk (`rawFile`). When capture is stopped via `stop()`, lines 134-140 open `FileInputStream(raw)`, wrap it in `AudioInputStream`, and invoke `AudioSystem.write(..., AudioFileFormat.Type.WAVE, wav)` to write the exact same audio samples out a second time as a `.wav` file, after which `raw.delete()` deletes the original file. The implementation notes this was done because *"a WAV header carries its own length, which is not known until capture stops"*. This causes 3x disk I/O: 1x streaming write, 1x full-file read, and 1x streaming write.
- **Cost model:** frequency x unit cost x multiplier:
  - *Frequency:* Every voice message recorded on Desktop.
  - *Unit cost:* For a 60-second recording at 44.1 kHz 16-bit mono: `44,100 * 2 = 88.2 KB/s` = 5.29 MB PCM.
  - *Multiplier:* Total I/O performed = `5.29 MB (raw write) + 5.29 MB (raw read) + 5.29 MB (wav write) = 15.87 MB` of disk I/O, plus two file allocations and one deletion.
- **User-visible effect:** Noticeable delay and disk grinding after releasing the voice record button before the message appears in the composer tray.
- **Expected gain:** 66.7% reduction in disk I/O (from 15.87 MB to 5.29 MB for a 60s note); zero intermediate temporary files.
- **Proposed change:** Write directly to the destination `.wav` file from the start. Reserve 44 bytes at offset 0 for a blank RIFF/WAVE header, stream PCM directly into the file during recording, and on `stop()` use `RandomAccessFile` to seek back to offset 0 and overwrite the 44-byte header with the final chunk sizes.
- **Invariants touched (5.3):** None. The resulting `.wav` file format is bit-for-bit identical.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Unit test recording 5 seconds of audio, verifying that only one `.wav` file is created on disk, no `.pcm` file is touched, and the output is valid PCM WAV playable by `AudioSystem`.
- **Related:** OPT-O23.

---

### OPT-O05-04 — Redundant Full-File Disk Re-reads for Image EXIF Orientation Header Parsing
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** S, T, C   **Runtime:** Android, Desktop
- **Component:** `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashImageDecoder.android.kt:385-409`, `ui/platform-shims/src/jvmMain/kotlin/com/transfer/flash/ui/shims/FlashImageDecoder.jvm.kt:107,159-214`
- **Bottleneck class (transfer/media only):** storage
- **What is wasteful:** In both Android and Desktop image decoders, the media file is opened and read to parse dimensions and decode the pixel bitmap (`BitmapFactory.decode*` or `ImageIO.read`). Immediately after the bitmap is created, both decoders invoke a secondary orientation routine (`applyExifRotation` on Android, `readExifOrientation` on JVM). On Android, lines 394-407 open `ExifInterface(file.absolutePath)` or re-open `openStream(context, source)` to read EXIF metadata. On Desktop, lines 160-213 open a fresh `FileInputStream` via `openStream(source)` and scan JPEG APP1 segments from byte 0. Both platforms perform an unneeded second disk read pass over the file header.
- **Cost model:** frequency x unit cost x multiplier:
  - *Frequency:* Every image decoded in chat bubbles and the media viewer.
  - *Unit cost:* Reopening file descriptors, acquiring ContentResolver input streams, and re-reading 14 KB – 64 KB of file headers.
  - *Multiplier:* 20–50 images in a typical active chat gallery = 20–50 extra file opens and disk reads on scroll.
- **User-visible effect:** Added latency to thumbnail display; unnecessary disk I/O and ContentResolver Binder transactions on mobile devices.
- **Expected gain:** 50% fewer file opens and header reads during image decoding.
- **Proposed change:** Read EXIF orientation before or during the initial decode pass. On Android, pass the open `FileDescriptor` or extract EXIF during the `inJustDecodeBounds` pass. On Desktop, read the APP1 EXIF segment while reading the stream before passing to `ImageIO`.
- **Invariants touched (5.3):** None.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Benchmark decoding 20 JPEG images; verify file descriptor open count drops from 40 to 20.
- **Related:** OPT-O23.

---

### OPT-O05-05 — Peak Memory Allocation Spike & CPU Blit from Post-Decode Pixel Matrix Rotation
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** M, C, J   **Runtime:** Android, Desktop
- **Component:** `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashImageDecoder.android.kt:433-438`, `ui/platform-shims/src/jvmMain/kotlin/com/transfer/flash/ui/shims/FlashImageDecoder.jvm.kt:275-288`
- **Bottleneck class (transfer/media only):** CPU
- **What is wasteful:** When decoding portrait camera photos (which standard mobile cameras save as landscape orientation with an EXIF orientation tag, typically value 6 = 90° CW), both Android and Desktop decoders perform pixel-level rotation by allocating a second, separate bitmap:
  - Android: `Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)`
  - JVM: `new BufferedImage(newW, newH, ...)` + `g2d.drawRenderedImage(img, null)`
  During this rotation call, both the original decoded bitmap and the newly rotated bitmap coexist simultaneously in memory before `bitmap.recycle()` or GC can reclaim the original. For a 2048x1536 viewer image, each bitmap occupies 12.58 MB. During rotation, peak memory spikes to 25.16 MB for a single image, creating severe allocation pressure and GC pauses on 2 GB Android handsets.
- **Cost model:** frequency x unit cost x multiplier:
  - *Frequency:* Majority of smartphone camera photos (portrait orientation = EXIF 6).
  - *Unit cost:* 100% duplicate memory allocation of the bitmap payload (2x memory footprint) + CPU/GPU matrix transformation blit.
  - *Multiplier:* For full-screen viewer (2048px): `12.58 MB * 2 = 25.16 MB` peak heap allocation per portrait image.
- **User-visible effect:** GC jank when opening full-screen photos on 2 GB phones; risk of `OutOfMemoryError` on constrained heaps.
- **Expected gain:** 50% reduction in peak memory allocation during image decoding; zero CPU pixel-copy blitting.
- **Proposed change:** On Android API 28+, use `ImageDecoder.decodeBitmap` which applies EXIF orientation directly in native code during decoding into a single target buffer. Alternatively, carry the rotation angle in `FlashImageAttachmentUi` and rotate the canvas in the Compose draw phase via `Modifier.graphicsLayer { rotationZ = ... }`, completely eliminating memory duplication and software pixel rotation.
- **Invariants touched (5.3):** None.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Measure peak heap allocation during decode of a 4000x3000 portrait photo with EXIF orientation 6. Before: ~25 MB peak. After: ~12.5 MB peak.
- **Related:** OPT-O02-08, OPT-O03.

---

### OPT-O05-06 — 10 Hz Whole-Card Recomposition During Voice Note Audio Playback
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** J, C, B   **Runtime:** Android, Desktop
- **Component:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashVoiceMessageCard.kt:209-228,347,368`
- **Bottleneck class (transfer/media only):** CPU
- **What is wasteful:** While a voice note is playing, lines 209-218 execute a `while (isPlaying)` loop that polls `audioPlayer.positionMs()` and mutates `elapsedMs = ...` every 100 ms (`FlashVoiceMath.TICK_MS = 100L`, 10 Hz). `elapsedMs` is a top-level `@Composable` `remember` state in `FlashVoiceMessageCard`. Mutating it at 10 Hz causes the entire `FlashVoiceMessageCard` composable to recompose 10 times per second on the Main UI thread, re-evaluating layout rows, accessibility strings, modifiers, and speed pills. Furthermore, the trailing time readout (`trailingLabel`, line 368) formats to `-m:ss`, which only changes once per second (1 Hz), yet it is re-evaluated and recomposed at 10 Hz throughout playback.
- **Cost model:** frequency x unit cost x multiplier:
  - *Frequency:* 10 recompositions per second (10 Hz) for the entire duration of voice note playback.
  - *Unit cost:* Re-executing `FlashVoiceMessageCard` and its sub-composables on ART/JVM main thread (~0.5 ms – 2 ms per recomposition on budget phones).
  - *Multiplier:* A 60-second voice note generates **600 recompositions** on the main thread, 540 of which produce identical text labels.
- **User-visible effect:** Increased CPU usage, battery drain, and thermal throttling while listening to voice messages; frame drops if the user scrolls the chat list while listening.
- **Expected gain:** 90% reduction in card recompositions during audio playback (down from 10 Hz to 1 Hz for the card body), with waveform progress animated smoothly in the draw phase.
- **Proposed change:** Decouple waveform drawing from card recomposition. Supply a state lambda `() -> Float` or `() -> Int` to `FlashVoiceWaveform` and read progress inside the `Canvas` draw block (`drawWithContent`). Update the text timer label only when the second boundary ticks (1 Hz).
- **Invariants touched (5.3):** None.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Run a Compose recomposition counter on `FlashVoiceMessageCard` during a 10-second voice playback. Before: 100 recompositions. After: <= 10 recompositions.
- **Related:** OPT-O03, EXP-013.

---

### OPT-O05-07 — Voice Note Waveform String Parsing & Boxing Allocation Churn per Conversation State Emission
- **Impact rank:** Low-Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** M, C   **Runtime:** Android, Desktop
- **Component:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:7155,7331-7341`
- **Bottleneck class (transfer/media only):** CPU
- **What is wasteful:** Voice note durations and waveforms are stored packed in the SQLite message `text` column as a comma-delimited string (e.g. `vmsg:14500:12,18,45,67,...`). In `RealFlashChatRepository`, whenever the message list is mapped (which occurs on every incoming message, delivery receipt, reaction, typing status change, and transfer progress tick), line 7155 invokes `decodeVoiceMeta(entity.text)` for every voice message in the conversation. `decodeVoiceMeta` calls `.split(',').mapNotNull { it.trim().toIntOrNull() }`. For an 84-sample waveform, this allocates 84 substring objects, 84 trimmed strings, 84 boxed `java.lang.Integer` objects, plus intermediate `ArrayList` collections, for *each* voice message on *each* repository emission.
- **Cost model:** frequency x unit cost x multiplier:
  - *Frequency:* Every conversation flow emission (1 Hz – 10 Hz during active transfers or typing).
  - *Unit cost:* ~250 heap object allocations per voice message per emission.
  - *Multiplier:* In a chat containing 10 voice messages, each conversation emission allocates ~2,500 temporary objects (~100 KB heap). During a running file transfer with 10 Hz progress ticks, this generates ~1 MB/s of pure garbage collection churn.
- **User-visible effect:** Accelerated garbage collection passes and micro-stutter in chat scrolling during concurrent transfers.
- **Expected gain:** 100% elimination of string split and integer boxing churn on conversation updates.
- **Proposed change:** Memoize the parsed `List<Int>` waveform by `entity.id` / `entity.text`, or parse the string in-place with a custom zero-allocation integer scanner without allocating intermediate strings.
- **Invariants touched (5.3):** None.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Benchmark mapping a list of 20 voice message entities 100 times. Measure allocated bytes with `ThreadMXBean.getThreadAllocatedBytes`. Verify allocations drop by >90%.
- **Related:** OPT-O04, OPT-O15.

---

### OPT-O05-08 — Unbounded Storage Accumulation of Discarded Camera Captures and Voice M4A Cache Files
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** S   **Runtime:** Android
- **Component:** `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashCameraCapture.android.kt:44-46`, `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashVoiceRecorder.android.kt:49`
- **Bottleneck class (transfer/media only):** storage
- **What is wasteful:** Every time the user opens the camera to take a photo in chat, `FlashCameraCapture` creates a new timestamped file in `context.cacheDir/camera_captures/flash_photo_<timestamp>.jpg` (3–10 MB per full-resolution JPEG). Similarly, every voice recording creates a new file in `context.cacheDir/flash-voice-<timestamp>.m4a`. If the user cancels the camera, retakes a photo, removes a staged attachment from the tray (`FlashConversationScreen.kt:934`), or records multiple discarded takes, the files remain stored in the application cache directory indefinitely. There is no cache cleanup policy, size quota, or lifecycle reaper for `camera_captures/` or orphaned voice files in the entire application.
- **Cost model:** frequency x unit cost x multiplier:
  - *Frequency:* Every photo taken or voice note recorded.
  - *Unit cost:* 3 MB – 10 MB per photo capture.
  - *Multiplier:* Over 1 month of regular photo and voice sharing (e.g. 5 photos/day + 3 retakes = ~50 MB/day), cache storage accumulates 1.5 GB – 3 GB of abandoned JPEG files in private app storage.
- **User-visible effect:** Android storage warnings ("Storage almost full"); app data footprint silently balloons into gigabytes.
- **Expected gain:** Reclaims gigabytes of disk space; prevents silent app storage bloat.
- **Proposed change:** Add a periodic cleanup routine (e.g. during `FlashApplication.onCreate` or via `WorkManager`) that deletes files in `camera_captures/` and `cacheDir/flash-voice-*` older than 24 hours. Furthermore, delete the local file when an attachment is explicitly removed from `stagedAttachments`.
- **Invariants touched (5.3):** None.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Unit test verifying that photos/recordings older than 24 hours are pruned, while active transfers and un-sent staged files are preserved.
- **Related:** OPT-O23.

---

### OPT-O05-09 — Unchecked Multi-Iteration Decode Retry Spin on Corrupted or Unsupported Images
- **Impact rank:** Low        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** C, S   **Runtime:** Android
- **Component:** `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashImageDecoder.android.kt:207-218,239-250,270-281`
- **Bottleneck class (transfer/media only):** CPU
- **What is wasteful:** In `FlashMediaDecoder.decodeImage`, the decode loop is designed to back off sample sizes if decoding fails:
  ```kotlin
  while (bitmap == null && sample <= 32) {
      options.inSampleSize = sample
      try {
          bitmap = BitmapFactory.decodeFile(localFile.absolutePath, options)
      } catch (oom: OutOfMemoryError) {
          bitmap = null
      }
      if (bitmap == null) {
          sample *= 2
          options.inPreferredConfig = Bitmap.Config.RGB_565
      }
  }
  ```
  If a file is corrupted, truncated, or an unsupported image format, `BitmapFactory.decode*` returns `null` because the image cannot be decoded, NOT because of `OutOfMemoryError`. The loop nevertheless multiplies `sample *= 2` and re-attempts decoding up to 5–6 times (at sample = 2, 4, 8, 16, 32), re-reading the file and re-invoking `openStream` / ContentResolver descriptors repeatedly for an unrecoverable failure.
- **Cost model:** frequency x unit cost x multiplier:
  - *Frequency:* Any corrupt, partial, or non-image file received or selected as media.
  - *Unit cost:* 5–6 redundant native decode attempts and stream opens.
  - *Multiplier:* 5x–6x CPU and disk overhead per failed image decode.
- **User-visible effect:** Thread stall and battery waste on `Dispatchers.IO` when encountering invalid or corrupt media files.
- **Expected gain:** 80% reduction in decode attempts and stream opens on corrupted files.
- **Proposed change:** Only retry with a larger sample size if an `OutOfMemoryError` was explicitly caught. If `BitmapFactory.decode*` returns `null` without throwing OOM, the image payload is invalid — break immediately.
- **Invariants touched (5.3):** None.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Unit test decoding a corrupted JPEG file. Assert `openStream` is called exactly once instead of 6 times.
- **Related:** None.

---

### OPT-O05-10 — Pure-Java JCodec Software Video Frame Extraction and Color Space Conversion on Desktop
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** C, M, T   **Runtime:** Desktop
- **Component:** `ui/platform-shims/src/jvmMain/kotlin/com/transfer/flash/ui/shims/FlashImageDecoder.jvm.kt:123-150`
- **Bottleneck class (transfer/media only):** CPU
- **What is wasteful:** On Desktop, video thumbnail frames are extracted using JCodec (`org.jcodec`). JCodec runs a pure-Java software H.264 video decoder to seek to frame 0, producing a full-resolution YUV420 picture (`pic`). It then allocates a full-size uncompressed RGB `Picture` on the JVM heap (for 1080p, 1920x1080x3 = 6.22 MB; for 4K, 3840x2160x3 = 24.88 MB), executes a pure-Java software color conversion loop (`Yuv420pToRgb().transform`), and then runs an un-optimized pixel-by-pixel `img.setRGB(...)` loop across hundreds of thousands of pixels. For high-resolution video files, this consumes tens of megabytes of temporary heap and locks up background dispatcher threads for several hundred milliseconds per video thumbnail.
- **Cost model:** frequency x unit cost x multiplier:
  - *Frequency:* Once per video attachment displayed in chat on Desktop.
  - *Unit cost:* 6.2 MB – 24.9 MB heap allocation + software YUV-to-RGB conversion + 200,000 to 500,000 `setRGB` method calls.
  - *Multiplier:* In a chat with 5 video files, generates >50 MB of temporary heap allocations and several seconds of sustained CPU load.
- **User-visible effect:** Fan spin and high CPU usage on Desktop when scrolling past video attachments; sluggish thumbnail rendering.
- **Expected gain:** 80% reduction in heap allocation and CPU time for video thumbnail extraction on Desktop.
- **Proposed change:** Subsample during YUV extraction before color conversion so that `Picture.create` only allocates the target thumbnail dimensions (e.g. 720px max edge = ~1 MB rather than 25 MB). Replace pixel-by-pixel `setRGB` with bulk array copy (`BufferedImage.getRaster().setDataElements`).
- **Invariants touched (5.3):** None.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Benchmark extracting a thumbnail from a 4K MP4 file. Measure heap allocations and execution time. Verify heap allocations drop from ~25 MB to <2 MB.
- **Related:** OPT-O19.

---

## 5. "No Problem, Because..." Notes

1. **Video Playback Surface Lifecycle & Leaks:**
   - *Status:* No problem.
   - *Reason:* `FlashVideoSurface.android.kt` wraps `VideoView` inside `AndroidView` with explicit cleanup: `onRelease = { view -> view.stopPlayback() }`. Furthermore, `FlashMediaViewer.kt:447` stops video playback whenever the pager swipes away from the video page (`LaunchedEffect(isCurrentPage)` sets `isPlayingVideo = false`). Multiple players are never active concurrently; chat bubbles only display static thumbnails and never instantiate native player instances.
2. **Image Downsampling to Displayed Size:**
   - *Status:* No problem.
   - *Reason:* Both in-bubble tiles (`FlashImageGrid`) and the full-screen viewer (`FlashMediaViewer`) pass explicit `maxLongEdge` caps (160px for ultra-low, 480px for minimal, 720px standard, 2048px/1536px for viewer) to `computeInSampleSize`, ensuring downsampling occurs natively during the decode pass via `inSampleSize` (Android) and `setSourceSubsampling` (JVM). Images are never loaded full-resolution into memory.
3. **Animated Content (GIF / Animated WebP) Memory & Loop Overheads:**
   - *Status:* No problem.
   - *Reason:* The application does not include any animated GIF or WebP frame decoders, tickers, or multi-frame caches. Both `BitmapFactory` on Android and `ImageIO.read(0)` on Desktop strictly decode frame 0 as a static still image. There is zero risk of animation-driven frame drops or unbounded heap growth from multi-frame buffering.
4. **Attachment Staging Duplication:**
   - *Status:* No problem.
   - *Reason:* Files selected through `FlashFilePicker` (SAF / OpenDocument contract) are NOT copied into private app storage at selection time. Read grants are maintained via `takePersistableUriPermission` and passed directly as `content://` URIs to `transfers.sendFile`, eliminating redundant multi-gigabyte staging copies.
5. **Chat Image Compression / Transcoding Overhead:**
   - *Status:* No problem (Architectural feature absence).
   - *Reason:* There is no client-side JPEG/WebP compression or transcoding pipeline for chat file attachments. All images and media are sent bit-for-bit verbatim over the transfer engine. Thus, there is zero CPU re-encoding overhead and zero memory spike from image compression during chat send.

---

## 6. Summary of Findings

| ID | Title | Impact Rank | Dimensions | Evidence | Runtime | Bottleneck | Effort | Risk |
|---|---|---|---|---|---|---|---|---|
| OPT-O05-01 | Multi-Pass Full-File Disk I/O on Attachment Send via Unconditional `hashOnly` Pre-Pass | High | S, T, C | STATIC-ARITHMETIC | Android, Desktop | storage | M | Med |
| OPT-O05-02 | Synchronous Pure-Java AAC Audio Decoding on Main/UI Thread in Desktop Voice Player | High | T, J, C, M | STATIC-ARITHMETIC | Desktop | CPU | S | Low |
| OPT-O05-03 | Double-Pass Disk I/O and Intermediate Temp File Churn in Desktop Voice Recording | Medium | S, T, C | STATIC-ARITHMETIC | Desktop | storage | S | Low |
| OPT-O05-04 | Redundant Full-File Disk Re-reads for Image EXIF Orientation Header Parsing | Medium | S, T, C | STATIC-ARITHMETIC | Android, Desktop | storage | S | Low |
| OPT-O05-05 | Peak Memory Allocation Spike & CPU Blit from Post-Decode Pixel Matrix Rotation | Medium | M, C, J | STATIC-ARITHMETIC | Android, Desktop | CPU | S | Low |
| OPT-O05-06 | 10 Hz Whole-Card Recomposition During Voice Note Audio Playback | Medium | J, C, B | STATIC-ARITHMETIC | Android, Desktop | CPU | S | Low |
| OPT-O05-07 | Voice Note Waveform String Parsing & Boxing Allocation Churn per Conversation State Emission | Low-Medium | M, C | STATIC-ARITHMETIC | Android, Desktop | CPU | S | Low |
| OPT-O05-08 | Unbounded Storage Accumulation of Discarded Camera Captures and Voice M4A Cache Files | Medium | S | STATIC-ARITHMETIC | Android | storage | S | Low |
| OPT-O05-09 | Unchecked Multi-Iteration Decode Retry Spin on Corrupted or Unsupported Images | Low | C, S | STATIC-ARITHMETIC | Android | CPU | S | Low |
| OPT-O05-10 | Pure-Java JCodec Software Video Frame Extraction and Color Space Conversion on Desktop | Medium | C, M, T | STATIC-ARITHMETIC | Desktop | CPU | S | Low |
