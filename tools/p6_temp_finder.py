"""Hunt P6 realtime byte offsets that look like temperature sensors.

Loads the 'P6 again' btsnoop, isolates 96-byte realtime bodies (sub 0x07
responses, with the V2 wrapper + status pair stripped), then for every
byte offset 0..95 evaluates whether the column behaves like a temperature
sensor (slow drift, narrow band, rises during the ride window).

Wall-clock anchor: video 0:53 = btsnoop start, ride window 5:22..6:57.
That puts the ride window at ~269..364 s into the capture.

Constraints: stdlib only (struct, sys, collections, datetime). ASCII-only
output (Windows cp1252 console safe). Run:
    python tools/p6_temp_finder.py "D:/Downloads/P6 again/btsnoop_hci (1).log"
"""

import struct
import sys
from collections import Counter

RIDE_START_S = 5 * 60 + 22 - 53   # 269 s
RIDE_END_S = 6 * 60 + 57 - 53     # 364 s

KNOWN_OFFSETS = (
    set(range(0, 4)) | set(range(8, 10)) | set(range(12, 14))
    | set(range(20, 24)) | set(range(58, 62)) | {68}
)


def iter_btsnoop(path):
    with open(path, "rb") as f:
        if f.read(8) != b"btsnoop\x00":
            raise SystemExit("bad btsnoop magic")
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


def att_events(path):
    for ts, _flags, payload in iter_btsnoop(path):
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


def collect_realtime(path):
    bodies = []
    for ts, dirn, frame in reasm(att_events(path)):
        if dirn != "RX" or len(frame) < 9:
            continue
        if frame[4] != 0x21 or frame[5] != 0x02:
            continue
        if (frame[6] & 0x7F) != 0x07:
            continue
        body = frame[7:-1]  # strip CRC
        if len(body) >= 2 and body[:2] == b"\x01\x00":
            body = body[2:]
        bodies.append((ts, body))
    return bodies


def sparkline(values, width=80):
    if not values:
        return ""
    mn, mx = min(values), max(values)
    if mx == mn:
        return "_" * min(len(values), width)
    levels = " .:-=+*#%@"
    step = max(1, len(values) // width)
    sampled = [values[i] for i in range(0, len(values), step)][:width]
    return "".join(levels[int((v - mn) / (mx - mn) * (len(levels) - 1))]
                   for v in sampled)


def column_stats(col, ride_mask):
    diffs = [abs(col[i + 1] - col[i]) for i in range(len(col) - 1)]
    mean_d = sum(diffs) / len(diffs) if diffs else 0
    pre = [v for v, m in zip(col, ride_mask) if m == "pre"]
    ride = [v for v, m in zip(col, ride_mask) if m == "ride"]
    post = [v for v, m in zip(col, ride_mask) if m == "post"]
    return {
        "distinct": len(set(col)),
        "mn": min(col), "mx": max(col),
        "mean_d": mean_d,
        "ap": sum(pre) / len(pre) if pre else 0,
        "ar": sum(ride) / len(ride) if ride else 0,
        "ao": sum(post) / len(post) if post else 0,
    }


def temp_score(col, ride_mask):
    """Score how temperature-like a column is. Returns 0 for clearly not-temp."""
    if len(set(col)) < 3 or len(set(col)) > 40:
        return 0
    s = column_stats(col, ride_mask)
    band = s["mx"] - s["mn"]
    if not (3 <= band <= 30):
        return 0
    if s["mean_d"] > 3.0:
        return 0
    score = 0.0
    if s["mean_d"] < 1.0:
        score += 2
    elif s["mean_d"] < 2.0:
        score += 1
    rise = s["ao"] - s["ar"]  # post - ride mean (ride heats sensor up)
    if 0.5 <= rise <= 15:
        score += 2
    rise2 = s["ar"] - s["ap"]
    if rise2 > 0.5:
        score += 1
    return score


def main(path):
    bodies = collect_realtime(path)
    lens = Counter(len(b) for _, b in bodies)
    if not lens:
        raise SystemExit("no realtime bodies found")
    common_len, _ = lens.most_common(1)[0]
    aligned = [(t, b) for t, b in bodies if len(b) == common_len]
    if not aligned:
        raise SystemExit("no aligned bodies")

    t0 = aligned[0][0]
    rel = [(t - t0) / 1_000_000.0 for t, _ in aligned]

    def phase(s):
        if s < RIDE_START_S:
            return "pre"
        if s <= RIDE_END_S:
            return "ride"
        return "post"

    ride_mask = [phase(s) for s in rel]
    n_pre = ride_mask.count("pre")
    n_ride = ride_mask.count("ride")
    n_post = ride_mask.count("post")

    # Score every offset for temperature-shape and pick the top few.
    scored = []
    for off in range(common_len):
        if off in KNOWN_OFFSETS:
            continue
        col = [b[off] for _, b in aligned]
        sc = temp_score(col, ride_mask)
        if sc > 0:
            scored.append((off, sc, column_stats(col, ride_mask), col))
    scored.sort(key=lambda x: (-x[1], x[0]))
    top = scored[:5]

    print("# P6 temperature offset hunt")
    print()
    print(f"- frames: {len(aligned)} aligned at body length {common_len}")
    print(f"- ride window {RIDE_START_S}..{RIDE_END_S} s "
          f"(pre={n_pre}, ride={n_ride}, post={n_post})")
    print(f"- offsets passing temp-shape filter: {len(scored)}")
    print()

    print("## Top candidates (slow drift + warming during ride)")
    print()
    print("| off | score | distinct | byte min..max | pre / ride / post mean | rise pre->post | mean delta |")
    print("|----:|------:|---------:|--------------:|:----------------------:|---------------:|-----------:|")
    for off, sc, s, _ in top:
        print(f"| {off} | {sc:.1f} | {s['distinct']} | {s['mn']}..{s['mx']} | "
              f"{s['ap']:.1f} / {s['ar']:.1f} / {s['ao']:.1f} | "
              f"{s['ao'] - s['ap']:+.2f} | {s['mean_d']:.2f} |")
    print()

    # Trio analysis: 30/31/32 cluster together. Print sampled timeline.
    print("## Sampled timeline at offsets 30 / 31 / 32 (suspected temp triple)")
    print()
    print("Reference (different session, same wheel): MOS 27.8 C, driver 32.8 C, motor 50.5 C")
    print()
    print("| frame | t (s) | phase | b30 | b31 | b32 |")
    print("|------:|------:|:-----:|----:|----:|----:|")
    n = len(aligned)
    sample_indices = [0, 5, 15, 17, 30, 50, 70, 90, 99, 110, 130, 150, 200, n - 1]
    for i in sample_indices:
        if i >= n:
            continue
        b = aligned[i][1]
        print(f"| {i} | {rel[i]:.0f} | {ride_mask[i]} | {b[30]} | {b[31]} | {b[32]} |")
    print()

    # ASCII traces for the top candidates.
    print("## ASCII traces (one tick per frame, height = value)")
    print()
    for off, sc, s, col in top:
        print(f"### offset {off}  byte {s['mn']}..{s['mx']}  "
              f"distinct={s['distinct']}  mean_delta={s['mean_d']:.2f}")
        print(f"  `{sparkline(col, 80)}`")
        # Simple encoding hypotheses
        ride_post_mean = (s['ar'] + s['ao']) / 2
        print(f"  encoding hints (ride+post mean byte {ride_post_mean:.1f}):")
        print(f"    as byte-160 -> {ride_post_mean - 160:.1f} C   "
              f"as byte-175 -> {ride_post_mean - 175:.1f} C   "
              f"as byte/4   -> {ride_post_mean / 4:.1f} C")
        print()

    print("## Interpretation")
    print()
    print("MOS coolest, driver mid, motor hottest. Look for 3 stratified offsets")
    print("that move slowly and warm up during the ride window. Encoding K (offset")
    print("subtracted from raw byte) needs a second labelled capture to confirm.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: python tools/p6_temp_finder.py <btsnoop.log>")
        sys.exit(1)
    main(sys.argv[1])
