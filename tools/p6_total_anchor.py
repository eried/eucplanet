"""Find the P6 total-mileage offset using three labelled mileage values.

Per the labelled video:
    video 5:43 = wall 13:30:02   total 1776.8 mi  battery 91%
    video 6:05 = wall 13:30:24   total 1776.9 mi  battery 91%
    video 6:19 = wall 13:30:38   total 1777.0 mi  battery 90%

1776.8 mi = 2859.99 km   -> stored as 285999 if scale is 0.01 km
1776.9 mi = 2860.15 km   -> 286015
1777.0 mi = 2860.31 km   -> 286031

Tool scans every 4-byte uint32 LE position in each labelled frame and
flags the offset whose value is in the right neighbourhood (and whose
delta across the three frames matches the expected delta of 16/16).
"""

import struct
import sys

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
        if body[0] == 0x1B:
            yield ts, body[3:]


def reasm(events):
    buf = bytearray()
    last_ts = 0
    for ts, value in events:
        if value.startswith(b"\xaa\xaa") and buf:
            yield (last_ts, bytes(buf))
            buf.clear()
        buf.extend(value)
        last_ts = ts
        if len(buf) >= 5:
            total = 5 + buf[3]
            if len(buf) >= total:
                yield (last_ts, bytes(buf[:total]))
                tail = bytes(buf[total:])
                buf.clear()
                buf.extend(tail)


def find_nearest(bodies, label_sec):
    return min(
        bodies,
        key=lambda tb: abs(((tb[0] - BTSNOOP_EPOCH_US) / 1_000_000) % 86400 - label_sec)
    )


def main(path):
    frames = list(reasm(att(path)))
    bodies = []
    for ts, frame in frames:
        if len(frame) < 8 or frame[:2] != b"\xaa\xaa":
            continue
        if frame[4] != 0x21 or frame[5] != 0x02 or frame[6] != 0x87:
            continue
        body = frame[7:-1]
        if len(body) >= 2 and body[:2] == b"\x01\x00":
            body = body[2:]
        if len(body) >= 80:
            bodies.append((ts, body))

    LABELS = [
        ("5:43", 13 * 3600 + 30 * 60 + 2,  1776.8 * 1.609344, 91),
        ("6:05", 13 * 3600 + 30 * 60 + 24, 1776.9 * 1.609344, 91),
        ("6:19", 13 * 3600 + 30 * 60 + 38, 1777.0 * 1.609344, 90),
    ]

    selected = []
    for tag, t, km, batt in LABELS:
        ts, body = find_nearest(bodies, t)
        actual = ((ts - BTSNOOP_EPOCH_US) / 1_000_000) % 86400
        h = int(actual // 3600); m = int(actual // 60) % 60; s = actual % 60
        print(f"label {tag}: target sec {t} km={km:.2f} batt={batt}%, got {h:02d}:{m:02d}:{s:06.3f}")
        selected.append((tag, body, km, batt))

    L = min(len(b) for _, b, _, _ in selected) - 4
    print()
    print("Offsets where the uint32 LE matches each label's expected total km*100 (+/- 5%):")
    print(f"{'off':>3s} {'5:43':>10s} {'6:05':>10s} {'6:19':>10s}  match")
    for off in range(L):
        vals = [struct.unpack_from("<I", b, off)[0] for _, b, _, _ in selected]
        targets = [int(km * 100) for _, _, km, _ in selected]
        ok = True
        for v, target in zip(vals, targets):
            if not (target * 0.95 <= v <= target * 1.05):
                ok = False
                break
        if ok:
            print(f"{off:3d} {vals[0]:10d} {vals[1]:10d} {vals[2]:10d}  matches expected (~{targets[0]}, {targets[1]}, {targets[2]})")
    print()
    print("Battery checks at offset 20-21 (uint16 LE / 100, %):")
    for tag, body, _, batt in selected:
        v = struct.unpack_from("<H", body, 20)[0] / 100
        print(f"  {tag}: offset 20 reads {v:.2f}% (label said {batt}%)")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: p6_total_anchor.py <log>", file=sys.stderr)
        sys.exit(1)
    main(sys.argv[1])
