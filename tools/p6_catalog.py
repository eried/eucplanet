"""Build a comprehensive packet catalog from a P6 btsnoop capture.

Outputs:
- Every distinct TX command (routing + sub + first-byte arg) with count
- Every distinct RX response shape (routing + sub + length) with count
- For each direction, a short payload sample so we can see the data layout
- Settings response (sub 0x20) decoded with byte-by-byte annotations and
  candidate field interpretations for tiltback / alarm thresholds

Usage:
    python p6_catalog.py "path/to/btsnoop_hci.log"
"""

import struct
import sys
from collections import Counter, defaultdict

BTSNOOP_EPOCH_US = 0xdcddb30f2f8000


def iter_btsnoop(path):
    with open(path, "rb") as f:
        if f.read(8) != b"btsnoop\x00":
            raise SystemExit("bad magic")
        f.read(8)
        while True:
            hdr = f.read(24)
            if len(hdr) < 24:
                return
            _, incl, flags, _, ts = struct.unpack(">IIIIq", hdr)
            payload = f.read(incl)
            if len(payload) < incl:
                return
            yield ts, flags, payload


def att(path):
    for ts, flags, payload in iter_btsnoop(path):
        if not payload or payload[0] != 0x02 or len(payload) < 9:
            continue
        l2 = struct.unpack("<H", payload[5:7])[0]
        cid = struct.unpack("<H", payload[7:9])[0]
        body = payload[9:9 + l2]
        if cid != 0x0004 or not body:
            continue
        op = body[0]
        if op in (0x12, 0x52):
            yield ts, "TX", struct.unpack("<H", body[1:3])[0], body[3:]
        elif op == 0x1B:
            yield ts, "RX", struct.unpack("<H", body[1:3])[0], body[3:]


def reasm(events):
    bufs = {"TX": bytearray(), "RX": bytearray()}
    last = {"TX": 0, "RX": 0}
    for ts, dirn, _, value in events:
        buf = bufs[dirn]
        if value.startswith(b"\xaa\xaa") and buf:
            yield (last[dirn], dirn, bytes(buf))
            buf.clear()
        buf.extend(value)
        last[dirn] = ts
        if len(buf) >= 5:
            total = 5 + buf[3]
            if len(buf) >= total:
                yield (last[dirn], dirn, bytes(buf[:total]))
                tail = bytes(buf[total:])
                buf.clear()
                buf.extend(tail)


def fmt(ts):
    s = (ts - BTSNOOP_EPOCH_US) / 1_000_000
    return f"{int(s//3600)%24:02d}:{int(s//60)%60:02d}:{s%60:06.3f}"


def parse_v2_frame(frame):
    """Return (flag, length, routing, sub, args, crc) or None if malformed."""
    if len(frame) < 8 or frame[:2] != b"\xaa\xaa":
        return None
    flag = frame[2]
    length = frame[3]
    routing = (frame[4], frame[5])
    sub = frame[6]
    args = frame[7:-1]
    crc = frame[-1]
    return (flag, length, routing, sub, args, crc)


def main(path):
    frames = list(reasm(att(path)))

    # ------------------------------------------------------------------
    # Section 1: TX command catalog (group by routing+sub+first-arg-byte)
    # ------------------------------------------------------------------
    print("# P6 BLE catalog (from btsnoop capture)\n")
    print("## TX commands (phone -> wheel)\n")
    print("Grouped by `routing` + `sub` + `arg[0]` so toggle on/off variants stay distinct.")
    print()
    print("| flag | routing | sub  | arg[0] | count | example payload |")
    print("|------|---------|------|--------|-------|-----------------|")
    tx_groups = defaultdict(list)
    for ts, dirn, frame in frames:
        if dirn != "TX":
            continue
        p = parse_v2_frame(frame)
        if p is None:
            continue
        flag, length, routing, sub, args, _ = p
        arg0 = args[0] if args else None
        key = (flag, routing, sub, arg0)
        tx_groups[key].append((ts, args))
    for (flag, routing, sub, arg0), examples in sorted(tx_groups.items(), key=lambda kv: (kv[0][0], kv[0][1], kv[0][2], -1 if kv[0][3] is None else kv[0][3])):
        n = len(examples)
        ex_args = examples[0][1]
        ex_hex = " ".join(f"{b:02x}" for b in ex_args[:16])
        if len(ex_args) > 16:
            ex_hex += " ..."
        arg0_str = f"0x{arg0:02x}" if arg0 is not None else "-"
        print(f"| 0x{flag:02x} | {routing[0]:02x}-{routing[1]:02x} | 0x{sub:02x} | {arg0_str} | {n} | `{ex_hex or '(none)'}` |")
    print()

    # ------------------------------------------------------------------
    # Section 2: RX response catalog (group by routing+sub+length-bucket)
    # ------------------------------------------------------------------
    print("## RX responses (wheel -> phone)\n")
    print("Grouped by `routing` + `sub` + body length. Sub bytes have the high bit set in")
    print("the wire format (e.g. `0x87` = response to query `0x07`); the table strips it.")
    print()
    print("| flag | routing | sub (resp) | body len | count | example body (first 24 bytes) |")
    print("|------|---------|------------|----------|-------|-------------------------------|")
    rx_groups = defaultdict(list)
    for ts, dirn, frame in frames:
        if dirn != "RX":
            continue
        p = parse_v2_frame(frame)
        if p is None:
            continue
        flag, length, routing, sub, args, _ = p
        sub_clean = sub & 0x7F
        key = (flag, routing, sub_clean, len(args))
        rx_groups[key].append((ts, args))
    for (flag, routing, sub, blen), examples in sorted(rx_groups.items()):
        n = len(examples)
        ex_body = examples[0][1]
        ex_hex = " ".join(f"{b:02x}" for b in ex_body[:24])
        if len(ex_body) > 24:
            ex_hex += " ..."
        print(f"| 0x{flag:02x} | {routing[0]:02x}-{routing[1]:02x} | 0x{sub:02x} | {blen} | {n} | `{ex_hex}` |")
    print()

    # ------------------------------------------------------------------
    # Section 3: Sub 0x20 settings response — full byte-by-byte view
    # ------------------------------------------------------------------
    print("## Settings response (sub 0x20) — annotated layout\n")
    print("Body is the data block after `aa aa [flag] [len] 21 02 [sub|0x80]`. The first")
    print("byte echoes the sub byte (`0x20`), the rest is the section payload.\n")
    sample = None
    for ts, dirn, frame in frames:
        if dirn != "RX":
            continue
        p = parse_v2_frame(frame)
        if p is None:
            continue
        flag, length, routing, sub, args, _ = p
        if (sub & 0x7F) == 0x20 and len(args) >= 50:
            sample = (ts, args)
            break

    if sample is None:
        print("(no sub 0x20 RX response of >=50 bytes in capture)")
        return

    ts, body = sample
    print(f"Sample at {fmt(ts)}, body length {len(body)}:")
    print(f"`{' '.join(f'{b:02x}' for b in body)}`\n")
    print("Byte-by-byte (offset is from start of body, including `0x20` sub-echo at 0):\n")
    print("| off | hex   | u8  | as u16 LE | as i16 LE | guess |")
    print("|-----|-------|-----|-----------|-----------|-------|")

    guesses = {
        0: "sub-cmd echo (0x20)",
        5: "pedalAdjustment? (i16 LE / 10 = +/- few degrees)",
        13: "tiltback max speed (u16 LE / 100 = km/h)",
        15: "absolute hardware max (u16 LE / 100 = km/h)",
        17: "alarm threshold A (u16 LE / 100 = km/h)",
        19: "alarm threshold B (u16 LE / 100 = km/h)",
        21: "?",
        23: "?",
    }
    for off in range(len(body)):
        u8 = body[off]
        u16 = struct.unpack_from("<H", body, off)[0] if off + 2 <= len(body) else None
        i16 = struct.unpack_from("<h", body, off)[0] if off + 2 <= len(body) else None
        u16s = f"{u16:5d}" if u16 is not None else "  -  "
        i16s = f"{i16:5d}" if i16 is not None else "  -  "
        guess = guesses.get(off, "")
        print(f"| {off:3d} | 0x{u8:02x}  | {u8:3d} | {u16s}     | {i16s}     | {guess} |")
    print()

    # ------------------------------------------------------------------
    # Section 4: Sub 0x20 sample with arg=0x33 (alternate page)
    # ------------------------------------------------------------------
    print("## Settings response with arg=0x33 (alternate page)\n")
    for ts, dirn, frame in frames:
        if dirn != "RX":
            continue
        p = parse_v2_frame(frame)
        if p is None:
            continue
        flag, length, routing, sub, args, _ = p
        if (sub & 0x7F) == 0x20 and len(args) < 10:
            print(f"At {fmt(ts)}, len {len(args)}: `{' '.join(f'{b:02x}' for b in args)}`")
            break
    print()

    # ------------------------------------------------------------------
    # Section 5: Hypothesis - tiltback/alarm WRITE format
    # ------------------------------------------------------------------
    print("## Hypotheses for tiltback/alarm write commands\n")
    print("- The InMotion app **never writes settings during this capture** — only reads")
    print("  sub 0x20 (sections 0x20 and 0x33). So we can't observe a write opcode here.")
    print()
    print("- Most likely candidates, in priority order:")
    print("  1. **`02 21 60 21 [tilt_lo tilt_hi]`** — V14's setMaxSpeed sub 0x21 with")
    print("     only the tiltback bytes (no alarm field). Some older InMotion firmware")
    print("     accepts this short form.")
    print("  2. **`02 21 60 21 [tilt_lo tilt_hi alarm_lo alarm_hi]`** — V14 long form.")
    print("     This is what our app sends today; per Gio's report it does not change")
    print("     anything on the P6, so this is probably wrong for P6.")
    print("  3. **`02 21 20 20 [...50-byte section payload with new tiltback at off 12]`**")
    print("     — write the full settings section back with the changed value. The")
    print("     InMotion app reads section 0x20; the symmetric write would put modified")
    print("     bytes at the same offsets. This is how WheelLog's V11 writes settings.")
    print()
    print("- To confirm, capture a btsnoop session where the user opens the official")
    print("  InMotion app, changes the tiltback slider on a P6, and saves. The write")
    print("  packet will appear within ~100 ms of the slider release.\n")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: p6_catalog.py <log>", file=sys.stderr)
        sys.exit(1)
    main(sys.argv[1])
