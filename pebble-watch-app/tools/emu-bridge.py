#!/usr/bin/env python3
"""
WSL-side bridge for the EUC Pebble shim.

Receives telemetry frames (JSON lines) from the fake Pebble app
(`:pebble-emu-shim`, running on the Android emulator) and injects them into the
running emery emulator with `pebble send-app-message`. It coalesces to the
latest frame and sends at ~1.5 Hz so the emulator isn't flooded by the phone's
~3.5 Hz pump.

Wire format per line, keys tagged by type by EmuForwarder.kt:
    {"i0":1,"i1":234,"i2":77,...,"s7":"kmh","s8":"C"}   (i = int, s = string)

Run (in WSL, emulator already up + watchapp installed):
    python3 pebble-watch-app/tools/emu-bridge.py
Then expose it to the Android emulator from Windows:
    adb -s emulator-5588 reverse tcp:5599 tcp:5599
"""
import json
import os
import socket
import subprocess
import threading
import time

HOST, PORT = "0.0.0.0", 5599
UUID = "71cc8578-8aad-4179-8d5c-98bb0b13c2e1"
PEBBLE = os.path.expanduser("~/.local/bin/pebble")
SEND_PERIOD = 0.66  # seconds (~1.5 Hz)

_latest = {}
_lock = threading.Lock()


def _sender_loop():
    last_sent = None
    while True:
        time.sleep(SEND_PERIOD)
        with _lock:
            frame = dict(_latest)
        if not frame or frame == last_sent:
            continue
        last_sent = frame
        ints, strs = [], []
        for k, v in frame.items():
            if k.startswith("i"):
                ints.append(f"{k[1:]}={int(v)}")
            elif k.startswith("s"):
                strs.append(f"{k[1:]}={v}")
        args = [PEBBLE, "send-app-message", "--emulator", "emery", "--app-uuid", UUID]
        if ints:
            args += ["--int"] + ints
        if strs:
            args += ["--string"] + strs
        try:
            subprocess.run(args, stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, timeout=12)
            print(f"-> emu: {' '.join(ints)} {' '.join(strs)}")
        except Exception as e:  # noqa: BLE001
            print("send-app-message failed:", e)


def main():
    threading.Thread(target=_sender_loop, daemon=True).start()
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((HOST, PORT))
    srv.listen(8)
    print(f"euc-emu-bridge listening on {HOST}:{PORT} (Ctrl-C to stop)")
    while True:
        conn, _ = srv.accept()
        with conn:
            conn.settimeout(2)
            buf = b""
            try:
                while b"\n" not in buf:
                    chunk = conn.recv(2048)
                    if not chunk:
                        break
                    buf += chunk
            except socket.timeout:
                pass
            for line in buf.decode(errors="ignore").splitlines():
                line = line.strip()
                if not line:
                    continue
                try:
                    with _lock:
                        _latest.update(json.loads(line))
                except Exception as e:  # noqa: BLE001
                    print("parse error:", e, line)


if __name__ == "__main__":
    main()
