import sys
import time
import argparse
import serial

FEND = 0xC0
FESC = 0xDB
TFEND = 0xDC
TFESC = 0xDD

CMD_DATA = 0x00
CMD_TXDELAY = 0x01
CMD_P = 0x02
CMD_SLOTTIME = 0x03
CMD_TXTAIL = 0x04
CMD_FULLDUPLEX = 0x05
CMD_EXIT_KISS = 0xFF

def escape_kiss(payload: bytes) -> bytes:
    out = bytearray()
    for b in payload:
        if b == FEND:
            out.extend([FESC, TFEND])
        elif b == FESC:
            out.extend([FESC, TFESC])
        else:
            out.append(b)
    return bytes(out)

def unescape_kiss(payload: bytes) -> bytes:
    out = bytearray()
    i = 0
    while i < len(payload):
        b = payload[i]
        if b == FESC and i + 1 < len(payload):
            next_b = payload[i + 1]
            if next_b == TFEND:
                out.append(FEND)
                i += 2
                continue
            elif next_b == TFESC:
                out.append(FESC)
                i += 2
                continue
        out.append(b)
        i += 1
    return bytes(out)

def make_kiss_frame(cmd: int, data: bytes) -> bytes:
    return bytes([FEND, cmd]) + escape_kiss(data) + bytes([FEND])

def encode_callsign(call: str, ssid: int, last: bool = False) -> bytes:
    call = call.upper().ljust(6)[:6]
    encoded = bytearray()
    for c in call:
        encoded.append((ord(c) << 1) & 0xFE)
    ssid_byte = 0b01100000 | ((ssid & 0x0F) << 1) | (1 if last else 0)
    encoded.append(ssid_byte)
    return bytes(encoded)

def decode_callsign(raw: bytes) -> str:
    if len(raw) < 7:
        return "?"
    call = "".join(chr((b >> 1) & 0x7F) for b in raw[:6]).strip()
    ssid = (raw[6] >> 1) & 0x0F
    return f"{call}-{ssid}" if ssid > 0 else call

def make_ax25_ui_frame(dest: str, dest_ssid: int, src: str, src_ssid: int, payload: bytes, digis=None) -> bytes:
    frame = bytearray()
    has_digis = bool(digis)
    frame.extend(encode_callsign(dest, dest_ssid, last=False))
    frame.extend(encode_callsign(src, src_ssid, last=(not has_digis)))
    if digis:
        for i, (d_call, d_ssid) in enumerate(digis):
            is_last = (i == len(digis) - 1)
            frame.extend(encode_callsign(d_call, d_ssid, last=is_last))
    frame.append(0x03)  # UI frame
    frame.append(0xF0)  # PID: No layer 3
    frame.extend(payload)
    return bytes(frame)

def parse_ax25_frame(raw: bytes):
    if len(raw) < 16:
        return None
    dest = decode_callsign(raw[0:7])
    src = decode_callsign(raw[7:14])
    idx = 14
    digis = []
    while idx + 7 <= len(raw) and not (raw[idx - 1] & 0x01):
        digis.append(decode_callsign(raw[idx:idx+7]))
        idx += 7
    if idx + 2 > len(raw):
        return None
    control = raw[idx]
    pid = raw[idx+1]
    payload = raw[idx+2:]
    return {
        "dest": dest,
        "src": src,
        "digis": digis,
        "control": hex(control),
        "pid": hex(pid),
        "payload": payload
    }

def main():
    parser = argparse.ArgumentParser(description="VR-N76 Bluetooth KISS TNC Test Tool")
    parser.add_argument("--port", default="COM16", help="Serial port (default: COM16)")
    parser.add_argument("--baud", type=int, default=115200, help="Baud rate (default: 115200)")
    parser.add_argument("--mode", choices=["ping", "aprs", "raw220", "listen"], default="ping",
                        help="Action to perform: ping (set KISS params), aprs (send APRS text message), raw220 (send 220-byte payload), listen (monitor incoming)")
    parser.add_argument("--dest", default="ALL", help="APRS Addressee or destination callsign")
    parser.add_argument("--src", default="FLASH", help="Source callsign (default: FLASH)")
    parser.add_argument("--msg", default="Flash BT-00 test packet", help="Message text")
    parser.add_argument("--listen-sec", type=int, default=10, help="Listen time in seconds (default: 10)")
    args = parser.parse_args()

    print(f"=== Opening {args.port} at {args.baud} baud ===")
    try:
        ser = serial.Serial(args.port, args.baud, timeout=0.5, write_timeout=2.0)
    except Exception as e:
        print(f"Failed to open {args.port}: {e}")
        sys.exit(1)

    print(f"Serial port {args.port} connected successfully.")

    try:
        ser.dtr = True
        ser.rts = True
        time.sleep(0.5)

        # Clear any stale input/output buffers
        ser.reset_input_buffer()
        ser.reset_output_buffer()

        # Send initial FEND to clear any framing errors
        ser.write(bytes([FEND]))
        ser.flush()
        time.sleep(0.1)

        # Step 1: Send KISS initialization commands
        print("\n--- Sending KISS parameter configuration ---")
        # TXDELAY: 300 ms (0x1E * 10 ms)
        txdelay_frame = make_kiss_frame(CMD_TXDELAY, bytes([0x1E]))
        ser.write(txdelay_frame)
        print("  -> Sent KISS TXDELAY (300 ms)")

        # Persistence: 64 (0x40)
        p_frame = make_kiss_frame(CMD_P, bytes([0x40]))
        ser.write(p_frame)
        print("  -> Sent KISS Persistence (0x40)")

        # SlotTime: 100 ms (0x0A * 10 ms)
        slot_frame = make_kiss_frame(CMD_SLOTTIME, bytes([0x0A]))
        ser.write(slot_frame)
        print("  -> Sent KISS SlotTime (100 ms)")

        # TXTAIL: 20 ms (0x02 * 10 ms)
        txtail_frame = make_kiss_frame(CMD_TXTAIL, bytes([0x02]))
        ser.write(txtail_frame)
        print("  -> Sent KISS TXTAIL (20 ms)")

        ser.flush()
        time.sleep(0.5)

        if args.mode == "aprs":
            # Format APRS addressed message: :ADDRESSEE:message
            addressee = args.dest.upper().ljust(9)[:9]
            aprs_text = f":{addressee}:{args.msg}"
            payload = aprs_text.encode("ascii", errors="replace")

            ax25 = make_ax25_ui_frame(dest="APRS", dest_ssid=0, src=args.src, src_ssid=1, payload=payload)
            kiss_data = make_kiss_frame(CMD_DATA, ax25)

            print(f"\n--- Transmitting APRS text frame ({len(kiss_data)} total KISS bytes) ---")
            print(f"  Source: {args.src}-1  ->  Dest: APRS-0")
            print(f"  APRS Payload: {aprs_text}")
            print(f"  Raw KISS Hex: {kiss_data.hex()}")

            # Countdown so user can watch the radio
            for c in [3, 2, 1]:
                print(f"  Transmitting in {c} seconds... (watch the radio TX LED and LCD)")
                time.sleep(1.0)

            print("  >>> FIRING PACKET NOW <<<")
            t0 = time.time()
            ser.write(kiss_data)
            ser.flush()
            t_write = time.time() - t0
            print(f"  Packet written in {t_write * 1000:.1f} ms.")

            # Repeat packet 2 more times spaced by 2.5s so user has multiple chances to observe
            for rep in range(1, 3):
                time.sleep(2.5)
                print(f"\n  >>> FIRING REPEAT PACKET #{rep + 1} NOW <<<")
                ser.write(kiss_data)
                ser.flush()


        elif args.mode == "raw220":
            # 220-byte test frame to measure throughput & timing
            payload = bytes([i % 256 for i in range(220)])
            ax25 = make_ax25_ui_frame(dest="APRS", dest_ssid=0, src=args.src, src_ssid=1, payload=payload)
            kiss_data = make_kiss_frame(CMD_DATA, ax25)

            print(f"\n--- Transmitting 220-byte raw payload ({len(kiss_data)} total KISS bytes) ---")
            print("Theoretical 1200-baud on-air duration: ~1.6s RF modulation + 0.3s TXDELAY = ~1.9s total TX time")
            
            for c in [3, 2, 1]:
                print(f"  Firing in {c} seconds... (watch red TX LED duration!)")
                time.sleep(1.0)

            print("  >>> TRANSMITTING 220-BYTE FRAME NOW <<<")
            t0 = time.time()
            ser.write(kiss_data)
            ser.flush()
            t_write = time.time() - t0
            print(f"  Serial write took {t_write * 1000:.1f} ms.")
            print(">>> OBSERVE: Count how many seconds the RED TX LED stays lit on the radio! <<<")


        # Step 2: Listen for incoming frames
        print(f"\n--- Listening on {args.port} for {args.listen_sec} seconds (press Ctrl+C to stop) ---")
        buffer = bytearray()
        end_time = time.time() + args.listen_sec
        in_frame = False
        frame_bytes = bytearray()

        while time.time() < end_time:
            available = ser.in_waiting
            if available > 0:
                chunk = ser.read(available)
                for b in chunk:
                    if b == FEND:
                        if in_frame and len(frame_bytes) > 0:
                            unescaped = unescape_kiss(frame_bytes)
                            cmd = unescaped[0] if len(unescaped) > 0 else -1
                            data = unescaped[1:] if len(unescaped) > 1 else b""
                            print(f"\n[RECEIVED KISS FRAME] Cmd: {hex(cmd)}, Length: {len(data)} bytes")
                            if cmd == CMD_DATA:
                                parsed = parse_ax25_frame(data)
                                if parsed:
                                    print(f"  AX.25: {parsed['src']} -> {parsed['dest']} (Control={parsed['control']}, PID={parsed['pid']})")
                                    try:
                                        print(f"  Payload text: {parsed['payload'].decode('latin-1')}")
                                    except Exception:
                                        print(f"  Payload hex: {parsed['payload'].hex()}")
                                else:
                                    print(f"  Raw data hex: {data.hex()}")
                            else:
                                print(f"  KISS command byte: {hex(cmd)} data: {data.hex()}")
                            frame_bytes.clear()
                        in_frame = True
                    else:
                        if in_frame:
                            frame_bytes.append(b)
            else:
                time.sleep(0.05)

        print("\nListen window finished.")

    except KeyboardInterrupt:
        print("\nInterrupted by user.")
    finally:
        ser.close()
        print(f"Serial port {args.port} closed.")

if __name__ == "__main__":
    main()
