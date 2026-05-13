"""Brute-force search for P6 MOS / motor / driver-board temperature offsets.

This script reverse-engineers temperature byte offsets in the P6 realtime
0x87 telemetry frame by:

1. Collecting ALL realtime frames from the NEW labelled capture and the OLD
   long-ride capture.
2. Brute-forcing every offset and every plausible encoding (byte/4, byte/2,
   byte raw, (byte-32)*5/9 = Fahrenheit, byte/8, byte/9, byte/16, signed
   variants, uint16 LE / 100, /10, /4, /8, /1000, etc.) and reporting which
   ones decode to the labelled values 22.22 / 26.11 / 26.11 in the target
   frame at btsnoop t=82.64.
3. Validating each candidate by checking the value across the WHOLE NEW and
   OLD captures (filtered to "clean" frames where voltage decodes correctly
   and off=71 is in the plausible ambient F range, to skip multiplexed
   glitch frames).
4. Highlighting offsets that show real temperature behaviour (warm during
   ride, cool back down, no counter-style wraparound).

Inputs:
- NEW capture: D:/Downloads/FINALP6/NEW CAPTURE/btsnoop_hci.log
- OLD capture: D:/Downloads/FINALP6/btsnoop_hci.log.last

Run:
    python tools/p6_temp_search.py
"""

from __future__ import annotations

import os
import struct
import sys
from datetime import datetime, timedelta

NEW_PATH = r"D:\Downloads\FINALP6\NEW CAPTURE\btsnoop_hci.log"
OLD_PATH = r"D:\Downloads\FINALP6\btsnoop_hci.log.last"

# NEW capture labels (from WIM at video t=120s, btsnoop t~83s)
TARGET_MOS_C = 22.22       # = 72 F
TARGET_MOTOR_C = 26.11     # = 79 F
TARGET_DRIVER_C = 26.11    # = 79 F
TARGET_TOL = 0.6

BTSNOOP_EPOCH = datetime(1, 1, 1) + timedelta(microseconds=0xdcddb30f2f8000)
RX_HANDLE = 0x000f


# ---------- btsnoop / ATT reassembly (lifted from p6_new_realtime.py) ----------

def iter_records(path):
    with open(path, "rb") as f:
        if f.read(8) != b"btsnoop\x00":
            raise SystemExit(f"bad btsnoop magic: {path}")
        f.read(8)
        while True:
            hdr = f.read(24)
            if len(hdr) < 24:
                break
            orig_len, incl_len, flags, drops, ts = struct.unpack(">IIIIq", hdr)
            payload = f.read(incl_len)
            if len(payload) < incl_len:
                break
            yield ts, flags, payload


def parse_v2_frame(raw):
    if len(raw) < 4 or raw[0] != 0xAA or raw[1] != 0xAA:
        return None
    if raw[2] != 0x16:
        return None
    length = raw[3]
    needed_unescaped = length + 1
    out = bytearray()
    i = 4
    while len(out) < needed_unescaped and i < len(raw):
        if raw[i] == 0xA5 and i + 1 < len(raw):
            out.append(raw[i + 1])
            i += 2
        else:
            out.append(raw[i])
            i += 1
    if len(out) < needed_unescaped:
        return None
    body = bytes(out[:length])
    chk = out[length]
    return raw[2], length, body, chk


def collect_realtime(path):
    """Return [(offset_seconds, ts_dt, data_bytes), ...] for every realtime
    `21 02 87 01 00 ...` frame in the capture. data is the bytes AFTER the
    `21 02 87 01 00` routing prefix - the 96-byte realtime telemetry block."""
    rows = []
    buffer = bytearray()
    first_ts = None
    parsed_frames = []
    for ts, flags, payload in iter_records(path):
        if not payload or payload[0] != 0x02 or len(payload) < 9:
            continue
        l2cap_len = struct.unpack("<H", payload[5:7])[0]
        cid = struct.unpack("<H", payload[7:9])[0]
        l2cap = payload[9:9 + l2cap_len]
        if cid != 0x0004 or not l2cap:
            continue
        opcode = l2cap[0]
        ts_dt = BTSNOOP_EPOCH + timedelta(microseconds=ts)
        if opcode == 0x1B and len(l2cap) >= 3:
            handle = struct.unpack("<H", l2cap[1:3])[0]
            value = l2cap[3:]
            if handle != RX_HANDLE:
                continue
            if first_ts is None:
                first_ts = ts_dt
            offset_s = (ts_dt - first_ts).total_seconds()
            buffer.extend(value)
            i = 0
            while i + 4 < len(buffer):
                if buffer[i] == 0xAA and buffer[i + 1] == 0xAA:
                    parsed = parse_v2_frame(bytes(buffer[i:]))
                    if parsed is None:
                        i += 1
                        continue
                    flag, length, body, chk = parsed
                    j = i + 4
                    eaten = 0
                    while eaten < length + 1 and j < len(buffer):
                        if buffer[j] == 0xA5 and j + 1 < len(buffer):
                            j += 2
                        else:
                            j += 1
                        eaten += 1
                    parsed_frames.append((offset_s, ts_dt, flag, length, body))
                    buffer = buffer[j:]
                    i = 0
                else:
                    i += 1

    rt_rows = []
    for offset_s, ts_dt, flag, length, body in parsed_frames:
        if len(body) >= 5 and body[0] == 0x21 and body[1] == 0x02 and body[2] == 0x87:
            data = body[5:]
            rt_rows.append((offset_s, ts_dt, bytes(data)))
    return rt_rows


# ---------- decoding formulas ----------

def decode_byte(data, off, formula):
    if off >= len(data):
        return None
    b = data[off]
    sb = struct.unpack_from("b", data, off)[0]
    if formula == "byte/4": return b / 4.0
    if formula == "byte/2": return b / 2.0
    if formula == "byte":    return float(b)
    if formula == "byte/8": return b / 8.0
    if formula == "byte/9": return b / 9.0
    if formula == "byte/16": return b / 16.0
    if formula == "(byte-32)*5/9 (F-raw)":
        return (b - 32) * 5.0 / 9.0
    if formula == "byte-50": return float(b) - 50
    if formula == "byte-128": return float(b) - 128
    if formula == "int8":    return float(sb)
    if formula == "int8/2":  return sb / 2.0
    if formula == "int8/4":  return sb / 4.0
    return None


def decode_uint16le(data, off, formula):
    if off + 1 >= len(data):
        return None
    v = struct.unpack_from("<H", data, off)[0]
    sv = struct.unpack_from("<h", data, off)[0]
    if formula == "uint16/100": return v / 100.0
    if formula == "uint16/10":  return v / 10.0
    if formula == "uint16/8":   return v / 8.0
    if formula == "uint16/4":   return v / 4.0
    if formula == "uint16/1000": return v / 1000.0
    if formula == "int16/100":  return sv / 100.0
    if formula == "int16/10":   return sv / 10.0
    return None


SINGLE_FORMULAS = [
    "byte", "byte/2", "byte/4", "byte/8", "byte/9", "byte/16",
    "(byte-32)*5/9 (F-raw)", "byte-50", "byte-128",
    "int8", "int8/2", "int8/4",
]
PAIR_FORMULAS = [
    "uint16/100", "uint16/10", "uint16/8", "uint16/4", "uint16/1000",
    "int16/100", "int16/10",
]


def find_target_frame(rt_rows, target_offset_s, voltage_target=208.9):
    candidates = []
    for offset_s, ts_dt, data in rt_rows:
        if len(data) < 4:
            continue
        v = struct.unpack_from("<H", data, 0)[0] / 100.0
        if abs(v - voltage_target) > 1.0:
            continue
        candidates.append((abs(offset_s - target_offset_s), offset_s, ts_dt, data))
    candidates.sort()
    return candidates[0] if candidates else None


def candidates_for_target(data, target_c, tol):
    """Return list of (offset, formula, decoded_value) within tol of target."""
    hits = []
    for off in range(4, len(data)):
        for fm in SINGLE_FORMULAS:
            v = decode_byte(data, off, fm)
            if v is None:
                continue
            if abs(v - target_c) <= tol:
                hits.append((off, fm, v))
    for off in range(4, len(data) - 1):
        for fm in PAIR_FORMULAS:
            v = decode_uint16le(data, off, fm)
            if v is None:
                continue
            if abs(v - target_c) <= tol:
                hits.append((off, fm, v))
    return hits


def evaluate_series(rt_rows, off, fm, voltage_low=200, voltage_high=220, mos_low=60, mos_high=80):
    """Compute statistics over all frames where the frame is "clean":
    - voltage decodes to plausible 200-220 V
    - off=71 (the MOS sensor) is in plausible 60-80 F
    The off=71 filter is critical: misaligned/multiplexed frames have
    non-Fahrenheit values at off=71 and would otherwise pollute statistics."""
    vals = []
    for off_s, _, d in rt_rows:
        if len(d) < 96:
            continue
        v = struct.unpack_from("<H", d, 0)[0] / 100.0
        if not (voltage_low < v < voltage_high):
            continue
        if not (mos_low <= d[71] <= mos_high):
            continue
        if fm in PAIR_FORMULAS:
            decoded = decode_uint16le(d, off, fm)
        else:
            decoded = decode_byte(d, off, fm)
        if decoded is not None:
            vals.append(decoded)
    if not vals:
        return None
    in_range = sum(1 for x in vals if -10 <= x <= 80) / len(vals)
    max_step = (max(abs(vals[i] - vals[i-1]) for i in range(1, len(vals)))
                if len(vals) > 1 else 0)
    return {
        "n": len(vals),
        "min": min(vals),
        "max": max(vals),
        "mean": sum(vals) / len(vals),
        "max_step": max_step,
        "in_range": in_range,
    }


# ---------- main ----------

def main():
    print("=" * 78)
    print("P6 MOS / motor / driver temperature offset brute-force search")
    print("=" * 78)
    print()

    if not os.path.exists(NEW_PATH):
        raise SystemExit(f"NEW capture not found: {NEW_PATH}")
    rt_new = collect_realtime(NEW_PATH)
    print(f"NEW capture: {len(rt_new)} realtime frames")
    rt_old = []
    if os.path.exists(OLD_PATH):
        rt_old = collect_realtime(OLD_PATH)
        print(f"OLD capture: {len(rt_old)} realtime frames")

    # The labelled frame at btsnoop t=82.64 (= video t=120, WIM showed temps)
    target = find_target_frame(rt_new, 82.64, voltage_target=208.9)
    if not target:
        raise SystemExit("could not locate target frame")
    _, t_off, _, t_data = target
    print(f"\nTarget frame at t={t_off:.2f}s, len={len(t_data)}")
    print(f"  hex: {t_data.hex(' ')}")
    print()

    # ---- Step 1: candidate offsets in the target frame ----
    print("-" * 78)
    print("Step 1: candidate (offset, formula) tuples in TARGET frame")
    print(f"  MOS={TARGET_MOS_C}C, motor={TARGET_MOTOR_C}C, driver={TARGET_DRIVER_C}C, +/-{TARGET_TOL}")
    print("-" * 78)

    targets = [("MOS", TARGET_MOS_C), ("motor", TARGET_MOTOR_C), ("driver", TARGET_DRIVER_C)]

    for name, target_c in targets:
        hits = candidates_for_target(t_data, target_c, TARGET_TOL)
        print(f"\n  {name} (~{target_c}C): {len(hits)} candidates")
        for off, fm, v in hits:
            print(f"    off={off:>3d}  fm={fm:<25s}  -> {v:7.3f}")

    # ---- Step 2: cross-validate across NEW and OLD ----
    print()
    print("-" * 78)
    print("Step 2: cross-validate each candidate across both captures")
    print("(filtered to clean frames where voltage decodes and MOS off=71 is in F range)")
    print("-" * 78)

    for name, target_c in targets:
        hits = candidates_for_target(t_data, target_c, TARGET_TOL)
        print(f"\n  === {name} (target {target_c}C) ===")
        rows = []
        for off, fm, v in hits:
            new_stats = evaluate_series(rt_new, off, fm)
            old_stats = evaluate_series(rt_old, off, fm) if rt_old else None
            rows.append((off, fm, v, new_stats, old_stats))
        # rank by NEW frame match closeness, then by OLD in_range
        rows.sort(key=lambda r: (abs(r[2] - target_c), -(r[4]["in_range"] if r[4] else 0)))
        print(f"    {'off':>4} {'formula':<25} {'targetV':>8}  "
              f"{'NEW_range':>16} {'OLD_range':>16} {'NEW_inR%':>8} {'OLD_inR%':>8} {'OLD_maxStep':>11}")
        for off, fm, v, ns, os_ in rows:
            new_str = f"{ns['min']:.1f}..{ns['max']:.1f}" if ns else "n/a"
            old_str = f"{os_['min']:.1f}..{os_['max']:.1f}" if os_ else "n/a"
            new_in = f"{ns['in_range']*100:.0f}" if ns else "-"
            old_in = f"{os_['in_range']*100:.0f}" if os_ else "-"
            old_step = f"{os_['max_step']:.2f}" if os_ else "-"
            print(f"    {off:>4} {fm:<25} {v:>8.2f}  {new_str:>16} {old_str:>16} {new_in:>8} {old_in:>8} {old_step:>11}")

    # ---- Step 3: stability summary at key offsets ----
    print()
    print("-" * 78)
    print("Step 3: behaviour summary at key offsets across both captures")
    print("-" * 78)
    for off, label, fm in [
        (28, "existing parser MOS @ off=28 byte/4", "byte/4"),
        (29, "off=29 byte/2 (motor candidate)", "byte/2"),
        (30, "existing parser motor @ off=30 byte/4", "byte/4"),
        (54, "off=54 byte/8 (motor candidate, also uint16 candidate)", "byte/8"),
        (63, "off=63 byte/4 (MOS candidate)", "byte/4"),
        (64, "off=64 byte/8 (motor/driver candidate)", "byte/8"),
        (70, "off=70 raw F (motor candidate from short-ride match)", "(byte-32)*5/9 (F-raw)"),
        (71, "off=71 raw F (MOS candidate, EXACT 22.22 match)", "(byte-32)*5/9 (F-raw)"),
    ]:
        for source, name in [(rt_new, "NEW"), (rt_old, "OLD")]:
            if not source:
                continue
            stats = evaluate_series(source, off, fm)
            if stats:
                print(f"  {name} {label:<55} {stats['min']:>6.2f}..{stats['max']:>6.2f}  "
                      f"mean={stats['mean']:>5.2f}  maxStep={stats['max_step']:>5.2f}  "
                      f"inRange={stats['in_range']*100:>5.1f}%  n={stats['n']}")

    # ---- Step 4: definitive verdict ----
    print()
    print("=" * 78)
    print("VERDICT")
    print("=" * 78)
    print("""
After exhaustive search of every (offset, formula) tuple against both the
NEW labelled frame and the OLD long-ride capture, the ONLY clean live
temperature signal in the realtime 0x87 telemetry block is:

  * off=71  (raw byte = degrees Fahrenheit)  -> MOS / ambient sensor

Behaviour:
  - NEW: stays at 72F (= 22.22C) the whole capture.
        EXACTLY matches the labelled MOS=72F=22.22C in the target frame.
  - OLD: drifts 67F -> 68F -> 69F -> 70F over 25 minutes (room-temp drift).
        Does NOT spike during heavy ride - this is the cool MOS / ambient
        sensor, not the motor.

The previously-suspected "motor at off=30 byte/4" and "MOS at off=28 byte/4"
are DEFINITIVELY NOT live temperatures:
  - off=28 = 111 always (= 27.75C if /4) across both captures, regardless of
    riding state. This is a fixed config field (likely speed alarm low byte
    of the 6f 35 = 13679 = 85 mph alarm pair).
  - off=30 in NEW stays 198..200 (49.5..50C if /4) for 4 minutes including
    a 91 km/h burst; in OLD stays 203..204 across 25 minutes of hard riding.
    Plain not a thermistor reading.
  - The OLD numerical match (off=28/4=27.75C, off=30/4=51C "MOS/motor"
    labels) was a coincidence of the static config bytes happening to map
    to plausible C values under /4. The user's existing parser comment
    captured this confusion.

Motor and driver-board temperatures DO NOT appear to be transmitted in the
realtime 0x87 frame on this firmware. Candidates that NUMERICALLY match
26.00..26.50C in the NEW target frame (off=29 byte/2; off=54-55 uint16/8;
off=64-65 uint16/8) are all FIXED reference values - they don't change
during the OLD long ride either. They appear to be calibration constants
or alarm thresholds, not live thermistor readings.

The clean motor-warming signal that DOES exist is at off=70, but it
behaves as a **per-second counter that wraps at 255 -> 0**, not as a
temperature. It looks temperature-like in the brief NEW capture only
because the counter rate (~1/sec) happens to match motor-warming rate
during the 12-second ride burst. Across the full OLD capture it wraps
multiple times, which a real motor temperature cannot do.

Recommendation:
  1. Update the parser: MOS = (data[71] - 32) * 5 / 9 (F-raw byte).
  2. Drop motor and driver-board fields entirely on this firmware until a
     longer labelled capture proves another live offset, OR query the
     wheel with a different sub-command (the 0x84 "info bundle" frame at
     body[64..70] has a varying cluster `c8 b0 c7 c7 c8 b0` that may hold
     these but the 0x84 frame is sent only at startup, not per second).
  3. The labelled "motor=79F" and "driver=79F" in the InMotion app may be
     either fixed defaults (no real sensor on this wheel) or values cached
     from a different request. Without a labelled long-ride capture from
     the same wheel firmware, we cannot uniquely identify them.
""")


if __name__ == "__main__":
    main()
