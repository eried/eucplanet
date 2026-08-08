# Release v0.12.1 — Trip map, charging cells, reliability + interface polish

_versionName 0.12.1 · versionCode 255 (wear 100255) · builds on v0.12.0 (254)_

---

## GITHUB RELEASE  (full notes — Markdown)

### 🔄 Landscape & rotation
- Per-screen rotation settings with adaptive layouts.
- Landscape navigator with a docked stops panel over the map.
- Tiny flip-cover screen support.

### 🔋 Charging monitor
- Packs tab shows real pack voltage and imbalance.
- Scrubbable prediction line through the 80% / 100% markers.

### 📈 Trip recording & sync
- Pending uploads self-heal on start and in the background.
- Cancel a running trip / Dropbox sync.
- Human-readable durations and a sustained (anti-spike) top speed.

### 📡 HUD (Moto Eye)
- Adaptive ping and faster off-air recovery.

### 🗣️ Voice
- Per-language voice picker with a spoken preview.

### 🎛️ Settings & wheels
- Settings visibility with a Reorganize dialog.
- New Advanced tunables (lock max speed, trip-finalize grace, upload retry).

---

### ✨ New in 0.12.1

**Trip details**
- Title now shows the ride span: **start → end** time.
- The **speed graph scales to a realistic top speed**, so one lone GPS/sensor
  spike no longer flattens the whole ride into the floor. The true peak is shown
  in the corner label (e.g. `0 – 35 (peak 80)`).
- The **route map now takes a one-finger pan** instead of fighting the page
  scroll, has a **fullscreen view** (button next to the map style), and a
  **marker that follows the charts**: scrub any graph and a dot moves along the
  route while every other chart shows the same moment.

**Charging**
- Battery **cells are tinted by how far they sit below the pack median**, so a
  slightly-weak cell stands out instead of blending into a flat green field.

**Reliability & alarms**
- A beep can **no longer be cut short** — it waits for the sound to finish, fixing
  the intermittent "half a beep" over Bluetooth.
- **"Stop all" now reliably clears the ongoing notification** (it could linger if
  telemetry arrived mid-shutdown).
- The numeric stepper bubble no longer flashes as the keyboard opens.

**Garmin watch**
- The watch app **auto-launches from the phone**, **closes on exit / Stop all**,
  and no longer **freezes or crashes the dial** on real devices.

**Interface polish**
- Floating field labels now match the system dropdowns (colour, height, and
  vertical alignment) in every theme.
- Rounded corners unified to 12dp across dialogs, menus and buttons.
- Settings **"More" always sits last**, and **Advanced can be reordered or
  hidden**. Native country picker in the profile.
- G-force sparkline reads calmly at idle.

---

## PLAY STORE  ("What's new" — keep under ~500 chars, plain text)

Trip details: start-to-end time in the title, a realistic speed graph, one-finger
map panning, a fullscreen map, and a marker that follows the charts as you scrub.

Charging cells now shade so slightly-weak cells stand out.

Fixes: beeps no longer cut off over Bluetooth, Stop All always clears the
notification, and Garmin watch launch/close is reliable.

Plus interface polish across settings.
