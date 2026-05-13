"""Find auto-headlight + DRL opcodes in the P6 capture.

Reuses btsnoop reassembly from p6_command_audit. Scans every TX write
whose body looks like an extended-routing control frame
(02 21 [sub] [args...]) and groups by (sub, args). Then it filters down
to the user's video 4:20-4:37 window where the auto-headlight toggles
happened.

Capture-to-video alignment notes:
- The existing P6_CAPTURE_LABELS.md (older capture) used video + 13:24:13.
- For this NEW capture we anchor by the first TX write timestamp and
  match it to the FIRST labelled event in the new video (BT first event).
  We dump the alignment so the human can sanity-check.

Usage:
    python p6_light_extras.py "path/to/btsnoop_hci (1).log"
"""

import struct
import sys
from collections import defaultdict

BTSNOOP_EPOCH_US = 0xdcddb30f2f8000  # microseconds since year 1


# Opcodes already mapped in P6_CAPTURE_LABELS.md (do NOT mark as unknown):
KNOWN_SUBS = {
    0x21: "setMaxSpeed live drag",
    0x22: "unknown toggle (seen before)",
    0x24: "Speed Clamp at 25 km/h",
    0x25: "Pedal Hardness",
    0x31: "Lock/Unlock",
    0x34: "auth challenge",
    0x3e: "commit scalar setting",
    0x4c: "PWM thresholds",
    0x4e: "unknown toggle (older capture)",
    0x50: "Light on/off",
    0x51: "Horn",
}


def iter_btsnoop(path):
    with open(path, "rb") as f:
        magic = f.read(8)
        if magic != b"btsnoop\x00":
            raise SystemExit(f"bad magic: {magic!r}")
        f.read(8)
        while True:
            hdr = f.read(24)
            if len(hdr) < 24:
                return
            orig_len, incl_len, flags, drops, ts = struct.unpack(">IIIIq", hdr)
            payload = f.read(incl_len)
            if len(payload) < incl_len:
                return
            yield ts, flags, payload


def att_writes(path):
    for ts, flags, payload in iter_btsnoop(path):
        if not payload or payload[0] != 0x02 or len(payload) < 9:
            continue
        l2cap_len = struct.unpack("<H", payload[5:7])[0]
        cid = struct.unpack("<H", payload[7:9])[0]
        body = payload[9:9 + l2cap_len]
        if cid != 0x0004 or not body:
            continue
        opcode = body[0]
        if opcode in (0x12, 0x52) and len(body) >= 3:
            handle = struct.unpack("<H", body[1:3])[0]
            yield ts, "TX", handle, body[3:]
        elif opcode == 0x1B and len(body) >= 3:
            handle = struct.unpack("<H", body[1:3])[0]
            yield ts, "RX", handle, body[3:]


def reassemble_frames(events):
    bufs = {"TX": bytearray(), "RX": bytearray()}
    last_ts = {"TX": 0, "RX": 0}
    for ts, dirn, handle, value in events:
        buf = bufs[dirn]
        if value.startswith(b"\xaa\xaa") and buf:
            yield (last_ts[dirn], dirn, bytes(buf))
            buf.clear()
        buf.extend(value)
        last_ts[dirn] = ts
        if len(buf) >= 5:
            length = buf[3]
            total = 5 + length
            if len(buf) >= total:
                yield (last_ts[dirn], dirn, bytes(buf[:total]))
                tail = bytes(buf[total:])
                buf.clear()
                buf.extend(tail)


def fmt_ts(ts):
    secs = (ts - BTSNOOP_EPOCH_US) / 1_000_000
    h = int(secs // 3600) % 24
    m = int(secs // 60) % 60
    s = secs % 60
    return f"{h:02d}:{m:02d}:{s:06.3f}"


def secs_of_day(ts):
    return (ts - BTSNOOP_EPOCH_US) / 1_000_000 % 86400


def main(path):
    frames = list(reassemble_frames(att_writes(path)))

    # P6_CAPTURE_LABELS.md naming: extended-routing 02-21 frames carry a
    # CONTROL byte (almost always 0x60) at frame[6], then the actual "sub"
    # opcode at frame[7]. The labels file ignores 0x60 and uses frame[7]
    # as "sub" (e.g. 0x50 = light, 0x21 = setMaxSpeed). For non-0x60
    # control bytes (queries/state) we keep the control byte itself as
    # "sub" so they don't shadow real CONTROL ops, prefixing with "ctl=".
    tx_events = []  # (ts, control, sub, args_bytes)
    for ts, dirn, frame in frames:
        if dirn != "TX" or len(frame) < 7 or frame[:2] != b"\xaa\xaa":
            continue
        if frame[4] != 0x02 or frame[5] != 0x21:
            continue
        control = frame[6] & 0xFF
        if control == 0x60 and len(frame) >= 8:
            sub = frame[7] & 0xFF
            args = bytes(frame[8:-1])
        else:
            sub = control
            args = bytes(frame[7:-1])
        tx_events.append((ts, control, sub, args))

    if not tx_events:
        print("no TX control frames found")
        return

    first_ts = tx_events[0][0]
    last_ts = tx_events[-1][0]
    print(f"capture TX span: {fmt_ts(first_ts)} -> {fmt_ts(last_ts)}  "
          f"(N={len(tx_events)} control frames)")
    print()

    # Distinct (control, sub, args) and how many times seen, with first/last
    # wall-clock. For 0x60 (CONTROL) frames the "sub" is the labels-file
    # opcode; for everything else "sub" == "control".
    by_key = defaultdict(list)
    for ts, control, sub, args in tx_events:
        by_key[(control, sub, args)].append(ts)

    print("=" * 72)
    print("All distinct (control, sub, args) seen across the capture:")
    print(f"  {'ctl':>4s}  {'sub':>4s}  {'args':<26s}  {'count':>5s}  "
          f"{'first':>14s}  {'last':>14s}  note")
    for (control, sub, args), ts_list in sorted(by_key.items()):
        ahex = " ".join(f"{b:02x}" for b in args) if args else "(none)"
        if control == 0x60:
            note = KNOWN_SUBS.get(sub, "*** UNKNOWN ***")
        else:
            note = "(non-CONTROL frame)"
        print(f"  0x{control:02x}  0x{sub:02x}  {ahex:<26s}  "
              f"{len(ts_list):>5d}  {fmt_ts(ts_list[0]):>14s}  "
              f"{fmt_ts(ts_list[-1]):>14s}  {note}")
    print()

    # Establish video-to-wall-clock offset by anchoring on the FIRST TX
    # control event = "video 0:53" hypothesis (BT first event per task brief).
    # Older labels file said offset = +13:24:13. Print both candidate
    # alignments so the human can sanity-check by spotting the lock event.
    fh = first_ts - BTSNOOP_EPOCH_US
    fh_secs = fh / 1_000_000 % 86400
    print(f"first TX wall-clock = {fmt_ts(first_ts)}  ({fh_secs:.3f}s of day)")
    print("If first TX = video 0:53, then video t -> wall-clock =")
    base = fh_secs - 53.0
    print(f"  wall = {int(base // 3600):02d}:{int(base // 60) % 60:02d}:"
          f"{base % 60:06.3f} + video_t")
    print("Older labels file hint: video t -> wall-clock = +13:24:13.")
    print()

    # Verify 0x50 light at "video 1:14" under both alignments.
    print("=" * 72)
    print("Verifying 0x50 (Light) usage:")
    light_evts = [(ts, args) for ts, control, sub, args in tx_events
                   if control == 0x60 and sub == 0x50]
    for ts, args in light_evts:
        ahex = " ".join(f"{b:02x}" for b in args)
        # video time under 'first TX = 0:53' anchor:
        v_anchor = (ts - first_ts) / 1_000_000 + 53.0
        # video time under 'wall - 13:24:13' anchor (older capture style):
        v_old = secs_of_day(ts) - (13 * 3600 + 24 * 60 + 13)
        print(f"  {fmt_ts(ts)}  args=[{ahex}]  "
              f"video~{int(v_anchor // 60)}:{v_anchor % 60:05.2f} (anchor=first-TX@0:53) "
              f"or {int(v_old // 60)}:{v_old % 60:05.2f} (anchor=wall-13:24:13)")
    print()

    # Auto-headlight window: video 4:20 to 4:38 -> wall-clock under both
    # anchor candidates. List every TX in BOTH candidate windows so the
    # human can pick whichever matches the lock/light verification above.
    candidates = []
    # candidate A: first TX = video 0:53
    a_lo = first_ts + int((4 * 60 + 20 - 53) * 1_000_000)
    a_hi = first_ts + int((4 * 60 + 38 - 53) * 1_000_000)
    candidates.append(("A: first-TX = video 0:53", a_lo, a_hi))
    # candidate B: wall-clock = video + 13:24:13 (legacy alignment)
    b_lo_secs = (4 * 60 + 20) + (13 * 3600 + 24 * 60 + 13)
    b_hi_secs = (4 * 60 + 38) + (13 * 3600 + 24 * 60 + 13)
    # Convert to absolute btsnoop ts (use same day as first_ts).
    day_start_us = BTSNOOP_EPOCH_US + (
        int((first_ts - BTSNOOP_EPOCH_US) / 1_000_000 // 86400) * 86400 * 1_000_000
    )
    b_lo = day_start_us + b_lo_secs * 1_000_000
    b_hi = day_start_us + b_hi_secs * 1_000_000
    candidates.append(("B: wall = video + 13:24:13", b_lo, b_hi))

    for label, lo, hi in candidates:
        print("=" * 72)
        print(f"TX writes in window {label}:  "
              f"{fmt_ts(lo)} .. {fmt_ts(hi)}")
        rows = [(ts, control, sub, args) for ts, control, sub, args in tx_events
                if lo <= ts <= hi]
        if not rows:
            print("  (no TX in this window)")
            continue
        print(f"  {'time':>14s}  {'ctl':>4s}  {'sub':>4s}  args")
        for ts, control, sub, args in rows:
            ahex = " ".join(f"{b:02x}" for b in args) if args else "(none)"
            is_known = control == 0x60 and sub in KNOWN_SUBS
            mark = "" if is_known else "  <-- UNKNOWN"
            note = KNOWN_SUBS.get(sub, "") if control == 0x60 else "(non-CONTROL)"
            print(f"  {fmt_ts(ts):>14s}  0x{control:02x}  0x{sub:02x}  "
                  f"[{ahex}]  {note}{mark}")
        # Distinct (control, sub, args) inside the window:
        win_by_key = defaultdict(int)
        for _, control, sub, args in rows:
            win_by_key[(control, sub, args)] += 1
        print(f"  -- distinct (control, sub, args) in window --")
        for (control, sub, args), n in sorted(win_by_key.items()):
            ahex = " ".join(f"{b:02x}" for b in args) if args else "(none)"
            is_known = control == 0x60 and sub in KNOWN_SUBS
            mark = "" if is_known else "  *** UNKNOWN ***"
            print(f"  0x{control:02x}  0x{sub:02x}  [{ahex}]  x{n}{mark}")
        print()


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: p6_light_extras.py <log>", file=sys.stderr)
        sys.exit(1)
    main(sys.argv[1])
