"""
UI survey runner. Generates 20 randomized dashboard configurations
(6 metrics + 6 actions each, with random side stats), pushes each
into the running app via the debug SurveyControlReceiver, screenshots
the dashboard, and writes an annotated index.html.

Pre-reqs:
  - emulator-5558 running with the debug APK installed
  - Virtual V14 connects on the first iteration (live telemetry follows)
"""
import base64, json, os, random, subprocess, time, html

ADB_BIN = os.environ.get("ADB", r"C:\Users\erwin\AppData\Local\Android\Sdk\platform-tools\adb.exe")
ADB = [ADB_BIN, "-s", "emulator-5558"]
OUT_DIR = os.path.dirname(os.path.abspath(__file__))
SHOTS_DIR = os.path.join(OUT_DIR, "shots")
os.makedirs(SHOTS_DIR, exist_ok=True)

# Catalog: metric key -> short display label (for annotation only).
METRICS = [
    ("BATTERY", "Battery"), ("TEMPERATURE", "Temp"), ("VOLTAGE", "Volts"),
    ("CURRENT", "Amps"), ("LOAD", "PWM"), ("TRIP", "Trip"),
    ("SPEED", "Speed"), ("BATTERY_POWER", "Bat W"), ("MOTOR_POWER", "Mot W"),
    ("ODOMETER", "Odo"), ("BATTERY_1", "Pack 1"), ("BATTERY_2", "Pack 2"),
    ("PITCH", "Pitch"), ("ROLL", "Roll"), ("G_FORCE", "G"),
    ("FORWARD_G", "Fwd g"), ("LATERAL_G", "Side g"), ("TORQUE", "Torque"),
    ("DYN_SPEED_LIMIT", "Sp limit"), ("DYN_CURRENT_LIMIT", "A limit"),
    ("MOTOR_TEMP", "Mot temp"), ("CONTROLLER_TEMP", "Ctrl temp"),
    ("BATTERY_TEMP", "Bat temp"), ("PHONE_BATTERY", "Phone"),
    ("GPS_ALTITUDE", "Alt"), ("GPS_SPEED", "GPS"), ("GPS_HEADING", "Bearing"),
    ("GPS_ACCURACY", "GPS acc"),
]
ACTIONS = [
    "HORN", "LIGHT_TOGGLE", "LOCK_TOGGLE", "SAFETY_TOGGLE", "SAFETY_ON",
    "SAFETY_OFF", "VOICE_ANNOUNCE", "RECORD_TOGGLE", "RECORD_START",
    "RECORD_STOP", "MEDIA_PLAY_PAUSE", "MEDIA_NEXT", "MEDIA_PREVIOUS",
    "OPEN_NAVIGATION", "OPEN_STUDIO", "OPEN_ABOUT", "OPEN_SERVICE",
    "OPEN_TRIPS", "MUTE_ALARMS", "RESET_TRIP", "TOGGLE_UNITS",
]
STATS = ["NONE", "CURRENT", "MIN", "MAX", "AVG", "SUSTAINED_PEAK", "MEDIAN", "P75", "P95", "P99"]

def adb_run(*args, capture=False):
    cmd = ADB + list(args)
    if capture:
        return subprocess.check_output(cmd)
    return subprocess.check_call(cmd)

def adb_shell(*args, capture=False):
    return adb_run("shell", *args, capture=capture)

def broadcast(action, extras, encode=True):
    # Base64-encode every extra (when encode=True) so JSON braces /
    # quotes / equals signs survive the Windows cmd.exe -> adb shell ->
    # Android am pipeline. The SURVEY_APPLY receiver decodes on device.
    # Simple alphanum extras (wheel_id) skip encoding via encode=False.
    cmd = ["shell", "am", "broadcast", "-a", action,
           "-n", "com.eried.eucplanet/com.eried.eucplanet.survey.SurveyControlReceiver"]
    for k, v in extras.items():
        out = base64.b64encode(v.encode("utf-8")).decode("ascii") if encode else v
        cmd += ["--es", k, out]
    adb_run(*cmd)

def screencap(path):
    # exec-out is mandatory on Windows — `adb shell screencap -p` goes
    # through the terminal layer which CRLF-translates the binary PNG
    # bytes and produces a corrupted file. `exec-out` is a passthrough
    # that hands the raw stdout from the device to us untouched.
    raw = subprocess.check_output(ADB + ["exec-out", "screencap", "-p"])
    with open(path, "wb") as f:
        f.write(raw)

def gen_config(seed):
    rng = random.Random(seed)
    metric_keys = rng.sample([m[0] for m in METRICS], 6)
    action_keys = rng.sample(ACTIONS, 6)
    # Stats per metric: weighted to occasionally hit "all stats" and
    # occasionally "no stats" so the survey exercises both extremes.
    stats_blob = {}
    annotations_metric = []
    for key in metric_keys:
        # 30% no stats, 40% one stat, 30% two/three
        n_sides = rng.choices([0, 1, 2], weights=[3, 4, 3])[0]
        left = rng.choice(STATS[2:]) if n_sides >= 2 else "NONE"
        right = rng.choice(STATS[2:]) if n_sides >= 1 else "NONE"
        # Center can be CURRENT or a stat override
        center = rng.choices([
            "CURRENT", rng.choice(STATS[2:])
        ], weights=[7, 3])[0]
        spark = rng.choice([True, False])
        stats_blob[key] = {"l": left, "c": center, "r": right, "spark": spark}
        ann = []
        if left != "NONE": ann.append(f"L:{left}")
        if center != "CURRENT": ann.append(f"C:{center}")
        if right != "NONE": ann.append(f"R:{right}")
        if not spark: ann.append("no spark")
        suffix = (" — " + ", ".join(ann)) if ann else ""
        annotations_metric.append(f"{key}{suffix}")
    metric_order = ",".join(metric_keys)
    action_order = ",".join(action_keys)
    return {
        "metric_order": metric_order,
        "metric_stats": json.dumps(stats_blob),
        "action_order": action_order,
        "annotations": {
            "metrics": annotations_metric,
            "actions": action_keys,
        }
    }

def main():
    print("Launching app...")
    adb_shell("am", "start", "-n", "com.eried.eucplanet/.MainActivity")
    time.sleep(3)

    print("Connecting virtual V14...")
    broadcast("com.eried.eucplanet.SURVEY_CONNECT_VIRTUAL",
              {"wheel_id": "V14"}, encode=False)
    time.sleep(5)  # let the virtual wheel produce ~5 seconds of telemetry
    # Bring the app back to the foreground if the foreground-service spin
    # for the connect demoted it. MainActivity is single-task so this is a
    # cheap no-op when it's already on top.
    adb_shell("am", "start", "-n", "com.eried.eucplanet/.MainActivity")
    time.sleep(1)

    configs = []
    for i in range(1, 21):
        cfg = gen_config(seed=i)
        print(f"[{i:02d}] applying config...")
        broadcast("com.eried.eucplanet.SURVEY_APPLY", {
            "metric_order": cfg["metric_order"],
            "metric_stats": cfg["metric_stats"],
            "action_order": cfg["action_order"],
            "composites": "{}",
            "custom_tiles": "{}",
            "action_groups": "{}",
        })
        time.sleep(1.6)  # let settings flow + dashboard recompose
        out_path = os.path.join(SHOTS_DIR, f"config_{i:02d}.png")
        screencap(out_path)
        configs.append((i, cfg))
        print(f"     -> {out_path}")

    write_index(configs)
    print("\nDone. Open:", os.path.join(OUT_DIR, "index.html"))

def write_index(configs):
    rows = []
    for i, cfg in configs:
        metric_html = "<ol>" + "".join(
            f"<li>{html.escape(m)}</li>" for m in cfg["annotations"]["metrics"]
        ) + "</ol>"
        action_html = "<ol>" + "".join(
            f"<li>{html.escape(a)}</li>" for a in cfg["annotations"]["actions"]
        ) + "</ol>"
        rows.append(f"""
        <section>
          <h2>Configuration #{i}</h2>
          <div class="row">
            <img src="shots/config_{i:02d}.png" alt="config {i}" />
            <div class="anno">
              <h3>Metrics</h3>{metric_html}
              <h3>Actions</h3>{action_html}
            </div>
          </div>
        </section>
        """)
    body = "\n".join(rows)
    html_doc = f"""<!doctype html>
<html>
<head>
<meta charset="utf-8">
<title>EUC Planet UI survey</title>
<style>
  body {{ font-family: -apple-system, system-ui, sans-serif; background: #181a1f; color: #d0d5dd; margin: 0; padding: 24px; }}
  h1 {{ color: #fff; }}
  section {{ background: #21252e; border: 1px solid #2c313b; border-radius: 12px; padding: 16px; margin-bottom: 24px; }}
  h2 {{ margin: 0 0 12px; color: #f0f3f7; }}
  .row {{ display: flex; gap: 20px; align-items: flex-start; }}
  .row img {{ max-height: 720px; border-radius: 8px; border: 1px solid #2c313b; }}
  .anno {{ flex: 1; min-width: 260px; }}
  .anno h3 {{ margin: 12px 0 4px; color: #9aa3b2; text-transform: uppercase; font-size: 12px; letter-spacing: 0.5px; }}
  .anno ol {{ margin: 0; padding-left: 20px; font-size: 13px; }}
  .anno li {{ padding: 2px 0; }}
</style>
</head>
<body>
  <h1>EUC Planet UI survey — 20 randomized layouts</h1>
  <p>Each section shows what the dashboard looks like with a particular random configuration. Annotations list the metric / stat-overlay configuration and the action ordering used. Scan for clipping, overlap, asymmetric spacing, or empty-looking widgets.</p>
  {body}
</body>
</html>
"""
    with open(os.path.join(OUT_DIR, "index.html"), "w", encoding="utf-8") as f:
        f.write(html_doc)

if __name__ == "__main__":
    main()
