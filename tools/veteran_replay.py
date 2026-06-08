"""Replay a diagnostics .txt against the no-timeout Veteran reassembler.

Reads RX lines (with timestamps) from a service-mode diagnostic file, mimics
VeteranParser's feed/tryExtractFrame logic byte-for-byte, decodes base
telemetry (voltage, speed, current, temp, page selector, Oryx SoC) from each
reassembled frame, and writes a CSV of the per-frame results.

Use to verify that a captured stream produces clean, steady telemetry under
the new parser before shipping a fix.

    python tools/veteran_replay.py <diagnostic.txt> [out.csv]
"""

import re
import sys
import zlib
from pathlib import Path

MAGIC = b"\xdc\x5a\x5c"
LONG_FRAME_THRESHOLD = 38  # LEN > this => long frame with CRC32 trailer

RX_RE = re.compile(r"^(\d{2}:\d{2}:\d{2}\.\d{3})\s+RX\s+\d+\s+(.*)$")


def parse_rx_lines(path):
    """Yield (timestamp_ms, bytes) for every RX entry in the diagnostic."""
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        m = RX_RE.match(line)
        if not m:
            continue
        ts = m.group(1)
        h, mi, s = ts.split(":")
        sec, ms = s.split(".")
        t_ms = ((int(h) * 3600 + int(mi) * 60 + int(sec)) * 1000) + int(ms)
        b = bytes.fromhex(m.group(2).replace(" ", ""))
        yield t_ms, b


def reassemble(rx_stream):
    """Yield (timestamp_ms, frame_bytes) for every complete frame.

    Mirrors VeteranParser.feed + tryExtractFrame with no timeout: bytes
    accumulate until a magic-aligned frame of LEN+4 bytes either passes CRC
    (long) or is accepted as-is (short). Bad bytes self-heal via the
    magic-trim loop.
    """
    buf = bytearray()
    for t_ms, chunk in rx_stream:
        for byte in chunk:
            buf.append(byte)
            # Trim leading garbage until buf starts with magic prefix.
            while buf and not (
                buf[: min(len(buf), 3)] == MAGIC[: min(len(buf), 3)]
            ):
                buf.pop(0)
            # Try to extract complete frames off the front.
            while True:
                if len(buf) < 4:
                    break
                length = buf[3]
                total = length + 4
                if len(buf) < total:
                    break
                frame = bytes(buf[:total])
                del buf[:total]
                if length > LONG_FRAME_THRESHOLD:
                    crc = zlib.crc32(frame[:length]) & 0xFFFFFFFF
                    expected = int.from_bytes(frame[length : length + 4], "big")
                    if crc != expected:
                        # Bad frame -- drop and let the next magic re-sync us.
                        continue
                yield t_ms, frame


def decode(frame):
    """Return a dict with the base telemetry fields the parser pulls."""
    voltage_cv = int.from_bytes(frame[4:6], "big")
    raw_speed = int.from_bytes(frame[6:8], "big", signed=True)
    raw_current = int.from_bytes(frame[16:18], "big", signed=True)
    raw_temp = int.from_bytes(frame[18:20], "big", signed=True)
    raw_version = (
        int.from_bytes(frame[28:30], "big") if len(frame) >= 30 else 0
    )
    page = frame[46] if len(frame) >= 47 else -1
    oryx_soc = None
    if raw_version // 1000 == 8 and page == 2 and len(frame) >= 51:
        soc = frame[50]
        if 0 <= soc <= 100:
            oryx_soc = soc
    return {
        "voltage_v": voltage_cv / 100.0,
        "speed_kmh_signed": raw_speed / 10.0,
        # Repository applies abs() before showing on the UI.
        "speed_kmh_abs": abs(raw_speed) / 10.0,
        "current_a": raw_current / 10.0,
        "temp_c": raw_temp / 100.0,
        "mver": raw_version / 1000,
        "page": page,
        "oryx_soc": oryx_soc,
    }


def fmt_ts(t_ms):
    h, rem = divmod(t_ms, 3_600_000)
    m, rem = divmod(rem, 60_000)
    s, ms = divmod(rem, 1000)
    return f"{h:02d}:{m:02d}:{s:02d}.{ms:03d}"


def main():
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        sys.exit(1)
    inp = Path(sys.argv[1])
    out = (
        Path(sys.argv[2])
        if len(sys.argv) >= 3
        else inp.with_suffix(".veteran.csv")
    )

    rx = list(parse_rx_lines(inp))
    print(f"RX entries: {len(rx)}", file=sys.stderr)

    frames = list(reassemble(rx))
    print(f"Reassembled frames: {len(frames)}", file=sys.stderr)

    rows = [
        (
            "timestamp",
            "frame_len",
            "voltage_v",
            "speed_kmh_signed",
            "speed_kmh_abs",
            "current_a",
            "temp_c",
            "mver",
            "page",
            "oryx_soc",
        )
    ]
    speeds = []
    for t_ms, frame in frames:
        d = decode(frame)
        rows.append(
            (
                fmt_ts(t_ms),
                len(frame),
                f"{d['voltage_v']:.2f}",
                f"{d['speed_kmh_signed']:.1f}",
                f"{d['speed_kmh_abs']:.1f}",
                f"{d['current_a']:.1f}",
                f"{d['temp_c']:.2f}",
                d["mver"],
                d["page"],
                d["oryx_soc"] if d["oryx_soc"] is not None else "",
            )
        )
        speeds.append(d["speed_kmh_abs"])

    out.write_text("\n".join(",".join(str(c) for c in r) for r in rows))
    print(f"Wrote {out}", file=sys.stderr)

    if speeds:
        n = len(speeds)
        zeros = sum(1 for s in speeds if s == 0.0)
        big = sum(1 for s in speeds if s > 100.0)
        avg = sum(speeds) / n
        print(
            f"frames={n}  zeros={zeros}  >100kmh={big}  "
            f"avg_speed_abs={avg:.1f}kmh  "
            f"min={min(speeds):.1f}  max={max(speeds):.1f}",
            file=sys.stderr,
        )


if __name__ == "__main__":
    main()
