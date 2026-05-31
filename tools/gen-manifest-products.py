#!/usr/bin/env python3
"""
Regenerates the <iq:products> block in garmin-watch-app/manifest.xml from
the local CIQ SDK Manager device inventory. Picks every installed device
with `connectIQVersion >= 3.0.0` (matches our minApiLevel) and skips
handheld GPS / retired marvel-saga categories that aren't useful for an
EUC dial. Re-run after installing new device profiles via the SDK
Manager so the multi-device .iq build picks them up.

    python tools/gen-manifest-products.py
"""

import json
import os
import re
import sys

DEV_ROOT = os.path.expandvars(
    r"%APPDATA%\Garmin\ConnectIQ\Devices"
) if os.name == "nt" else os.path.expanduser(
    "~/.Garmin/ConnectIQ/Devices"
)
MANIFEST = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "garmin-watch-app",
    "manifest.xml",
)
MIN_VERSION = (3, 0, 0)
SKIP_PREFIX = (
    "gpsmap", "etrextouch", "oregon", "rino", "montana",
    "legacyhero", "legacysaga",
)


def parse_ver(s):
    parts = s.split(".")
    return tuple(int(p) for p in parts[:3])


def main():
    if not os.path.isdir(DEV_ROOT):
        print(f"Device root not found: {DEV_ROOT}", file=sys.stderr)
        sys.exit(1)

    ids = []
    for d in sorted(os.listdir(DEV_ROOT)):
        if d.startswith(SKIP_PREFIX):
            continue
        cfg = os.path.join(DEV_ROOT, d, "compiler.json")
        if not os.path.isfile(cfg):
            continue
        try:
            obj = json.load(open(cfg))
        except Exception:
            continue
        parts = obj.get("partNumbers", [])
        if not parts:
            continue
        ver = parts[0].get("connectIQVersion", "0.0.0")
        if parse_ver(ver) < MIN_VERSION:
            continue
        # Some Edge entry-level computers (130 / 130 Plus) only support
        # `datafield` and `background`, not `watchApp` — they'd fail the
        # build with "do not support app type 'watch-app'". Skip them so
        # the multi-device .iq still builds.
        types = {t.get("type") for t in obj.get("appTypes", [])}
        if "watchApp" not in types:
            continue
        ids.append(d)

    block = [
        "    <iq:products>",
        "      <!-- Auto-generated from the local CIQ SDK Manager device",
        "           inventory: every installed device with connectIQVersion",
        "           >= 3.0.0 (matches the minApiLevel above). Re-run",
        "           tools/gen-manifest-products.py after installing new",
        "           device profiles. -->",
    ]
    for d in ids:
        block.append(f'      <iq:product id="{d}"/>')
    block.append("    </iq:products>")
    new = "\n".join(block)

    with open(MANIFEST, "r", encoding="utf-8") as f:
        src = f.read()
    pat = re.compile(r"    <iq:products>.*?</iq:products>", re.DOTALL)
    out = pat.sub(new, src)
    with open(MANIFEST, "w", encoding="utf-8") as f:
        f.write(out)

    print(f"manifest updated with {len(ids)} devices (CIQ >= 3.0.0)")


if __name__ == "__main__":
    main()
