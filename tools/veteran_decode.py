#!/usr/bin/env python3
"""
Veteran/LeaperKim decoder, replays a diagnostics .txt file through the same
reassembler logic the app uses (known short layouts with structural/physical
checks and CRC32 mandatory for LEN > 38). It prints decoded frames to
validate the decoder against real captures and pinpoint bad telemetry.

Usage: python veteran_decode.py <diagnostics.txt> [--all-frames]
"""
import re
import sys
import zlib

MAGIC = b"\xdc\x5a\x5c"
LONG_FRAME_THRESHOLD = 38

RX_LINE = re.compile(r"^\d\d:\d\d:\d\d\.\d+\s+(?:RX|RECV)\s+\d+\s+([0-9a-fA-F ]+)$")


def extract_rx_bytes(path):
    out = bytearray()
    with open(path, encoding="utf-8") as f:
        for line in f:
            m = RX_LINE.match(line.rstrip())
            if not m:
                continue
            hex_part = m.group(1).strip()
            out.extend(bytes.fromhex(hex_part.replace(" ", "")))
    return bytes(out)


def decode_base(frame):
    """Return (voltage_V, speed_kmh, current_A, temp_C) per the published spec."""
    v_raw = int.from_bytes(frame[4:6], "big", signed=False)
    s_raw = int.from_bytes(frame[6:8], "big", signed=True)
    c_raw = int.from_bytes(frame[16:18], "big", signed=True)
    t_raw = int.from_bytes(frame[18:20], "big", signed=True)
    return v_raw / 100.0, s_raw / 10.0, c_raw / 10.0, t_raw / 100.0

def valid_short(frame):
    if frame[3] not in (32, 34) or len(frame) != frame[3] + 4:
        return False
    voltage = int.from_bytes(frame[4:6], "big")
    speed = int.from_bytes(frame[6:8], "big", signed=True)
    current = int.from_bytes(frame[16:18], "big", signed=True)
    temp = int.from_bytes(frame[18:20], "big", signed=True)
    return (frame[22] == 0 and frame[23] & 0xFE == 0
            and frame[30] in (0, 7) and 5000 <= voltage <= 25000
            and abs(speed) <= 2000 and abs(current) <= 4000
            and -4000 <= temp <= 15000)


def run(buf, show_all=False):
    i = 0
    n = len(buf)
    accepted = []
    crc_fails = 0
    short_fails = 0
    skipped_bytes = 0

    while i < n:
        # Magic-trim: advance to next DC 5A 5C
        j = buf.find(MAGIC, i)
        if j < 0:
            skipped_bytes += n - i
            break
        if j > i:
            skipped_bytes += (j - i)
            i = j

        if i + 4 > n:
            break

        length = buf[i + 3]
        if length <= LONG_FRAME_THRESHOLD and length not in (32, 34):
            short_fails += 1
            i += 1
            continue
        total = length + 4
        if i + total > n:
            # not enough bytes for this frame, stop (in app we'd wait for more)
            break

        frame = buf[i : i + total]
        is_long = length > LONG_FRAME_THRESHOLD

        if is_long:
            expected = int.from_bytes(frame[length : length + 4], "big")
            got = zlib.crc32(bytes(frame[:length])) & 0xFFFFFFFF
            if got != expected:
                crc_fails += 1
                # Drop only the false magic, preserving any embedded real frame.
                i += 1
                continue
        elif not valid_short(frame):
            short_fails += 1
            i += 1
            continue
        v, s, c, t = decode_base(frame)
        accepted.append({
            "offset": i,
            "len": length,
            "total": total,
            "is_long": is_long,
            "v": v,
            "s": s,
            "c": c,
            "t": t,
            "raw_hex": frame.hex(),
        })
        i += total

    # Stats
    print(f"input bytes        : {n}")
    print(f"accepted frames    : {len(accepted)}")
    print(f"  long (CRC ok)    : {sum(1 for a in accepted if a['is_long'])}")
    print(f"  short (no CRC)   : {sum(1 for a in accepted if not a['is_long'])}")
    print(f"CRC failures       : {crc_fails}")
    print(f"skipped (no magic) : {skipped_bytes}")
    print(f"short validation failures: {short_fails}")

    if not accepted:
        return

    vs = [a["v"] for a in accepted]
    ss = [a["s"] for a in accepted]
    cs = [a["c"] for a in accepted]
    ts = [a["t"] for a in accepted]

    def stats(name, vals):
        print(f"  {name:8s}  min={min(vals):8.2f}  max={max(vals):8.2f}  avg={sum(vals)/len(vals):8.2f}")

    print()
    print("decoded ranges:")
    stats("voltage", vs)
    stats("speed",   ss)
    stats("current", cs)
    stats("temp",    ts)

    # Anything outside plausible bounds?
    bad = [a for a in accepted
           if not (50.0 <= a["v"] <= 250.0)
           or abs(a["s"]) > 200
           or abs(a["c"]) > 400
           or not (-40.0 <= a["t"] <= 150.0)]
    print()
    print(f"out-of-bounds accepted frames: {len(bad)}")
    for a in bad[:10]:
        print(f"  off={a['offset']} len={a['len']} long={a['is_long']} v={a['v']:.1f} s={a['s']:.1f} c={a['c']:.1f} t={a['t']:.2f}")
        print(f"    raw: {a['raw_hex']}")

    # Per-frame dump
    if show_all:
        print()
        print("all accepted frames:")
        for a in accepted:
            kind = "LONG" if a["is_long"] else "SHRT"
            print(f"  off={a['offset']:5d} len={a['len']:3d} {kind} v={a['v']:6.2f}V s={a['s']:6.1f}km/h c={a['c']:6.1f}A t={a['t']:6.2f}C")


def main():
    if len(sys.argv) < 2:
        print("usage: veteran_decode.py <diagnostics.txt> [--all-frames]", file=sys.stderr)
        sys.exit(1)
    show_all = "--all-frames" in sys.argv[2:]
    buf = extract_rx_bytes(sys.argv[1])
    if not buf:
        sys.exit("no RX/RECV bytes found; check diagnostic format")
    run(buf, show_all=show_all)


if __name__ == "__main__":
    main()
