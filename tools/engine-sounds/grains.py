"""Cut the grain banks the app's recorded engines play from.

A recorded engine is rebuilt from short grains of a real recording. Each
grain is two pattern periods long (a pattern is one crank revolution for a
piston engine, one chuff for the steam engine), Hann windowed and centred on
the combustion pulse, so grains laid end to end at their own rate give the
recording back and grains from anywhere in the bank line up pulse on pulse.
At another rate the app lays the same grains closer together or further
apart: the firing rate follows the wheel while the pipes keep their real
pitch, which is what a real engine does. The grains are grouped into bands
by pattern frequency so the app can pick the band the recording made at
about the target speed.

usage: python tools/engine-sounds/grains.py [--only KEY,KEY] [--report]

Reads tools/engine-sounds/grains.json and the recordings in
tools/engine-sounds/src/, writes app/src/main/res/raw/grain_<key>.bin.
Needs ffmpeg on the PATH and numpy + scipy.

Per engine in grains.json (besides the sources, hints and regions):
  grainsPerBand   grains kept per band (default 20)
  average         consecutive cycles averaged into each grain, 1 or 3 (default 1):
                  the engine repeats cycle to cycle, wind and hiss do not, so
                  three cycles averaged take 4.8 dB off the noise and leave the engine
  speakerLift     how much of the low end's rhythm is put into the speaker band (default 0.8)
  rumbleHz        everything under this is cut before the grains are made (default 70)

Bank format, little endian:
  "EUCG" u16 version=1  u16 sampleRate  f32 patternPerRev  u16 bands  u16 oneShots
  band:    f32 hz  u16 runs   run: u16 grains   grain: u16 length  i16[length]
  oneShot: u8 kind (0 startup, 1 shutdown, 2 pop)  u32 length  i16[length]
"""
import argparse, json, os, struct, subprocess, sys
import numpy as np
from scipy.signal import butter, medfilt, sosfiltfilt

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
SRC = os.path.join(HERE, "src")
OUT = os.path.join(ROOT, "app", "src", "main", "res", "raw")
SR = 22050
FRAME, HOP = 4096, 1024
# Rumble under 70 Hz is mud on any speaker; the firing harmonics above it are the engine.
# A recording that is nearly all thump (a small bike close to its pipe) is cut higher.
def rumble_hp(hz):
    return butter(2, hz, btype="highpass", fs=SR, output="sos")
# A phone speaker plays nothing under about 170 Hz, where a piston engine's firing
# fundamental and first harmonics sit: the chop of the engine. What a bass enhancer
# does: the low band is pushed through a curve that makes its harmonics, and those
# are added back where the speaker starts, so the low end's rhythm is heard on the
# phone while headphones still get the real low end.
LOW_BAND = butter(2, [40, 170], btype="bandpass", fs=SR, output="sos")
LIFT_BAND = butter(2, [160, 520], btype="bandpass", fs=SR, output="sos")
JUMP_COST = float(os.environ.get("GRAIN_JUMP_COST", "0.5"))


def decode(path):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-ar", str(SR), "-f", "f32le", "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32).astype(np.float64)


def speaker_lift(x, amount):
    if amount <= 0:
        return x
    low = sosfiltfilt(LOW_BAND, x)
    rms = np.sqrt(np.mean(low ** 2)) + 1e-9
    harm = sosfiltfilt(LIFT_BAND, low * np.abs(low) / rms + 0.5 * low * low / rms)
    hr = np.sqrt(np.mean(harm ** 2)) + 1e-9
    return x + harm * (amount * rms / hr)


def yin_scores(x, hz_lo, hz_hi):
    """Per frame: how well each of 300 log-spaced periods between 1/hz_hi and
    1/hz_lo repeats the signal (YIN's cumulative mean normalised difference,
    0 is a perfect period), the frame rms and the frame times. Short periods
    get a small bonus so the shortest true period wins over its multiples."""
    tau_min = max(2, int(SR / hz_hi)); tau_max = int(SR / hz_lo)
    W = tau_max
    N = 1
    while N < 4 * W: N *= 2
    hop = HOP
    n = (len(x) - 2 * W) // hop
    lags = np.exp(np.linspace(np.log(tau_min), np.log(tau_max), 300))
    cands = SR / lags
    bonus = -0.12 * np.log2(lags / tau_min)
    scores = np.zeros((n, 300)); rms = np.zeros(n); times = np.zeros(n)
    taus = np.arange(tau_max + 1)
    for i in range(n):
        seg = x[i * hop:i * hop + 2 * W]
        a = seg[:W]
        rms[i] = np.sqrt(np.mean(a ** 2)) + 1e-9
        corr = np.fft.irfft(np.fft.rfft(seg, N) * np.conj(np.fft.rfft(a, N)), N)[:tau_max + 1]
        c2 = np.concatenate([[0.0], np.cumsum(seg ** 2)])
        e_tau = c2[taus + W] - c2[taus]
        d = e_tau[0] + e_tau - 2 * corr
        d[0] = 0
        cmnd = np.ones_like(d)
        cum = np.cumsum(d[1:])
        cmnd[1:] = d[1:] * np.arange(1, tau_max + 1) / np.maximum(cum, 1e-12)
        scores[i] = -np.interp(lags, taus, cmnd) + bonus
        times[i] = (i * hop + W) / SR
    return cands, scores, rms, times


def viterbi(cands, scores, jump_cost=0.5):
    """The smoothest path through the candidate rates: a jump of one octave
    between frames costs jump_cost score points."""
    logc = np.log(cands)
    trans = jump_cost * np.abs(logc[:, None] - logc[None, :]) / np.log(2)
    n, m = scores.shape
    cost = np.zeros((n, m)); back = np.zeros((n, m), dtype=np.int32)
    cost[0] = -scores[0]
    for i in range(1, n):
        total = cost[i - 1][:, None] + trans                  # from x to
        back[i] = np.argmin(total, axis=0)
        cost[i] = total[back[i], np.arange(m)] - scores[i]
    path = np.zeros(n, dtype=np.int32)
    path[-1] = int(np.argmin(cost[-1]))
    for i in range(n - 1, 0, -1):
        path[i - 1] = back[i][path[i]]
    return cands[path]


def resolve_factor(tracked_low, expected):
    best, bestd = 1.0, 1e9
    for k in (0.25, 1 / 3, 0.5, 1, 2, 3, 4, 6, 8, 12, 16):
        d = abs(np.log(tracked_low / k / expected))
        if d < bestd: bestd, best = d, k
    return best


def cut_centres(x, times, fpat, usable):
    """Consecutive grain centres one period apart, each where it best lines up with the one before.
    Returns (hz, centre, period, run) per grain; a run is an unbroken stretch of the recording."""
    out = []
    dt = times[1] - times[0]
    end = len(x) - 2
    pos = FRAME // 2
    prev_c = None; prev_raw = None; run = 0
    while pos < end - 4 * FRAME:
        fi = int(np.clip(pos / SR / dt, 0, len(times) - 1))
        if not usable[fi]:
            pos += HOP; prev_c = None; run += 1
            continue
        hz = fpat[fi]; P = int(round(SR / hz))
        if pos + 2 * P >= end: break
        if prev_c is None:
            c = pos + P
        else:
            c0 = prev_c + P
            best, bestv = c0, -1e18
            L = 2 * P
            for c in range(c0 - P // 6, c0 + P // 6 + 1, max(1, P // 160)):
                a = c - P
                if a < 0 or a + L >= end: continue
                seg = x[a:a + L]
                m = min(L, len(prev_raw))
                v = float(np.dot(seg[:m], prev_raw[:m]))
                if v > bestv: bestv, best = v, c
            c = best
        a, b = c - P, c + P
        if a < 0: pos += P; continue
        out.append((hz, c, P, run))
        prev_c, prev_raw = c, x[a:b]
        pos = c + P // 2
    return out


def centre_on_pulse(x, items):
    """Moves every run of centres onto its combustion pulse: the loudest point of the cycle, found
    on each grain's smoothed envelope and taken as the run's median so a run keeps its continuity.
    Grains anywhere in the bank then line up pulse on pulse when the app crossfades or jumps."""
    out = []
    runs = {}
    for it in items: runs.setdefault(it[3], []).append(it)
    for run, its in runs.items():
        offs = []
        for hz, c, P, _ in its:
            k = max(3, P // 24)
            seg = np.abs(x[c - P // 2:c + P // 2])
            env = np.convolve(seg, np.ones(k) / k, mode="same")
            offs.append(int(np.argmax(env)) - P // 2)
        shift = int(np.median(offs))
        for hz, c, P, _ in its:
            c2 = c + shift
            if c2 - P >= 0 and c2 + P < len(x): out.append((hz, c2, P, run))
    return out


def warp(a, L):
    return np.interp(np.linspace(0, len(a) - 1, L), np.arange(len(a)), a)


def window_grains(x, items, average):
    """The grains: two periods around each centre, the neighbours in the run averaged in when asked
    (each warped to this grain's length so the cycles sit on top of each other), Hann windowed."""
    grains = []
    raw = [x[c - P:c + P] for hz, c, P, run in items]
    half = average // 2
    for i, (hz, c, P, run) in enumerate(items):
        acc = raw[i].copy(); n = 1
        for j in range(i - half, i + half + 1):
            if j == i or j < 0 or j >= len(items) or items[j][3] != run: continue
            acc += warp(raw[j], len(acc)); n += 1
        grains.append((hz, c, (acc / n * np.hanning(len(acc))).astype(np.float32), run))
    return grains


def make_bands(grains, per_octave, max_grains, min_run):
    hz = np.array([g[0] for g in grains])
    lo = hz.min() * 0.999
    edges = lo * 2 ** (np.arange(0, 60) / per_octave)
    bands = []
    for e0, e1 in zip(edges[:-1], edges[1:]):
        idx = np.where((hz >= e0) & (hz < e1))[0]
        if len(idx) < min_run: continue
        runs, cur = [], [idx[0]]
        for a, b in zip(idx[:-1], idx[1:]):
            if b == a + 1 and grains[a][3] == grains[b][3]: cur.append(b)
            else:
                if len(cur) >= min_run: runs.append(cur)
                cur = [b]
        if len(cur) >= min_run: runs.append(cur)
        if not runs: continue
        runs.sort(key=len, reverse=True)
        picked, total = [], 0
        for r in runs[:3]:
            take = r[:max(min_run, max_grains - total)]
            if len(take) < min_run: break
            picked.append(take); total += len(take)
            if total >= max_grains: break
        bands.append({"hz": float(np.median(hz[np.concatenate(picked)])),
                      "runs": [[grains[i][2] for i in r] for r in picked]})
    return bands


def one_shot(files, spec, scale=1.0):
    x = files[spec[0]] * scale
    a, b = int(spec[1] / 1000 * SR), int(spec[2] / 1000 * SR)
    seg = x[a:b].copy()
    fade = min(len(seg) // 8, int(0.03 * SR))
    if fade > 0:
        seg[:fade] *= np.linspace(0, 1, fade); seg[-fade:] *= np.linspace(1, 0, fade)
    return seg.astype(np.float32)


def i16(a):
    return (np.clip(a, -1, 1) * 32767).astype("<i2").tobytes()


def build(engine, report):
    key = engine["key"]
    files = {f: decode(os.path.join(SRC, f)) for f in engine["sources"]}
    fpr, ppr = engine["firingsPerRev"], engine.get("patternPerRev", 1.0)
    idle_rpm, max_rpm = engine["idleRpmHint"], engine["maxRpmHint"]
    average = int(engine.get("average", 1))
    lift = float(engine.get("speakerLift", 0.8))
    hp = rumble_hp(float(engine.get("rumbleHz", 70)))
    excl = {f: [] for f in files}
    for kind in ("startup", "shutdown"):
        if kind in engine: excl[engine[kind][0]].append(engine[kind][1:3])
    for p in engine.get("pops", []): excl[p[0]].append(p[1:3])
    for e in engine.get("exclude", []): excl[e[0]].append(e[1:3])
    all_grains = []
    run_base = 0
    for name, x in files.items():
        cands, scores, rms, times = yin_scores(x, idle_rpm / 60 * min(ppr, fpr) * 0.5, min(max_rpm / 60 * fpr * 1.3, 1200))
        ff = medfilt(viterbi(cands, scores, engine.get("jumpCost", JUMP_COST)), 5)
        loud = rms > rms.max() * engine.get("quietFrac", 0.12)
        for s, e in excl[name]:
            loud[(times >= s / 1000 - 0.1) & (times <= e / 1000 + 0.1)] = False
        if not loud.any(): continue
        k = resolve_factor(np.percentile(ff[loud], 15), idle_rpm / 60 * ppr)
        fpat = ff / k
        xs = speaker_lift(sosfiltfilt(hp, x), lift)
        files[name] = xs
        items = centre_on_pulse(xs, cut_centres(xs, times, fpat, loud))
        g = window_grains(xs, items, average)
        for hz, c, arr, run in g: all_grains.append((hz, c, arr, run + run_base))
        run_base += 10000
        if report:
            blocks = []
            for b0 in range(0, int(times[-1]) + 1, 2):
                m = (times >= b0) & (times < b0 + 2) & loud
                blocks.append(f"{np.median(fpat[m]):.0f}" if m.sum() > 2 else "-")
            print(f"  {name}: tracker/{k:g}, pattern {np.percentile(fpat[loud], 5):.1f}..{np.percentile(fpat[loud], 95):.1f} Hz, {len(g)} grains")
            print("    " + " ".join(blocks))
    bands = make_bands(all_grains, engine.get("bandsPerOctave", 4), engine.get("grainsPerBand", 20), 6)
    # the idle band is the lowest with real material; bands above eight times it are not this engine running
    enough = min(16, engine.get("grainsPerBand", 20))
    idle_band = next(b for b in bands if sum(len(r) for r in b["runs"]) >= enough)
    bands = [b for b in bands if idle_band["hz"] * 0.9 <= b["hz"] <= idle_band["hz"] * 8]
    bands = bands[:engine.get("maxBands", 12)]
    # Level: every band is set, not left to the microphone. The engine at idle sits
    # at -16 dB rms and grows 3 dB per octave of revs, up to +6 dB (a Hann grain's
    # rms is 0.612 of the signal under it).
    def band_rms(b):
        return np.mean([np.sqrt(np.mean(g ** 2)) for r in b["runs"] for g in r]) / 0.612
    scale = 1.0
    for b in bands:
        target_db = -16 + min(6.0, 3.0 * np.log2(b["hz"] / idle_band["hz"]))
        bscale = 10 ** (target_db / 20) / max(band_rms(b), 1e-6)
        if b is idle_band: scale = bscale
        b["runs"] = [[np.clip(g * bscale, -1, 1).astype(np.float32) for g in r] for r in b["runs"]]
    shots = []
    if "startup" in engine: shots.append((0, one_shot(files, engine["startup"], scale)))
    for p in engine.get("pops", []): shots.append((2, one_shot(files, p, scale)))
    out = bytearray(b"EUCG" + struct.pack("<HHfHH", 1, SR, ppr, len(bands), len(shots)))
    for b in bands:
        out += struct.pack("<fH", b["hz"], len(b["runs"]))
        for r in b["runs"]:
            out += struct.pack("<H", len(r))
            for g in r: out += struct.pack("<H", len(g)) + i16(g)
    for kind, seg in shots: out += struct.pack("<BI", kind, len(seg)) + i16(seg)
    path = os.path.join(OUT, engine.get("asset", f"grain_{key.lower()}") + ".bin")
    with open(path, "wb") as fh: fh.write(out)
    lo_rpm = bands[0]["hz"] / ppr * 60; hi_rpm = bands[-1]["hz"] / ppr * 60
    print(f"{key}: {len(out) / 1024:.0f} KB, {len(bands)} bands {bands[0]['hz']:.1f}..{bands[-1]['hz']:.1f} Hz "
          f"({sum(sum(len(r) for r in b['runs']) for b in bands)} grains), recording covers {lo_rpm:.0f}..{hi_rpm:.0f} rpm, {len(shots)} one-shots")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="")
    ap.add_argument("--report", action="store_true")
    args = ap.parse_args()
    manifest = json.load(open(os.path.join(HERE, "grains.json")))
    only = {k for k in args.only.split(",") if k}
    for engine in manifest["engines"]:
        if only and engine["key"] not in only: continue
        build(engine, args.report)


if __name__ == "__main__":
    main()
