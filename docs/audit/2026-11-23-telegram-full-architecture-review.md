# Telegram vs Flash — Full Architecture, UI Speed, Customization, Media & WebRTC Review

**Date:** 2026-11-23  
**Mode:** Strictly read-only static source review (no builds, runtime traces, or code edits performed)  
**Repositories Inspected:**
- **Flash:** `c:\Users\KaliOxygen\Downloads\Flash` (active `dev` working tree)
- **Telegram Desktop & Linux (`tdesktop`):** `c:\Users\KaliOxygen\Downloads\telegram-src\tdesktop` (`7.4.5`)
- **Telegram Android (`DrKLO/Telegram`):** `c:\Users\KaliOxygen\Downloads\telegram-src\Telegram-Android` (`13.2.2` / `7303`)

---

## Executive Summary

1. **Tech Stack Split:** Telegram **Windows, macOS, and Linux** all compile from a single C++ / Qt 6 Widgets codebase (`telegramdesktop/tdesktop`, with Rust `tlottie`/`wallet-engine` and Qt `QRhi` shaders). **Telegram Android** is a completely separate codebase (`2,292` `.java` files + C++17/20 + Rust `libtmessages.49.so`), with Kotlin (`2.1.0`) used only in `buildSrc/` Gradle build/codegen tasks.
2. **Is Skipping Jetpack Compose Why Telegram Changes Screens So Fast?**  
   **Partly, yes — but Telegram's specific engineering matters more than avoiding Compose alone.** Skipping Compose/Fragments/XML inflation removes slot-table/modifier allocations, first-composition JIT warmup, and `FragmentManager` overhead. Equally decisive are Telegram's retained `ActionBarLayout` view stack (views stay alive instead of disposing), `150 ms` GPU-layered transitions + predictive swipe-back, `NotificationCenter.setAnimationInProgress` heavy-work freezing, `Canvas`-only message cells (`ChatMessageCell`), background `StaticLayout` generation (`MessagesStorage`), and Desktop `QPixmap` transition snapshots (`SlideAnimation`).
3. **Where Flash Loses Fluidity Today:** Flash already ships solid foundations (`FlashMotion`, keyed `FlashMessageList`, `FlashPerformanceMode`, `CallQualityGovernor`, `GroupVideoRouter`, `MemoryGovernor`), **but** currently loses responsiveness to:
   - `AnimatedContent` disposing outgoing screens (`FlashAnimatedScreen`) and `Conversation` bypassing `rememberSaveableStateHolder` / recreating `LazyListState`;
   - an unwired `ui/chat/compose_compiler_config.conf` and unwired `BaselineProfileGenerator` (`androidx.profileinstaller`);
   - ~37 root `collectAsState()` calls in `MainActivity.kt` and ~25 in `DesktopShell.kt`;
   - `RealFlashChatRepository` re-mapping 100+ entities on every progress/reaction/draft/radio-note change and unwired `loadMoreHistory`;
   - `tcpNoDelay` missing on WebSocket transfer paths (`WsTransferClient`/`WsTransferServer`), blocking `Downloaded`-only gates for media playback, Windows-only D3D11 CPU-readback Desktop video, and `webrtc-java:0.19.0`'s VP8-only limitation on Desktop.

---

## 1. Platform Tech Stack Overview (Desktop, Linux, Android)

| Layer | Telegram Desktop (Win / macOS) | Telegram Linux | Telegram Android |
| :--- | :--- | :--- | :--- |
| **Repository** | `telegramdesktop/tdesktop` | Same (`tdesktop`) | `DrKLO/Telegram` |
| **App Languages** | C++ (+ Obj-C++/Swift on macOS) + Rust (`tlottie`, `wallet-engine`) | C++ + Rust | Java (`2,292` files in `TMessagesProj/src`) + C++17/20 + Rust (`tlottie`, `wallet-engine`) |
| **UI Stack** | Qt 6 Widgets (`6.12.0`, patched) + custom `lib_ui` / `td_ui` + `QRhi` shaders (no QML) | Qt 6 Widgets (`WAYLAND` + `XCB`) + `QRhi` | Custom Android `View`/`Canvas` + vendored `RecyclerView` + GLSL/AGSL shaders (no Compose, no Fragments) |
| **MTProto Engine** | `td_mtproto` + OpenSSL (`3.2.1`) | `td_mtproto` + OpenSSL | `tgnet` (native C++ `epoll_wait`) + BoringSSL |
| **E2EE** | `tde2e` (from `tdlib/td`, `-DTD_E2E_ONLY=ON`) | `tde2e` | `tde2e` + `tdutils` (from `tdlib/td`) |
| **Calls / WebRTC** | `tgcalls` + `tg_owt` (WebRTC fork) | `tgcalls` + `tg_owt` + PipeWire | `tgcalls` + WebRTC + RNNoise + OpenH264 |
| **Media & Lottie** | FFmpeg (D3D11VA/DXVA2/CUDA/VideoToolbox) + OpenAL Soft + `tlottie` | FFmpeg (VAAPI/VDPAU/NVDEC) + OpenAL + GStreamer + `tlottie` | Forked Media3/ExoPlayer (`TMessagesProj_Modules/media/`) + FFmpeg + `dav1d` + `libvpx` + `tlottie` |
| **Local Storage** | Custom encrypted binary files (`lib_storage`, `TDEF` AES-256-CTR + `xxHash`/`lz4`) | Same (`lib_storage`) | Native SQLite (`sqlite3.c`, WAL, `PRAGMA secure_delete = ON`, `BuffersStorage`) |
| **HTML Widgets** | `lib_widget_sandbox` + `QuickJS-ng` + `litehtml` | Same | `libtwidget.so` in `WidgetEngineService` (`android:isolatedProcess="true"` + seccomp) |
| **Build & Packaging** | CMake (`3.25`–`3.31`), MSVC (VS 2026) / Xcode | CMake + Ninja in Rocky Linux 8 Docker (`Implib.so`, `-static-libstdc++`), Snap (`core24`), Flatpak | Gradle (AGP `8.13.2`), SDK `36`, minSdk `23`, NDK `27.2.12479018`, CMake `3.22.1` |

### Linux-Specific Implementation Notes (`tdesktop`)
- **Platform Integration (`Telegram/SourceFiles/platform/linux/`)**: Dual `wayland;xcb` QPA backend, D-Bus XML interfaces (`org.freedesktop.Notifications.xml`, `org.freedesktop.login1.Manager.xml`, `org.freedesktop.portal.Flatpak.xml`), `xdg-desktop-portal`, GTK3, KDE `kcoreaddons`/`kimageformats`, PipeWire, and `fcitx5-qt`/`nimf`/`hime` IME support.
- **Portable Static Binary (`Telegram/build/docker/centos_env/Dockerfile`)**: Built on Rocky Linux 8 with `gcc-toolset-15`, `pkg-config --static`, `-static-libstdc++ -static-libgcc`, and **`Implib.so`** lazy-loading trampolines for system shared libraries (`libgtk-3.so`, `libX11.so`, `libxcb.so`, `libwayland-client.so`, `libGL.so`, `libEGL.so`, `libva.so`, `libvdpau.so`) so one static binary runs across Linux distros.

---

## 2. Screen Transitions, Navigation Speed & Rendering Performance

### Why Telegram Feels So Fast
- **Zero Compose, Fragments, or XML Inflation on Android**:
  - `TMessagesProj/build.gradle` excludes `androidx.recyclerview:recyclerview` and vendors customized `RecyclerView.java`, `GapWorker.java`, and `GridLayoutManagerFixed.java` under `TMessagesProj/src/main/java/androidx/recyclerview/widget/`, subclassed by `RecyclerListView`.
  - `LaunchActivity.java` calls `setContentView(frameLayout)` once (`ActivityContentLayout` + `DrawerLayoutContainer` + `ActionBarLayout`), and `ChatActivityAdapter` instantiates `new ChatMessageCell(...)` directly in Java.
- **Retained Screen Stack & Hardware-Layered Swipe-Back (`ActionBarLayout.java`, `BaseFragment.java`)**:
  - `containerView` and `containerViewBack` keep `fragmentsStack` views alive in memory (`removeViewInLayout` / `addView`), avoiding view tree destruction/recreation on push/pop.
  - `startLayoutAnimation` runs a **150 ms** (`190 ms` preview) `dp(48)` slide + fade (`DecelerateInterpolator`), activates `LAYER_TYPE_HARDWARE` during `prepareForMoving()` swipe-back, and clips rounded corners/shadows directly in `drawChild(Canvas)`.
- **Coordinated Animation Freeze**:
  - `ChatActivity.needDelayOpenAnimation()` defers fragment push up to 200 ms until initial messages are ready (`resumeDelayedFragmentAnimation()`).
  - `NotificationCenter.setAnimationInProgress(...)` emits `stopAllHeavyOperations` (`512`) and throttles non-whitelisted updates while transitions or `ChatListItemAnimator` run.
- **Canvas-Only Cells, Background Text Layout & Device Tiering**:
  - `ChatMessageCell extends BaseCell` has zero child `View`s inside bubbles (`drawInternal(Canvas)` draws paths, `StaticLayout`/`TextLayoutBlock`, and `ImageReceiver` directly).
  - `MessagesStorage` builds `MessageObject(..., generateLayout=true, ...)` on its background storage thread.
  - `Theme.java` reads primitive `int[] defaultColors` and `SparseIntArray currentColors`.
  - `SharedConfig.measureDevicePerformanceClass()` classifies devices into `PERFORMANCE_CLASS_LOW/AVERAGE/HIGH` from `Build.SOC_MODEL`, CPU core count, `/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq`, and RAM.
- **Desktop Pixmap Transitions & Virtualized History (`tdesktop`)**:
  - `MainWidget::showNewSection` and `Controller::showAccount` grab `QPixmap` snapshots via `Ui::GrabWidget(this)`, hide child widgets, and animate only two static pixmaps in `SlideAnimation::paintContents` (`window_slide_animation.cpp`).
  - `HistoryView::ListWidget::paintEvent` binary-searches item tops (`std::lower_bound`) and paints only visible elements.
  - `EnsureWindowRhi` (`rhi_surface.cpp`) primes Qt 6.7+ `QRhi` backing stores (Vulkan/OpenGL/Metal/D3D11) for GPU shader surfaces.

### Flash Comparison & Current Bottlenecks
**Already implemented in Flash:**
- `MessageDao.observeConversationPaged(conversationId, limit = 100)` and batch `allActiveMembers()` in `RealFlashChatRepository.kt`;
- `pendingOptimisticEchoes` & `FlashConversationDraftState` in `FlashConversationScreen.kt`;
- Hoisted callbacks, `flashMessageContentType`, and conditional `graphicsLayer` in `FlashMessageList.kt`;
- `Modifier.bubbleWidthCap()` replacing `BoxWithConstraints` in `FlashMessageBubble.kt`, and `SwipeToDismissBox` bypassed on Desktop/selection in `FlashChatListRow.kt`.

**Verified remaining bottlenecks in Flash:**
1. **`FlashAnimatedScreen` (`FlashNavigation.kt`) disposes outgoing screens**: `AnimatedContent` tears down outgoing tabs/screens and recomposes incoming ones during `SlowMillis = 320` ms (`ScreenSlide = 0.3f`); `Conversation` skips `rememberSaveableStateHolder` and recreates `rememberLazyListState()`, and `MainActivity.kt` uses binary `BackHandler` rather than interactive predictive back.
2. **`ui/chat/compose_compiler_config.conf` is unwired**: No `build.gradle.kts` sets `stabilityConfigurationFiles`, so `List<FlashMessageUi>`, `Set<String>`, and `core.*.model.*` are unstable under Strong Skipping.
3. **Missing Baseline Profile in `:app`**: `benchmark/` (`BaselineProfileGenerator.kt`, `StartupBenchmark.kt`) exists, but neither `app/src/main/baseline-prof.txt` nor `androidx.profileinstaller` is wired into `:app`.
4. **Root `collectAsState()` Fan-In**: `MainActivity.kt` (~37 root `collectAsState()` calls) and `DesktopShell.kt` (~25) widen recomposition scopes (e.g., `rawConversationState` + `ongoingGroupCalls`, `totalUnreadCount`).
5. **Full-Window `entities.map` & Unwired Paging**: `RealFlashChatRepository.kt` (`messageContentFlow`/`contentWithRadioNotes`) re-maps all 100+ entities (`computeInitials`, `formatTime`, `assignDaySeparators`, `computeMessageGroupPositions`) on any draft, reaction, progress, or radio-note emission, and `loadMoreHistory` is never called by the UI.
6. **Per-Bubble Modifiers & `SubcomposeLayout` Remains**: `FlashBubbleSurface` (`FlashMessageBubble.kt`) allocates per-bubble `MutableInteractionSource`, `collectIsPressedAsState()`, `animateFloatAsState()`, unconditional `graphicsLayer {}`, `.clip(shape)`, and a coroutine `pointerInput(onOpenActions)` loop even on Android; `BoxWithConstraints` remains on `FlashNearbyScreen.kt`, `FlashTransfersScreen.kt`, `FlashHubScreen.kt`, `FlashSettingsScreen.kt`, and `FlashMediaViewer.kt` (`IntrinsicSize.Min` on `FlashHubNews.kt`, `HcComponents.kt`).
7. **Tiering & Desktop Refresh Cap**: `FlashPerformanceMode.MEDIUM` sets `reduceMotion = true` (`this != HIGH`), disabling transitions; Windows polls `SPI_GETCLIENTAREAANIMATION` (`DesktopMain.kt`), while Linux/macOS have no OS reduce-motion check and `DesktopMain.kt` caps `skiko.fps=60`.

---

## 3. Chat Interface, Customization, App Icons, Shaders & Animations

### Telegram Patterns
- **Message Bubbles & Grouping**:
  - Android `ChatMessageCell` + `MessageDrawable` (`backgroundDrawable[4][4]`, `NinePatchBuilder`, Compact Mode) and Desktop `HistoryView::Message` + `Ui::PaintBubble` (1-byte `BubbleRounding` per corner, `TornEdgeCache`).
  - **Grouped Media Albums** (`MessageObject.GroupedMessages`) lay out photo/video mosaics inside a single bubble with shared timestamp/reactions.
  - **Viewport-Anchored Outgoing Bubble Gradients**: `MessageDrawable.setTop(...)` and Desktop `BubblePattern` anchor vertical gradient shaders to the viewport so bubbles shift color smoothly as you scroll.
- **Themes & Per-Peer Colors**:
  - `.attheme` (Android `Theme.java` key/value + wallpaper payload, live `ThemeDescription` updates without Activity recreate) and `.tdesktop-theme`/`.tdesktop-palette` (Desktop ZIP + `EnsureBubblesReadable` / `EnsurePeerNameReadable` contrast clamping).
  - `PeerColorActivity` / `Api::PeerColors` / `ColorCollectible` tint sender names, reply/quote bars (`ReplyMessageLine`), and profile headers per peer/group.
- **Chat Folders / Filter Tabs**: `FilterTabsView` (Android, supports `AnimatedEmojiSpan`) and `ChatsFiltersTabsMode` / `Ui::FilterIcon` (Desktop) provide customizable horizontal or sidebar folder tabs with unread badges and drag-reorder.
- **App Icon Customization**:
  - Android `LauncherIconController.java` + `AndroidManifest.xml`: 6 `<activity-alias>` targets (`DefaultIcon`, `VintageIcon`, `AquaIcon`, `PremiumIcon`, `TurboIcon`, `NoxIcon`) toggled via `PackageManager.setComponentEnabledSetting(..., DONT_KILL_APP)`.
  - Desktop `Platform::SetApplicationIcon` (live dock/taskbar icon & unread badge) + `base::Platform::SetCustomAppIcon` (macOS icon presets).
- **Wallpapers & Parallax**: `ChatBackgroundDrawable` + `MotionBackgroundDrawable` compute `60x80`/`80x80` 4-stop gradient bitmaps (3 cached animation frames), blend monochrome patterns via `BlendMode.SOFT_LIGHT` / `wallpaper_*_intensity.agsl`, and apply 3-sample accelerometer parallax (`WallpaperParallaxEffect`, 16dp overscan).
- **Animations, `tlottie` & GPU Shaders**:
  - **`tlottie` (`no_std` Rust)**: Compiled to `libtlottie.a` for both Android (`TLOTTIE_CHANNEL_RGBA`) and Desktop (`TLOTTIE_CHANNEL_BGRA`) with skin-tone (`TlottieFitzModifier`) and layer color replacement; Android `RLottieDrawable` renders on 4-thread/2-thread pools with `BitmapsCache` and haptic `vibrationPattern`.
  - **Rich Transitions & Particles**: `SpringAnimation`/`SpringForce`, `TextMessageEnterTransition` & `VoiceMessageEnterTransition` (morphing composer input/recording into the new bubble), `ReactionsEffectOverlay` / `Ui::ReactionFlyAnimation` (parabolic flying emoji + particle burst), `ThanosEffect` (`thanos_vertex.glsl`/`thanos_fragment.glsl` delete disintegration), `superripple_effect.agsl` damped wave ripple, and `LiquidGlassEffect` (`liquid_glass_shader.agsl` SDF refraction + `DownscaleScrollableNoiseSuppressor` + `GlassRimRenderer` 4 MiB tinted 9-patch cache on API 33+).

### Flash Baseline vs Gaps
Flash already has `FlashTheme` (`minimalChrome`, `ultraLowUi`, WCAG `>= 4.5:1` `flashContrastRatio` / `adjustColorToward`, Android 12+ `dynamicAccent`), `FlashBubbleShape` concave scoop tail, `FlashQuotedReplyCard`, `FlashImageGrid`, `FlashReactionsDock`, 5 vector `FlashChatWallpaper` presets + dark-mode lerp, and `drawWithCache` clock deferral in `FlashBrandAnimation` / `FlashInkSplash`.  
**Missing:** App icon switching, shareable `.flashtheme` presets, per-peer accent colors, chat folders/filter tabs, grouped media album mosaics, 4-color gradient/parallax wallpapers, viewport-anchored bubble gradients, composer-to-bubble send morph, reaction flight overlay, Lottie/animated stickers, and API 33+ AGSL `RuntimeShader` glass/ripples.

---

## 4. Media Player, Streaming, Voice & Viewer

### Telegram Media & Streaming Architecture
- **Android Media3 / ExoPlayer Fork (`TMessagesProj_Modules/media/`)**:
  - `ExoPlayer.setWorkerQueue(...)` moves `TextureView.SurfaceTextureListener` callbacks off the main thread.
  - Restored `VideoListener.onSurfaceDestroyed(SurfaceTexture)` so live video surfaces transfer cleanly between chat, `PhotoViewer`, and floating **PiP** without decoder teardown.
  - Built-in `decoder_ffmpeg` (`FfmpegAudioRenderer`, `ExperimentalFfmpegVideoRenderer`, `jni/ffmpeg_jni.cc`, `libdav1d`) used via `EXTENSION_RENDERER_MODE_ON`.
- **Android Progressive Streaming & Instant Scrubbing**:
  - `VideoPlayer` uses `DefaultLoadControl` (`bufferForPlaybackMs = 100` ms for videos, `1000` ms for stories), `ExtendedDefaultDataSource` (`tg://`, `mtproto:`, `.enc`), and `AudioVisualizerRenderersFactory`.
  - `FileStreamLoadOperation` wakes ExoPlayer's `read()` via `CountDownLatch` as `FileLoadOperation` writes chunks (`128 KB`/`512 KB`, 4–8 parallel requests). `findNextPreloadDownloadOffset()` inspects MP4 boxes and **fetches the `moov` atom from the tail immediately** if placed at the end.
  - `VideoPlayerSeekBar` + `VideoSeekPreviewImage` (`x-tgstoryboard` sprites) + `VideoPlayerRewinder` (`gifvideo.cpp` `VideoFramesRewinder` scaled by `SharedConfig` tier) power smooth forward/backward scrubbing.
- **Desktop Media (`tdesktop/Telegram/SourceFiles/media/`)**:
  - `Media::Streaming` fetches 128 KiB parts into 8 MiB slices (`Storage::Cache::Database`) with a semaphore-backed `AVIOContext`, decodes via HW FFmpeg (`D3D11VA`/`DXVA2`/`CUDA`, `VideoToolbox`, `VAAPI`), and renders planar `YUV420`/`NV12` textures via `QRhi` shaders (`yuv420.frag`, `nv12.frag`, `pip_*.frag`).
  - `Media::Audio` decodes via FFmpeg into OpenAL Soft buffers and applies live playback speed changes via `libavfilter` `atempo` (`Loaders::rebufferOnSpeedChange`).
- **Voice Notes & Viewer**: Android `audio.c` packs 100 waveform bars into 5-bit values (63 bytes) and plays voice notes through `VideoPlayer` (streaming + Sonic pitch-preserving speed). `PhotoViewer` animates `ClippingImageView` from chat cell thumbnail bounds (`PlaceProviderObject`) into zoomable full-screen.

### Flash Comparison
- **Android (`FlashVideoSurface.android.kt`, `FlashAudioPlayer.android.kt` in `ui/platform-shims/`)**: Video uses vanilla Media3 `1.6.1` for local-only files (200 ms polling, no streaming `DataSource`, no FFmpeg fallback, no PiP/scrub previews). Audio uses `android.media.MediaPlayer` with synchronous `prepare()` on the caller thread, no audio focus, and strict `FlashFileTransferStatus.Downloaded` gating.
- **Desktop (`FlashVideoSurface.jvm.kt`, `FlashAudioPlayer.jvm.kt`, `MediaFoundation.cpp`)**: Video is **Windows-only** (`IMFMediaEngine` → D3D11 `CopyResource` → `Map(D3D11_MAP_READ)` → JNA `memcpy` → `org.jetbrains.skia.Image.makeRaster`), leaving Linux and macOS without in-app video playback. Audio (`JvmAudioPlayer`) loads the entire file into RAM (`DecodedPcm` up to 32 MB) via `AudioSystem`/JCodec and rebuilds a `Clip` with pure-Kotlin WSOLA on every speed change. Android records `.m4a` while Desktop records `.wav`.
- **Viewer (`FlashMediaViewer.kt`)**: Has smooth zero-recomposition `FlashZoomState` and memory-bounded `FlashMediaDecoder` (`LruCache` 4–24 MB, `inSampleSize`, `RGB_565`), but lacks `thumbUri`-first progressive display, shared-bounds hero transitions, and non-JCodec desktop video thumbnails.

---

## 5. WebRTC Fork (`tgcalls`), Networking, Storage, Security & Core

### Telegram `tgcalls` / WebRTC Fork Highlights
- **DTLS Bypass (`v2/MtProtoDtlsTransport.cpp`) + `EncryptedConnection.cpp`**: Subclasses `cricket::DtlsTransportInternal` to skip the DTLS handshake (using the 256-byte MTProto DH key) and seals `PF_SRTP_BYPASS` RTP/RTCP and SCTP (`kSctpMagic = 0xdcdcdcdc`) with AES-CTR, 16-byte truncated SHA-256 `msgKey`, piggybacked ACKs, and a 64-entry replay window.
- **Compact Gzipped Signaling & Tunable SCTP**: `SignalingProtocolVersion::V3` gzip-compresses structured messages over `DcSctpTransport` (`CustomDcSctpSocket`), with remotely tunable SCTP/backoff limits (`CustomParameters.h`).
- **Reflector Relay & IP Privacy**: `ReflectorPort` implements lightweight UDP/TCP relays (`peer_tag_` + `SendReflectorHello`); when `!_enableP2P`, `NetworkManager` strips UDP/STUN/reflexive candidates (`PORTALLOCATOR_DISABLE_UDP`/`STUN`, `~CF_REFLEXIVE`), and `standaloneReflectorMode` can bind sockets to a synthetic `0.1.2.3` network to prevent local IP leaks.
- **Codecs, Bitrates & RNNoise**: Field trials enable `WebRTC-Audio-SendSideBwe`, `WebRTC-FlexFEC-03`, and `WebRTC-Audio-MinimizeResamplingOnMobile`; Opus runs 48 kHz with inband FEC (`ptime=60/120`, 6–32 kbps); `CodecSelectHelper` filters hardware codecs (AV1/HEVC/H.264/VP8/VP9); `GroupAudioCapturePostProcessor` runs **RNNoise** + moving-average VAD as `webrtc::CustomProcessing`.
- **SFU Group Calls & E2EE (`group/GroupFrameTransformer.cpp`, `tde2e`)**: SFU injects quantized VAD/audio levels into RTP `0xBEDE`, and `GroupFrameTransformer` provides SFrame-style Insertable Streams encryption (parsing H.264 Annex B NALUs / VP8 headers so headers remain SFU-routable and retrying encryption up to 4× to avoid `00 00 01` start-code emulation), coordinated via `tde2e` hash-chained conference state logs (`call_create_*_block`).

### Networking, Storage, Security & OS Integration
- **`tgnet` / `td_mtproto`**: Native `epoll_wait` loop with typed per-DC pools (`generic`, `genericMedia`, `push`, `download[8]`, `upload[8]`, `proxy[4]`), a per-`(dc, small)` `DownloadRequestBudget` (`2 MiB` / `16 MiB` Premium) rebuilt each scheduling pass so control RPCs never starve behind bulk downloads, and `TLSHello` ClientHello obfuscation.
- **Storage**: Android `sqlite3.c` uses `PRAGMA secure_delete = ON`, `WAL` (`journal_size_limit = 10485760`), and `BuffersStorage` direct `ByteBuffer` pooling; Desktop `lib_storage` uses encrypted seekable `TDEF` files.
- **OS Services & Sandboxing**: Android `TelegramConnectionService` (`Telecom` `ConnectionService`), `VoIPPreNotificationService` (`IMPORTANCE_HIGH`, DND bypass), `GcmPushListenerService` (`ConnectionTypePush`), and isolated HTML widget process (`WidgetEngineService` with `android:isolatedProcess="true"` + `SECCOMP_FILTER_FLAG_TSYNC` in `twidget-seccomp.cpp`).

### Flash Core Comparison
**Flash's existing strengths:**
- `CallQualityGovernor.kt` (4-rung voice-protecting concession ladder), `GroupVideoRouter.kt` (`vreq`/`vgrant`/`vdeny` + 2.4 GHz split budget), `FlashWebRtcEngine.kt` (startup capture frame probe for broken OEM HALs + `LOW`-tier playout tuning), and `CallIceService` ephemeral coturn HMAC credentials;
- `WsKeepaliveTicker`, Doze-tolerant `WsKeepalive`, `BoundedSendQueue` priority lane, `FlashTlsContextFactory` session cache, `MultiStreamDispatcher`/`MultiStreamReceiver` with receiver-authoritative `ACK_BATCH`;
- KMP Room + SQLCipher (`FlashDatabaseOpener.kt`, `MemoryGovernor` `shrink_memory` + `incremental_vacuum`), Desktop `sqlite-jdbc-crypt` WAL recovery, `PairingV2` SPKI-pinned commit-before-reveal, `KeystoreFlashCrypto` (StrongBox/TEE), `SealedEnvelope` (`FLSE`), plus `core/ptt` & `core/radio`.

**Flash's core gaps:**
- `WsTransferClient.kt` / `WsTransferServer.kt` leave Nagle enabled (`tcpNoDelay` missing, unlike `DataChannelClient`/`DataChannelServer`);
- `FlashDatabaseOpener.kt` / `JvmFlashDatabaseOpener.kt` do not set `PRAGMA secure_delete = ON`;
- `Chunker.kt` skips already-transferred bytes linearly on resume rather than seeking;
- `webrtc-java:0.19.0` on Desktop forces VP8-only (`CallSdp.kt` strips `a=fmtp:`) and lacks `networkPriority`/`bitratePriority`/`degradationPreference` (`RtpSenderTuning.jvm.kt`), while fallback `MIC`/`DEFAULT` capture and Desktop/PTT lack RNNoise;
- Group calls are full-mesh (capped around 4–5 video/share sends in `GroupVideoLimits`/`ShareLadder`);
- Android uses `FlashCallService` (`CallStyle` FGS) without `Telecom` `ConnectionService`, `MessagingStyle` + `RemoteInput`, or `ShortcutManager` conversation shortcuts; off-LAN Hub users rely on a persistent `FlashBackgroundService` FGS rather than optional push wake.

---

## 6. Prioritized Roadmap for Flash

### P0 — Immediate High-ROI Wins (Low Risk)
1. **Wire `ui/chat/compose_compiler_config.conf`** into `:ui:chat`, `:ui:callui`, `:ui:theme`, `:app`, and `:desktop` (`stabilityConfigurationFiles`) and verify with Compose compiler metrics.
2. **Generate & ship a Baseline Profile + `androidx.profileinstaller`**: Run `BaselineProfileGenerator`, commit `app/src/main/baseline-prof.txt`, and wire `profileinstaller` into `:app`.
3. **Set `tcpNoDelay = true` & 1 MiB socket buffers on `WsTransferClient.kt` / `WsTransferServer.kt`**: Eliminates Nagle + delayed-ACK stalls on small WS control/ACK frames.
4. **Add `PRAGMA secure_delete = ON`**: Enable in `FlashDatabaseOpener.kt` and `JvmFlashDatabaseOpener.kt` to zero deleted SQLite pages immediately.
5. **App Icon Customization**: Add `<activity-alias>` entries in `app/src/main/AndroidManifest.xml` + `FlashLauncherIconController` (`DONT_KILL_APP`) and expose in `FlashSettingsScreen.kt`.

### P1 — Core UI & Media Responsiveness
6. **Retained Tab Slots, `PredictiveBackHandler` & Transition Tightening**: Keep the 4 tab roots alive in `FlashAnimatedScreen` instead of disposing them with `AnimatedContent`; keep an LRU `LazyListState` cache for recent conversations; tighten `SlowMillis` (`320` → `150–200` ms) and allow GPU-composited slides on `MEDIUM` tier; add `PredictiveBackHandler` + `CompositingStrategy.ModulateAlpha` (Android) and snapshot transitions (Desktop).
7. **De-Scope Shell Root State & Optimize Thread Updates**: Push `MainActivity.kt` (~37) and `DesktopShell.kt` (~25) `collectAsState()` calls down to feature leaves (`collectAsStateWithLifecycle()` on Android); wire `loadMoreHistory` into `FlashMessageList`; split fast-moving progress/reactions out of `RealFlashChatRepository` `entities.map`; gate `pointerInput(onOpenActions)` and press scale in `FlashMessageBubble.kt`; replace remaining `BoxWithConstraints` / `IntrinsicSize.Min` on hot screens; extend `UiScrollGovernor` to throttle non-critical background emissions during transitions.
8. **Unify Android Audio on Media3 + Progressive Streaming**: Replace `android.media.MediaPlayer` (`FlashAudioPlayer.android.kt`) with ExoPlayer + audio focus; expose a chunk-aware `DataSource` backed by `ReceivePipeline` / `HubFileTransfers` bitfields (prefetching chunk 0 + tail `moov`) so audio/video play before `Downloaded` completes; replace `Chunker.kt`'s linear resume skip with seekable offsets.
9. **Streaming Desktop Audio + Unified Opus**: Replace Desktop's full-RAM `DecodedPcm` + `Clip` rebuild (`FlashAudioPlayer.jvm.kt`) with streaming `SourceDataLine` + incremental WSOLA, and unify `.opus`/Ogg voice recording across Android & Desktop.
10. **Android `MessagingStyle` + `RemoteInput` + Conversation Shortcuts**: Add inline notification reply and OS People/conversation shortcuts.

### P2 — Chat Customization, Animations & Call Polish
11. **Chat Customization Pack**: Shareable `.flashtheme` presets (validated with `flashContrastRatio`), per-peer/group accent colors, custom chat folders/tabs, grouped media album mosaics in `FlashImageGrid`, 4-color gradient + pattern wallpapers (with optional accelerometer parallax), and viewport-anchored outgoing bubble gradients (gated by `!minimalChrome && !ultraLowUi`).
12. **Viewer & Transition Polish**: Display cached `thumbUri` immediately while full-res decodes, add shared-bounds hero transitions from `FlashImageGrid`, buffered scrub previews, PiP surface handoff, composer-to-bubble send morph, and a parabolic reaction flight overlay.
13. **Android `Telecom` `ConnectionService`, RNNoise & `RELAY_ONLY` Privacy Toggle**: Add `ConnectionService` alongside `FlashCallService.kt`, integrate RNNoise into fallback `MIC`/`DEFAULT` & Desktop/PTT capture, expose a user-facing `RELAY_ONLY` IP-hiding call toggle, and pre-gather ICE (`iceCandidatePoolSize`) to cut call setup RTTs.
14. **AGSL `RuntimeShader` Effects & `tlottie` Evaluation**: Gate API 33+ HIGH-tier liquid-glass chrome and send/transfer ripples on `!minimalChrome && !ultraLowUi`, and evaluate Telegram's MIT-licensed `no_std` Rust `tlottie` for lightweight animated stickers/reactions.

### P3 — Cross-Platform Native Video & Large-Group Scale
15. **Cross-Platform Desktop Video & Native WebRTC Replacement**: Replace Windows-only D3D11 CPU readback (`FlashVideoSurface.jvm.kt`) and `webrtc-java:0.19.0`'s VP8-only/missing-pacer limitations (`CallSdp.kt`, `RtpSenderTuning.jvm.kt`) with an FFmpeg/Skia zero-copy pipeline (unlocking Linux & macOS playback) and a native WebRTC wrapper with hardware H.264/dav1d + voice-first pacer priority.
16. **Hub SFU + Frame-Level E2EE & Optional Hub Push Wake**: Scale group video/screen-share beyond full-mesh limits with a Hub SFU + header-preserving frame encryption (`GroupFrameTransformer` pattern), and add optional UnifiedPush/FCM wake for off-LAN Hub users.
