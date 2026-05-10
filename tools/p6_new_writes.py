"""Walk the new P6 capture and list every Nordic UART TX write on
handle 0x000d. Group by the command head (first 3 bytes after the
aa aa <len> <seq> 02 prefix) and print timestamps relative to the
first ATT write so we can map them to the video.
"""

import struct
import sys
from datetime import datetime, timedelta
from collections import defaultdict, OrderedDict

BTSNOOP_EPOCH = datetime(1, 1, 1) + timedelta(microseconds=0xdcddb30f2f8000)
TX_HANDLE = 0x000d  # Nordic UART RX char (phone -> wheel write)


def iter_records(path):
    with open(path, "rb") as f:
        if f.read(8) != b"btsnoop\x00":
            raise SystemExit("bad magic")
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


def main(path, mode="writes"):
    first_ts = None
    rows = []
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
        if opcode in (0x12, 0x52) and len(l2cap) >= 3:
            handle = struct.unpack("<H", l2cap[1:3])[0]
            value = l2cap[3:]
            if handle != TX_HANDLE:
                continue
            if first_ts is None:
                first_ts = ts_dt
            offset = (ts_dt - first_ts).total_seconds()
            rows.append((offset, ts_dt, "TX", value))
        elif opcode == 0x1B and len(l2cap) >= 3:
            handle = struct.unpack("<H", l2cap[1:3])[0]
            value = l2cap[3:]
            # Nordic UART notify is typically handle 0x000f
            if handle != 0x000f:
                continue
            if first_ts is None:
                first_ts = ts_dt
            offset = (ts_dt - first_ts).total_seconds()
            rows.append((offset, ts_dt, "RX", value))

    if mode == "writes":
        # Print only TX rows with a friendly head/key.
        for offset, ts_dt, kind, value in rows:
            if kind != "TX":
                continue
            head = decode_head(value)
            print(f"{offset:7.2f}s  {ts_dt.strftime('%H:%M:%S.%f')[:-3]}  {head:<32}  {value.hex(' ')}")
    elif mode == "summary":
        counter = defaultdict(int)
        first_seen = {}
        examples = {}
        for offset, ts_dt, kind, value in rows:
            if kind != "TX":
                continue
            head = decode_head(value)
            counter[head] += 1
            if head not in first_seen:
                first_seen[head] = offset
                examples[head] = value
        # Sort by first-seen time
        ordered = sorted(counter.keys(), key=lambda k: first_seen[k])
        print(f"{'first_t':>9}  {'count':>5}  {'head':<40}  example")
        for head in ordered:
            ex = examples[head].hex(' ')
            print(f"{first_seen[head]:9.2f}  {counter[head]:5d}  {head:<40}  {ex}")
    elif mode == "writes_only_unusual":
        # Skip the periodic poll commands, show only ones that look like state changes
        polls = {"21:06", "21:07", "21:10", "21:11", "21:0c", "21:0d", "2f:04", "2f:02"}
        for offset, ts_dt, kind, value in rows:
            if kind != "TX":
                continue
            head = decode_head(value)
            short = ":".join(head.split()[1:3]) if " " in head else head
            if short in polls:
                continue
            print(f"{offset:7.2f}s  {ts_dt.strftime('%H:%M:%S.%f')[:-3]}  {head:<32}  {value.hex(' ')}")


def decode_head(value):
    """Best-effort label of a TX frame: if it's an aa aa V2 frame, show
    the type+subtype byte that follows the 02 direction marker."""
    if len(value) >= 7 and value[0] == 0xAA and value[1] == 0xAA:
        # aa aa [len] [seq] 02 [type] [sub] ...
        # length byte at index 2, seq at index 3, direction at 4 (0x02)
        try:
            length = value[2]
            seq = value[3]
            direction = value[4]
            type_b = value[5]
            sub_b = value[6]
            return f"L{length:02x} S{seq:02x} D{direction:02x} T{type_b:02x}:{sub_b:02x}"
        except IndexError:
            return "short_frame"
    return f"raw:{value[:6].hex(' ')}"


if __name__ == "__main__":
    args = sys.argv[1:]
    if not args:
        print("usage: p6_new_writes.py <btsnoop.log> [writes|summary|writes_only_unusual]")
        sys.exit(1)
    path = args[0]
    mode = args[1] if len(args) > 1 else "writes"
    main(path, mode)
