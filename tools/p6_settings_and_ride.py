"""Pull settings (sub 0x20) responses and any ride-time realtime frames.

We want two things this pass:
  1. Sample the sub 0x20 RX response so we can locate tiltback/alarm-speed
     offsets — Gio reports those don't change in our app, so we need to
     parse them correctly to start with.
  2. Find any sub 0x07 realtime frame with non-zero speed indicators.
     The earlier filter dropped short frames; here we relax it and dump
     every frame's first 32 bytes so a riding sample (if any) shows up.

Usage:
    python p6_settings_and_ride.py "path/to/btsnoop_hci.log"
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


def main(path):
    frames = list(reasm(att(path)))

    # --- Settings (sub 0x20) RX layouts ---
    print("=" * 70)
    print("Settings (sub 0x20) RX responses — first sample of each unique length:")
    seen_lens = {}
    for ts, dirn, frame in frames:
        if dirn != "RX" or len(frame) < 8:
            continue
        if frame[4] != 0x21 or frame[5] != 0x02 or (frame[6] & 0x7F) != 0x20:
            continue
        body = frame[7:-1]
        L = len(body)
        if L in seen_lens:
            continue
        seen_lens[L] = (ts, body)
        print(f"  {fmt(ts)}  len={L}  body=[{' '.join(f'{b:02x}' for b in body)}]")
    print(f"  total unique lengths: {len(seen_lens)}")
    print()

    # --- Settings query/arg pattern: what does each TX `02 21 20 [arg]` ask for? ---
    print("=" * 70)
    print("Settings (sub 0x20) TX queries by arg byte — count of each:")
    arg_counts = Counter()
    for ts, dirn, frame in frames:
        if dirn != "TX" or len(frame) < 8:
            continue
        if frame[4] != 0x02 or frame[5] != 0x21 or frame[6] != 0x20:
            continue
        if len(frame) >= 9:
            arg = frame[7]
            arg_counts[arg] += 1
    for arg, n in sorted(arg_counts.items()):
        print(f"  arg=0x{arg:02x}: {n} queries")
    print()

    # --- All sub 0x07 realtime frames with their data block hex,
    #     even short / partial ones (so any riding frames show up) ---
    print("=" * 70)
    print("All sub 0x07 realtime RX frames (first 32 body bytes):")
    realtime_frames = []
    for ts, dirn, frame in frames:
        if dirn != "RX" or len(frame) < 8:
            continue
        if frame[4] != 0x21 or frame[5] != 0x02 or frame[6] != 0x87:
            continue
        body = frame[7:-1]
        # Strip optional `01 00` status pair so offsets line up across frames.
        prefix = ""
        if len(body) >= 2 and body[:2] == b"\x01\x00":
            prefix = "01 00 | "
            body = body[2:]
        realtime_frames.append((ts, body, prefix))
    for ts, body, prefix in realtime_frames:
        head = " ".join(f"{b:02x}" for b in body[:32])
        print(f"  {fmt(ts)}  len={len(body):3d}  {prefix}{head}")

    # --- Any frame with NON-ZERO byte at the candidate speed offsets ---
    print()
    print("=" * 70)
    print("Frames with non-zero bytes at candidate speed offsets (4-15):")
    for ts, body, prefix in realtime_frames:
        if len(body) < 16:
            continue
        nz = [(o, body[o]) for o in range(4, 16) if body[o] != 0]
        if nz:
            head = " ".join(f"{b:02x}" for b in body[:24])
            nz_s = ", ".join(f"o{o}=0x{v:02x}" for o, v in nz)
            print(f"  {fmt(ts)}  len={len(body)}  body=[{head}]  nz: {nz_s}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: p6_settings_and_ride.py <log>", file=sys.stderr)
        sys.exit(1)
    main(sys.argv[1])
