"""Parse a btsnoop_hci.log focusing on RaceBox BLE traffic.

btsnoop v1 file format:
    Identification Pattern (8 bytes): b'btsnoop\0'
    Version Number       (4 bytes BE u32)
    Datalink Type        (4 bytes BE u32)
    Record (repeated):
        Original Length   (4 bytes BE u32)
        Included Length   (4 bytes BE u32)
        Packet Flags      (4 bytes BE u32)
        Cumulative Drops  (4 bytes BE u32)
        Timestamp (us)    (8 bytes BE u64)
        Packet Data       (Included Length bytes)

For BLE we care about HCI packets:
    type byte: 0x01 = HCI Command, 0x02 = ACL data, 0x04 = HCI Event

ACL data (0x02) carries L2CAP -> ATT for GATT activity. We dump every ATT
operation that touches the RaceBox Nordic UART characteristic UUIDs.
"""
import struct
import sys
from pathlib import Path

PATH = Path(sys.argv[1] if len(sys.argv) > 1 else r"D:/Downloads/bugreport_b0qsqw_BP2A_250605_031_A3_2026_05_13_18_51_27_racebox/FS/data/log/bt/btsnoop_hci.log")

# Nordic UART UUIDs, big endian per 128-bit form. Bluetooth advertises 128-bit
# UUIDs little-endian on the air, but here we just match the ASCII pattern.
SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
RX_CHAR = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"  # write
TX_CHAR = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"  # notify

OPCODES = {
    0x01: "ERR_RSP",
    0x02: "MTU_REQ",
    0x03: "MTU_RSP",
    0x04: "FIND_INFO_REQ",
    0x05: "FIND_INFO_RSP",
    0x06: "FIND_BY_TYPE_REQ",
    0x07: "FIND_BY_TYPE_RSP",
    0x08: "READ_BY_TYPE_REQ",
    0x09: "READ_BY_TYPE_RSP",
    0x0A: "READ_REQ",
    0x0B: "READ_RSP",
    0x0C: "READ_BLOB_REQ",
    0x0D: "READ_BLOB_RSP",
    0x10: "READ_BY_GROUP_REQ",
    0x11: "READ_BY_GROUP_RSP",
    0x12: "WRITE_REQ",
    0x13: "WRITE_RSP",
    0x16: "PREP_WRITE_REQ",
    0x18: "EXEC_WRITE_REQ",
    0x1B: "HANDLE_VALUE_NTF",
    0x1D: "HANDLE_VALUE_IND",
    0x52: "WRITE_CMD",
}

data = PATH.read_bytes()
assert data[:8] == b"btsnoop\x00", "Not a btsnoop file"
version, dlt = struct.unpack(">II", data[8:16])
print(f"# btsnoop v{version} dlt={dlt} size={len(data)} bytes")

cursor = 16
records = []
while cursor + 24 <= len(data):
    orig_len, incl_len, flags, drops, ts = struct.unpack(">IIIIq", data[cursor:cursor+24])
    cursor += 24
    payload = data[cursor:cursor+incl_len]
    cursor += incl_len
    records.append((ts, flags, payload))

print(f"# {len(records)} HCI records")

# Track handles known to belong to the Nordic UART service. We learn them by
# observing FIND_INFO_RSP / READ_BY_TYPE_RSP that include the 128-bit UUIDs.
rx_handle = None
tx_handle = None
tx_cccd_handle = None

ble_acl_count = 0
att_count = 0

# First pass: collect handle discovery.
def hex_uuid128(b):
    # Bluetooth on-air 128-bit UUIDs are little-endian. Reverse to canonical.
    if len(b) != 16:
        return None
    h = b[::-1].hex()
    return f"{h[0:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:32]}"

def parse_att(att):
    if not att:
        return None
    op = att[0]
    name = OPCODES.get(op, f"op_0x{op:02x}")
    return op, name, att[1:]

for idx, (ts, flags, pkt) in enumerate(records):
    if not pkt:
        continue
    hci_type = pkt[0]
    if hci_type != 0x02:
        continue  # only ACL
    ble_acl_count += 1
    if len(pkt) < 9:
        continue
    # ACL header: handle+flags (2 LE), data total length (2 LE)
    # then L2CAP: length (2 LE), CID (2 LE), then payload
    handle_flags, acl_len = struct.unpack("<HH", pkt[1:5])
    l2cap_len, cid = struct.unpack("<HH", pkt[5:9])
    if cid != 0x0004:  # ATT
        continue
    att = pkt[9:9+l2cap_len]
    if not att:
        continue
    att_count += 1
    op = att[0]

    # Service / characteristic discovery responses carry 128-bit UUIDs.
    # READ_BY_GROUP_RSP for primary service: each tuple = (start_h, end_h, uuid)
    # READ_BY_TYPE_RSP   for characteristics: each tuple = (decl_h, props(1), value_h(2), uuid)
    if op == 0x11 and len(att) >= 2:  # READ_BY_GROUP_RSP
        length = att[1]
        body = att[2:]
        for i in range(0, len(body), length):
            tup = body[i:i+length]
            if length == 20:
                start_h, end_h = struct.unpack("<HH", tup[0:4])
                uuid = hex_uuid128(tup[4:20])
                if uuid and uuid.lower() == SERVICE.lower():
                    print(f"# t={ts/1e6:.3f} primary service NUS at handles 0x{start_h:04x}..0x{end_h:04x}")
    elif op == 0x09 and len(att) >= 2:  # READ_BY_TYPE_RSP (characteristic decls)
        length = att[1]
        body = att[2:]
        for i in range(0, len(body), length):
            tup = body[i:i+length]
            if length == 21:  # 2 handle + 1 prop + 2 value_handle + 16 uuid
                decl_h = struct.unpack("<H", tup[0:2])[0]
                props = tup[2]
                value_h = struct.unpack("<H", tup[3:5])[0]
                uuid = hex_uuid128(tup[5:21])
                if uuid and uuid.lower() == RX_CHAR.lower():
                    rx_handle = value_h
                    print(f"# t={ts/1e6:.3f} RX char value_handle=0x{value_h:04x} props=0x{props:02x}")
                elif uuid and uuid.lower() == TX_CHAR.lower():
                    tx_handle = value_h
                    print(f"# t={ts/1e6:.3f} TX char value_handle=0x{value_h:04x} props=0x{props:02x}")
    elif op == 0x05 and len(att) >= 2:  # FIND_INFO_RSP (descriptor discovery)
        fmt = att[1]
        body = att[2:]
        if fmt == 1:  # 16-bit UUIDs
            for i in range(0, len(body), 4):
                h_handle, h_uuid16 = struct.unpack("<HH", body[i:i+4])
                # CCCD UUID = 0x2902. We want the one immediately after TX handle.
                if h_uuid16 == 0x2902 and tx_handle and h_handle == tx_handle + 1:
                    tx_cccd_handle = h_handle
                    print(f"# t={ts/1e6:.3f} TX CCCD at handle 0x{h_handle:04x}")

print(f"# ATT packets: {att_count} / ACL: {ble_acl_count}")
print(f"# RX handle: {rx_handle}, TX handle: {tx_handle}, TX CCCD: {tx_cccd_handle}")
print()
print("# === Activity summary on Nordic UART handles ===")

# Second pass: emit a chronological log of writes / reads / notifications on
# our handles of interest.
def short(b, n=32):
    return b[:n].hex() + ("..." if len(b) > n else "")

# Pre-count notifications so we can print first + last with an in-between count.
ntf_count_total = 0
for ts, flags, pkt in records:
    if not pkt or pkt[0] != 0x02 or len(pkt) < 9:
        continue
    l2cap_len, cid = struct.unpack("<HH", pkt[5:9])
    if cid != 0x0004:
        continue
    att = pkt[9:9+l2cap_len]
    if att and att[0] == 0x1B and len(att) >= 3:
        att_handle = struct.unpack("<H", att[1:3])[0]
        if att_handle == tx_handle:
            ntf_count_total += 1
print(f"# (Total TX notifications across capture: {ntf_count_total})")

ntf_count = [0]
start_ts = None
for idx, (ts, flags, pkt) in enumerate(records):
    if not pkt or pkt[0] != 0x02 or len(pkt) < 9:
        continue
    handle_flags, _ = struct.unpack("<HH", pkt[1:5])
    l2cap_len, cid = struct.unpack("<HH", pkt[5:9])
    if cid != 0x0004:
        continue
    att = pkt[9:9+l2cap_len]
    if not att:
        continue
    op = att[0]
    if start_ts is None:
        start_ts = ts
    rel = (ts - start_ts) / 1e6
    name = OPCODES.get(op, f"op_0x{op:02x}")

    if op == 0x02 and len(att) >= 3:  # MTU_REQ
        mtu = struct.unpack("<H", att[1:3])[0]
        print(f"[{rel:8.3f}] {name} client_mtu={mtu}")
    elif op == 0x03 and len(att) >= 3:  # MTU_RSP
        mtu = struct.unpack("<H", att[1:3])[0]
        print(f"[{rel:8.3f}] {name} server_mtu={mtu}")
    elif op == 0x12 and len(att) >= 3:  # WRITE_REQ
        att_handle = struct.unpack("<H", att[1:3])[0]
        value = att[3:]
        tag = ""
        if att_handle == tx_cccd_handle:
            tag = " (TX CCCD)"
            if value == b"\x01\x00":
                tag = " (TX CCCD: enable notifications)"
            elif value == b"\x02\x00":
                tag = " (TX CCCD: enable indications)"
        elif att_handle == rx_handle:
            tag = " (RX char)"
        if att_handle in (rx_handle, tx_cccd_handle):
            print(f"[{rel:8.3f}] {name} handle=0x{att_handle:04x}{tag} value={short(value, 60)}")
    elif op == 0x52 and len(att) >= 3:  # WRITE_CMD (no response)
        att_handle = struct.unpack("<H", att[1:3])[0]
        value = att[3:]
        tag = ""
        if att_handle == rx_handle:
            tag = " (RX char, no-rsp)"
        if att_handle in (rx_handle, tx_cccd_handle):
            print(f"[{rel:8.3f}] {name} handle=0x{att_handle:04x}{tag} value={short(value, 60)}")
    elif op == 0x1B and len(att) >= 3:  # HANDLE_VALUE_NTF
        att_handle = struct.unpack("<H", att[1:3])[0]
        if att_handle == tx_handle:
            value = att[3:]
            # Don't dump every NAV-PVT — just the first few and a counter
            ntf_count[0] = ntf_count[0] + 1
            if ntf_count[0] <= 3 or ntf_count[0] == ntf_count_total:
                print(f"[{rel:8.3f}] NTF #{ntf_count[0]} ({len(value)}B) {short(value, 24)}")

