"""Compare P6 sub 0x07 telemetry frames between parked and riding states.

Anchors at wall-clock times we know are parked vs riding from the labelled
video. For each byte offset in the data block, prints the value while
parked and the value while riding so we can spot which bytes encode
speed / PWM / temps / gear.

Usage:
    python p6_riding_diff.py "path/to/btsnoop_hci.log"
"""

import struct
import sys
from collections import defaultdict

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


def main(path):
    frames = list(reasm(att(path)))

    # Pull realtime bodies (post `02 87 01 00` prefix).
    bodies = []
    for ts, dirn, frame in frames:
        if dirn != "RX" or len(frame) < 8 or frame[:2] != b"\xaa\xaa":
            continue
        if frame[4] != 0x21 or frame[5] != 0x02 or frame[6] != 0x87:
            continue
        body = frame[7:-1]
        if len(body) >= 2 and body[:2] == b"\x01\x00":
            body = body[2:]
        if len(body) >= 90:  # only full-length frames
            bodies.append((ts, body))

    print(f"Total full-length sub 0x07 frames: {len(bodies)}")
    if not bodies:
        return

    first = bodies[0][0]
    last = bodies[-1][0]
    span = (last - first) / 1_000_000
    print(f"Time span: {fmt(first)} - {fmt(last)} ({span:.1f}s)\n")

    # Anchor points keyed off the labelled video.
    # video offset: video_time_s + 13:24:13 = wall-clock
    # Parked snapshots: pre-13:25:53 (before the user enters Settings).
    # Riding snapshots: 13:30:00 - 13:33:00 (rider going ~7 mph per video).
    def in_window(ts_us, start_hms, end_hms):
        s = (ts_us - BTSNOOP_EPOCH_US) / 1_000_000
        sec = s % (24 * 3600)
        return start_hms <= sec <= end_hms

    parked = [b for ts, b in bodies if in_window(ts, 13*3600+25*60+30, 13*3600+25*60+45)]
    riding = [b for ts, b in bodies if in_window(ts, 13*3600+30*60+0, 13*3600+33*60+0)]

    print(f"Parked frames (13:25:30-13:25:45): {len(parked)}")
    print(f"Riding frames (13:30:00-13:33:00): {len(riding)}\n")

    if not parked or not riding:
        return

    # For each offset, summarise parked vs riding distributions.
    L = min(len(parked[0]), len(riding[0]), 96)
    print("offset | parked u8 / u16LE / i16LE | riding u8 / u16LE / i16LE | comment")
    print("-" * 100)
    for off in range(L):
        # Parked
        p_bytes = sorted({b[off] for b in parked})
        # Riding
        r_bytes = sorted({b[off] for b in riding})

        p_u16 = sorted({struct.unpack_from("<H", b, off)[0] for b in parked if off + 2 <= len(b)})
        r_u16 = sorted({struct.unpack_from("<H", b, off)[0] for b in riding if off + 2 <= len(b)})

        p_i16 = sorted({struct.unpack_from("<h", b, off)[0] for b in parked if off + 2 <= len(b)})
        r_i16 = sorted({struct.unpack_from("<h", b, off)[0] for b in riding if off + 2 <= len(b)})

        # Heuristic flags
        flag = ""
        # Constant in parked but varies in riding -> candidate for speed/PWM/etc.
        if len(p_bytes) == 1 and len(r_bytes) > 5:
            flag += " * varies-only-in-riding"
        # Different mean range
        if p_u16 and r_u16:
            p_med = sorted(p_u16)[len(p_u16) // 2]
            r_med = sorted(r_u16)[len(r_u16) // 2]
            if abs(p_med - r_med) > 50 and p_med < 32768 and r_med < 32768:
                flag += f" * shift({p_med}->{r_med})"

        if not flag and len(p_bytes) == 1 and len(r_bytes) == 1:
            continue  # boring constant

        ps = ",".join(f"{v:02x}" for v in p_bytes[:4])
        rs = ",".join(f"{v:02x}" for v in r_bytes[:6])
        ps_u16 = p_u16[0] if p_u16 else 0
        rs_u16_min = min(r_u16) if r_u16 else 0
        rs_u16_max = max(r_u16) if r_u16 else 0
        ps_i16 = p_i16[0] if p_i16 else 0
        rs_i16_min = min(r_i16) if r_i16 else 0
        rs_i16_max = max(r_i16) if r_i16 else 0
        print(f"{off:3d}    | u8={ps} u16={ps_u16} i16={ps_i16:6d} | u8={rs} u16=[{rs_u16_min}..{rs_u16_max}] i16=[{rs_i16_min:6d}..{rs_i16_max:6d}]{flag}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: p6_riding_diff.py <log>", file=sys.stderr)
        sys.exit(1)
    main(sys.argv[1])
