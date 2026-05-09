"""Find the P6 speed offset by anchoring on a labelled high-speed moment.

Per the labelled video, video 6:19-6:26 (= wall-clock 13:30:30-13:30:37 with
the recording offset of 53s) the rider accelerates from ~16 mph to 42 mph
and brakes hard back to 0. 42 mph = 67.6 km/h. Encoded as int16 LE * 100
that's 6760 = 0x1A68 -> bytes `68 1a`. As uint16 LE in mph * 100 it'd be
4200 = 0x1068 -> `68 10`.

This tool dumps the full body of each frame in the high-speed window and
flags any byte position where a value matches one of the plausible
encodings (6760 / 4200 / 676 / 420 / 67 / 42 / 18800 / etc) so we can
pin the speed offset definitively.

Usage:
    python p6_speed_finder.py "path/to/btsnoop_hci.log"
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
        op = body[0]
        if op == 0x1B:
            yield ts, "RX", body[3:]


def reasm(events):
    buf = bytearray()
    last_ts = 0
    for ts, dirn, value in events:
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
    for ts, frame in frames:
        if len(frame) < 8 or frame[:2] != b"\xaa\xaa":
            continue
        if frame[4] != 0x21 or frame[5] != 0x02 or frame[6] != 0x87:
            continue
        body = frame[7:-1]
        if len(body) >= 2 and body[:2] == b"\x01\x00":
            body = body[2:]
        if len(body) >= 90:
            bodies.append((ts, body))

    # Per labelled video:
    #   6:00 = 10-12 mph (~17 km/h), wall-clock 13:30:11
    #   6:15 = 16 mph (~25.7 km/h), wall-clock 13:30:26
    #   6:19 = ramping to 42 mph, wall-clock 13:30:30
    #   6:26 = brake to 0, wall-clock 13:30:37
    #   6:43 = go again, wall-clock 13:30:54
    #   6:57 = brake, wall-clock 13:31:08
    LABELS = [
        ("13:30:11", 13 * 3600 + 30 * 60 + 11, "10-12 mph (~17 km/h)", [(1700, 0.05),
                                                                       (1100, 0.05),
                                                                       (170, 0.05),
                                                                       (10, 0.10)]),
        ("13:30:26", 13 * 3600 + 30 * 60 + 26, "16 mph (~25.7 km/h)",  [(2570, 0.10),
                                                                       (1600, 0.05),
                                                                       (257, 0.10),
                                                                       (16, 0.10)]),
        ("13:30:30", 13 * 3600 + 30 * 60 + 30, "ramp to 42 mph",       [(6760, 0.20),
                                                                       (4200, 0.20),
                                                                       (676, 0.20),
                                                                       (420, 0.20)]),
        ("13:30:37", 13 * 3600 + 30 * 60 + 37, "braked to 0",          [(0, 0.0)]),
    ]

    for label_ts_str, label_sec, desc, candidates in LABELS:
        # Find the closest body within +/- 1.5 s of the labelled time.
        nearest = min(
            bodies,
            key=lambda tb: abs(((tb[0] - BTSNOOP_EPOCH_US) / 1_000_000) % 86400 - label_sec)
        )
        ts, body = nearest
        actual = fmt(ts)
        print(f"\n=== {label_ts_str} {desc} ===")
        print(f"closest btsnoop frame: {actual}")
        # Show body bytes
        hex_str = " ".join(f"{b:02x}" for b in body[:96])
        print(f"body: {hex_str[:240]}{'...' if len(hex_str) > 240 else ''}")
        # Highlight any value matching a candidate
        print("\noffset matches:")
        for off in range(0, min(len(body), 96) - 2):
            u16 = struct.unpack_from("<H", body, off)[0]
            i16 = struct.unpack_from("<h", body, off)[0]
            for target, tol in candidates:
                lo = int(target * (1 - tol)) if tol > 0 else target
                hi = int(target * (1 + tol)) if tol > 0 else target
                if lo <= u16 <= hi:
                    print(f"  off {off:3d}: u16={u16:5d}, i16={i16:6d} -> matches u16 ~={target}")
                if lo <= i16 <= hi and i16 != u16:
                    print(f"  off {off:3d}: u16={u16:5d}, i16={i16:6d} -> matches i16 ~={target}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: p6_speed_finder.py <log>", file=sys.stderr)
        sys.exit(1)
    main(sys.argv[1])
