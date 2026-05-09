"""Analyze D:/Downloads/FINALP6/btsnoop_hci.log.last using user video labels.

Anchor: the user labelled "4:00 sport / 4:04 comfort / 4:08 sport / 4:12 comfort"
(four mode toggles in 14 seconds). The BLE log shows four `0x24` toggles in
that window, evenly spaced. We use the first `0x24 [00]` write as the
anchor for user-label v4:00, sidestepping the year-3997 epoch math.

User labels (FINAL video, all relative to that anchor):
    1:07  PWM 1.70-1.78%, motor 124F, total 1801.8mi, speed 0
    1:23  MOS 82F, motor 122-124F, driver 91F, current -0.18A, V 209.7V
    1:35  PWM 1.75%
    1:50  torque 4.59-5.05 Nm
    2:02  reverse 2 mph
    2:16  end of reverse
    3:20  DRL off + auto-light off
    3:26  light on/off 4 times -> end OFF
    3:50  DRL + auto-light on
    4:00  sport
    4:04  comfort -> sport -> comfort -> sport
    4:22  park
"""

import struct
import sys


def iter_btsnoop(p):
    with open(p, "rb") as f:
        f.read(8); f.read(8)
        while True:
            h = f.read(24)
            if len(h) < 24:
                return
            _, i, fl, _, t = struct.unpack(">IIIIq", h)
            d = f.read(i)
            if len(d) < i:
                return
            yield t, fl, d


def att(p):
    for t, fl, d in iter_btsnoop(p):
        if not d or d[0] != 0x02 or len(d) < 9:
            continue
        l = struct.unpack("<H", d[5:7])[0]
        cid = struct.unpack("<H", d[7:9])[0]
        b = d[9:9 + l]
        if cid != 4 or not b:
            continue
        op = b[0]
        if op in (0x12, 0x52):
            yield t, "TX", b[3:]
        elif op == 0x1B:
            yield t, "RX", b[3:]


def reasm(events):
    bufs = {"TX": bytearray(), "RX": bytearray()}
    last = {"TX": 0, "RX": 0}
    for t, d, v in events:
        bf = bufs[d]
        if v.startswith(b"\xaa\xaa") and bf:
            yield last[d], d, bytes(bf)
            bf.clear()
        bf.extend(v)
        last[d] = t
        if len(bf) >= 5:
            tot = 5 + bf[3]
            if len(bf) >= tot:
                yield last[d], d, bytes(bf[:tot])
                tail = bytes(bf[tot:])
                bf.clear()
                bf.extend(tail)


def main():
    path = r"D:/Downloads/FINALP6/btsnoop_hci.log.last"
    frames = list(reasm(att(path)))

    # Realtime sub-0x07 frames, 96-byte body
    rt = []
    for t, d, fr in frames:
        if d != "RX" or len(fr) < 9:
            continue
        if fr[4] != 0x21 or fr[5] != 0x02:
            continue
        if (fr[6] & 0x7F) != 0x07:
            continue
        body = fr[7:-1]
        if len(body) >= 2 and body[:2] == b"\x01\x00":
            body = body[2:]
        if len(body) == 96:
            rt.append((t, body))
    print(f"realtime 96B frames: {len(rt)}")

    # Find anchor: first 0x24 TX write
    anchor_us = None
    for t, d, fr in frames:
        if d != "TX" or len(fr) < 8:
            continue
        if fr[4] != 0x02 or fr[5] != 0x21 or fr[6] != 0x60:
            continue
        if len(fr) < 8 or fr[7] != 0x24:
            continue
        anchor_us = t
        anchor_arg = fr[8] if len(fr) >= 9 else None
        print(f"anchor: first 0x24 write at btsnoop ts {t}, arg=0x{anchor_arg:02x}")
        break
    if anchor_us is None:
        print("no 0x24 write found")
        return

    # user-label v4:00 -> anchor_us
    # so user-label seconds(v) -> anchor_us + (v - 240) * 1e6
    USER_ANCHOR_SEC = 4 * 60

    def vt_of_ts(ts_us):
        return USER_ANCHOR_SEC + (ts_us - anchor_us) / 1_000_000

    def closest(vs):
        target_ts = anchor_us + (vs - USER_ANCHOR_SEC) * 1_000_000
        return min(rt, key=lambda kv: abs(kv[0] - target_ts))

    print(f"realtime span (user-label time): v{vt_of_ts(rt[0][0]):.1f}s ... v{vt_of_ts(rt[-1][0]):.1f}s")
    print()

    # ---- Task 1: temp encoding via v1:23 (MOS 27.8 / driver 32.8 / motor 51.1) ----
    print("=" * 70)
    print("TEMPERATURE ENCODING at user v1:23 (MOS=27.8C, driver=32.8C, motor=51.1C)")
    print("=" * 70)
    t, body = closest(83)
    print(f"closest realtime frame: v{vt_of_ts(t):.1f}s (target v1:23 = 83s)")
    print(f"all 96 bytes: {' '.join(f'{b:02x}' for b in body)}")
    print()
    print("Per-byte encoding hits (delta < 1.5C from any reference):")
    refs = [("MOS", 27.8), ("driver", 32.8), ("motor", 51.1)]
    for off in range(96):
        b = body[off]
        for label, ref_c in refs:
            for desc, val in [("byte", b), ("byte-160", b - 160), ("byte-175", b - 175),
                              ("byte-180", b - 180), ("byte/4", b / 4),
                              ("byte/2", b / 2), ("(byte-32)*5/9", (b - 32) * 5 / 9),
                              ("byte-128", b - 128)]:
                if isinstance(val, float):
                    err = abs(val - ref_c)
                    if err < 1.5:
                        print(f"  off {off:2d} byte 0x{b:02x}={b:3d}  -> {desc} = {val:.2f}C (matches {label} {ref_c}C, delta {val-ref_c:+.2f})")
                else:
                    err = abs(val - ref_c)
                    if err < 1.5:
                        print(f"  off {off:2d} byte 0x{b:02x}={b:3d}  -> {desc} = {val}C   (matches {label} {ref_c}C, delta {val-ref_c:+.2f})")
    print()

    # ---- Task 2: verify torque + drive flag at v1:50 (idle, parked, 4.59-5.05 Nm) ----
    print("=" * 70)
    print("TORQUE at user v1:50 (idle parked, ref 4.59-5.05 Nm)")
    print("=" * 70)
    t, body = closest(110)
    print(f"closest frame: v{vt_of_ts(t):.1f}s")
    print(f"i16 LE at every offset 0..94 — looking for ~4.59-5.05 Nm at /100, or 459-505 at /1:")
    for off in range(0, 95):
        v = struct.unpack_from("<h", body, off)[0]
        if 459 <= abs(v) <= 510 or 45 <= abs(v) <= 51:
            scale = 100 if 459 <= abs(v) <= 510 else 10
            print(f"  off {off}: i16={v} (= {v/scale:+.2f} Nm with /{scale})")
    # also dump our current parser claim (offset 18-19 i16/100)
    tq18 = struct.unpack_from("<h", body, 18)[0] / 100
    print(f"  current parser reads off 18-19 = {tq18:.2f} Nm")
    print()

    # ---- Task 3: voltage/current at v1:23 (V 209.7V, I -0.18A) ----
    print("=" * 70)
    print("VOLTAGE/CURRENT at v1:23 (V 209.7V, I -0.18A)")
    print("=" * 70)
    t, body = closest(83)
    voltage = struct.unpack_from("<H", body, 0)[0] / 100
    current = struct.unpack_from("<h", body, 2)[0] / 100
    print(f"  current parser: V={voltage:.2f}V at off 0-1, I={current:+.2f}A at off 2-3")
    print(f"  reference: V=209.7V, I=-0.18A")
    print()

    # ---- Task 4: speed-sign at reverse window v2:02 to v2:16 ----
    print("=" * 70)
    print("REVERSE SPEED at v2:02-v2:16 (label '2 mph' = ~3.2 km/h reverse)")
    print("=" * 70)
    for vs in (2*60+2, 2*60+8, 2*60+12, 2*60+16):
        t, body = closest(vs)
        sp_i = struct.unpack_from("<h", body, 8)[0] / 100
        sp_u = struct.unpack_from("<H", body, 8)[0] / 100
        print(f"  v{vt_of_ts(t):.1f}s: i16 speed={sp_i:+6.2f} km/h | u16={sp_u:6.2f} km/h")
    print()

    # ---- Task 5: total mileage at v1:07 (1801.8 mi = 2900 km) ----
    print("=" * 70)
    print("TOTAL MILEAGE at v1:07 (1801.8 mi = 2900 km)")
    print("=" * 70)
    t, body = closest(67)
    print(f"  current parser off 58-61 u32/100: {struct.unpack_from('<I', body, 58)[0] / 100:.2f} km")
    # try alternative offsets too just in case
    for off in (54, 56, 58, 60, 62):
        v = struct.unpack_from("<I", body, off)[0]
        print(f"  off {off}: u32 LE = {v} (/100 = {v/100:.2f} km, /1000 = {v/1000:.2f} km, /160934 = {v/160934:.2f} mi)")


if __name__ == "__main__":
    main()
