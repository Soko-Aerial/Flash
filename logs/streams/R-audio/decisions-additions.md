# Architectural Decision Additions — Stream R-audio

Reserved ADR range: ADR-210 through ADR-214.  
Status of all entries: **PROPOSED (Awaiting Owner Review)**.

---

## ADR-210 — Recommendation to Drop Modes 3 and 5 (Walkie-Talkie Bridge) from Plan R

### Status
PROPOSED (Stream R-audio recommendation following Phase R4 / BT-R10 spike)

### Context
`docs/plans/RADIO-GATEWAY-PLAN.md` §5 and §9, parent plan ADR-173, and Phase R4 define **Mode 3 (Walkie-Talkie Voice Bridge)** and **Mode 5 (All-in-One Dual Mode)**. The intention was to cross-patch Flash Push-to-Talk (PTT) voice sessions (ADR-032) to the Vero Telecom VR-N76 / BTECH UV-Pro handheld radio via Bluetooth Hands-Free Profile (HFP), allowing Flash users to converse directly with conventional analog VHF/UHF FM walkie-talkies.

Hardware spike `BT-00` (`EXP-023`) proved data communication (1200-baud Bell 202 AFSK KISS TNC over Bluetooth SPP) is reliable, but noted that attempting to connect the radio via the Windows Bluetooth audio settings UI failed with "failed because no call was active".

Spike `BT-R10` was tasked with answering the four core feasibility questions of Plan R §9:
1. Bluetooth Hands-Free profile roles and connection behavior.
2. Transmitter RF keying mechanism from Flash.
3. Voice channel audio quality and inbound discriminator audio routing.
4. Fit with Flash's push-to-talk turn-taking architecture.

### Findings & Evidence Levels

1. **Bluetooth Profile Role Collision & Windows WASAPI Model:**
   - *Evidence Level: Verified (Bluetooth SIG HFP Spec v1.8, Windows Core Audio Docs, EXP-023).*
   - Bluetooth HFP mandates strictly one Audio Gateway (AG) and one Hands-Free unit (HF). Smartphones and Windows PCs operate natively in the AG role.
   - When paired to a PC or phone, the VR-N76 advertises UUID `0000111F-0000-1000-8000-00805F9B34FB` (HandsfreeAudioGateway). If the radio operates as an AG (designed to link to wireless headsets), it cannot form an HFP link with an Android phone or PC AG.
   - Even when Windows recognizes the radio as a Hands-Free audio endpoint (`BTHHFENUM`), Windows 10/11 treats HFP audio as an on-demand telephony bearer. The SCO voice channel is strictly closed while idle (endpoint state `DEVICE_STATE_UNPLUGGED` 0x8), and Windows refuses connection until an application actively opens a communications/VoIP stream (`eCommunications`).

2. **Absence of Standard Software PTT Keying:**
   - *Evidence Level: Verified (Bluetooth SIG HFP Spec) & Reported (VR-N76 Manual).*
   - Bluetooth HFP contains zero commands for Push-to-Talk RF keying (it provides only telephony call control: answer `ATA`, hang up `AT+CHUP`, volume `+VGM`/`+VGS`).
   - The only hands-free transmission mechanism supported by the radio firmware is **Bluetooth VOX** (Voice-Operated Exchange, added in UV-Pro firmware v0.8.0).
   - VOX is inherently flawed for professional communications: it incurs a 100–300 ms attack delay (clipping initial speech syllables), produces squelch tail unkey delays (broadcasting dead air after speech ceases), and suffers from false triggers due to acoustic pops or background noise.

3. **Narrowband Degradation & Asymmetric Audio Routing:**
   - *Evidence Level: Verified (HFP Audio Spec) & UNPROVEN on hardware.*
   - Bluetooth SCO audio is restricted to 8 kHz CVSD mono (300 Hz – 3.4 kHz telephone bandwidth), severely degrading Flash's 16 kHz HD PTT audio stream.
   - Furthermore, it is UNPROVEN whether incoming analog RF received by the radio's discriminator is routed into the Bluetooth SCO uplink (acting as a microphone stream to the phone), or whether it only routes out of the radio's internal loudspeaker.

4. **Severe Architectural Incompatibility with Flash PTT Turn-Taking:**
   - *Evidence Level: Verified (Flash PTT Architecture, ADR-032).*
   - Flash PTT (`FlashPtt`, `PttFloorMachine`) relies on strict, distributed, cryptographic half-duplex floor control: a station requests the floor (`Start`), other stations lock into `Listening`, and audio is streamed over encrypted WebSockets to authenticated peers with total-order tie-breaking.
   - Analog VHF/UHF radio is uncoordinated, unauthenticated, and unencrypted simplex RF: any external station can transmit at any time without a digital floor token.
   - Bridging the two environments creates irreconcilable hazards:
     a. **RF Collisions ("Doubling"):** If a Flash peer speaks while an external analog station is transmitting, Flash blindly keys the radio via VOX, transmitting over active emergency or tactical radio traffic.
     b. **Squelch Tail Glare:** The radio's squelch tail and Bluetooth unkey latency hold the audio channel busy for 300–500 ms after speech stops, causing rapid turn-taking replies in Flash to be dropped or clipped.
     c. **Acoustic Feedback Loops:** Sidetone from the radio speaker picked up by local microphones can re-trigger VOX, creating continuous transmitter lockup.
     d. **Security Compromise:** Flash's primary value proposition is end-to-end encrypted, verified communication (Profile M). Bridging Flash PTT to analog FM broadcasts private team voice communications as cleartext analog audio over the airwaves, completely violating Profile M security requirements.

### Decision / Recommendation
**DROP Modes 3 (Walkie-Talkie Bridge) and 5 (All-in-One Dual Mode) from Plan R.**  
Focus Flash's hardware radio development strictly on digital packet data over the KISS TNC:
- **Mode 1: Field Companion** (End-to-end encrypted Profile M text chat & cryptographic receipts over 1200-baud AX.25).
- **Mode 2: Base Station Relay** (LAN/Wi-Fi to radio mesh message forwarding).
- **Mode 4: Tactical GPS Radar** (Offline encrypted position reports).

### Revisit When
Revisit only if future commercial transceivers provide:
1. Native digital audio over Bluetooth SPP / BLE ISO streams (e.g. digital DMR/P25 voice packets instead of analog FM audio).
2. Hardware carrier-sense / channel-busy telemetry exposed over serial/Bluetooth API to prevent doubling.

---

## ADR-211 — Minimal Architecture and Floor Arbitration Model if Modes 3 & 5 are Rescoped or Built

### Status
PROPOSED (Contingency Architecture if the Owner Overrules ADR-210)

### Context
If the repository owner decides to retain and implement Mode 3 or Mode 5 despite the findings in ADR-210, a sound architectural boundary and floor arbitration model must be defined to prevent destabilizing `:core:ptt` or `:core:engine`.

### Decision
1. **Module Seam & Dependency Direction:**
   - All radio audio bridge logic must reside in `:core:radio` under package `com.transfer.flash.core.radio.audio`.
   - `:core:ptt` must NEVER depend on `:core:radio`.
   - `:core:radio` may depend on `:core:ptt` interfaces (`PttAudioCapture`, `PttAudioPlayout`, `PttFloorState`).
2. **Floor Arbitration & Cross-Patch Engine:**
   - An `AnalogRfBridgeController` must mediate between Flash's `PttFloorMachine` and the radio audio endpoint:
     - **Outbound (Flash $\rightarrow$ RF):** When a Flash client acquires the digital floor (`PttFloorState.Talking`), audio samples are downsampled from 16 kHz to 8 kHz mono PCM and streamed to the Bluetooth SCO render device. Carrier Sense Multiple Access (CSMA) must check KISS TNC channel activity (`isChannelClear`) before permitting playout.
     - **Inbound (RF $\rightarrow$ Flash):** An `AnalogRfSquelchDetector` monitors the Bluetooth SCO capture endpoint. When energy exceeds a calibrated threshold for > 150 ms, the bridge claims a synthetic local floor in Flash (`RadioStationFloor`) and relays audio to LAN peers.
     - **Preemption Rule:** Hardware radio PTT physically pressed by an operator immediately terminates digital playback.
3. **Regulatory and Security Profile Constraint:**
   - Analog FM RF audio cannot be encrypted.
   - When the audio bridge is enabled, the UI must prominently display an unencrypted warning badge: `[ANALOG RF - UNENCRYPTED CLEAR-TEXT BROADCAST]`.
   - Encrypted Profile M keys are NEVER applied to analog audio streams.

---

## ADR-212 — Windows Core Audio and Android SCO Routing Abstraction for Radio Peripheral Audio

### Status
PROPOSED (Standardized Peripheral Audio Routing Abstraction)

### Context
During Phase R4 (`BT-R10`), diagnostic tools were implemented to detect and route Bluetooth Hands-Free audio on Windows and Android. These diagnostic mechanisms establish the architectural pattern for any future Bluetooth audio integration.

### Decision
1. **Windows Platform Routing (Core Audio / WASAPI):**
   - Query endpoints through `IMMDeviceEnumerator` filtered to `eCommunications`.
   - To open the Bluetooth SCO bearer to a Hands-Free device on Windows 10/11, the application must initialize an `IAudioClient` stream configured with `AudioCategory_Communications`.
   - Correlation between virtual COM ports and Hands-Free audio endpoints must use container GUID matching (`ContainerID`) from `HKLM\SYSTEM\CurrentControlSet\Enum\BTHHFENUM` and `BTHENUM`.
2. **Android Platform Routing (Audio Routing APIs):**
   - On Android 12+ (API 31+), audio routing must use `AudioManager.setCommunicationDevice(AudioDeviceInfo)` where `type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO`.
   - On Android 11 and lower, legacy fallback `AudioManager.startBluetoothSco()` and `setBluetoothScoOn(true)` must be used.
   - Runtime permissions `BLUETOOTH_CONNECT`, `RECORD_AUDIO`, and `MODIFY_AUDIO_SETTINGS` are required.
   - The audio mode must be set to `AudioManager.MODE_IN_COMMUNICATION` during active routing and restored to `AudioManager.MODE_NORMAL` immediately upon release.
