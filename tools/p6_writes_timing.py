"""Show every TX write to extended-routing 02-21 sub 0x60 in time order,
plus the settings-read response right before/after each write so we can
correlate the written value with what the user saw on screen.

Usage:
    python p6_writes_timing.py "path/to/btsnoop_hci.log"
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

    # Pull all CONTROL writes (sub 0x60) in time order, with timestamps.
    print("All `02 21 60` (CONTROL) writes in time order:\n")
    print(f"{'time':>14s}  {'sub':>4s}  payload")
    for ts, dirn, frame in frames:
        if dirn != "TX" or len(frame) < 8 or frame[:2] != b"\xaa\xaa":
            continue
        if frame[4] != 0x02 or frame[5] != 0x21 or frame[6] != 0x60:
            continue
        args = frame[7:-1]
        if not args:
            continue
        sub = args[0]
        rest = args[1:]
        rest_hex = " ".join(f"{b:02x}" for b in rest)
        # Interpret a few well-known sub forms.
        meaning = ""
        if sub == 0x21 and len(rest) == 2:
            v = struct.unpack("<H", rest)[0]
            meaning = f"setMaxSpeed value {v} = {v/100:.2f} km/h (if /100) or {v/1000:.3f} m/s = {v/1000*3.6:.2f} km/h (if mm/s)"
        elif sub == 0x3e and len(rest) == 4:
            v = struct.unpack("<I", rest)[0]
            v16 = struct.unpack("<H", rest[:2])[0]
            meaning = f"sub 0x3e u32={v} u16[0..1]={v16} ({v16/100:.2f} km/h?)"
        elif sub == 0x31:
            meaning = f"lock {'ON' if rest and rest[0] else 'OFF'}"
        elif sub == 0x50 and len(rest) == 2:
            meaning = f"light {'ON' if rest[0] else 'OFF'} (mirror byte: {rest[1]:02x})"
        elif sub == 0x4e and len(rest) == 1:
            meaning = f"toggle 0x4e {'ON' if rest[0] else 'OFF'}"
        elif sub == 0x51:
            meaning = "horn"
        elif sub == 0x34:
            meaning = "auth handshake"
        print(f"  {fmt(ts)}  0x{sub:02x}  args=[{rest_hex}]  {meaning}")
    print()

    # Show every sub 0x20 settings response that's >=50 bytes, looking for
    # a change at offset 13-14 (our tiltback hypothesis) over the capture.
    print("Settings (sub 0x20) RX responses — value at offsets 13-14, 15-16, 17-18, 19-20 over time:\n")
    print(f"{'time':>14s}  {'len':>3s}  {'o13-14':>7s}  {'o15-16':>7s}  {'o17-18':>7s}  {'o19-20':>7s}")
    for ts, dirn, frame in frames:
        if dirn != "RX" or len(frame) < 8 or frame[:2] != b"\xaa\xaa":
            continue
        if frame[4] != 0x21 or frame[5] != 0x02 or (frame[6] & 0x7F) != 0x20:
            continue
        body = frame[7:-1]
        if len(body) < 25:
            continue
        v13 = struct.unpack_from("<H", body, 13)[0]
        v15 = struct.unpack_from("<H", body, 15)[0]
        v17 = struct.unpack_from("<H", body, 17)[0]
        v19 = struct.unpack_from("<H", body, 19)[0]
        print(f"  {fmt(ts)}  {len(body):3d}  {v13:7d}  {v15:7d}  {v17:7d}  {v19:7d}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: p6_writes_timing.py <log>", file=sys.stderr)
        sys.exit(1)
    main(sys.argv[1])
