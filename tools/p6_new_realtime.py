"""Pull realtime telemetry frames from the new P6 capture and dump them
side-by-side with the labelled values at video t=120s (MOS 22.22 °C,
motor 26.11 °C, driver board 26.11 °C, voltage 208.9 V, current -0.18 A,
total mileage 1803.0 mi).

The realtime frame arrives via Nordic UART notifications on handle 0x000f
with body shape `aa aa 16 65 21 02 87 01 00 [data...] [chk]` (length 0x65,
sub 0x07 reply marked as 0x87). The data block is everything after
`02 87 01 00`.
"""

import struct
import sys
from datetime import datetime, timedelta

BTSNOOP_EPOCH = datetime(1, 1, 1) + timedelta(microseconds=0xdcddb30f2f8000)
RX_HANDLE = 0x000f


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


def unescape(escaped):
    out = bytearray()
    i = 0
    while i < len(escaped):
        if escaped[i] == 0xA5 and i + 1 < len(escaped):
            out.append(escaped[i + 1])
            i += 2
        else:
            out.append(escaped[i])
            i += 1
    return bytes(out)


def parse_v2_frame(raw):
    """raw starts with aa aa. Returns (flags, length, unescaped_payload, ok)."""
    if len(raw) < 4 or raw[0] != 0xAA or raw[1] != 0xAA:
        return None
    if raw[2] != 0x16:  # extended
        return None
    length = raw[3]
    # remaining unescaped bytes (excluding flags and length): length bytes + 1 checksum
    # Walk on-wire bytes until we have length unescaped + 1 checksum
    needed_unescaped = length + 1  # data bytes + checksum
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


def main(path, target_offset=None):
    first_ts = None
    rows = []
    buffer = bytearray()
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
            offset = (ts_dt - first_ts).total_seconds()
            # Could be a partial frame — buffer reassemble
            buffer.extend(value)
            # Try to extract complete aa aa frames
            i = 0
            while i + 4 < len(buffer):
                if buffer[i] == 0xAA and buffer[i + 1] == 0xAA:
                    parsed = parse_v2_frame(bytes(buffer[i:]))
                    if parsed is None:
                        i += 1
                        continue
                    flag, length, body, chk = parsed
                    # Consume frame: walk until we've eaten length+1 unescaped bytes
                    j = i + 4
                    eaten = 0
                    while eaten < length + 1 and j < len(buffer):
                        if buffer[j] == 0xA5 and j + 1 < len(buffer):
                            j += 2
                        else:
                            j += 1
                        eaten += 1
                    rows.append((offset, ts_dt, flag, length, body))
                    buffer = buffer[j:]
                    i = 0
                else:
                    i += 1

    # Filter to realtime telemetry: body starts with 02 87 ...
    rt_rows = []
    for offset, ts_dt, flag, length, body in rows:
        if len(body) >= 5 and body[0] == 0x21 and body[1] == 0x02 and body[2] == 0x87:
            data = body[5:]
            rt_rows.append((offset, ts_dt, data))
    print(f"Captured {len(rt_rows)} realtime telemetry frames")

    if target_offset is not None:
        # Find frames near target offset
        target = float(target_offset)
        nearest = sorted(rt_rows, key=lambda r: abs(r[0] - target))[:6]
        print(f"\n=== Frames near t={target}s ===")
        for offset, ts_dt, data in nearest:
            v_raw = int.from_bytes(data[0:2], "little")
            i_raw = int.from_bytes(data[2:4], "little", signed=True)
            mos = data[28] if len(data) > 28 else None
            motor = data[30] if len(data) > 30 else None
            print(f"\n  t={offset:6.2f}s  voltage={v_raw/100:6.1f}V  current={i_raw/100:+6.2f}A")
            print(f"    off28={mos:#04x}={mos}  off30={motor:#04x}={motor}")
            print(f"    motor C = {motor/4:5.2f}    MOS C = {mos/4:5.2f}")
            print(f"    full data ({len(data)} bytes): {data.hex(' ')}")
    else:
        # Print first and last
        if rt_rows:
            for label, row in [("first", rt_rows[0]), ("last", rt_rows[-1])]:
                offset, ts_dt, data = row
                print(f"\n  {label}: t={offset:6.2f}s  len={len(data)}  data={data.hex(' ')[:80]}...")


if __name__ == "__main__":
    args = sys.argv[1:]
    path = args[0] if args else None
    target = args[1] if len(args) > 1 else None
    if not path:
        print("usage: p6_new_realtime.py <btsnoop.log> [target_offset_seconds]")
        sys.exit(1)
    main(path, target)
