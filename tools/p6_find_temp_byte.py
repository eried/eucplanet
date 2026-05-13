"""Find which P6 realtime byte holds motor / imu / mos temperatures.

User-supplied ground truth from the diagnostics log:
  06:50:54.907  motor=81 F, imu=77 F, mos=73 F   (start)
  06:51:35.814  motor=84 F                       (~41 s later)
  06:51:43.922  imu=81 F                         (~49 s later)
  06:51:50.403  mos=75 F                         (~56 s later)

Strategy: at each labelled timestamp average the +/- 2.5 s window, then for
each byte offset check whether the value matches the expected sensor under
each candidate scaling:
  - direct Fahrenheit  (byte == F)
  - direct Celsius     (byte == round((F-32)*5/9))
  - byte / 4 = Celsius (byte/4 == round((F-32)*5/9), i.e. byte ~= 4*(F-32)*5/9)

Print bytes that match within +/- 2 units at t0 for at least one sensor and
move in the right direction at the corresponding later label.
"""
import re
from datetime import datetime
from statistics import mean

LOG = r"D:\Downloads\diagnostics-20260510-065156.txt"

LABELS = [
    ("t0", "06:50:54.907", {"motor": 81, "imu": 77, "mos": 73}),
    ("motor1", "06:51:35.814", {"motor": 84}),
    ("imu1", "06:51:43.922", {"imu": 81}),
    ("mos1", "06:51:50.403", {"mos": 75}),
]


def parse_ts(s):
    return datetime.strptime("2026-05-10 " + s, "%Y-%m-%d %H:%M:%S.%f")


def parse_log():
    note_re = re.compile(
        r"(\d\d:\d\d:\d\d\.\d{3})\s+NOTE\s+P6 realtime len=(\d+) body=([0-9a-f ]+)"
    )
    with open(LOG, "r", encoding="utf-8") as f:
        for line in f:
            m = note_re.search(line)
            if not m:
                continue
            ts = parse_ts(m.group(1))
            body = bytes.fromhex(m.group(3).replace(" ", ""))
            yield ts, body


def f_to_c(f):
    return (f - 32) * 5.0 / 9.0


def candidates_for(f):
    """Return possible byte values that decode to F under the three scalings."""
    return {
        "F": f,
        "C": round(f_to_c(f)),
        "C*4": round(f_to_c(f) * 4),
    }


def main():
    frames = list(parse_log())
    body_len = len(frames[0][1])
    print(f"Loaded {len(frames)} frames, body length {body_len}")

    label_data = {}
    for name, ts_str, sensors in LABELS:
        center = parse_ts(ts_str)
        window = [b for ts, b in frames if abs((ts - center).total_seconds()) < 2.5]
        avgs = [mean(b[i] for b in window) for i in range(body_len)]
        label_data[name] = (sensors, avgs)
        print(f"  {name} @ {ts_str}: {len(window)} frames")
    print()

    # For each sensor, find offsets where t0's value matches one of the
    # candidate scalings within tolerance.
    for sensor in ("motor", "imu", "mos"):
        f0 = label_data["t0"][0][sensor]
        cands = candidates_for(f0)
        # which label has the later reading for this sensor?
        later_label = {"motor": "motor1", "imu": "imu1", "mos": "mos1"}[sensor]
        f1 = label_data[later_label][0][sensor]
        c1 = candidates_for(f1)
        delta_f = f1 - f0
        print(f"=== {sensor}: t0={f0}F -> {later_label}={f1}F (delta {delta_f:+d}F) ===")
        for scale in ("F", "C", "C*4"):
            target0 = cands[scale]
            target1 = c1[scale]
            tol = max(2, abs(target1 - target0))
            print(f"  scale={scale:>3}  expect ~{target0} at t0, ~{target1} at {later_label}")
            hits = []
            for i in range(body_len):
                v0 = label_data["t0"][1][i]
                v1 = label_data[later_label][1][i]
                if abs(v0 - target0) <= 2 and abs(v1 - target1) <= 2:
                    hits.append((i, round(v0, 1), round(v1, 1)))
            for i, v0, v1 in hits:
                print(f"    offset {i}: {v0} -> {v1}")
        print()


if __name__ == "__main__":
    main()
