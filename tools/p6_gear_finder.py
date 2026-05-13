"""Find the gear-state offset by looking for bytes that toggle binarily
during the labelled P<->S switching window (wall-clock 13:30:50-13:31:30,
= video 6:37-7:17 in `d:\\Downloads\\P6 again`).

Usage:
    python p6_gear_finder.py "path/to/btsnoop_hci.log"
"""

import struct
import sys
from collections import Counter

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
            yield ts, "TX", body[3:]
        elif op == 0x1B:
            yield ts, "RX", body[3:]


def reasm(events):
    bufs = {"TX": bytearray(), "RX": bytearray()}
    last = {"TX": 0, "RX": 0}
    for ts, dirn, value in events:
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


def in_window(ts_us, start_hms, end_hms):
    s = (ts_us - BTSNOOP_EPOCH_US) / 1_000_000
    sec = s % (24 * 3600)
    return start_hms <= sec <= end_hms


def main(path):
    frames = list(reasm(att(path)))
    bodies = []
    for ts, dirn, frame in frames:
        if dirn != "RX" or len(frame) < 8 or frame[:2] != b"\xaa\xaa":
            continue
        if frame[4] != 0x21 or frame[5] != 0x02 or frame[6] != 0x87:
            continue
        body = frame[7:-1]
        if len(body) >= 2 and body[:2] == b"\x01\x00":
            body = body[2:]
        if len(body) >= 90:
            bodies.append((ts, body))

    # Per the labelled video:
    #   video < 5:47  → P (parked)               wall < 13:30:00
    #   5:47 - 6:39   → S (sport, riding)        13:30:00 - 13:30:52
    #   6:39 - end    → switching P <-> S        13:30:52 onwards
    park_window = (13 * 3600 + 26 * 60, 13 * 3600 + 30 * 60)
    sport_window = (13 * 3600 + 30 * 60 + 5, 13 * 3600 + 30 * 60 + 50)
    mixed_window = (13 * 3600 + 30 * 60 + 52, 13 * 3600 + 31 * 60 + 30)

    park = [b for ts, b in bodies if in_window(ts, *park_window)]
    sport = [(ts, b) for ts, b in bodies if in_window(ts, *sport_window)]
    mixed = [(ts, b) for ts, b in bodies if in_window(ts, *mixed_window)]

    print(f"Park-only window {park_window}: {len(park)} frames")
    print(f"Sport-only window {sport_window}: {len(sport)} frames")
    print(f"Mixed P/S window {mixed_window}: {len(mixed)} frames\n")

    if not park or not mixed:
        return

    L = min(len(park[0]), min(len(b) for _, b in sport + mixed), 96)

    print("Offsets where the byte has ONE value in P, a DIFFERENT value in S,")
    print("and toggles between exactly those two values during P<->S switching:")
    print()
    print(f"{'off':>3s}  {'P byte':>6s}  {'S byte':>6s}  {'mixed':<10s}  {'transitions':>11s}")
    print("-" * 60)
    found = 0
    for off in range(L):
        park_vals = sorted({b[off] for b in park})
        sport_vals = sorted({b[off] for _, b in sport})
        mixed_vals = sorted({b[off] for _, b in mixed})
        # Strict gear signal: single value in each pure window, two values
        # in the mixed window.
        if len(park_vals) != 1 or len(sport_vals) != 1:
            continue
        if park_vals[0] == sport_vals[0]:
            continue
        if set(mixed_vals) != {park_vals[0], sport_vals[0]} and \
           not set(mixed_vals).issubset({park_vals[0], sport_vals[0]}):
            continue
        seq = [b[off] for _, b in mixed]
        transitions = sum(1 for i in range(1, len(seq)) if seq[i] != seq[i - 1])
        if transitions < 2:
            continue
        mixed_str = " ".join(f"{v:02x}" for v in mixed_vals)
        print(f"{off:3d}   {park_vals[0]:02x}      {sport_vals[0]:02x}      {mixed_str:<10s}  {transitions:>11d}")
        found += 1
    if found == 0:
        print("(no clean toggles — gear may be a bitfield, see relaxed scan below)")
        print()
        print("Relaxed scan: bytes that differ between P-only and S-only:")
        print(f"{'off':>3s}  {'P set':<20s}  {'S set':<20s}")
        print("-" * 60)
        for off in range(L):
            park_vals = sorted({b[off] for b in park})
            sport_vals = sorted({b[off] for _, b in sport})
            if set(park_vals) == set(sport_vals):
                continue
            if len(park_vals) > 4 or len(sport_vals) > 4:
                continue
            ps = " ".join(f"{v:02x}" for v in park_vals)
            ss = " ".join(f"{v:02x}" for v in sport_vals)
            print(f"{off:3d}   {ps:<20s}  {ss:<20s}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: p6_gear_finder.py <log>", file=sys.stderr)
        sys.exit(1)
    main(sys.argv[1])
