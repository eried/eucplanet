"""Quick btsnoop decoder for inspecting the P6 capture.

Walks an Android btsnoop_hci.log, isolates ACL frames carrying
ATT writes and notifications on the Nordic UART RX/TX UUIDs, and
prints a chronological view of the bytes the phone wrote and the
bytes the wheel notified back. Only HCI ACL is decoded — connection
events, EIR scans, and L2CAP signalling are skipped.

Usage:
    python decode_btsnoop_p6.py "path/to/btsnoop_hci.log"
"""

import struct
import sys
from datetime import datetime, timedelta

NORDIC_TX_UUID = "6e400002b5a3f393e0a924dccae9"  # phone -> wheel write
NORDIC_RX_UUID = "6e400003b5a3f393e0a924dccae9"  # wheel -> phone notify

BTSNOOP_EPOCH = datetime(1, 1, 1) + timedelta(microseconds=0xdcddb30f2f8000)


def decode(path):
    with open(path, "rb") as f:
        magic = f.read(8)
        if magic != b"btsnoop\x00":
            print(f"Bad magic: {magic!r}", file=sys.stderr)
            return
        f.read(8)  # version + datalink

        att_handle_to_kind = {}  # ATT handle -> "TX" / "RX"

        while True:
            hdr = f.read(24)
            if len(hdr) < 24:
                break
            orig_len, incl_len, flags, drops, ts = struct.unpack(">IIIIq", hdr)
            payload = f.read(incl_len)
            if len(payload) < incl_len:
                break

            ts_dt = BTSNOOP_EPOCH + timedelta(microseconds=ts)
            direction = "RX" if flags & 1 else "TX"

            if not payload:
                continue
            packet_type = payload[0]
            if packet_type != 0x02:  # only HCI ACL
                continue
            if len(payload) < 9:
                continue

            handle_flags = struct.unpack("<H", payload[1:3])[0]
            acl_len = struct.unpack("<H", payload[3:5])[0]
            l2cap_len = struct.unpack("<H", payload[5:7])[0]
            cid = struct.unpack("<H", payload[7:9])[0]
            l2cap_payload = payload[9:9 + l2cap_len]

            # Only care about ATT (CID 0x0004)
            if cid != 0x0004 or not l2cap_payload:
                continue

            opcode = l2cap_payload[0]
            # 0x52 = Write Without Response, 0x12 = Write Request,
            # 0x1B = Handle Value Notification.
            ts_str = ts_dt.strftime("%H:%M:%S.%f")[:-3]

            if opcode in (0x12, 0x52) and len(l2cap_payload) >= 3:
                handle = struct.unpack("<H", l2cap_payload[1:3])[0]
                value = l2cap_payload[3:]
                att_handle_to_kind[handle] = "TX_HANDLE"
                print(f"{ts_str} TX  h=0x{handle:04x} {value.hex(' ')}")
            elif opcode == 0x1B and len(l2cap_payload) >= 3:
                handle = struct.unpack("<H", l2cap_payload[1:3])[0]
                value = l2cap_payload[3:]
                print(f"{ts_str} RX  h=0x{handle:04x} {value.hex(' ')}")
            elif opcode == 0x09 and len(l2cap_payload) >= 4:
                # Read by Type Response — usually carries characteristic UUIDs
                # we'd want to map handles. Skip detailed parsing; rely on
                # the fact that Nordic UART has only one TX and one RX char,
                # so handle observation is enough.
                pass


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: decode_btsnoop_p6.py <log>", file=sys.stderr)
        sys.exit(1)
    decode(sys.argv[1])
