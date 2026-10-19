# APEX ZENITH — Flash Hub UI/UX Competition Design Document

## 1. Overview & Vision

**Apex Zenith** (`com.transfer.flash.ui.hub.competition.apex`) is the flagship multiplatform Hub suite designed for the Flash-dev repository. It translates the enterprise and peer-to-peer security architecture of Flash into an intuitive, visually stunning, high-craft user experience.

The design is built on three core pillars:
1. **Cryptographic Honesty & Transparency**: Cryptographic states (E2E content sealing via BLAKE3 + Ed25519, certificate pinning, CRL revocation status, zero-plaintext server promises per ADR-138) are rendered as first-class UI surfaces rather than hidden debug logs.
2. **Flash Design Token Fidelity**: 100% adherence to `FlashTheme.colors`, `FlashTheme.typography`, `FlashSpacing`, `FlashShapes`, and `FlashIcons`. Typography is strictly rendered through `FlashText`; tactile feedback utilizes `flashPressScale(interactionSource)` without Material ripple conflicts.
3. **Adaptive Performance & Motion**: Layouts adapt responsively between Phone (412dp viewport) and Desktop Split-Pane (1180dp viewport). All animations dynamically respect `FlashTheme.motion.reduceMotion` and `FlashTheme.minimalChrome`.

---

## 2. Package & Architecture Isolation

In strict accordance with the competition sandbox rules:
- **Zero Production Modifications**: Shipped UI (`MainActivity.kt`, `HubUiPrefs.kt`, `DesktopShell.kt`) is untouched.
- **Zero Core Logic Changes**: No modifications made to `:core:enterprise`, `:core:messaging`, `:core:hub-protocol`, `:core:persistence`, or `:core:network`.
- **Package Location**: `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/hub/competition/apex/`

### Package Files
- `ApexTokens.kt`: Design tokens, dimensions, radii, semantic tones (`ApexTone`), formatting helpers (`apexFormatAgo`, `apexFormatBytes`, `apexFormatHex`, `apexMono`).
- `ApexModels.kt`: Clean, immutable presentation models representing the entire Hub domain and judge harness.
- `ApexMockWorld.kt`: Rich, multi-scenario data generator modeling realistic organizations, colleagues, certificates, rosters, announcements, and packet traces.
- `ApexComponents.kt`: Reusable high-craft visual primitives (`ApexCard`, `ApexTile`, `ApexStatusDot`, `ApexBadge`, `ApexButton`, `ApexSecondaryButton`, `ApexIconButton`, `ApexSearchBar`, `ApexFilterChip`, `ApexCodeBox`, `ApexEmptyState`).
- `ApexPageCommandCenter.kt` (Page 1): Hub status hero, live telemetry meters, sealing engine dashboard, actionable alert banners.
- `ApexPageDirectory.kt` (Page 2): Colleague and department directory, search, sync watermarks, interactive Peer Resolution Drawer.
- `ApexPageAnnouncements.kt` (Page 3): Broadcast feed, pinned banners, read/unread states, draft broadcast composer modal.
- `ApexPageTrustVault.kt` (Page 4): Certificate chain inspector, hardware keystore attestation, step-up role escalation, enterprise enrollment onboarding.
- `ApexPageRostersBridge.kt` (Page 5): Verified group rosters, cryptographic statement inspector, relay port monitor, packet traces table.
- `ApexShowcase.kt`: `HubCompetitionShowcaseScreen` with responsive mobile/desktop frames and floating Judge's Control Bar.
- `ApexPreviews.kt`: Compose Multiplatform previews for Android and Desktop.

---

## 3. Detailed Page Breakdown

### Page 1: Hub Command Center (Overview & Live Telemetry)
- **Hero Status Header**: Visualizes `HubStatus.kt`, `HubSession.kt`, and `RelaySessionState.kt` with live connection states (`Connected`, `Authenticating`, `Relay Fallback`, `Reconnecting`, `Offline`, `Revoked`).
- **Cryptographic Sealing Engine**: Visualizes `HubSealingKeyManager.kt`, `DirectoryHubSealing.kt`, and `HubSealingPins.kt`, highlighting BLAKE3 derivation, active key pins (3/3 verified), and key rotation windows.
- **Relay Telemetry Grid**: 4 dense metric tiles tracking Latency (ms), Outbox Buffer Queue, Active Sockets, and Packet Health (%).
- **Actionable System Banners**: Modeled on `HubNotice.kt` and `HubTabModels.kt` with tone-aware washes and direct remediation actions.

### Page 2: Organization Directory & Peer Explorer
- **Search & Filters**: Instant search bar with query clear button; horizontal carousel for department filtering; filter pills for LAN Direct, Online, and Org Verified.
- **Sync Watermark**: Real-time snapshot sequence number, freshness timestamp, and snapshot hash indicator.
- **Colleague List**: Rich colleague rows with role badges (Head, Admin, Member), online presence beacon, reachability tags, and cryptographic verification badges.
- **Peer Profile / Resolution Drawer**: Detailed side drawer visualizing `OrgPeerResolver.kt` reachability endpoints, `DirectoryHubSealing.kt` public key fingerprints with 1-tap copy, and `DirectoryPrivacyClient.kt` visibility controls.

### Page 3: Hub Announcements & Broadcast Feed
- **Broadcast Feed**: High-craft feed modeled on `HubAnnouncementModels.kt` and `HubAnnouncementsApi.kt`.
- **Pinned Highlights**: Prominent pinned broadcast card with accent tone wash and expiry tracking.
- **Read/Unread Interactions**: 1-tap read status toggling, automatic read marking on card expansion, and unread badge counters.
- **Draft Broadcast Modal**: Complete composer allowing authorized personnel (Admins / Department Heads) to preview, title, body, and pin announcements.

### Page 4: Enterprise Enrollment, Certificates & Trust Vault
- **Unenrolled Onboarding**: Sleek invite token redemption screen (`APEX-INV-9941-X7K2`), explaining the 4 cryptographic verification steps.
- **Certificate Hierarchy Tree**: Interactive multi-level chain (`Root CA` → `Hub Delegation` → `Member Statement` → `Device Hardware Keystore Key`).
- **Certificate Details Inspector**: Inspects serial number, issuer, validity period, and copyable public key fingerprint for each node.
- **Step-Up Escalation Simulator**: Interactive hardware challenge simulator for elevating roles (`HubRoles.kt`, `HubStepUp.kt`).
- **Revocation Inspector**: Displays CRL sequence status and active security policies (`RevocationList.kt`).

### Page 5: Verified Group Rosters & Relay Bridge
- **Enterprise Group Rosters**: Managed rosters (`HubGroupRosters.kt`, `GroupRosterStatement.kt`) with member/online counts, approval policies, and admin lock indicators.
- **Cryptographic Statement Expansion**: Expandable view showing roster statement version, signature validity, and zero-plaintext routing guarantees.
- **Hub Relay Port Telemetry**: Port 9443 status, TLS socket streams, and outbox buffer monitoring.
- **Live Relay Packet Traces**: Real-time log table showing trace ID, recipient device, payload type (`ROSTER_SYNC_V2`, `CHAT_STREAM_PACKET`), size in bytes, latency in ms, and delivery status (`DELIVERED`, `IN_FLIGHT`, `BUFFERED`).

---

## 4. The Interactive Judge's Control Bar

The floating control bar (`HubCompetitionShowcaseScreen`) docks smoothly at the bottom of the screen and provides 1-tap live toggling:
1. **Hub Scenarios**:
   - `Active & Sealed`: Full enterprise connection, 100% green telemetry, valid certificates, fresh directory.
   - `Relay Degraded`: Intermittent hub link, relay fallback active with 19 queued packets and 485ms latency.
   - `Enrolling`: Device key agreement in progress with active invitation token validation.
   - `Unenrolled`: Fresh slate with enterprise invite redemption onboarding flow.
   - `Cert Revoked`: Security alert state with revoked certificate node, terminated sessions, and CRL #49102 details.
   - `Empty / Offline`: Unreachable hub with zero cached records and diagnostic retry triggers.
2. **Form Factors**:
   - `Phone (412dp)`: Authentic mobile viewport with rounded device corners and bottom navigation bar.
   - `Desktop Split (1180dp)`: Multi-pane desktop layout with navigation rail, central content pane, and side telemetry summary.
3. **Themes**:
   - `Pulse Dark` vs. `Pulse Light`.
4. **Motion Modes**:
   - `60fps Spring` (full fluid physics) vs. `Reduced` (instant flat snapping for power/accessibility).

---

## 5. Verification & Build Confirmation

- **Kotlin JVM Target**: `:ui:chat:compileKotlinJvm` — `BUILD SUCCESSFUL` (0 errors, 0 warnings).
- **Multiplatform Metadata Target**: `:ui:chat:compileKotlinMetadata` — `BUILD SUCCESSFUL`.
- **Test Suite**: `:ui:chat:jvmTest` — All 46 tasks passing with 100% success rate.
