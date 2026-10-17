# Owner Runbook: Hardware Radio Phase R3 (BT-R05, BT-R08, BT-R09)

**Document:** `logs/streams/R-chat/runbook-R3.md`  
**Stream:** `R-chat`  
**Purpose:** Numbered manual test procedure for the repository owner with real hardware radios (Vero Telecom VR-N76 / BTECH UV-Pro) running Flash on Android and Windows Desktop.  
**Safety & Regulatory Notice:** The hardware radio transmits RF. Testing MUST be conducted on an authorised amateur radio frequency with an appropriate callsign or using a 50-ohm dummy load/attenuator. Automated CI/agent tests use in-memory links only; real RF keying occurs ONLY under direct owner execution of this runbook.

---

## 1. Objectives

This runbook validates the three Phase R3 hardware milestones (`docs/plans/RADIO-GATEWAY-PLAN.md` §7, §8, and §11):
1. **BT-R05 — Companion Mode Chat Round-Trip:** Two Flash stations transmit text over real radio TNCs; message shows `'sent by radio, waiting'` upon driver transmit, and transitions to `'delivered'` ONLY upon cryptographic ACK frame receipt.
2. **BT-R08 — Air Capture & Privacy Verification:** An RF receiver/SDR captures frames on air; verifies that under Profile M zero plaintext substrings leak onto the air, while under Profile A the operator callsign and clear text with ECDSA signature appear.
3. **BT-R09 — RF Loss & Reconnection Recovery:** Demonstrates retry recovery when RF is temporarily blocked, and correct queued state `'radio not connected'` when the gateway is disabled.

---

## 2. Hardware & Environment Preparation

1. **Station 1 (Windows 11 PC):**
   - VR-N76 connected via Bluetooth SPP (virtual COM port e.g. `COM16`).
   - Run desktop app from `E:\FlashWork\R-chat` (`tools\flash-gradle.cmd :desktop:run`).
   - In Settings -> Radio Gateway: Enable Gateway, set connection to the VR-N76 COM port.
   - Authorisation confirmation recorded for Profile M.
2. **Station 2 (Android Test Phone):**
   - VR-N76 connected via Bluetooth SPP in Flash app.
   - In Settings -> Radio Gateway: Enable Gateway, connect to Station 2 radio.
3. **RF Channel:**
   - Both radios tuned to the same simplex UHF frequency (e.g. 433.500 MHz / 146.520 MHz) with squelch open or set to minimum, or connected via coaxial attenuators.
4. **Third Monitoring Receiver (SDR for BT-R08):**
   - RTL-SDR or SDRplay running GQRX / SDR# / Direwolf demodulator on the test frequency to record air bytes.

---

## 3. Numbered Execution Steps

### Part 1: BT-R05 — Text Message Round-Trip & Cryptographic ACK

#### Step 1.1 — Establish Pairing & Radio Reachability
1. Ensure Station 1 (PC) and Station 2 (Phone) are paired in Flash (via QR code or LAN pairing).
2. Disconnect Wi-Fi on both devices or move out of LAN range so NO live LAN path exists between Station 1 and Station 2.
3. In Flash Chat, open the conversation between Station 1 and Station 2.
4. Verify the connection pill displays radio reachability (`BLUETOOTH` / Radio link).

#### Step 1.2 — Transmit Text Message
1. On Station 1, enter message: `"Test text over radio link BT-R05"`.
2. Press Send.
3. Observe Station 1 UI immediately:
   - Message status indicator MUST display: `'sent by radio, waiting'`.
   - Message bubble remains in waiting state (NOT delivered).

#### Step 1.3 — Inbound Delivery & Automatic ACK
1. Observe Station 2 (Android phone):
   - Radio RX LED lights up as KISS TNC receives AX.25 frame.
   - Message appears in Station 2 conversation with text: `"Test text over radio link BT-R05"`.
2. Observe Station 2 TX LED briefly pulse as it automatically transmits the cryptographic ACK frame.

#### Step 1.4 — Delivery Confirmation on Station 1
1. Observe Station 1 (PC):
   - Radio RX LED lights up as Station 1 receives the ACK frame.
   - Message status indicator on Station 1 transitions from `'sent by radio, waiting'` to `'delivered'`.

**Pass Line for BT-R05:**
```text
PASS BT-R05: Text message arrives on Station 2; Station 1 shows 'sent by radio, waiting' then transitions to 'delivered' upon cryptographic ACK.
```

---

### Part 2: BT-R08 — Air Capture & Security Profile Verification

#### Step 2.1 — Profile M (Encrypted AEAD)
1. Ensure both stations are set to **Profile M (Encrypted)** in Radio Gateway Settings.
2. Start raw audio / baseband recording on the SDR monitoring receiver.
3. On Station 1, send text message: `"TOP_SECRET_COORDINATES_47_N_122_W"`.
4. Capture the AX.25 UI frame on the SDR.
5. In SDR / Direwolf packet log:
   - Verify AX.25 Destination is `FLASH-0`.
   - Verify AX.25 Source is a 6-character rotating alphanumeric tag (NOT a plaintext callsign).
   - Inspect the packet Information field bytes in hex / ASCII:
     - Search for substring `"TOP_SECRET"`.
     - Search for substring `"COORDINATES"`.
     - Confirm ZERO plaintext substrings appear.
   - Verify all body bytes are AES-256-GCM ciphertext with a 16-byte authentication tag.

**Pass Line for BT-R08 (Profile M):**
```text
PASS BT-R08-M: Air capture under Profile M contains zero plaintext substrings; all frame bodies are AEAD ciphertext with rotating tags.
```

#### Step 2.2 — Profile A (Plaintext Signed)
1. On Station 1, navigate to Radio Gateway Settings.
2. Switch Security Profile to **Profile A (Plaintext Signed)**.
3. Set Operator Callsign (e.g. `W1AW`).
4. On Station 1, send text message: `"CQ CQ DE W1AW TEST PACKET"`.
5. Capture the AX.25 frame on the SDR:
   - Verify AX.25 Source callsign is displayed as `W1AW`.
   - Verify packet payload contains the clear text message `"CQ CQ DE W1AW TEST PACKET"`.
   - Verify trailing 64-byte raw `r||s` ECDSA signature is present.
   - Station 2 receives and validates the frame signature against Station 1's public key.

**Pass Line for BT-R08 (Profile A):**
```text
PASS BT-R08-A: Air capture under Profile A displays operator callsign in AX.25 source and valid ECDSA signature.
```

---

### Part 3: BT-R09 — RF Loss & Reconnection Recovery

#### Step 3.1 — RF Loss & Retry Ladder
1. Switch Station 2 radio off or unscrew antenna into a shielded dummy load to simulate RF signal blockage.
2. On Station 1, send: `"Recovery test message BT-R09"`.
3. Station 1 transmits: status shows `'sent by radio, waiting'`.
4. Because Station 2 is blocked, no ACK returns.
5. Observe Station 1:
   - Station 1 performs exponential backoff re-transmissions (attempts 1 to 5).
6. Restore Station 2's antenna before the 5th attempt.
7. Station 2 receives the re-transmitted frame, delivers it to chat, and emits the cryptographic ACK.
8. Station 1 receives the ACK and transitions status to `'delivered'`.

#### Step 3.2 — Gateway Off / Disconnected Queueing
1. On Station 1, toggle Radio Gateway to **OFF** in Settings.
2. Send message: `"Queued while gateway off"`.
3. Observe UI:
   - Message status immediately shows `'radio not connected'`.
   - Station 1 does NOT key the transmitter or send any bytes to the serial port.
4. Toggle Radio Gateway back to **ON**.
5. Once link status indicates `CONNECTED`, the outbox drains and transmits the queued message over the radio.

**Pass Line for BT-R09:**
```text
PASS BT-R09: Radio message re-transmits through retry backoff upon temporary RF interruption, and completes with 'delivered' once RF path is restored.
```

---

## 4. Summary of Concrete Pass Lines

| Milestone | Criterion | Concrete Pass Line |
|---|---|---|
| **BT-R05** | Companion Mode Round-Trip | `PASS BT-R05: Text message arrives on Station 2; Station 1 shows 'sent by radio, waiting' then transitions to 'delivered' upon cryptographic ACK.` |
| **BT-R08-M** | Profile M Ciphertext Only | `PASS BT-R08-M: Air capture under Profile M contains zero plaintext substrings; all frame bodies are AEAD ciphertext with rotating tags.` |
| **BT-R08-A** | Profile A Callsign & Signature | `PASS BT-R08-A: Air capture under Profile A displays operator callsign in AX.25 source and valid ECDSA signature.` |
| **BT-R09** | RF Loss Recovery & Offline Queue | `PASS BT-R09: Radio message re-transmits through retry backoff upon temporary RF interruption, and completes with 'delivered' once RF path is restored.` |
