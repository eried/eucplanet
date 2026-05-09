"""Search for torque (Nm) offset in P6 realtime 96-byte bodies.
Score = Pearson correlation with current + magnitude plausibility.
Tries i16 LE /100, i16 LE /10, u16 LE /100. Stdlib + ASCII only."""

import math, struct, sys
from collections import Counter


def iter_btsnoop(path):
    with open(path, "rb") as f:
        if f.read(8) != b"btsnoop\x00":
            raise SystemExit("bad magic")
        f.read(8)
        while True:
            hdr = f.read(24)
            if len(hdr) < 24:
                return
            _, incl, _, _, ts = struct.unpack(">IIIIq", hdr)
            p = f.read(incl)
            if len(p) < incl:
                return
            yield ts, p


def att(path):
    for ts, p in iter_btsnoop(path):
        if not p or p[0] != 0x02 or len(p) < 9:
            continue
        l2 = struct.unpack("<H", p[5:7])[0]
        cid = struct.unpack("<H", p[7:9])[0]
        body = p[9:9 + l2]
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
    for ts, d, val in events:
        buf = bufs[d]
        if val.startswith(b"\xaa\xaa") and buf:
            yield (last[d], d, bytes(buf))
            buf.clear()
        buf.extend(val)
        last[d] = ts
        if len(buf) >= 5 and len(buf) >= 5 + buf[3]:
            total = 5 + buf[3]
            yield (last[d], d, bytes(buf[:total]))
            tail = bytes(buf[total:])
            buf.clear()
            buf.extend(tail)


def collect_bodies(path):
    out = []
    for ts, d, fr in reasm(att(path)):
        if d != "RX" or len(fr) < 9 or fr[4] != 0x21 \
                or fr[5] != 0x02 or (fr[6] & 0x7F) != 0x07:
            continue
        body = fr[7:-1]
        if len(body) >= 2 and body[:2] == b"\x01\x00":
            body = body[2:]
        out.append((ts, body))
    return out


def pearson(xs, ys):
    n = len(xs)
    if n == 0:
        return 0.0
    mx = sum(xs) / n
    my = sum(ys) / n
    num = dx2 = dy2 = 0.0
    for x, y in zip(xs, ys):
        a, b = x - mx, y - my
        num += a * b
        dx2 += a * a
        dy2 += b * b
    return 0.0 if dx2 == 0 or dy2 == 0 else num / math.sqrt(dx2 * dy2)


def main(path):
    bodies = collect_bodies(path)
    common, _ = Counter(len(b) for _, b in bodies).most_common(1)[0]
    rows = [b for _, b in bodies if len(b) == common
            and abs(struct.unpack_from("<h", b, 2)[0]) <= 15000
            and abs(struct.unpack_from("<h", b, 8)[0]) <= 12000
            and abs(struct.unpack_from("<h", b, 12)[0]) <= 12000]
    if not rows:
        raise SystemExit("no realtime frames")

    cur = [struct.unpack_from("<h", b, 2)[0] for b in rows]
    spd = [struct.unpack_from("<h", b, 8)[0] for b in rows]
    print(f"# P6 torque finder")
    print(f"frames: {len(rows)} of length {common}")
    print(f"I (A): {min(cur)/100:.2f} .. {max(cur)/100:.2f}, "
          f"spd (km/h): {min(spd)/100:.2f} .. {max(spd)/100:.2f}")

    i_idle = [i for i, c in enumerate(cur) if abs(c) < 100 and abs(spd[i]) < 200]
    i_16 = [i for i, s in enumerate(spd) if 2200 < s < 3000]
    i_a = max(range(len(cur)), key=lambda i: cur[i])
    i_r = min(range(len(cur)), key=lambda i: cur[i])
    print(f"peak accel I={cur[i_a]/100:.1f} A spd={spd[i_a]/100:.1f} km/h")
    print(f"peak regen I={cur[i_r]/100:.1f} A spd={spd[i_r]/100:.1f} km/h\n")

    mapped = {0, 1, 2, 3, 8, 9, 12, 13, 20, 21, 22, 23,
              58, 59, 60, 61, 68}
    cands = []
    abs_cur = [abs(c) for c in cur]

    for off in range(common - 1):
        if off in mapped or off + 1 in mapped:
            continue
        i16 = [struct.unpack_from("<h", b, off)[0] for b in rows]
        u16 = [struct.unpack_from("<H", b, off)[0] for b in rows]
        if len(set(i16)) < 5:
            continue
        rc = pearson(i16, cur)
        rs = pearson(i16, spd)
        idle = [i16[i] for i in i_idle]
        im = sorted(idle)[len(idle)//2] if idle else 0
        ix = max(abs(v) for v in idle) if idle else 0
        ap, rp = i16[i_a], i16[i_r]
        for scl, hi_idle, lo_pk, hi_pk in [(100, 1500, 2500, 6000),
                                           (10, 150, 250, 600)]:
            pl = 0.0
            if abs(im) <= hi_idle and ix <= hi_idle * 2:
                pl += 1.0
            if lo_pk <= ap <= hi_pk:
                pl += 2.0
            elif lo_pk / 2 <= ap <= hi_pk * 1.5:
                pl += 1.0
            if -hi_pk <= rp <= -lo_pk:
                pl += 2.0
            elif -hi_pk * 1.5 <= rp <= -lo_pk / 2:
                pl += 1.0
            sc = pl + max(0.0, rc) * 3.0 + max(0.0, abs(rs)) * 0.5
            cands.append((sc, off, f"i16/{scl}", scl, rc, rs, im, ap, rp,
                          min(i16), max(i16), i16))
        # u16/100 view
        ru = pearson(u16, abs_cur)
        idleu = [u16[i] for i in i_idle]
        imu = sorted(idleu)[len(idleu)//2] if idleu else 0
        plu = 0.0
        if 0 <= imu <= 1500:
            plu += 1.0
        if 2500 <= max(u16) <= 6000:
            plu += 2.0
        scu = plu + max(0.0, ru) * 3.0
        if scu >= 1.0:
            cands.append((scu, off, "u16/100", 100, ru, 0.0, imu,
                          u16[i_a], u16[i_r], min(u16), max(u16), u16))

    cands.sort(reverse=True, key=lambda c: c[0])
    print("# Top candidates")
    print("rank | off | type    | r_cur  | r_spd  | min     max    "
          "| idle | accel_pk | regen_pk | score")
    for i, c in enumerate(cands[:12]):
        sc, off, t, _, rc, rs, im, ap, rp, mn, mx, _ = c
        print(f"{i+1:4d} | {off:3d} | {t:7s} | {rc:+0.3f} | {rs:+0.3f} | "
              f"{mn:6d}  {mx:6d} | {im:5d} | {ap:8d} | {rp:8d} | {sc:.2f}")

    print("\n# Top 3 detail")
    for i, c in enumerate(cands[:3]):
        sc, off, t, scl, rc, rs, im, ap, rp, mn, mx, col = c
        print(f"\n## #{i+1} offset {off} ({t})")
        if i_idle:
            sm = [col[j] for j in i_idle[:5]]
            print(f"  idle:    {sm}  Nm={[round(v/scl, 2) for v in sm]}")
        if i_16:
            s16 = [col[j] for j in i_16[:5]]
            print(f"  16 mph:  {s16}  Nm={[round(v/scl, 2) for v in s16]}")
        print(f"  accel_pk I={cur[i_a]/100:.1f} A -> {ap}  ({ap/scl:.2f} Nm)")
        print(f"  regen_pk I={cur[i_r]/100:.1f} A -> {rp}  ({rp/scl:.2f} Nm)")

    print("\n# I(A) vs top3, frames sorted by |I| (top 10):")
    top = cands[:3]
    print("  idx | I(A)   | spd     | " +
          " | ".join(f"off{c[1]}({c[2]})" for c in top))
    for j in sorted(range(len(rows)), key=lambda i: abs(cur[i]),
                    reverse=True)[:10]:
        ln = f"  {j:3d} | {cur[j]/100:+6.2f} | {spd[j]/100:+7.2f} | "
        for c in top:
            v = c[11][j]
            ln += f"{v:+6d}({v/c[3]:+6.2f}) | "
        print(ln)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1
         else r"D:\Downloads\P6 again\btsnoop_hci (1).log")
