"""Explore P6 realtime sub-0x07 frames in the 'P6 again' capture and
look for unmapped fields (temps, torque, signed speed sign).

The capture has ~209 realtime frames at 106 bytes wire size. After
stripping the V2 wrapper (`aa aa flag len 21 02 87 01 00`) the data
block is ~95 bytes. We already know:
    0..1   voltage  u16 LE / 100
    2..3   current  i16 LE / 100
    8..9   speed    u16 LE / 100  (suspected actually i16, see below)
    12..13 pwm      i16 LE / 100
    20..21 battery1 u16 LE / 100
    22..23 battery2 u16 LE / 100
    58..61 total km u32 LE / 100
    68     pcMode   0x0f=park, else=drive

For the rest, this script:
- Prints byte variance across all frames (which bytes change?)
- Looks for plausible temperature fields (signed byte +80 in 60-130
  range, OR raw byte in 20-90C range, slowly changing)
- Looks for torque (i16 LE matching motor power / current * pitch)
- Detects speed sign by checking sign of int16 LE at the speed offset
  in frames where current is large negative (= regen / reverse).
"""

import struct
import sys
from collections import Counter, defaultdict


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


def main(path):
    frames = list(reasm(att(path)))
    realtime_bodies = []
    for ts, dirn, frame in frames:
        if dirn != "RX" or len(frame) < 9:
            continue
        if frame[4] != 0x21 or frame[5] != 0x02:
            continue
        if (frame[6] & 0x7F) != 0x07:
            continue
        body = frame[7:-1]  # strip CRC
        # Strip leading 01 00 (status pair)
        if len(body) >= 2 and body[:2] == b"\x01\x00":
            body = body[2:]
        realtime_bodies.append((ts, body))

    body_lens = Counter(len(b) for _, b in realtime_bodies)
    print(f"realtime bodies: {len(realtime_bodies)}")
    print(f"length distribution: {dict(body_lens)}")
    common_len, _ = body_lens.most_common(1)[0]
    aligned = [(t, b) for t, b in realtime_bodies if len(b) == common_len]
    print(f"using {len(aligned)} frames of length {common_len}")
    print()

    # Per-byte variance
    print("offset variance (only bytes that change):")
    print("off | distinct | min  max  range | as i8 min/max | as i16 LE min/max | as u16 LE min/max")
    print("----+----------+-----------------+---------------+-------------------+-------------------")
    interesting = []
    for off in range(common_len):
        col = [b[off] for _, b in aligned]
        if len(set(col)) <= 1:
            continue
        mn, mx = min(col), max(col)
        i8_col = [v if v < 128 else v - 256 for v in col]
        i8_mn, i8_mx = min(i8_col), max(i8_col)
        # i16 view at off (if not last)
        i16_str = ""
        u16_str = ""
        if off + 2 <= common_len:
            i16_col = [struct.unpack_from("<h", b, off)[0] for _, b in aligned]
            u16_col = [struct.unpack_from("<H", b, off)[0] for _, b in aligned]
            i16_str = f"{min(i16_col):6d}/{max(i16_col):6d}"
            u16_str = f"{min(u16_col):5d}/{max(u16_col):5d}"
        print(f" {off:3d}|  {len(set(col)):4d}    | {mn:3d}  {mx:3d}  {mx-mn:5d} | {i8_mn:4d}/{i8_mx:4d}     | {i16_str}     | {u16_str}")
        interesting.append(off)
    print()

    # Speed sign detection: look at frames where current is most-negative
    # (heavy regen brake) and most-positive (acceleration). If speed at 8-9
    # is i16 with sign tied to direction, extreme regen + reverse direction
    # would give negative i16.
    print("Speed-sign analysis (i16 LE at offset 8-9):")
    speeds_i16 = [(t, struct.unpack_from("<h", b, 8)[0],
                   struct.unpack_from("<h", b, 2)[0],
                   struct.unpack_from("<h", b, 12)[0])
                  for t, b in aligned]
    neg = [(t, sp, cu, pw) for t, sp, cu, pw in speeds_i16 if sp < 0]
    print(f"  frames where i16 speed < 0: {len(neg)} / {len(speeds_i16)}")
    if neg:
        for t, sp, cu, pw in neg[:8]:
            print(f"    speed={sp/100:.2f} km/h  current={cu/100:.2f} A  pwm={pw/100:.2f}%")
    else:
        print("  none — capture has no reverse direction.")
    # Highest forward speeds (sanity)
    speeds_sorted = sorted(speeds_i16, key=lambda x: x[1])
    print(f"  speed range as i16: {speeds_sorted[0][1]/100:.2f} .. {speeds_sorted[-1][1]/100:.2f} km/h")
    print(f"  speed range as u16: {min(struct.unpack_from('<H', b, 8)[0] for _, b in aligned)/100:.2f} .. {max(struct.unpack_from('<H', b, 8)[0] for _, b in aligned)/100:.2f} km/h")
    print()

    # Temperature hunt: look for bytes where the value is in 50-150 range
    # AND moves slowly (low std-dev between adjacent frames).
    print("Temperature candidates (byte stays in 60..130 range and changes slowly):")
    for off in range(common_len):
        col = [b[off] for _, b in aligned]
        if min(col) < 30 or max(col) > 150:
            continue
        if len(set(col)) < 3 or len(set(col)) > 30:
            continue
        # Adjacent diffs
        diffs = [abs(col[i+1] - col[i]) for i in range(len(col)-1)]
        if not diffs:
            continue
        mean_diff = sum(diffs) / len(diffs)
        max_diff = max(diffs)
        if mean_diff < 1.5 and max_diff < 8:
            mn, mx = min(col), max(col)
            # As celsius (raw byte)
            c_mn_raw, c_mx_raw = mn, mx
            # As celsius (signed +80 offset)
            c_mn_off = mn - 80
            c_mx_off = mx - 80
            print(f"  off {off}: byte {mn}..{mx} (mean delta {mean_diff:.2f}, max delta {max_diff})")
            print(f"           raw C: {c_mn_raw}..{c_mx_raw} | +80 signed C: {c_mn_off}..{c_mx_off} | F (raw): {c_mn_raw*9/5+32:.0f}..{c_mx_raw*9/5+32:.0f}")
    print()

    # Torque hunt: i16 LE that scales with current * speed (motor torque proxy)
    print("First 5 frames hex dump:")
    for t, b in aligned[:5]:
        print("  " + " ".join(f"{x:02x}" for x in b))
    print()


if __name__ == "__main__":
    main(sys.argv[1])
