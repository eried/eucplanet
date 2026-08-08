# Release v0.12.2 — Continuous-tone alarms, clean audio, charging imbalance graph

_versionName 0.12.2 · versionCode 256 (wear 100256, HUD 0.1.12) · builds on v0.12.1 (255)_

---

## GITHUB RELEASE  (full notes — Markdown)

### 🔔 Alarms & sound
- **Continuous-tone alarms**: set a beep's gap to 0 and it offers to play one
  unbroken tone that holds while the alarm is active and glides with your
  pitch/volume, instead of separate beeps.
- **No more start "scratch"**: a keep-alive holds the audio output warm while a
  wheel is connected (and while the alarm editor is open), so the first beep OR
  voice line after a quiet stretch no longer carries the speaker power-up pop.
- **Beep shaping**: smooth raised-cosine attack/release with a per-alarm
  Transition knob; gap 0 merges repeats into one longer tone.
- **Reliability**: a failed beep can no longer leak an audio track and silence
  later alarms.
- **Editor**: the delete confirmation now appears on top of the editor instead
  of dropping you back to the list first.

### 🔋 Charging monitor
- **Per-pack imbalance graph** on the Packs tab (pack-voltage lines plus an
  imbalance band, centered on zero).
- **Landscape side-by-side** layout with hold-to-scrub level and ETA.
- **CSV export**, taller portrait charts, and battery data that survives a
  disconnect on the same wheel, with a Reset data option.
- **InMotion P6** charge detection via percent-climb inference.

### 🗣️ Voice
- **Phone battery report** — e.g. "Phone fifty seven".

### 🧭 Trip
- **Landscape split layout**, a fullscreen route map that survives rotation, and
  a dedicated rotation section.
- **Short share links** (#d-...) for the trip viewer.

### 📡 HUD (Moto Eye)
- Subnet scan is staggered and capped so it stops starving mDNS discovery; the
  scan-delay setting is localized to all languages.

### 🔄 Sync
- Pending eucstats and Dropbox uploads reconcile on app start and via a periodic
  safety net.

---

## PLAY STORE  ("What's new" — keep under ~500 chars, plain text)

Continuous-tone alarms: set a beep's gap to 0 for one unbroken tone that holds while the alarm is active.

No more "scratch" before beeps or voice - the audio output now stays warm.

Charging: per-pack imbalance graph, a landscape layout with scrub and ETA, CSV export, and data that survives a disconnect.

Plus a phone-battery voice report, trip landscape layout and short share links, steadier HUD discovery, and alarm reliability fixes.
