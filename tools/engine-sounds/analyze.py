"""Listen with numbers: metrics and spectrograms for the rendered engine WAVs.

usage: python -I analyze_engines.py <render dir> [--png]

Per engine, over each ride phase (phases.txt):
  rms dB, peak, DC, spectral centroid, share of energy under 200 Hz (a
  phone speaker cannot play it), share above 6 kHz (hiss / aliasing),
  click rate (sample-to-sample jumps above 0.25), pitch of the strongest
  partial, and how much the pitch moves across the ride.
"""
import sys, os, wave, numpy as np
from numpy.fft import rfft, rfftfreq

d = sys.argv[1]
png = "--png" in sys.argv
phases = []
for line in open(os.path.join(d, "phases.txt")):
    t, name = line.strip().split("s ", 1)
    phases.append((float(t), name))

def read(path):
    with wave.open(path) as w:
        sr = w.getframerate()
        x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
    return sr, x

def spec(x, sr):
    n = len(x)
    if n < 2048: return None, None
    win = np.hanning(n)
    f = rfftfreq(n, 1 / sr)
    p = np.abs(rfft(x * win)) ** 2
    return f, p

def band(f, p, lo, hi):
    m = (f >= lo) & (f < hi)
    return p[m].sum() / max(p.sum(), 1e-12)

def analyze(path):
    sr, x = read(path)
    rows = []
    bounds = [t for t, _ in phases] + [len(x) / sr]
    for i, (t, name) in enumerate(phases):
        a, b = int(t * sr), int(bounds[i + 1] * sr)
        # skip the first 300 ms of a phase so smoothing has settled
        seg = x[a + int(0.3 * sr):b]
        if len(seg) < 4096:
            continue
        rms = np.sqrt(np.mean(seg ** 2))
        peak = np.max(np.abs(seg))
        dc = np.mean(seg)
        clicks = np.sum(np.abs(np.diff(seg)) > 0.25) / (len(seg) / sr)
        f, p = spec(seg, sr)
        cen = (f * p).sum() / max(p.sum(), 1e-12)
        sub = band(f, p, 0, 200)
        hiss = band(f, p, 6000, sr / 2)
        # strongest partial above 40 Hz
        m = f > 40
        pk = f[m][np.argmax(p[m])]
        rows.append((name, 20 * np.log10(max(rms, 1e-9)), peak, dc, cen, sub, hiss, clicks, pk))
    return sr, x, rows

report = []
for fn in sorted(os.listdir(d)):
    if not fn.endswith(".wav"):
        continue
    sr, x, rows = analyze(os.path.join(d, fn))
    report.append(f"== {fn}")
    report.append(f"{'phase':18} {'rms dB':>7} {'peak':>5} {'dc':>6} {'centr':>6} {'<200':>5} {'>6k':>5} {'clk/s':>6} {'peakHz':>7}")
    for r in rows:
        name, rms, peak, dc, cen, sub, hiss, clicks, pk = r
        report.append(f"{name:18} {rms:7.1f} {peak:5.2f} {dc:6.3f} {cen:6.0f} {sub:5.2f} {hiss:5.2f} {clicks:6.1f} {pk:7.0f}")
    if png:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        from scipy.signal import spectrogram
        f, t, S = spectrogram(x, sr, nperseg=2048, noverlap=1536)
        fig, ax = plt.subplots(figsize=(14, 5))
        ax.pcolormesh(t, f, 10 * np.log10(S + 1e-12), shading="auto", vmin=-110, vmax=-20, cmap="magma")
        ax.set_yscale("symlog", linthresh=100)
        ax.set_ylim(20, sr / 2)
        for pt, name in phases:
            ax.axvline(pt, color="w", lw=0.5, alpha=0.6)
            ax.text(pt + 0.1, sr / 2.3, name, color="w", fontsize=7, rotation=0)
        ax.set_title(fn)
        fig.tight_layout()
        fig.savefig(os.path.join(d, fn.replace(".wav", ".png")), dpi=80)
        plt.close(fig)
text = "\n".join(report)
open(os.path.join(d, "analysis.txt"), "w").write(text)
print(text)
