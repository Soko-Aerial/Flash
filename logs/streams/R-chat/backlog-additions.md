# Test Backlog Additions — Stream R-chat

Test prefix: `RADCHAT-`

---

## RADCHAT-DEV01 — Companion Mode Text Chat & Cryptographic ACK Over Physical Radios (BT-R05)

### Setup
- Two physical Vero Telecom VR-N76 / BTECH UV-Pro handheld transceivers tuned to the same simplex UHF/VHF frequency with dummy loads or legal RF antennas.
- Station 1: Windows 11 Desktop running Flash with VR-N76 connected via Bluetooth SPP (virtual COM port).
- Station 2: Android phone running Flash with VR-N76 connected via Bluetooth SPP.
- Both stations paired in Flash with existing pairing keys in `FlashTrustStore`. Wi-Fi disabled on both devices (no LAN path).

### Steps
1. On Station 1, open conversation with Station 2; verify transport pill indicates Radio link.
2. Send message: `"Hello via hardware radio BT-R05"`.
3. Verify Station 1 immediately shows status `'sent by radio, waiting'`.
4. Verify Station 2 receives message and displays `"Hello via hardware radio BT-R05"`.
5. Verify Station 2 transmits cryptographic ACK frame.
6. Verify Station 1 receives ACK and transitions status to `'delivered'`.

### Pass Line
```text
PASS BT-R05: Text message arrives on Station 2; Station 1 shows 'sent by radio, waiting' then transitions to 'delivered' upon cryptographic ACK.
```

### Source
Plan R §7 / Phase R3 (`docs/plans/RADIO-GATEWAY-PLAN.md` §11).

### Status
TODO (Owner physical execution per `runbook-R3.md`)

---

## RADCHAT-DEV02 — Air Capture Confidentiality Under Profile M (BT-R08-M)

### Setup
- Station 1 and Station 2 operating under Profile M (Encrypted).
- Monitoring receiver (RTL-SDR / Direwolf / audio demodulator) tuned to the RF frequency.

### Steps
1. On Station 1, transmit message containing distinct string: `"CONFIDENTIAL_COORDINATES_47N_122W"`.
2. Inspect demodulated AX.25 frame in Direwolf / SDR packet log.
3. Verify AX.25 Destination address is `FLASH-0` and Source address is a rotating 6-character tag.
4. Verify hex and ASCII payload contains zero occurrences of `"CONFIDENTIAL"` or `"COORDINATES"`.

### Pass Line
```text
PASS BT-R08-M: Air capture under Profile M contains zero plaintext substrings; all frame bodies are AEAD ciphertext with rotating tags.
```

### Source
Plan R §8 (`docs/plans/RADIO-GATEWAY-PLAN.md` §8 and §11).

### Status
TODO (Owner physical execution per `runbook-R3.md`)

---

## RADCHAT-DEV03 — Air Capture Transparency Under Profile A (BT-R08-A)

### Setup
- Station 1 operating under Profile A (Plaintext Signed) with callsign set (e.g. `W1AW`).
- Monitoring receiver (RTL-SDR / Direwolf) recording channel traffic.

### Steps
1. On Station 1, transmit: `"CQ CQ DE W1AW TEST"`.
2. Capture frame on SDR.
3. Verify AX.25 Source address is `W1AW`.
4. Verify payload contains clear-text `"CQ CQ DE W1AW TEST"` followed by valid 64-byte ECDSA P-256 signature.

### Pass Line
```text
PASS BT-R08-A: Air capture under Profile A displays operator callsign in AX.25 source and valid ECDSA signature.
```

### Source
Plan R §8 (`docs/plans/RADIO-GATEWAY-PLAN.md` §8 and §11).

### Status
TODO (Owner physical execution per `runbook-R3.md`)

---

## RADCHAT-DEV04 — RF Loss Recovery & Backoff Retransmission (BT-R09)

### Setup
- Two stations running Flash over physical radios.
- Station 2's antenna disconnected or RF path attenuated to induce packet loss.

### Steps
1. Station 1 transmits message: `"Recovery message BT-R09"`.
2. Station 1 enters `'sent by radio, waiting'` and steps through backoff retry attempts (attempts 1 to 5).
3. Reconnect Station 2 antenna during attempt 3.
4. Station 2 receives retransmitted frame, displays message, and emits cryptographic ACK.
5. Station 1 receives ACK and marks message `'delivered'`.

### Pass Line
```text
PASS BT-R09: Radio message re-transmits through retry backoff upon temporary RF interruption, and completes with 'delivered' once RF path is restored.
```

### Source
Plan R §7 (`docs/plans/RADIO-GATEWAY-PLAN.md` §7 and §11).

### Status
TODO (Owner physical execution per `runbook-R3.md`)
