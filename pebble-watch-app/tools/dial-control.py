#!/usr/bin/env python3
"""
Interactive control panel for the EUC Pebble watchapp dial.

Serves a web page of sliders (speed, battery, PWM, voltage, current, temp) plus
a connected toggle and unit pickers. Dragging a slider injects the value into the
running emery emulator via `pebble send-app-message` — the watchapp's real
AppMessage receive path — so the dial moves live. No phone, AVD, or wheel needed.

Run (in WSL, with the emery emulator up + watchapp installed):
    pebble install --emulator emery        # once, so the watchapp is running
    python3 pebble-watch-app/tools/dial-control.py
Then open http://localhost:8080 in a browser on Windows.

Keys 0-9 mirror PebbleProtocol.kt / eucplanet.c.
"""
import json
import os
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8080
UUID = "71cc8578-8aad-4179-8d5c-98bb0b13c2e1"
PEBBLE = os.path.expanduser("~/.local/bin/pebble")

# Latest human-readable state from the sliders.
state = {
    "connected": 1,
    "speed": 24.0,      # km/h
    "battery": 78,      # %
    "voltage": 84.0,    # V
    "current": 6.0,     # A
    "pwm": 42,          # %
    "temp": 31,         # C
    "unitSpeed": "kmh",
    "unitTemp": "C",
}
_version = 0
_lock = threading.Lock()

PAGE = """<!doctype html><html><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1">
<title>EUC dial control</title>
<style>
 body{background:#111;color:#eee;font:15px system-ui,sans-serif;margin:0;padding:18px;max-width:520px}
 h1{font-size:18px;margin:0 0 14px}
 .row{display:flex;align-items:center;gap:12px;margin:14px 0}
 .row label{width:84px;color:#bbb}
 .row input[type=range]{flex:1}
 .row .val{width:66px;text-align:right;font-variant-numeric:tabular-nums;color:#7CFC9B}
 .seg{display:flex;gap:8px;align-items:center;margin:14px 0}
 select,button{background:#222;color:#eee;border:1px solid #444;border-radius:6px;padding:6px 10px;font-size:14px}
 .toggle{cursor:pointer;user-select:none;padding:6px 12px;border-radius:6px;border:1px solid #444}
 .toggle.on{background:#1f7a3a;border-color:#2fae54}
 .hint{color:#888;font-size:12px;margin-top:18px}
</style></head><body>
<h1>EUC dial control &rarr; emery</h1>
<div class=seg>
  <span class=toggle id=conn onclick="toggleConn()">CONNECTED</span>
  <select id=us onchange=send()>
    <option value=kmh>km/h</option><option value=mph>mph</option>
    <option value=ms>m/s</option><option value=kn>kn</option></select>
  <select id=ut onchange=send()>
    <option value=C>&deg;C</option><option value=F>&deg;F</option><option value=K>K</option></select>
</div>
<div class=row><label>Speed</label><input id=speed type=range min=0 max=70 step=0.5><span class=val id=speedv></span></div>
<div class=row><label>Battery</label><input id=battery type=range min=0 max=100 step=1><span class=val id=batteryv></span></div>
<div class=row><label>PWM</label><input id=pwm type=range min=0 max=100 step=1><span class=val id=pwmv></span></div>
<div class=row><label>Voltage</label><input id=voltage type=range min=40 max=134 step=0.1><span class=val id=voltagev></span></div>
<div class=row><label>Current</label><input id=current type=range min=-30 max=120 step=0.5><span class=val id=currentv></span></div>
<div class=row><label>Temp</label><input id=temp type=range min=-10 max=90 step=1><span class=val id=tempv></span></div>
<div class=hint>Drag a slider &mdash; the emery dial updates live. Toggle CONNECTED off to show &ldquo;no wheel&rdquo;.</div>
<script>
let S=%s, conn=S.connected, t=null;
const F={speed:'speedv',battery:'batteryv',pwm:'pwmv',voltage:'voltagev',current:'currentv',temp:'tempv'};
const UNIT={speed:'',battery:'%%',pwm:'%%',voltage:'V',current:'A',temp:'\\u00b0'};
function init(){
  for(const k in F){const e=document.getElementById(k);e.value=S[k];e.oninput=()=>{paint(k);queue()};}
  document.getElementById('us').value=S.unitSpeed;
  document.getElementById('ut').value=S.unitTemp;
  document.getElementById('conn').classList.toggle('on',!!conn);
  for(const k in F)paint(k);
}
function paint(k){document.getElementById(F[k]).textContent=document.getElementById(k).value+UNIT[k];}
function toggleConn(){conn=conn?0:1;document.getElementById('conn').classList.toggle('on',!!conn);send();}
function queue(){clearTimeout(t);t=setTimeout(send,120);}
function send(){
  const b={connected:conn,unitSpeed:document.getElementById('us').value,unitTemp:document.getElementById('ut').value};
  for(const k in F)b[k]=parseFloat(document.getElementById(k).value);
  fetch('/set',{method:'POST',body:JSON.stringify(b)});
}
init();send();
</script></body></html>"""


def _sender_loop():
    last = -1
    while True:
        time.sleep(0.22)
        with _lock:
            v, s = _version, dict(state)
        if v == last:
            continue
        last = v
        ints = [
            f"0={s['connected']}",
            f"1={round(s['speed']*10)}",
            f"2={round(s['battery'])}",
            f"3={round(s['voltage']*10)}",
            f"4={round(s['current']*10)}",
            f"5={round(s['pwm'])}",
            f"6={round(s['temp']*10)}",
        ]
        args = [PEBBLE, "send-app-message", "--emulator", "emery", "--app-uuid", UUID,
                "--int"] + ints + ["--string", f"7={s['unitSpeed']}", f"8={s['unitTemp']}"]
        try:
            subprocess.run(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=12)
        except Exception as e:  # noqa: BLE001
            print("send failed:", e)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):  # quiet
        pass

    def do_GET(self):
        with _lock:
            body = (PAGE % json.dumps(state)).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        global _version
        n = int(self.headers.get("Content-Length", 0))
        try:
            data = json.loads(self.rfile.read(n) or b"{}")
            with _lock:
                state.update({k: data[k] for k in data if k in state})
                _version += 1
        except Exception as e:  # noqa: BLE001
            print("bad post:", e)
        self.send_response(204)
        self.end_headers()


def main():
    threading.Thread(target=_sender_loop, daemon=True).start()
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"EUC dial control on http://localhost:{PORT}  (open it in a Windows browser)")
    srv.serve_forever()


if __name__ == "__main__":
    main()
