"""Analyse the FINAL P6 btsnoop capture against the labelled video.

The user's video labels riding events as video timestamps. The capture
starts when BT was turned on (video 0:49). For each labelled event we
locate the sub-0x20 settings-response frame closest to its capture-time
and extract candidate fields by matching expected reference values.

Inputs:
    btsnoop log path
Outputs:
    - per-offset value summary (constant vs varying, range)
    - timeline of changing bytes vs labelled events
    - candidate matches for: motor temp, MOS temp, driver temp,
      voltage, current, total mileage, PWM, torque, speed (incl. reverse)
"""

import struct
import sys
from collections import Counter, defaultdict
from datetime import datetime, timedelta

BTSNOOP_EPOCH = datetime(1, 1, 1) + timedelta(microseconds=0xdcddb30f2f8000)


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


def to_dt(ts):
    return BTSNOOP_EPOCH + timedelta(microseconds=ts)


def main(path):
    frames = list(reasm(att(path)))
    # Find sub-0x20 responses (size 51 only — page 0x20 not 0x33)
    page20 = []
    for ts, dirn, frame in frames:
        if dirn != "RX" or len(frame) < 8 or frame[:2] != b"\xaa\xaa":
            continue
        if frame[4] != 0x21 or frame[5] != 0x02:
            continue
        if (frame[6] & 0x7F) != 0x20:
            continue
        body = frame[7:-1]
        if len(body) == 51 and body[0] == 0x20:
            page20.append((ts, body))

    if not page20:
        print("no sub-0x20 page-0x20 responses found")
        return

    t0 = page20[0][0]
    print(f"# {len(page20)} sub-0x20 page-0x20 responses (51 bytes each)")
    print(f"# capture window: {to_dt(page20[0][0])} -- {to_dt(page20[-1][0])}")
    print(f"# duration: {(page20[-1][0]-t0)/1_000_000:.1f}s")
    print()

    # Video-time → capture offset (BT on at video 0:49 = capture start t0)
    VIDEO_BT_ON = 49.0  # seconds
    labels = [
        # (video_seconds, label)
        (1*60+7,  "1:07 PWM 1.70-1.78% motor 124F mile 1801.8 speed 0"),
        (1*60+23, "1:23 MOS 82F mot 122-124F drv 91F I -0.12A V 209.7V"),
        (1*60+35, "1:35 PWM 1.75%"),
        (1*60+50, "1:50 torque 4.59-5.05Nm"),
        (2*60+2,  "2:02 2 mph in reverse"),
        (2*60+16, "2:16 end of reverse"),
    ]

    def at_video(vs):
        target_us = t0 + int((vs - VIDEO_BT_ON) * 1_000_000)
        return min(page20, key=lambda kv: abs(kv[0] - target_us))

    # ------- Per-offset variance summary -------
    print("## Byte variance across all frames")
    print("offset  distinct  min  max   range   sample (first 8)")
    samples_at_off = [[] for _ in range(51)]
    for _, body in page20:
        for i, b in enumerate(body):
            samples_at_off[i].append(b)
    varying_offsets = []
    for i in range(51):
        col = samples_at_off[i]
        distinct = sorted(set(col))
        mn, mx = min(col), max(col)
        rng = mx - mn
        sample = " ".join(f"{v:02x}" for v in col[:8])
        marker = " *vary*" if rng > 0 else ""
        print(f"  {i:2d}     {len(distinct):3d}    {mn:3d}  {mx:3d}  {rng:5d}   {sample}{marker}")
        if rng > 0:
            varying_offsets.append(i)
    print(f"\n## Varying offsets: {varying_offsets}")
    print()

    # ------- Show frames at each label -------
    print("## Frames at labelled video times")
    print()
    for vs, lbl in labels:
        ts, body = at_video(vs)
        rel = (ts - t0) / 1_000_000 + VIDEO_BT_ON
        print(f"### {lbl}")
        print(f"capture+{(ts-t0)/1_000_000:6.2f}s  video~{rel:6.2f}s  ({to_dt(ts).strftime('%H:%M:%S.%f')[:-3]})")
        print(f"body: {' '.join(f'{b:02x}' for b in body)}")
        print()

    # ------- Reference-value hunt (only on varying offsets) -------
    print("## Reference-value hunt")
    print()

    # frames bracketing 1:23 -> values at MOS 82F=27.8C, motor 124F=51.1C, drv 91F=32.8C
    # voltage 209.7V, current ~-0.15A
    target_123_ts, target_123 = at_video(1*60+23)
    target_135_ts, target_135 = at_video(1*60+35)  # PWM 1.75%
    target_107_ts, target_107 = at_video(1*60+7)   # mileage 1801.8mi, motor 124F, PWM ~1.74%
    target_150_ts, target_150 = at_video(1*60+50)  # torque ~5Nm

    # Convert mileage 1801.8 mi -> 2899.7 km. As u32 LE / 100 km = 289970,
    # or as u32 LE / 10 m = 2899700. As feet/0.01 mi etc.
    # PWM 1.75% as i16 LE / 100 = 175. As u16 = 175. As u8 = 175 -> not byte.
    # voltage 209.7V as u16 LE / 100 = 20970. Battery percentage?
    # current -0.15A as i16 LE / 100 = -15.
    # MOS 82F = 27.78C. As byte signed +80 → 28+80 = 108 = 0x6c.
    # motor 124F = 51.11C → 51+80 = 131 = 0x83. As byte 51 = 0x33. As u16 LE /10 = 511.
    # driver 91F = 32.78C → 33+80=113=0x71. Or 33=0x21.
    # torque 5 Nm -> as u16 LE / 100 = 500. As u16 LE / 10 = 50.

    refs_at = {
        "1:07 motor=124F": (target_107, [
            ("u16/100=1.74", "u16", lambda v: 170 <= v <= 180),
            ("u16=motor 124F=0x83 byte +80", "u8", lambda v: v in (0x83, 0x84, 0x85)),
            ("u8 motor 51C raw", "u8", lambda v: 50 <= v <= 53),
            ("u32/100km=2899.7", "u32", lambda v: 289800 <= v <= 290100),
            ("u32/10m=2899700 (m)", "u32", lambda v: 2898000 <= v <= 2901000),
            ("u32/100mi=180180", "u32", lambda v: 180100 <= v <= 180300),
        ]),
        "1:23 MOS 82F mot 124F drv 91F V 209.7": (target_123, [
            ("MOS 28C +80=0x6c", "u8", lambda v: v in (0x6b, 0x6c, 0x6d)),
            ("MOS 28C raw", "u8", lambda v: 27 <= v <= 30),
            ("motor 51C raw", "u8", lambda v: 50 <= v <= 53),
            ("motor 51C +80=0x83", "u8", lambda v: v in (0x83, 0x84)),
            ("drv 33C raw", "u8", lambda v: 32 <= v <= 34),
            ("drv 33C +80=0x71", "u8", lambda v: v in (0x70, 0x71, 0x72)),
            ("voltage u16/100=20970", "u16", lambda v: 20960 <= v <= 20980),
            ("voltage u16/10=2097", "u16", lambda v: 2095 <= v <= 2100),
            ("current i16/100=-15", "i16", lambda v: -20 <= v <= -10),
            ("MOS u16 LE 28*10=280", "u16", lambda v: 270 <= v <= 290),
            ("motor u16 LE 51*10=511", "u16", lambda v: 505 <= v <= 520),
            ("drv u16 LE 33*10=328", "u16", lambda v: 325 <= v <= 335),
        ]),
        "1:35 PWM 1.75%": (target_135, [
            ("u16/100=1.75", "u16", lambda v: 170 <= v <= 180),
            ("i16/100=1.75", "i16", lambda v: 170 <= v <= 180),
        ]),
        "1:50 torque 5Nm": (target_150, [
            ("u16/100=500", "u16", lambda v: 480 <= v <= 520),
            ("i16/100=500", "i16", lambda v: 480 <= v <= 520),
            ("u16/10=50", "u16", lambda v: 45 <= v <= 55),
            ("i16/10=50", "i16", lambda v: 45 <= v <= 55),
        ]),
    }

    def value_at(off, kind, body):
        if kind == "u8" and off + 1 <= len(body):
            return body[off]
        if kind == "u16" and off + 2 <= len(body):
            return struct.unpack_from("<H", body, off)[0]
        if kind == "i16" and off + 2 <= len(body):
            return struct.unpack_from("<h", body, off)[0]
        if kind == "u32" and off + 4 <= len(body):
            return struct.unpack_from("<I", body, off)[0]
        return None

    for label, (body, refs) in refs_at.items():
        print(f"### {label}")
        for desc, kind, pred in refs:
            hits = []
            for off in range(51):
                v = value_at(off, kind, body)
                if v is not None and pred(v):
                    hits.append((off, v))
            if hits:
                print(f"  {desc}: {hits}")
        print()

    # ------- Reverse window -------
    print("## Reverse window 2:00-2:20: per-frame values at varying offsets")
    rev_start = t0 + int((2*60+0 - VIDEO_BT_ON) * 1_000_000)
    rev_end = t0 + int((2*60+25 - VIDEO_BT_ON) * 1_000_000)
    in_rev = [(ts, body) for ts, body in page20 if rev_start <= ts <= rev_end]
    print(f"frames in window: {len(in_rev)}")
    print()
    if in_rev:
        # show offsets that vary in the reverse window
        rev_var = []
        for off in varying_offsets:
            col = [b[off] for _, b in in_rev]
            if len(set(col)) > 1:
                rev_var.append(off)
        print(f"offsets varying within reverse window: {rev_var}")
        print()
        for off in rev_var:
            seq = [(((ts-t0)/1_000_000 + VIDEO_BT_ON), b[off]) for ts, b in in_rev]
            seq_str = ", ".join(f"{vs:.1f}s={v}" for vs, v in seq)
            # also show as i16 LE if even
            if off + 2 <= 51:
                seq_i16 = [(vs, struct.unpack_from("<h", b, off)[0]) for vs, b in [((ts-t0)/1_000_000+VIDEO_BT_ON, b) for ts, b in in_rev]]
                i16_str = ", ".join(f"{vs:.1f}s={v}" for vs, v in seq_i16)
                print(f"off {off}: u8 {seq_str}")
                print(f"           i16 {i16_str}")
            else:
                print(f"off {off}: u8 {seq_str}")
        print()


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: p6_final_analyze.py <log>", file=sys.stderr)
        sys.exit(1)
    main(sys.argv[1])
