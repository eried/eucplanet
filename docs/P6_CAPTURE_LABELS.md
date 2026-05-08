# P6 BLE capture — labelled timeline

Source: `d:\Downloads\P6 again\` (btsnoop_hci.log + split-screen video
showing the official InMotion app on the left, hand-held camera on the
right). Wall-clock = video time + ~13:24:13 (anchored on the phone clock
tick from `1:24` to `1:25` between video frames 1 and 4).

The btsnoop ends around `13:27:58`, so video frames after `~3:45`
(roughly the second half of the recording) capture later panel browsing
that is **not** in the BLE log. Useful for naming settings, not for
correlating writes.

## Timeline

| btsnoop time | sub | bytes | what the user did | InMotion app screen |
|-------------:|:---:|:------|:------------------|:--------------------|
| 13:25:20.976 | `0x31` | `01` | tap **Lock** | dashboard |
| 13:25:21.452 | `0x34` | `46 a1 96 55` | (auto) auth handshake | — |
| 13:25:24.070 | `0x31` | `00` | tap **Lock** again to unlock | dashboard |
| 13:25:27.085 | `0x50` | `01 01` | tap **Light** on | dashboard |
| 13:25:29.885 | `0x50` | `00 00` | tap **Light** off | dashboard |
| 13:25:32.623 | `0x50` | `01 01` | tap **Light** on | dashboard |
| 13:25:35.252 | `0x50` | `00 00` | tap **Light** off | dashboard |
| 13:25:37.688 | `0x51` | `18 01` | tap **Horn** | dashboard |
| 13:25:39.762 | `0x51` | `18 01` | tap **Horn** again | dashboard |
| 13:25:53.067 | `0x21` | `20 4e` (=20000) | drag **Tilt-Back Set Speed** to max (200 km/h placeholder) | General Settings |
| 13:25:56.350 | `0x21` | `34 21` (=8500) | drag down to ~85 km/h | General Settings |
| 13:25:56.417 | `0x3e` | `35 21 00 00` (=8501) | release: commit tiltback @ 85.01 km/h | General Settings |
| 13:25:59.961 | `0x21` | `20 4e` (=20000) | drag back up to max | General Settings |
| 13:26:04.009 | `0x21` | `34 21` (=8500) | drag down to ~85 km/h | General Settings |
| 13:26:08.350 | `0x21` | `77 3a` (=14967) | drag up to ~150 km/h (= 93 mph) | General Settings |
| 13:26:12.031 | `0x21` | `ce 1e` (=7886) | drag down to ~79 km/h | General Settings |
| 13:26:12.079 | `0x3e` | `cf 1e 00 00` (=7887) | release: commit tiltback @ 78.87 km/h | General Settings |
| 13:26:16.426 | `0x21` | `75 0e` (=3701) | drag down to ~37 km/h | General Settings |
| 13:26:16.486 | `0x3e` | `76 0e 00 00` (=3702) | release: commit tiltback @ 37.02 km/h | General Settings |
| 13:26:20.601 | `0x21` | `77 3a` (=14967) | drag up to **93 mph** = 149.67 km/h (final) | General Settings |
| 13:26:25.795 | `0x3e` | `77 3a 00 00` (=14967) | release: commit tiltback @ 149.67 km/h | General Settings |
| 13:26:29.593 | `0x3e` | `e4 17 00 00` (=6116) | adjust **Speed Limit Alarm** intermediate (~38 mph) | General Settings |
| 13:26:35.167 | `0x3e` | `6f 35 00 00` (=13679) | commit **Speed Limit Alarm** @ 136.79 km/h = **85 mph** | General Settings |
| 13:26:40.961 | `0x4c` | `40 1f 40 1f 28 23` | drag **PWM Tilt-back Limit** down (80/80/90) | General Settings |
| 13:26:44.802 | `0x4c` | `10 27 40 1f 28 23` | settle: 100/80/90 | General Settings |
| 13:26:50.810 | `0x4c` | `10 27 70 17 28 23` | drag **PWM Level 1 Alarm** to 60 | General Settings |
| 13:26:56.583 | `0x4c` | `10 27 40 1f 28 23` | back to 100/80/90 (final) | General Settings |
| 13:27:01.399 | `0x24` | `00` | toggle **Speed Clamp at 25 km/h** OFF | General Settings |
| 13:27:11.016 | `0x24` | `01` | toggle **Speed Clamp at 25 km/h** ON | General Settings |
| 13:27:21.865 | `0x25` | `64 2d` | drag **Pedal Hardness** to 100 (high byte 100) | General Settings |
| 13:27:25.232 | `0x25` | `54 2d` | drag down to 84 | General Settings |
| 13:27:30.459 | `0x25` | `3c 2d` | drag down to 60 | General Settings |
| 13:27:35.212 | `0x25` | `2e 2d` | drag down to 46 | General Settings |
| 13:27:40.135 | `0x25` | `2c 2d` | drag down to 44 | General Settings |
| 13:27:45.306 | `0x25` | `2d 2d` | settle at 45 (final) — matches 45% on screen | General Settings |
| 13:27:54.108 | `0x22` | `e8 03` (=1000) | tap a toggle (likely Pedal Assist) | General Settings |
| 13:27:58.368 | `0x22` | `00 00` (=0) | tap toggle back | General Settings |

## Confirmed opcodes (CONTROL = `0x60`, sub `arg[0]`)

| sub | format | meaning | scale / encoding |
|:---:|:-------|:--------|:-----------------|
| `0x21` | `[lo hi]` (2B uint16 LE) | **setMaxSpeed** live drag preview | km/h × 100 |
| `0x22` | `[lo hi]` (2B uint16 LE) | unknown — possibly **Pedal Assist** or similar dual-state toggle (1000 ↔ 0) | — |
| `0x24` | `[01\|00]` (1B) | **Speed Clamp at 25 km/h** toggle | 1 = on |
| `0x25` | `[primary, secondary]` (2B) | **Pedal Hardness**? (high byte changes during drag, low byte stays at final) | percent |
| `0x31` | `[01\|00]` (1B) | **Lock / Unlock** | 1 = lock |
| `0x34` | 4 random bytes | auth challenge (one-shot per connect) | — |
| `0x3e` | `[v_lo v_hi 00 00]` (4B uint32 LE) | **commit scalar setting** (final value, used for tiltback after release, alarm-speed, …) | unit varies — same as the setting being committed |
| `0x4c` | `[a_lo a_hi b_lo b_hi c_lo c_hi]` (3 × uint16 LE) | **PWM thresholds** in this order: Tilt-back Limit, Level 1 Alarm, Level 2 Alarm | percent × 100 |
| `0x4e` | `[01\|00]` (1B) | unknown toggle (seen in the *first* capture, not this one) | — |
| `0x50` | `[on/off, on/off]` (2B mirror) | **Light** on/off | mirrored byte for confirmation |
| `0x51` | `[18 01]` (2B fixed) | **Horn** beep | fixed args |

## Pending / still uncertain

- `0x22` semantics: only two values seen (1000 and 0). Could be:
  - Pedal Assist enable (with a magnitude byte), or
  - Beep volume, or
  - Tilt Angle Limit (but 1000 / 0.01° = 10° doesn't match the 55° on screen).
  Need a labelled capture where the user toggles a single named setting.
- `0x25` low byte: stays constant at the final-byte value during a drag.
  Working theory: it's a "saved baseline" the wheel echoes back; could
  also be a per-mode (Comfort / S) variant.
- `0x3e` standalone (not paired with `0x21`): used for **alarm-speed**
  in this capture. Confirmed by 13679 = 136.79 km/h = 85 mph matching
  the visible "Speed Limit Alarm 85 mph" on screen.

## What this unlocks for the EUC Planet adapter

- **setMaxSpeed** as currently wired (`60 21` + `60 3e`) is correct.
- **setAlarmSpeed**: send `60 3e [v_lo v_hi 00 00]` with `v = km/h × 100`.
  Distinct command, no `60 21` preface.
- **setPwmThresholds(tiltback, alarm1, alarm2)**: `60 4c [t_lo t_hi
  a1_lo a1_hi a2_lo a2_hi]`, all percent × 100 (e.g. 100% → 10000 →
  `10 27`).
- **setSpeedClamp25kmh(on)**: `60 24 [on/off]`.
- **setPedalHardness(percent)**: `60 25 [percent, last_committed]` —
  high byte is live, low byte mirrors the eventual committed value.
  Probably acceptable to send `60 25 [percent, percent]` for atomic
  set without a drag-then-release dance.
- **commitScalar(value)**: `60 3e [v_lo v_hi 00 00]` — used for any
  scalar (alarm, tiltback after-release) when the wheel needs the
  final committed value flushed to flash.
