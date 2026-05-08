"""Audit TX commands and RX telemetry across the P6 capture.

Two passes:

1. List every distinct TX command (opcode + sub) sent by the InMotion app and
   how many times. Useful for identifying the light, tiltback, and alarm
   write opcodes since the user said those don't work in our app while
   horn and lock do.

2. Walk every realtime sub 0x07 RX frame and print the running values at
   the offsets we already trust (voltage, current, battery, total km),
   plus the bytes at *candidate* offsets for speed / PWM / temps so we
   can spot which moves when the rider rolls.

Usage:
    python p6_command_audit.py "path/to/btsnoop_hci.log"
"""

import struct
import sys
from collections import Counter, defaultdict

BTSNOOP_EPOCH_US = 0xdcddb30f2f8000  # microseconds since year 1


def iter_btsnoop(path):
    with open(path, "rb") as f:
        magic = f.read(8)
        if magic != b"btsnoop\x00":
            raise SystemExit(f"bad magic: {magic!r}")
        f.read(8)
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
    bufs = {"TX": bytearray(), "RX": bytearray()}
    last_ts = {"TX": 0, "RX": 0}
    for ts, dirn, handle, value in events:
        buf = bufs[dirn]
        if value.startswith(b"\xaa\xaa") and buf:
            yield (last_ts[dirn], dirn, bytes(buf))
            buf.clear()
        buf.extend(value)
        last_ts[dirn] = ts
        if len(buf) >= 5:
            length = buf[3]
            total = 5 + length
            if len(buf) >= total:
                yield (last_ts[dirn], dirn, bytes(buf[:total]))
                tail = bytes(buf[total:])
                buf.clear()
                buf.extend(tail)


def fmt_ts(ts):
    secs = (ts - BTSNOOP_EPOCH_US) / 1_000_000
    h = int(secs // 3600) % 24
    m = int(secs // 60) % 60
    s = secs % 60
    return f"{h:02d}:{m:02d}:{s:06.3f}"


def main(path):
    frames = list(reassemble_frames(att_writes(path)))

    # --- TX command catalog ---
    print("=" * 70)
    print("TX commands (phone -> wheel) by routing+sub:")
    tx_summary = Counter()
    tx_first_example = {}
    for ts, dirn, frame in frames:
        if dirn != "TX" or len(frame) < 7 or frame[:2] != b"\xaa\xaa":
            continue
        # body layout: aa aa [flag] [len] [routing0] [routing1] [sub] [args...] [crc]
        flag = frame[2]
        routing = (frame[4], frame[5])
        sub = frame[6] & 0xFF
        # args after sub up to crc
        args = frame[7:-1]
        key = (flag, routing, sub)
        tx_summary[key] += 1
        if key not in tx_first_example:
            tx_first_example[key] = (ts, args)
    for (flag, routing, sub), n in sorted(tx_summary.items()):
        ts0, args = tx_first_example[(flag, routing, sub)]
        args_hex = " ".join(f"{b:02x}" for b in args[:16])
        if len(args) > 16:
            args_hex += " ..."
        print(f"  flag=0x{flag:02x} routing={routing[0]:02x}-{routing[1]:02x} sub=0x{sub:02x} "
              f"x{n}  first={fmt_ts(ts0)}  args=[{args_hex}]")
    print()

    # --- TX with extended-routing (02-21) — these are the P6's command set ---
    print("=" * 70)
    print("Detailed TX list for extended-routing (02-21) commands:")
    for ts, dirn, frame in frames:
        if dirn != "TX" or len(frame) < 7:
            continue
        if frame[4] != 0x02 or frame[5] != 0x21:
            continue
        sub = frame[6] & 0xFF
        args = frame[7:-1]
        args_hex = " ".join(f"{b:02x}" for b in args)
        print(f"  {fmt_ts(ts)}  sub=0x{sub:02x}  args=[{args_hex}]")
    print()

    # --- Realtime sweep: voltage, current, battery, total km, candidates ---
    print("=" * 70)
    print("Realtime sub 0x07 frames - decoded fields per frame:")
    print(f"{'time':>14s}  {'V':>7s} {'I':>7s} {'B1%':>6s} {'B2%':>6s} "
          f"{'totKm':>7s} {'tick':>5s} {'maxW':>5s} "
          f"{'o4-7':>9s} {'o8-11':>9s} {'o12-15':>11s} "
          f"{'o26':>4s} {'o28':>4s} {'o38':>4s} {'o68':>4s} {'o74':>4s}")
    for ts, dirn, frame in frames:
        if dirn != "RX" or len(frame) < 8:
            continue
        if frame[4] != 0x21 or frame[5] != 0x02 or frame[6] != 0x87:
            continue
        body = frame[7:-1]
        if len(body) >= 2 and body[:2] == b"\x01\x00":
            body = body[2:]
        if len(body) < 60:
            continue
        v = struct.unpack_from("<H", body, 0)[0] / 100
        i = struct.unpack_from("<h", body, 2)[0] / 100
        b1 = struct.unpack_from("<H", body, 20)[0] / 100
        b2 = struct.unpack_from("<H", body, 22)[0] / 100
        tot = struct.unpack_from("<I", body, 58)[0] / 100  # km
        tick = struct.unpack_from("<I", body, 54)[0]
        maxw = struct.unpack_from("<H", body, 36)[0] if len(body) >= 38 else 0
        o4_7 = " ".join(f"{b:02x}" for b in body[4:8])
        o8_11 = " ".join(f"{b:02x}" for b in body[8:12])
        o12_15 = " ".join(f"{b:02x}" for b in body[12:16])
        o26 = body[26]
        o28 = body[28]
        o38 = body[38] if len(body) > 38 else 0
        o68 = body[68] if len(body) > 68 else 0
        o74 = body[74] if len(body) > 74 else 0
        print(f"{fmt_ts(ts)}  {v:7.2f} {i:7.2f} {b1:6.2f} {b2:6.2f} "
              f"{tot:7.2f} {tick:5d} {maxw:5d} "
              f"{o4_7:>9s} {o8_11:>9s} {o12_15:>11s} "
              f"{o26:4d} {o28:4d} {o38:4d} {o68:4d} {o74:4d}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: p6_command_audit.py <log>", file=sys.stderr)
        sys.exit(1)
    main(sys.argv[1])
