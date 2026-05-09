"""Analyse the P6 btsnoop capture and locate telemetry-byte offsets.

Reads an Android btsnoop_hci log, walks the InMotion-V2 frames the wheel
notifies on the Nordic UART RX characteristic, isolates the realtime
sub 0x07 (`21 02 87 01 00 ...`) data blocks, and aligns them by offset
across all frames so we can spot which bytes encode which fields.

For each offset it prints:
    - the set of distinct values observed
    - whether the byte stays constant or changes over time
    - common-interpretation guesses (uint16 LE / int16 LE / uint32 LE / signed byte)

Then it scans for known reference values from the on-screen InMotion app
(231 V, ~1773 mi total mileage, 1511 W max power, 84/97/99 degF temps,
36.5 PSI tyre pressure) and reports the offsets where each shows up.

Usage:
    python p6_telemetry_offsets.py "path/to/btsnoop_hci.log"
"""

import struct
import sys
from collections import Counter, defaultdict

NORDIC_TX_HANDLE = 0x000d  # phone -> wheel write
NORDIC_RX_HANDLE = 0x000f  # wheel -> phone notify (in this capture)


def iter_btsnoop(path):
    with open(path, "rb") as f:
        magic = f.read(8)
        if magic != b"btsnoop\x00":
            raise SystemExit(f"bad magic: {magic!r}")
        f.read(8)  # version + datalink
        while True:
            hdr = f.read(24)
            if len(hdr) < 24:
                return
            orig_len, incl_len, flags, drops, ts = struct.unpack(">IIIIq", hdr)
            payload = f.read(incl_len)
            if len(payload) < incl_len:
                return
            yield ts, flags, payload


def att_writes(path):
    for ts, flags, payload in iter_btsnoop(path):
        if not payload or payload[0] != 0x02 or len(payload) < 9:
            continue
        l2cap_len = struct.unpack("<H", payload[5:7])[0]
        cid = struct.unpack("<H", payload[7:9])[0]
        body = payload[9:9 + l2cap_len]
        if cid != 0x0004 or not body:
            continue
        opcode = body[0]
        if opcode in (0x12, 0x52) and len(body) >= 3:
            handle = struct.unpack("<H", body[1:3])[0]
            yield ts, "TX", handle, body[3:]
        elif opcode == 0x1B and len(body) >= 3:
            handle = struct.unpack("<H", body[1:3])[0]
            yield ts, "RX", handle, body[3:]


def reassemble_frames(events):
    """Reassemble V2 frames split across multiple notifications.

    Yields (ts, direction, frame_bytes) for each complete `aa aa ... crc` frame.
    """
    bufs = {"TX": bytearray(), "RX": bytearray()}
    last_ts = {"TX": 0, "RX": 0}
    for ts, dirn, handle, value in events:
        buf = bufs[dirn]
        # New header? Flush anything pending and restart at the AA AA position.
        # The InMotion frames are short enough that this naive split-on-header
        # approach works as long as the wheel doesn't send back-to-back frames
        # without a notification break.
        if value.startswith(b"\xaa\xaa") and buf:
            yield (last_ts[dirn], dirn, bytes(buf))
            buf.clear()
        buf.extend(value)
        last_ts[dirn] = ts
        # Heuristic: V2 length byte at offset 3 means total payload = 5 + len.
        # If the buffer contains at least that many bytes, emit and clear.
        if len(buf) >= 5:
            length = buf[3]
            total = 5 + length
            if len(buf) >= total:
                yield (last_ts[dirn], dirn, bytes(buf[:total]))
                tail = bytes(buf[total:])
                buf.clear()
                buf.extend(tail)
    for dirn in ("TX", "RX"):
        if bufs[dirn]:
            yield (last_ts[dirn], dirn, bytes(bufs[dirn]))


def looks_like_p6_realtime(frame):
    """Return the data block if frame is a sub 0x07 realtime response."""
    if len(frame) < 8 or frame[:2] != b"\xaa\xaa":
        return None
    # `aa aa [flag] [len] 21 02 87 [data...] [crc]` — flag varies (0x16, 0x12, ...)
    if frame[4] != 0x21 or frame[5] != 0x02 or frame[6] != 0x87:
        return None
    # Skip the `01 00` status pair after the routing+sub if present.
    body = frame[7:-1]  # strip trailing CRC byte
    if len(body) >= 2 and body[:2] == b"\x01\x00":
        body = body[2:]
    return body


def main(path):
    events = list(att_writes(path))
    frames = list(reassemble_frames(events))

    # Group RX frames by their sub-cmd byte for a high-level catalog.
    sub_counts = Counter()
    realtime_bodies = []
    for ts, dirn, frame in frames:
        if dirn != "RX" or len(frame) < 7:
            continue
        if frame[4] == 0x21 and frame[5] == 0x02:
            sub = frame[6] & 0x7F
            sub_counts[sub] += 1
            if sub == 0x07:
                body = looks_like_p6_realtime(frame)
                if body is not None:
                    realtime_bodies.append((ts, body))

    print("=" * 60)
    print("RX frames by sub-cmd (extended-routing 21-02):")
    for sub, n in sorted(sub_counts.items()):
        print(f"  sub 0x{sub:02x}: {n} frames")
    print()

    if not realtime_bodies:
        print("no sub 0x07 realtime frames found")
        return

    print(f"found {len(realtime_bodies)} sub 0x07 realtime frames")
    body_lens = Counter(len(b) for _, b in realtime_bodies)
    print(f"data-block lengths: {dict(body_lens)}")
    print()

    # Align bodies and find the most common length so trailing partial frames
    # (sometimes truncated by the BLE notification limit) don't pollute the
    # offset analysis.
    common_len, _ = body_lens.most_common(1)[0]
    aligned = [b for _, b in realtime_bodies if len(b) == common_len]
    print(f"using {len(aligned)} frames of length {common_len} for offset analysis")
    print()

    # Per-offset distinct-value summary.
    print("Per-byte values across all aligned frames:")
    print("offset  distinct  example values")
    for off in range(common_len):
        col = [b[off] for b in aligned]
        distinct = sorted(set(col))
        if len(distinct) <= 6:
            example = " ".join(f"{v:02x}" for v in distinct)
        else:
            example = " ".join(f"{v:02x}" for v in distinct[:3]) + f" ... ({len(distinct)} unique)"
        flag = " const" if len(distinct) == 1 else ""
        print(f"  {off:3d}    {len(distinct):3d}    {example}{flag}")
    print()

    # Hunt for known reference values from the InMotion app screenshots.
    print("Offset hunt for reference values (uint16 LE, int16 LE, uint32 LE):")
    references = [
        ("voltage 230 V (uint16 LE / 100, +/-2 V)", lambda v: 22800 <= v <= 23200, "u16"),
        ("battery1 98% (uint16 LE / 100, +/-1)", lambda v: 9700 <= v <= 9999, "u16"),
        ("battery2 96-98%", lambda v: 9600 <= v <= 9899, "u16"),
        ("max power 1511 W (uint16 LE)", lambda v: 1505 <= v <= 1520, "u16"),
        ("total km ~ 2853 (uint32 LE / 100 km)", lambda v: 285200 <= v <= 285800, "u32"),
        ("total km ~ 2853 (uint32 LE / 10 m)", lambda v: 285200 <= v <= 285800, "u32"),
        ("motor temp 36 C / 97 F (signed byte +80)", lambda v: 110 <= v <= 120, "u8"),
        ("MOS temp 27 C / 81 F (signed byte +80)", lambda v: 100 <= v <= 110, "u8"),
        ("PWM 0% (int16 LE)", lambda v: -2 <= v <= 2, "i16"),
        ("tire 36.5 psi (uint16 LE / 10)", lambda v: 360 <= v <= 370, "u16"),
        ("tire 36.5 psi (uint16 LE / 100)", lambda v: 3640 <= v <= 3660, "u16"),
    ]

    def values_at(off, kind, body):
        if kind == "u8" and off + 1 <= len(body):
            return body[off]
        if kind == "u16" and off + 2 <= len(body):
            return struct.unpack_from("<H", body, off)[0]
        if kind == "i16" and off + 2 <= len(body):
            return struct.unpack_from("<h", body, off)[0]
        if kind == "u32" and off + 4 <= len(body):
            return struct.unpack_from("<I", body, off)[0]
        return None

    for label, predicate, kind in references:
        hits = []
        for off in range(common_len):
            matches = []
            for body in aligned:
                v = values_at(off, kind, body)
                if v is not None and predicate(v):
                    matches.append(v)
            if matches and len(matches) >= max(1, len(aligned) // 2):
                hits.append((off, matches[:3], len(matches)))
        line = f"  {label}:"
        if hits:
            details = ", ".join(
                f"off {o} ({n}/{len(aligned)} frames, e.g. {ex})"
                for o, ex, n in hits[:5]
            )
            print(f"{line} {details}")
        else:
            print(f"{line} no matches")
    print()

    # Print first few full data blocks side-by-side for quick visual scan.
    print("First 6 frames (data block hex, post `21 02 87 01 00` prefix):")
    for ts, body in realtime_bodies[:6]:
        s = " ".join(f"{b:02x}" for b in body)
        print(f"  {s[:240]}{'...' if len(s) > 240 else ''}")
    print()


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: p6_telemetry_offsets.py <log>", file=sys.stderr)
        sys.exit(1)
    main(sys.argv[1])
