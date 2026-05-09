"""Find P6 realtime bytes that distinguish ride modes (Sport vs
Comfort, Park vs Drive). Re-verifies offset 68. Wall = video + 13:24:13.
Windows: 5:35..6:26 sport1 ride, 6:32..6:41 park/sport toggle, 6:43..6:57
sport2 ride."""

import struct
import sys
import datetime as dt
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
            yield (last[dirn], dirn, bytes(buf)); buf.clear()
        buf.extend(value); last[dirn] = ts
        if len(buf) >= 5:
            total = 5 + buf[3]
            if len(buf) >= total:
                yield (last[dirn], dirn, bytes(buf[:total]))
                tail = bytes(buf[total:]); buf.clear(); buf.extend(tail)


def realtime_frames(path):
    out = []
    for ts, dirn, frame in reasm(att(path)):
        if dirn != "RX" or len(frame) < 9:
            continue
        if frame[4] != 0x21 or frame[5] != 0x02 or (frame[6] & 0x7F) != 0x07:
            continue
        body = frame[7:-1]
        if body[:2] == b"\x01\x00":
            body = body[2:]
        out.append((ts, body))
    return out


BTSNOOP_EPOCH = dt.datetime(1, 1, 1)


def vsec(mmss):
    m, s = mmss.split(":")
    return int(m) * 60 + int(s)


def video_anchor(first_ts):
    """Return video_seconds_of_first_frame: wall - 13:24:13."""
    first_wall = BTSNOOP_EPOCH + dt.timedelta(microseconds=first_ts)
    fw = first_wall.time()
    fw_s = fw.hour * 3600 + fw.minute * 60 + fw.second + fw.microsecond / 1e6
    return fw_s - (13 * 3600 + 24 * 60 + 13)


def main(path):
    rt = realtime_frames(path)
    if not rt:
        raise SystemExit("no realtime frames")
    common_len = Counter(len(b) for _, b in rt).most_common(1)[0][0]
    rt = [(t, b) for t, b in rt if len(b) == common_len]
    first_ts = rt[0][0]
    v0 = video_anchor(first_ts)
    print(f"realtime frames: {len(rt)} of length {common_len}")
    print(f"first frame is at video time {int(v0)//60}:{int(v0)%60:02d}")

    def vid(t):
        return v0 + (t - first_ts) / 1e6

    def slice_v(lo, hi):
        ls = vsec(lo) if isinstance(lo, str) else lo
        hs = vsec(hi) if isinstance(hi, str) else hi
        return [(t, b) for t, b in rt if ls <= vid(t) <= hs]

    windows = [
        ("pre-ride", slice_v(0, 90)),
        ("sport1", slice_v("5:35", "6:26")),
        ("toggle", slice_v("6:32", "6:41")),
        ("sport2", slice_v("6:43", "6:57")),
        ("after", slice_v("7:00", "9:30")),
    ]
    pre_ride, sport1, toggle, sport2, after = (w[1] for w in windows)
    for n, fl in windows:
        print(f"  {n:9s}: {len(fl)} frames")
    print()

    def col(frames, off):
        return [b[off] for _, b in frames]

    print("=== offset 68 re-verification (existing 'park/drive' byte) ===")
    for name, fl in windows:
        if not fl:
            continue
        items = ", ".join(f"{v:#04x}:{n}" for v, n in Counter(col(fl, 68)).most_common())
        print(f"  {name:9s}: {items}")
    print()

    # Score every byte by how cleanly it splits "engaged-driving" frames
    # (sport1 + sport2) from "still-on-pedals-but-stopped" frames inside
    # the toggle window. The toggle window contains both stopped and ride
    # samples: take only those with |speed| < 0.5 km/h as "park" candidates.
    def stopped(fl):
        return [(t, b) for t, b in fl
                if abs(struct.unpack_from("<h", b, 8)[0]) < 100
                and abs(struct.unpack_from("<h", b, 2)[0]) < 200]

    def moving(fl):
        return [(t, b) for t, b in fl
                if 500 <= struct.unpack_from("<h", b, 8)[0] <= 7000]

    park_set = stopped(toggle)
    drive_set = moving(sport1) + moving(sport2)
    print(f"toggle-stopped frames (|speed|<1, |current|<2): {len(park_set)}")
    print(f"sport1+sport2 moving frames (5..70 km/h): {len(drive_set)}")
    print()

    print("=== bytes that split park-stopped vs sport-moving ===")
    print("(park >=40%, drive >=80% concentration; off | park | drive)")
    found = False
    for off in range(common_len):
        if not park_set or not drive_set:
            break
        cp, cd = Counter(col(park_set, off)), Counter(col(drive_set, off))
        vp, np_ = cp.most_common(1)[0]
        vd, nd = cd.most_common(1)[0]
        if vp == vd:
            continue
        pp, pd = np_ / sum(cp.values()), nd / sum(cd.values())
        if pp >= 0.4 and pd >= 0.8:
            print(f" {off:3d} | {vp:#04x}({np_}/{sum(cp.values())}) | {vd:#04x}({nd}/{sum(cd.values())})")
            found = True
    if not found:
        print("  (none)")
    print()

    # Within-sport: slow (5:35..5:50, 8-12 mph) vs fast (6:14..6:22, 30-42 mph)
    slow, fast = moving(slice_v("5:35", "5:50")), moving(slice_v("6:14", "6:22"))
    print(f"=== within-sport variation (slow={len(slow)}f vs fast={len(fast)}f) ===")
    print("off | slow top     | fast top     | ride-distinct (skip >12)")
    for off in range(common_len):
        if not slow or not fast:
            break
        cs, cf = Counter(col(slow, off)), Counter(col(fast, off))
        vs, ns = cs.most_common(1)[0]
        vf, nf = cf.most_common(1)[0]
        if vs == vf:
            continue
        rd = len(set(col(sport1, off) + col(sport2, off)))
        if rd > 12:
            continue
        if ns / sum(cs.values()) < 0.6 or nf / sum(cf.values()) < 0.6:
            continue
        print(f" {off:3d} | {vs:#04x}({ns}/{sum(cs.values())})    | "
              f"{vf:#04x}({nf}/{sum(cf.values())})    | {rd}")
    print()

    # Static-config candidates: bytes constant across all 250 frames.
    print("=== bytes constant across all 250 frames (non-zero) ===")
    consts = [(off, next(iter(s))) for off in range(common_len)
              for s in [set(col(rt, off))] if len(s) == 1 and next(iter(s)) != 0]
    print("  " + (", ".join(f"off{o}={v:#04x}" for o, v in consts) or "(none)"))
    print()


if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else r"D:\Downloads\P6 again\btsnoop_hci (1).log"
    main(path)
