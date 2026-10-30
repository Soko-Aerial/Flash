# Flash Performance Optimization Findings Ledger
**Commit:** `4dcc7fad133a5a97b1de082ab7fdd94638bbbeae`  
**Date:** 2026-10-23  

| ID | Title | Impact Rank | Dimensions | Evidence Type | Runtime | Effort | Risk | Verifier Result | Status |
|---|---|---|---|---|---|---|---|---|---|
| OPT-O01-01 | Decorative Launch Splash (UI-056) Artificially Delays Usable UI by 3.7s | High | T, J, B | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O01-02 | Synchronous Main-Thread Disk I/O, IPC & Directory Traversal on Cold Start | High | T, S, C | STATIC-ARITHMETIC | Android | S | Low | Survived | OPEN |
| OPT-O01-03 | Missing Baseline Profile and Startup Profile in Android Release Artifacts | High | T, C, J | STATIC-ARITHMETIC | Android | M | Low | Survived | OPEN |
| OPT-O01-04 | Monolithic Eager Engine Assembly & Subnet Sweep Contention at Launch | High | T, C, M, N, B, S | STATIC-ARITHMETIC | Android, Desktop | M | Medium | Survived | OPEN |
| OPT-O01-05 | Synchronous File I/O & Vault Checks in DesktopEngine Main-Thread Constructor | Medium | T, S | STATIC-ARITHMETIC | Desktop | S | Low | Survived | OPEN |
| OPT-O01-06 | Missing AppCDS and Unoptimized JVM Runtime Flags in Desktop Packaging | Medium | T, M, Z | STATIC-ARITHMETIC | Desktop, Build | M | Low | Survived | OPEN |
| OPT-O01-07 | Redundant MediaCodecList Enumeration on Android Startup | Low | T, C | STATIC-ARITHMETIC | Android | S | Low | Survived | OPEN |
| OPT-O01-08 | Sequential PRAGMA Queries and Monolithic Schema Migration Open Overhead | Low | T, S | STATIC-ARITHMETIC | Android | S | Low | Survived | OPEN |
| OPT-O03-01 | Whole-Row 60/120 FPS Recomposition in Share Target Scanning Dot via Composition Phase Alpha Read | Medium | J, C, B | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O03-02 | Reaction Chip Frame-by-Frame Recomposition via Composition Phase State and Puff Reads | Low-Medium | J, C | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O03-03 | Root ConversationScreen Invalidation on Every Keystroke via Draft Snapshot Read | High | J, C, M | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O03-04 | Lack of Compose Compiler Stability Configuration File for Domain Models and Collections | Medium | J, C, M | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O03-05 | Fresh Lambda Allocations per Attachment Card per Recomposition in Message Bubble | Medium | M, C | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O03-06 | O(N) HashSet Structural Equality Check on Every LazyColumn Recomposition | Medium | C, J | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O03-07 | Re-instantiation of GroupFileSender via DiscoveredEndpoints Remember Key | Low-Medium | C, M | STATIC-ARITHMETIC | Android | S | Low | Survived | OPEN |
| OPT-O03-08 | Rapid-Fire Recompositions and List Allocation in Message Context Menu | Low | J, M | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O03-09 | Shell-Scope Unmemoized Regex Mapping of Trusted Peers on ART Main Thread | Medium | J, C, M | STATIC-ARITHMETIC | Android | S | Low | Survived | OPEN |
| OPT-O03-10 | Root DesktopShell Invalidation via 8 Background and Settings StateFlows | High | J, C, M, B | STATIC-ARITHMETIC | Desktop | M | Medium | Survived | OPEN |
| OPT-O03-11 | Infinite Transition Lacks reduceMotion Guard in Share Target Scanning Dot | Low-Medium | B, J | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O03-12 | Unconditional Infinite Transition animateFloat in HcDot | Low | B, C | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O03-13 | Per-Row BorderStroke Allocation on Every Recomposition of Incoming Bubbles | Low | M, C | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O03-14 | Canvas Allocations (Paths, Multiple Brushes, Array Spread) per Frame in Launch Splash | High | J, C, M, T | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O03-15 | Canvas Per-Frame Geometry Allocations in Active Swarm Piece Map | Low-Medium | M, C | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O03-16 | Double Text Measurement Pass via IntrinsicSize.Min in Quoted Reply Bubble | Medium | J, C | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O03-17 | SubcomposeLayout (BoxWithConstraints) in Bottom Navigation Bar | Low-Medium | J, C | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O03-18 | SubcomposeLayout (BoxWithConstraints) in Active Call Control Dock | Low-Medium | J, C | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O02-01 | Receive pipeline bypasses ChunkBufferPool (1 GB unpooled allocations in LOS) | High | M, C, J, T | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O02-02 | RealFlashChatRepository permanently retains closed conversation UI state in memory | Medium | M | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O02-03 | Org directory snapshot retains 5,000–20,000 members in memory (35–45 MB heap spike) | High | M, K | STATIC-ARITHMETIC | Android, Desktop | M | Medium | Survived | OPEN |
| OPT-O02-04 | SwarmDriver outbound queues use Channel.UNLIMITED (unbounded chunk memory) | Medium | M, K | STATIC-ARITHMETIC | Android, Desktop | S | Medium | Survived | OPEN |
| OPT-O02-05 | SwarmEngine retains all historical tombstones permanently in LinkedHashMap | Low | M, K | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O02-06 | Desktop and Server SQLite databases lack page cache bounds and trim hooks | Medium | M, S | STATIC-ARITHMETIC | Desktop, Server | S | Low | Survived | OPEN |
| OPT-O02-07 | Server per-connection heap footprint creates capacity cliff at ~1,000–1,500 connections | High | M, K | STATIC-ARITHMETIC | Server | S | Low | Survived | OPEN |
| OPT-O02-08 | Desktop image decoder retains 32 uncompressed bitmaps (~64 MB) without trim | Low | M | STATIC-ARITHMETIC | Desktop | S | Low | Survived | OPEN |
| OPT-O02-09 | AndroidImageDecoder captures Activity Context in member field | Low | M | STATIC-ARITHMETIC | Android | S | Low | Survived | OPEN |
| OPT-O02-10 | ResumeBitVector.doneIndexes allocates boxed ArrayList<Int> (~3.2 MB per 100k chunks) | Medium | M, C | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O04-01 | Unbounded Full-History Query & Thread Re-Mapping in Open Conversation | High | M, C, T, S | STATIC-ARITHMETIC | Android, Desktop | M | Medium | Survived | OPEN |
| OPT-O04-02 | N+1 Synchronous Database Queries in Chat List State Combine on Every Event | High | S, T, C, B | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O04-03 | Group Receipt & Reaction Invalidation Storm: Full Thread Re-Mapping per Ack | High | C, J, M, T | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O04-04 | Full Table Scan & Correlated Subqueries on Search and Unread Queries | Medium | S, T, C | STATIC-ARITHMETIC | Android, Desktop | M | Medium | Survived | OPEN |
| OPT-O04-05 | Chat List Row Layout & Allocation Overhead: Per-Item SwipeToDismissBox State | Medium | J, M, C | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O04-06 | Inlined Day Separators in Message List Break Compose Slot Re-use | Low | J, C | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O04-07 | Unconstrained animateScrollToItem in jumpToMessage Causes Measure Storm | Low | J, C, T | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O05-01 | Multi-Pass Full-File Disk I/O on Attachment Send via Unconditional hashOnly Pre-Pass | High | S, T, C | STATIC-ARITHMETIC | Android, Desktop | M | Medium | Survived | OPEN |
| OPT-O05-02 | Synchronous Pure-Java AAC Audio Decoding on Main/UI Thread in Desktop Voice Player | High | T, J, C, M | STATIC-ARITHMETIC | Desktop | S | Low | Survived | OPEN |
| OPT-O05-03 | Double-Pass Disk I/O and Intermediate Temp File Churn in Desktop Voice Recording | Medium | S, T, C | STATIC-ARITHMETIC | Desktop | S | Low | Survived | OPEN |
| OPT-O05-04 | Redundant Full-File Disk Re-reads for Image EXIF Orientation Header Parsing | Medium | S, T, C | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O05-05 | Peak Memory Allocation Spike & CPU Blit from Post-Decode Pixel Matrix Rotation | Medium | M, C, J | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O05-06 | 10 Hz Whole-Card Recomposition During Voice Note Audio Playback | Medium | J, C, B | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O05-07 | Voice Note Waveform String Parsing & Boxing Allocation Churn per Conversation State Emission | Low-Medium | M, C | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O05-08 | Unbounded Storage Accumulation of Discarded Camera Captures and Voice M4A Cache Files | Medium | S | STATIC-ARITHMETIC | Android | S | Low | Survived | OPEN |
| OPT-O05-09 | Unchecked Multi-Iteration Decode Retry Spin on Corrupted or Unsupported Images | Low | C, S | STATIC-ARITHMETIC | Android | S | Low | Survived | OPEN |
| OPT-O05-10 | Pure-Java JCodec Software Video Frame Extraction and Color Space Conversion on Desktop | Medium | C, M, T | STATIC-ARITHMETIC | Desktop | S | Low | Survived | OPEN |
| OPT-O06-01 | Correlated Scalar Subquery & Full Index Scan in `observeUnreadCounts` (697 ms at 100k messages) | High | T, C, S, M, B | MEASURED | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O06-02 | Full Table Scan in `observeLatestPreviews` (573 ms at 100k messages) Re-executed on Every Message Write | High | T, C, S | MEASURED | Android, Desktop | M | Medium | Survived | OPEN |
| OPT-O06-03 | FTS5 Deferral Cliff: `searchMessages` LIKE '%x%' Full Table Scan Costs 88 ms (PC) / ~800 ms (Phone) at 100k Messages | High | T, C, S, B | MEASURED | Android, Desktop | M | Medium | Survived | OPEN |
| OPT-O06-04 | Missing PRAGMA Configuration on Desktop (`JvmFlashDatabaseOpener`) - Runs in Non-WAL Synchronous=FULL Mode | High | T, S, C, D | STATIC-ARITHMETIC | Desktop | S | Low | Survived | OPEN |
| OPT-O06-05 | Missing Indexes on 5 Top DAO Queries (`outbox.nextAttemptAt`, `hub_file.createdAtMs`, `conversations(pinned, sortOrder)`, etc.) | Medium | T, S, C | MEASURED | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O06-06 | Missing `journal_size_limit` and Unchecked Freelist Fragmentation on Client Databases | Medium | S | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O06-07 | Split-Transaction Write Amplification per Confirmed Chunk Batch (`markChunksDone` + `setBytesDone`) | Medium | S, C, T | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O06-08 | Heavy BLOB Inlining in `SwarmContentEntity` (131 KB manifest) and `OrgDirectoryMemberEntity` | Medium | M, S, C | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O06-09 | Ineffective Index in `OrgDirectoryDao.searchMembers` Due to Leading Wildcard `OR` (20k Full Table Scan) | Medium | T, C | MEASURED | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O06-10 | SQLCipher Default 256k PBKDF2 Iterations and Ineffective `mmap_size` on Android Startup | Low-Medium | T, C | STATIC-ARITHMETIC | Android | S | Low | Survived | OPEN |
| OPT-O08-01 | $O(P \cdot N \cdot W)$ Scheduler Complexity & Allocation Churn in PiecePicker Loop | High | C, T, M | STATIC-ARITHMETIC | Android, Desktop | M | Medium | Survived | OPEN |
| OPT-O08-02 | Redundant Full-Piece SHA-256 Re-Hashing on Disk Read During Swarm Serving | Medium | C, T | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O08-03 | Greed-Biased Serve Slot Reservation Causes Starvation of Slower Peers Under Saturated Slots | Medium | T, K | STATIC-ARITHMETIC | Android, Desktop | M | Medium | Survived | OPEN |
| OPT-O08-04 | Synchronous Per-Piece Disk fsync and SQLite DAO Update Amplification (4,096 fsyncs/Transactions per 4 GB) | High | S, T, C, B | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O08-05 | Unconditional 4 Hz (250 ms) Idle Ticker in SwarmDriver Wakes CPU 14,400 Times/Hour With Zero Active Transfers | Medium | B, C | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O08-06 | Swarm Subsystem Lacks Thermal Governor Integration Under SEVERE / CRITICAL Thermal Events | Medium | B, C | STATIC-ARITHMETIC | Android | S | Low | Survived | OPEN |
| OPT-O08-07 | $O(N^2)$ HAVE Invalidation Storm and Redundant Scheduler Re-execution in 20-Node Mesh | High | C, N, B, J | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O08-08 | Unbounded Tombstone Retention in Memory and Unpruned Summary Frame Broadcast Overhead | Low-Medium | M, N, C | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O07-01 | Triple Disk Read and Five SHA-256 Passes per Transfer Cause Massive I/O and CPU Bottlenecks | High | T, C, S, B | MEASURED | Android, Desktop | M | Medium | Survived | OPEN |
| OPT-O07-02 | Missing Staging File (.tmp) and Atomic Rename Strategy Causes Corrupt Destination Exposure and Antivirus Contention | Medium | S, T | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O07-03 | Relational Write Amplification: 5,120 SQLite Transactions Inserting 163k Rows per 10 GB Transfer | High | S, C, B, T | STATIC-ARITHMETIC | Android, Desktop | M | Medium | Survived | OPEN |
| OPT-O07-04 | Synchronous Lock-Bound Pipeline: Network Reader Thread Computes SHA-256 and Writes Disk Under PlatformLock | High | T, J, C | STATIC-ARITHMETIC | Android, Desktop | M | Medium | Survived | OPEN |
| OPT-O07-05 | JCA Provider Lookup and Okio Segment Buffering Overhead in Per-Chunk Sha256.digest | Medium | C, M, T | MEASURED | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O07-06 | Lack of In-Flight Chunk Retransmission Forces 15-Second Timeout and Full Restart on Single Corrupt Chunk | Medium | T, N | STATIC-ARITHMETIC | Android, Desktop | M | Medium | Survived | OPEN |
| OPT-O07-07 | High Profile 1 MB Chunk Plan Silently Clamped to 256 KB by Chunker Hard Bounds | Medium | T, C, N | MEASURED | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O07-08 | Unthrottled 100 Hz Transfer Repository StateFlow Mutations and List Copying | Medium | C, M, J | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O07-09 | Geometric Vector Resizing in Receiver Done-Set Reconciles via Boxed ArrayList<Int> Allocation | Low | M, C | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O07-10 | Redundant Double Encryption: Application-Level AES-256-GCM Over TLS 1.3 Transport | Medium | C, B, T | STATIC-ARITHMETIC | Android, Desktop | M | Medium | Survived | OPEN |
| OPT-O09-01 | BoundedSendQueue Bypassed: Unbuffered Blocking Socket Writes Under Global writeLock | High | T, J, C, B, N | STATIC-ARITHMETIC | Android, Desktop | M | Medium | Survived | OPEN |
| OPT-O09-02 | Missing TLS Session Resumption & Repeated SSLContext Recreation on Every Connection | High | T, C, B, N | STATIC-ARITHMETIC | Android, Desktop | M | Low | Survived | OPEN |
| OPT-O09-03 | Double JSON Tree Parsing & Intermediate AST Allocation in RelayFrameCodec | Medium | C, M, T | STATIC-ARITHMETIC | Android, Desktop, Server | S | Low | Survived | OPEN |
| OPT-O09-04 | Excessive Base64 Inflation and Triple String-Escape Roundtrips for E2E Encrypted Frames | High | N, M, C, T | STATIC-ARITHMETIC | Android, Desktop | M | Medium | Survived | OPEN |
| OPT-O09-05 | Allocation Churn & Immediate ByteArrayPool Copy Discard in WebSocketCodec | Medium | M, C, J | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O09-06 | Synchronous Thread.sleep(25) Busy-Poll in WsSession Inbound Channel Backpressure | Medium | C, B, T, J | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O09-07 | High Overhead of Text-Line Protocol Framing for Small Interactive Frames | Medium | N, B, C | STATIC-ARITHMETIC | Android, Desktop | M | Medium | Survived | OPEN |
| OPT-O09-08 | Unconditional UUID.randomUUID() Generation for Uncollected frameAcks Flow | Low-Medium | C, M, T | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O10-01 | Screen-Off Discovery Runaway: No Downscaling to ECO or Pause When Screen Off / App Backgrounded (~3,960 Wake-ups/hr & Permanent MulticastLock Drain) | High | B, C, N, T | STATIC-ARITHMETIC | Android | M | Medium | Survived | OPEN |
| OPT-O10-02 | Permanent Android MulticastLock Retention Across All API Levels Prevents Wi-Fi PHY Power Collapse (15–35 mA Battery Drain) | High | B, C, N | STATIC-ARITHMETIC | Android | M | Medium | Survived | OPEN |
| OPT-O10-03 | Subnet Sweep Redundant Binder IPC & Interface Enumeration Storm (253 IPC Cycles per /24 Sweep) | Medium | C, T, S | STATIC-ARITHMETIC | Android | S | Low | Survived | OPEN |
| OPT-O10-04 | Head-of-Line Blocking in Legacy NSD Resolve Queue Delays Peer Discovery by up to 6 Seconds per Stale Service | Medium | T, K, C | STATIC-ARITHMETIC | Android | S | Low | Survived | OPEN |
| OPT-O10-05 | Unsynchronized Multi-Component Polling Sweepers Waste 2,520 Wake-ups/hr in Idle State | Medium | B, C | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O10-06 | Dual Asynchronous Connectivity Change Triggers Cause Redundant Discovery Rebind & Announce Churn | Low-Medium | N, C, T | STATIC-ARITHMETIC | Android | S | Low | Survived | OPEN |
| OPT-O10-07 | Directed Broadcast UDP Packets on Wi-Fi Incur AP Basic-Rate Airtime Penalty | Low-Medium | N, B | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O12-01 | 50 Hz StateFlow Emission and Snapshot Allocation in Playout Loop | High | C, J, M, B | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O12-02 | High Allocation Churn in Per-Packet Framing and Audio Pipelines (~550 allocs/sec) | Medium | M, C, B | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O12-03 | Desktop TargetDataLine Cold-Open (~1.0s) Latency Without Pre-Warm Clips Speech and Blocks commandMutex | High | T, J | MEASURED | Desktop | S | Low | Survived | OPEN |
| OPT-O12-04 | Non-Adaptive 120ms Jitter Buffer and $O(\log N)$ TreeMap Queue Allocation | Medium | T, M, C | STATIC-ARITHMETIC | Android, Desktop | M | Low | Survived | OPEN |
| OPT-O12-05 | Missing Low-Latency Performance Mode and Audio Attributes on Android AudioTrack | Medium | T, J | STATIC-ARITHMETIC | Android | S | Low | Survived | OPEN |
| OPT-O12-06 | 278 kbps Uncompressed PCM16 Airtime Consumption Over Half-Duplex Wi-Fi Mesh | Medium | N, B, K | STATIC-ARITHMETIC | Android, Desktop | M | Medium | Survived | OPEN |
| OPT-O13-01 | Perpetual Engine-Lifetime Partial Wake Lock Held 24/7 Without Timeout Guard Prevents AP Suspend (15–35 mA Idle Drain) | High | B, C | STATIC-ARITHMETIC | Android | M | Medium | Survived | OPEN |
| OPT-O13-02 | Unconditional Headless Engine Spin-Up in FlashKeepaliveWorker Ignores Empty Outbox and Queue (Wastes 180s Awake/Hour) | High | B, C, N | STATIC-ARITHMETIC | Android | S | Low | Survived | OPEN |
| OPT-O13-03 | Missing `FlashTransferState.Verifying` in Android 15 `TimeoutStopPlan` Leaves Verification Jobs Hanging on FGS Timeout | Medium | T, S, C | STATIC-ARITHMETIC | Android | S | Low | Survived | OPEN |
| OPT-O13-04 | Transsion Hiber Tier 3 Silent AudioTrack Fails to Check Screen State, Draining Battery and Blocking Audio DSP Power Collapse | Medium | B, C | STATIC-ARITHMETIC | Android | S | Low-Medium | Survived | OPEN |
| OPT-O13-05 | Absence of `ACTION_SCREEN_OFF` Handling in Background Service Keeps Full-Rate Polling and High-Frequency Tickers Running in Pocket | High | B, C, N | STATIC-ARITHMETIC | Android | M | Medium | Survived | OPEN |
| OPT-O13-06 | Permanent Background Foreground Service Execution with `connectedDevice` While 0 Peers Connected Violates Google Play Policy | Medium | B, D | STATIC-ARITHMETIC | Android | M | Medium | Survived | OPEN |
| OPT-O13-07 | Lack of AudioFocus Loss Transience Recovery Backoff in TranssionHiberKeepAlive Causes Focus Fighting with Media Players | Low-Medium | B, C | STATIC-ARITHMETIC | Android | S | Low | Survived | OPEN |
| OPT-O13-08 | Polling `delay(300)` Busy-Wait Loop in `observeSessionActivity` During Background Service Startup | Low | C, B, T | STATIC-ARITHMETIC | Android | S | Low | Survived | OPEN |
| OPT-O11-01 | 1:1 Video Calls Unconditionally Capture and Encode 1080p30 in Software VP8 on Desktop / HIGH Profile | High | C, T, B | MEASURED | Desktop, Android | S | Low | Survived | OPEN |
| OPT-O11-02 | Desktop Camera Capturer Continues Running at 30 FPS When Camera is Toggled Off | Medium | C, B, M | STATIC-ARITHMETIC | Desktop | S | Low | Survived | OPEN |
| OPT-O11-03 | Unconditional VP8 SDP Enforcement Strips Hardware MediaCodec on Android-to-Android Calls | High | C, B, T | STATIC-ARITHMETIC | Android | M | Medium | Survived | OPEN |
| OPT-O11-04 | High-Frequency $O(N)$ Stats Allocation Churn & Redundant JNI Polling in Mesh Group Calls | Medium | C, M, J | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O11-05 | Dead SDP Video Bitrate Tuning Stripped by Downstream VP8 Sanitizer | Low | C, M | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O11-06 | Desktop Video Surface Converts and Rasterizes Occluded Remote Frames During Data Saver / Remote Mute | Medium | C, M, J | STATIC-ARITHMETIC | Desktop | S | Low | Survived | OPEN |
| OPT-O11-07 | Uncapped Multi-Stream 1080p Software VP8 Encoding in Screen Share to 4 Watchers | Medium | C, B, N | STATIC-ARITHMETIC | Desktop | M | Medium | Survived | OPEN |

| OPT-O14-01 | Synchronous Monitor writeLock Across Socket I/O Forces Leaked async Jobs on Timeout | High | C, M, T | STATIC-ARITHMETIC | Android, Desktop | S | Medium | Survived | OPEN |
| OPT-O14-02 | Synchronous Thread.sleep(25) Busy-Poll in WsSession Inbound Backpressure | Medium | C, B, T, J | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O14-03 | Global drainMutex Serializes All Outbound Deliveries Across All Connected Peers | High | T, K | STATIC-ARITHMETIC | Android, Desktop | M | Medium | Survived | OPEN |
| OPT-O14-04 | SharingStarted.Eagerly in Core Repositories Keeps Inactive Room Query Observers Alive | Medium | M, C, S, B | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O14-05 | StateFlow Structural Equality Check Iterates Large Collections on Every State Mutation | Medium | C, J | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O14-06 | Desktop Client Spawns 30+ Threads at Idle via Fragmented Dispatcher Pools | Low-Medium | M, C | STATIC-ARITHMETIC | Desktop | M | Low | Survived | OPEN |
| OPT-O15-01 | Redundant ASN.1 SPKI Parsing in ecP256Verify Doubles Signature Verification Cost | High | C, T | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O15-02 | Repeated SecureRandom() Instantiation Triggers Entropy Pool Contention | Medium | C, T, B | STATIC-ARITHMETIC | Android | S | Low | Survived | OPEN |
| OPT-O15-03 | Cipher.getInstance("AES/GCM/NoPadding") Provider Lookup on Every Frame | Medium | C, M | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O15-04 | okio HashingSink Wrapping in Sha256.digest Allocates 3 Objects per Chunk | Low-Medium | M, C | MEASURED | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O16-01 | Sequential Stop-and-Wait Chunk Pipeline in Sealed Hub File Transfers | High | T, N | STATIC-ARITHMETIC | Android, Desktop, Server | M | Medium | Survived | OPEN |
| OPT-O16-02 | Multi-Page Directory Sync Accumulates All 20,000 Members in Heap Before Commit | High | M, K, T | STATIC-ARITHMETIC | Android, Desktop | M | Medium | Survived | OPEN |
| OPT-O16-03 | Redundant 30s HTTP Presence Polling Over Cellular While WebSocket Relay is Active | Medium | N, B, C | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O16-04 | Unbatched FLASH_RECV_ACK WebSocket Frames During Mailbox Queue Drain | Medium | N, C, S | STATIC-ARITHMETIC | Android, Desktop, Server | M | Medium | Survived | OPEN |
| OPT-O17-01 | Server Capacity Cliff: 5,000 Connections Exceed Default JVM Heap Allocation | High | K, M | STATIC-ARITHMETIC | Server | S | Low | Survived | OPEN |
| OPT-O17-02 | writeChunkFile Runs Inside db.withTransaction Blocking Server SQLite Write Connection | High | T, K, S | STATIC-ARITHMETIC | Server | S | Low | Survived | OPEN |
| OPT-O17-03 | Redundant JSON Serialization and Coroutine Spawning in handleGroupSend Fan-Out | Medium | C, M | STATIC-ARITHMETIC | Server | S | Low | Survived | OPEN |
| OPT-O17-04 | Missing -XX:+ExitOnOutOfMemoryError in Deploy Scripts Creates Zombie Processes | Medium | K, D | STATIC-ARITHMETIC | Server, Build | S | Low | Survived | OPEN |
| OPT-O18-01 | Uncompressed Static Assets: Missing Gzip/Deflate on Hub Server | Medium | T, N | STATIC-ARITHMETIC | Server, Console | S | Low | Survived | OPEN |
| OPT-O18-02 | 35+ Sequential HTTP Requests in Native ES Module Import Waterfall | Medium | T, N | STATIC-ARITHMETIC | Console | S | Low | Survived | OPEN |
| OPT-O18-03 | Eager Multi-Page Preloading of 20,000 Devices into Browser Heap | Low-Medium | M, T, K | STATIC-ARITHMETIC | Console | S | Low | Survived | OPEN |
| OPT-O19-01 | AWT DropTarget Recursive Registration Teardown on Every Navigation and Header State Change | High | J, C, T | STATIC-ARITHMETIC | Desktop | S | Low | Survived | OPEN |
| OPT-O19-02 | DesktopTransferToastWindow Observes High-Frequency activeTransfers StateFlow Causing 100 Hz Compose Wakes at Idle/Background | High | C, J, B, M | STATIC-ARITHMETIC | Desktop | S | Low | Survived | OPEN |
| OPT-O19-03 | Unconstrained Window Width Container Read in rememberFlashDesktopWindowSize Triggers Continuous Full-Shell Recompositions on Window Resize | Medium | J, C | STATIC-ARITHMETIC | Desktop | S | Low | Survived | OPEN |
| OPT-O19-04 | Sub-Optimal JVM Runtime Flags and Missing Frame-Budget G1 Pause Target (-XX:MaxGCPauseMillis) in Desktop Application | Medium | J, M, C, K | STATIC-ARITHMETIC | Desktop, Build | S | Low | Survived | OPEN |
| OPT-O19-05 | Missing AppCDS and Unstripped Native Binaries in Desktop jpackage Distribution Inflate Cold Start and Installer Size | Medium | T, Z, M | STATIC-ARITHMETIC | Desktop, Build | M | Low | Survived | OPEN |
| OPT-O19-06 | Synchronous Disk I/O, DPAPI Key Unwrapping, and Cryptographic Key Generation in DesktopEngine Main-Thread Constructor Blocks First Frame | Medium | T, S, C | STATIC-ARITHMETIC | Desktop | S | Low | Survived | OPEN |
| OPT-O19-07 | Lack of Idle Discovery Downscaling to ECO Mode When Window is Minimized or Hidden in Tray | Medium | B, C, N | STATIC-ARITHMETIC | Desktop | S | Low | Survived | OPEN |
| OPT-O20-01 | Eager String Interpolation and Lack of Level-Gated Filtering in FlashLog | High | C, M, T | STATIC-ARITHMETIC | Android, Desktop, Shared | S | Low | Survived | OPEN |
| OPT-O20-02 | Synchronous Triple Regex Redaction and Date Formatting on Calling Threads in RotatingFileLogSink | Medium-High | C, T, M | STATIC-ARITHMETIC | Android | S | Low | Survived | OPEN |
| OPT-O20-03 | Desktop Synchronous Unbuffered Disk Writes and Global Console Lock Contention in DesktopMain | Medium-High | T, C, S | STATIC-ARITHMETIC | Desktop | S | Low | Survived | OPEN |
| OPT-O20-04 | FlashProbe Vararg Pair Allocation Churn and Repeated Value Sanitization | Low-Medium | M, C | STATIC-ARITHMETIC | Shared | S | Low | Survived | OPEN |
| OPT-O20-05 | Absence of R8 / Proguard Log Stripping Rules Leaves All Internal Logs in Release Builds | Medium | Z, C, M, B | STATIC-ARITHMETIC | Android, Build | S | Low | Survived | OPEN |
| OPT-O20-06 | Uncompressed 5 MB Plaintext Diagnostic Log Export Duplicates Storage and Inflates Share Payloads | Low-Medium | S, N, M | STATIC-ARITHMETIC | Android | S | Low | Survived | OPEN |
| OPT-O21-01 | org.gradle.parallel=true Commented Out Across 25 Decoupled Subprojects | High | T, C, D | STATIC-ARITHMETIC | Build | S | Low | Survived | OPEN |
| OPT-O21-02 | Gradle Build Cache Disabled Monorepo-Wide via Missing org.gradle.caching=true | High | T, C, S | STATIC-ARITHMETIC | Build | S | Low | Survived | OPEN |
| OPT-O21-03 | Suboptimal Daemon Memory Ceiling (2 GB Heap) and Lack of Modern GC on 20 GB Host | Medium | T, M, C | STATIC-ARITHMETIC | Build | S | Low | Survived | OPEN |
| OPT-O21-04 | Single-Worker Sequential Execution in :server:test (238s Wall-Clock Baseline) | High | T, C, D | MEASURED | Server, Build | S | Low-Medium | Survived | OPEN |
| OPT-O21-05 | Hub Server Test Suite (:server:test) Completely Skipped in GitHub Actions CI | High | D, T | STATIC-ARITHMETIC | Build, Server | S | Low | Survived | OPEN |
| OPT-O21-06 | CI JDK 17 / Java 21 Toolchain Mismatch and Ephemeral --no-daemon Overhead | Medium | T, N, C | STATIC-ARITHMETIC | Build | S | Low | Survived | OPEN |
| OPT-O21-07 | Dynamic Version Range in BouncyCastle Transitive Dependency Causes 24h Configuration Cache Invalidation | Low-Medium | T, N | MEASURED | Build | S | Low | Survived | OPEN |
| OPT-O21-08 | ThirdPartyNotices and AboutLibraries Run Unconditionally on Every Local Debug Build | Medium | T, C, S | STATIC-ARITHMETIC | Android, Build | S | Low | Survived | OPEN |
| OPT-O21-09 | Monolithic buildSrc Invalidates Entire Monorepo Script Classpath for a Single Task | Low-Medium | T, D | STATIC-ARITHMETIC | Build | S | Low | Survived | OPEN |
| OPT-O21-10 | Redundant Room KSP Compiler Invocation in :app Module | Low-Medium | T, C, M | STATIC-ARITHMETIC | Android, Build | S | Low | Survived | OPEN |
| OPT-O22-01 | Monolithic Fat APK Without ABI Splits Bundles 4x Native Libraries Across Obsolete ABIs (54.6 MB vs 17.6 MB) | High | Z, N, S | MEASURED | Android, Build | S | Low | Survived | OPEN |
| OPT-O22-02 | sqlite-jdbc Desktop JAR Bundles 24 Multiplatform Native Binaries Inflating Installer Payload by 16.4 MB | High | Z, S, N | MEASURED | Desktop, Build | S | Low | Survived | OPEN |
| OPT-O22-03 | BouncyCastle Cryptographic Provider Suite (10.3 MB) Shipped for Single Self-Signed TLS Certificate Generator | High | Z, S, T, M | MEASURED | Desktop, Build | M | Medium | Survived | OPEN |
| OPT-O22-04 | Dead JRE Modules (java.naming, java.instrument, jdk.crypto.cryptoki, java.management) in jpackage nativeDistributions | Medium | Z, S | MEASURED | Desktop, Build | S | Low | Survived | OPEN |
| OPT-O22-05 | Unused Material 2 Runtime (1.7 MB) Dragged into Desktop Distribution via compose.desktop.currentOs Umbrella | Medium | Z, S | MEASURED | Desktop, Build | S | Low | Survived | OPEN |
| OPT-O22-06 | Blunt -keep class org.webrtc.** { *; } Rule Inhibits R8 Dead-Code Stripping in Android Calling Module | Medium | Z, C, M | STATIC-ARITHMETIC | Android, Build | M | Medium | Survived | OPEN |
| OPT-O22-07 | Parked Dead Dependencies in :ui:chat androidMain Shipped in Published AAR / Classpath | Low-Medium | Z, D, T | MEASURED | Android, Build | S | Low | Survived | OPEN |
| OPT-O23-01 | Synchronous File Open/Close and Per-Chunk Hardware fsync in HubFileDisk Causes Catastrophic Write Amplification and Latency | High | S, T, C, B | STATIC-ARITHMETIC | Android, Desktop | S | Medium | Survived | OPEN |
| OPT-O23-02 | MultiStreamDispatcher Re-reads and Hashes Entire File Sequentially on Resume Due to Non-Seekable Stream Pipeline | High | S, T, C, B | STATIC-ARITHMETIC | Android, Desktop | M | Medium | Survived | OPEN |
| OPT-O23-03 | Missing Disk Space Pre-allocation (fallocate/setLength) and FAT32 4 GB Boundary Validation Exposes Transfers to Mid-Flight Disk Exhaustion and Fragmentation | High | S, T, B | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O23-04 | Orphaned Storage Accumulation: Unpurged Hub File Binaries, Discarded Partial Transfers, Voice Notes, and Camera Captures Permanently Bloat Disk | Medium-High | S, B | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O23-05 | Lack of auto_vacuum and Freelist Trimming on Client SQLite Databases Causes Permanent File Size Bloat | Medium | S, C | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O23-06 | Unbuffered 8 KB Stream Copy in saveToGallery Duplicates Multi-Gigabyte Media Files via 250,000 Syscalls | Medium | S, T, C | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O23-07 | Java NIO Heap Buffer Wrapping in AndroidPieceStorage and JvmPieceStorage Forces Redundant Direct Buffer Memory Copies | Medium | C, M, S | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O24-01 | Missing Optimistic Local Echo on Message Send (Bubble Delayed Behind SQLite Transaction & Full-History Query) | High | T, J, S | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O24-02 | Staged Attachment Vanishes Without Visual Trace Before Synchronous sendFile Completes | High | T, J, S | STATIC-ARITHMETIC | Android, Desktop | M | Medium | Survived | OPEN |
| OPT-O24-03 | Keyboard Layout Resize Jitter via animateContentSize on IME-Padded Column | Medium-High | J, C, T | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O24-04 | Lack of Auto-Scroll to Bottom on Software Keyboard Appearance | Medium | T, J | STATIC-ARITHMETIC | Android | S | Low | Survived | OPEN |
| OPT-O24-05 | Setting Toggle Freezes on Tap Due to Synchronous Preferences DataStore Disk Write | Medium | T, J, S | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O24-06 | Eager Network Fetching and Un-Guarded Infinite Transitions During Sheet Entrance Animations | Medium | J, C, N | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O24-07 | Asynchronous Decoded Ratio Layout Shift (CLS) on Single Image Attachments in LazyColumn | Medium | J, T | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O24-08 | Desktop Application Completely Ignores OS-Level Reduce-Motion Preferences | Low-Medium | D, B, C | STATIC-ARITHMETIC | Desktop | S | Low | Survived | OPEN |
| OPT-O26-01 | Semi-Blocking 250 ms Read Loop in KissTncDriver Generates 14,400 Idle Wake-ups/Hour and Prevents OS Power Collapse | High | B, C, T | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O26-02 | Extreme Channel Occupancy & Sub-1% Goodput of 1200 Baud AFSK Link Under Multi-Segment Payloads and Half-Duplex Pacing | High | N, T, B, K | STATIC-ARITHMETIC | Android, Desktop | M | Medium | Survived | OPEN |
| OPT-O26-03 | Unbounded Memory Leaks in RadioChatEngine pendingAcks and UNLIMITED Channel Allocations | Medium | M, K | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O26-04 | High Buffer Allocation Churn in Radio Framing, AAD Derivation, and Serial Write Paths | Medium | M, C | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O26-05 | Synchronous Thread.sleep() Busy-Wait (up to 4,000 ms) Blocking Android Dispatchers.IO During Radio Connection Probing | Medium | T, C, B | STATIC-ARITHMETIC | Android | S | Low | Survived | OPEN |
| OPT-O26-06 | Inability to Transmit Real-Time Audio or Voice Clips Over 1200 Baud Radio Data Channel (Missing Architecture / Dead VOICE_CLIP Enum) | Low-Medium | N, T, K, D | STATIC-ARITHMETIC | Shared | S | Low | Survived | OPEN |
| OPT-O26-07 | Uncached JCA Provider Lookups and SPKI ASN.1 Re-parsing in JdkRadioCrypto and JdkRadioSignatureScheme | Low-Medium | C, M, T | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O25-01 | $O(N \cdot M)$ Linear Search Over List in MultiStreamDispatcher.pendingIndexes Initialization on Transfer Resume | High | T, C, M | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O25-02 | $O(D \cdot M)$ Full Directory Scan and Repetitive JSON Encoding in Hub Directory Sync & Department Routes | High | T, C, K, M, S | STATIC-ARITHMETIC | Server | S | Low | Survived | OPEN |
| OPT-O25-03 | Uncached Regex Compilations and Redundant NFKD Normalization in foldForSearch and Chat Header/Initials | High | C, M, J, T | STATIC-ARITHMETIC | Android, Desktop, Shared | S | Low | Survived | OPEN |
| OPT-O25-04 | High-Frequency Regex Compilation and Group Parsing in decodeReactorIds on Every Conversation State Update | Medium-High | C, M, J | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O25-05 | Unmemoized $O(N \log N)$ Sorting and Distinct Filtering in FlashNearbyScreen and FlashGroupVideoGrid Lazy Layouts | Medium | J, C, M | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O25-06 | Linear Scanning Over Unindexed memberDeviceIds: List<String> in Server Group Visibility and Membership Checks | High | T, C, K | STATIC-ARITHMETIC | Server | S | Low | Survived | OPEN |
| OPT-O25-07 | Unpooled Full-Chunk ByteArray Allocations and Redundant Sorting in ChunkFrame.serialize | Medium-High | M, C, T | STATIC-ARITHMETIC | Android, Desktop, Shared | M | Low | Survived | OPEN |
| OPT-O25-08 | Discarded $O(P \cdot N)$ Holder Computation in SwarmEngine.updateWaitReason and Redundant Piece Iterations | Medium | C, B, T | STATIC-ARITHMETIC | Android, Desktop, Shared | S | Low | Survived | OPEN |
| OPT-O25-09 | Default Collection Sizing in High-Throughput Directory Sync and Mailbox Drain Loops | Low-Medium | M, C | STATIC-ARITHMETIC | Server, Shared | S | Low | Survived | OPEN |
| OPT-O25-10 | N+1 Sequential Database Queries Inside Transaction During Delegation Revocation Cascade | High | T, S, K | STATIC-ARITHMETIC | Server | S | Low | Survived | OPEN |
| OPT-O28-01 | Complete Absence of AndroidX Macrobenchmark, Microbenchmark, and Jetpack Tracing Harnesses | High | T, J, D | STATIC-ARITHMETIC | Android, Desktop | M | Low | Survived | OPEN |
| OPT-O28-02 | Transfer Pipeline Lacks In-Flight Chunk Flight Telemetry and Backpressure Stall Duration Instrumentation | High | T, N, D | STATIC-ARITHMETIC | Shared | S | Low | Survived | OPEN |
| OPT-O28-03 | WebRTC / Calling Telemetry Missing Jitter Buffer Delay, Playout Sync Drift, and Packet Concealment Structured Probes | Medium-High | T, J, D | STATIC-ARITHMETIC | Shared | S | Low | Survived | OPEN |
| OPT-O28-04 | Swarm Piece Scheduler Operates Without Piece Contention, Duplicate Race Waste, or Scheduler Stall Telemetry | Medium | N, T, K, D | STATIC-ARITHMETIC | Shared | S | Low | Survived | OPEN |
| OPT-O28-05 | Room and SQLite Lack Query Execution Callbacks, Slow-Query Logging, and Write-Transaction Hold-Time Metrics | Medium-High | T, S, J, D | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| OPT-O27-01 | Single-Worker Test Execution (maxParallelForks = 1) Monorepo-Wide Wastes Multi-Core Host & CI Hardware | High | T, C, D | MEASURED | Build, Server, Shared | S | Low-Medium | Survived | OPEN |
| OPT-O27-02 | Real Wall-Clock Delays (Thread.sleep and delay()) Instead of kotlinx-coroutines-test Virtual Time Advances | High | T, C, D | MEASURED | Shared, Android, Desktop, Server | M | Low | Survived | OPEN |
| OPT-O27-03 | Real OS Socket, Netty Engine, and Ktor Server Lifecycles in Unit and Contract Test Suites | High | T, N, C | MEASURED | Server, Shared | M | Low-Medium | Survived | OPEN |
| OPT-O27-04 | Ephemeral Disk I/O & SQLite WAL Files in Unit Tests Instead of Shared-Memory In-Memory SQLite | High | S, T, C | MEASURED | Server, Shared | S | Low | Survived | OPEN |
| OPT-O27-05 | Flaky Test Race Conditions from Real-Time Polling Loops & Wall-Clock Deadlines Under Suite Load | High | T, D | MEASURED | Server, Shared | S | Low | Survived | OPEN |
| OPT-O27-06 | Heavyweight Cryptographic Operations & In-Test Benchmark Bloat in Standard Test Sweeps | Medium-High | T, C | MEASURED | Core, Server, Shared | S | Low | Survived | OPEN |







