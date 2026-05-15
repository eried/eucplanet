# WheelLog parity audit

Cross-reference of every wheel family our parsers cover against WheelLog's
adapter implementations. Source-of-truth is `Wheellog/Wheellog.Android@master`
under `app/src/main/java/com/cooper/wheellog/utils/`. Goal of the audit is
fix planning, not implementation. Items here are deferred to a focused
follow-up session.

Format:
- **OK** = formula and field offsets match WheelLog (any difference is internal
  units only).
- **MISMATCH** = behaviour or formula disagrees and produces wrong readings.
- **MISSING (parser)** = telemetry field WheelLog reads but we don't.
- **MISSING (model)** = wheel variant in WheelLog absent from our enum.

---

## Begode (Gotway) — `BegodeParser.kt`, `BegodeModel.kt`

Reference: `GotwayAdapter.java`.

### Telemetry fields

| Field | Status |
|---|---|
| Speed (0x00 offset 4) | OK |
| Phase current (0x00 offset 10) | OK |
| Battery current (0x07 offset 2) | MISMATCH — sign inverted vs WheelLog (`* -1`) |
| PWM from 0x00 | MISMATCH — scale unclear, needs labelled capture |
| True PWM (0x07 offset 8) | MISMATCH — likely missing `* 100` factor |
| Voltage (0x00 offset 2) | OK (raw u16 BE * scaler) |
| Voltage scaler indices 5/6 | MISMATCH — we have 2.25/2.50; WheelLog has 2.50/2.25 (idx5=168V, idx6=151V) |
| 1.875 (126 V) tier | Custom extension — RS/EX2 land here, not in WheelLog |
| Temperature MPU6050 | OK |
| Temperature MPU6500 (SmirnoV FW) | MISSING (parser) — SmirnoV formula `(short/333.87 + 21.00)*100` |
| Motor temp (0x07 offset 6) | MISMATCH — likely off by 10x or 100x, verify on hot wheel |
| Trip distance (0x00 offset 8) | OK |
| Total odometer (Live B offset 2..5 u32) | MISSING (parser) — never read, no lifetime mileage exposed |
| Reverse-sign config (`gotwayNegative`) | MISSING (parser) — riders with mounted-backwards wheels need this |
| Settings bitfield pedals/speed-alarms/roll-angle (Live B offset 6) | MISSING (parser) — only miles flag is read |
| Alert byte (Live B offset 14, 8 alarm bits) | MISSING (parser) — HIGH IMPACT (high-power/over-V/over-T/transport never surface) |
| Light mode (Live B offset 15) | OK |
| Tilt-back sentinel (>=200 vs WheelLog >=100) | OK — our 200 is safer for Master Pro etc. |
| LED mode (Live B offset 13) | MISSING (parser) |
| Power-off time (Live B offset 8..9) | MISSING (parser) |
| Smart-BMS frames 0x01 voltage gating | MISMATCH — we override unconditionally; WheelLog gates on `autoVoltage` user pref |
| Smart-BMS 0x02/0x03 cells / temps / semi-V | MISSING (parser) — returned as null |
| 0xFF Alexovik PID frame | MISSING (parser) — low priority, SmirnoV FW only |
| Frame validity + re-sync at sizes 5/6 | OK |
| Imperial flag (Live B settings bit 0) | OK — already wired (post our recent rc3) |

### Model registry (vs `DialogHelper.kt` PWM table)

WheelLog has **no model→nominal-voltage map** — the user picks pack voltage
manually (7-option preference). Our model registry is a layer WheelLog doesn't
have, so most divergence here is "we add value WheelLog doesn't ship".

| Model | Status |
|---|---|
| MTEN4, MTEN5 | OK |
| MCM5_V1, MCM5_V2 | OK |
| MSX | OK |
| MSP | OK |
| HERO (HS / HT) | MISSING (model) — WheelLog has both HS+HT, we have one |
| EX, EX_N | OK |
| EX2 (126 V) | OK (custom — not in WheelLog) |
| EX30 (151 V — voltage TBD) | MISSING in WheelLog too; our 151 V is a guess from rider reports |
| RS (HS / HT) | MISSING (model) — WheelLog distinguishes HS / HT |
| RS_HT (134 V) | MISMATCH — WheelLog has RS HT in the 126 V bucket, not 134 V |
| T3 | OK |
| T4 (134 V) | Not in WheelLog — verify if real, drop or document |
| MASTER (134 V) | OK |
| MASTER_PRO (151 V) | Not in WheelLog — verify against rider telemetry |
| Nikola 84 V / 100 V | MISSING (model) |
| Monster 84 V / 100 V | MISSING (model) |

---

## KingSong — `KingsongParser.kt`, `KingsongModel.kt`

Reference: `KingsongAdapter.java`.

### Telemetry fields

| Field | Status |
|---|---|
| Speed (0xA9 offset 4 i16 LE / 100) | OK |
| Current (0xA9 offset 10 i16 LE / 100) | OK |
| PWM (0xF5 offset 15) | OK |
| CPU load (0xF5 offset 14) | MISSING (parser) |
| Voltage (0xA9 offset 2 u16 LE / 100) | OK |
| Battery % curve endpoints | MISMATCH — slight divergence (e.g. 67 V class: ours 53.2-67.2, WheelLog 50.00-66.00 classic) |
| "Better percents" 4-knot curve | MISSING (parser) — only "classic" linear |
| Temperature (0xA9 offset 12 + 0xB9 offset 14) | OK |
| Trip distance vs total odometer | MISMATCH **(HIGH IMPACT)** — semantics swapped. 0xA9 offset 6 = lifetime odo (we map to trip), 0xB9 offset 2 = trip (we also map to trip and overwrite) |
| KS-18L imperial-mode mile scaler (`* 0.83`) | MISSING (parser) — KS-18L riders in mile mode see wrong distance |
| Mode byte sentinel (0xE0) | OK |
| Light / horn / pedal mode commands | OK |
| Fan flag (0xB9 offset 12) | MISSING (parser) |
| Charging flag (0xB9 offset 13) | OK |
| LED mode / strobe (0x6C / 0x53) | MISSING (parser, command) — low priority cosmetic |
| 0xA4 echo timing | MISMATCH — WheelLog writes immediately; we queue until next poll |
| Frame validity (header AA 55) | OK |
| BMS pages 0xF1/F2/D0 + 0xE5/E6 firmware | MISSING (parser) — full 30-cell parse, temps, cycles, capacity |

### Model registry

| Model | Status |
|---|---|
| KS14 | OK (84 V) |
| KS16 | OK |
| KS18 | OK |
| KS-S16 | OK |
| KS-S18 | OK |
| KS-S19 | OK |
| KS-S20 | OK |
| KS-S22 | OK |
| KS-F18P | OK |
| KS-F22P | OK |
| KS-16X, KS-16XF | MISSING (model) — important: wrong battery class without it |
| KS-18L, KS-18LH, KS-18LY | MISSING (model) — KS-18L is also the wheel with the imperial scaler |
| KS-S16P | MISSING (model) |
| RockWheel (prefix "RW" / "ROCKW") | MISSING (model) — WheelLog reroutes through KingSong protocol; we don't recognise |

---

## InMotion V1 (V5/V8/V10/V11 line) — `InMotionV1Parser.kt`, `InMotionV1Model.kt`, `InMotionV1Commands.kt`

Reference: `InmotionAdapter.java`.

### Telemetry / control

| Field | Status |
|---|---|
| Speed (LE@12 + LE@16 / factor*2) | OK for known models |
| Speed factor — R1S = 1000 | MISSING (model) |
| Speed factor — R1T = 3810 | MISSING (model) |
| Speed factor — R0 = 1000 | OK (we have R0) |
| Default speed factor 3812 | OK |
| Current (i32 LE @ 20) | OK |
| PWM / load %, derivation hook | MISSING (parser) — WheelLog calls `calculatePwm`/`calculatePower` after parse; we don't |
| Voltage (u32 LE @ 24 / 100) | OK |
| Battery % (V8/V10/default curves) | OK |
| Better-percents toggle | MISSING (parser) — we only ship the V10 "better" curve |
| Temperature MOS @32, IMU @34 | OK |
| Trip distance (LE @ 48) | OK |
| Total odometer (LE @ 44) — V8/V10 family | OK |
| Total odometer — R0 reads u64 (we read u32) | MISMATCH (HIGH for R0 riders) |
| Total odometer — L6 reads u64 then * 100 (we read u32 * 100) | MISMATCH |
| Total odometer — legacy R/V3 series `long@44 / 5.711e7` | MISSING (parser) — garbage total odo on R1*/R2*/V3* |
| Roll @72 zeroed for V8F / V8S | MISMATCH — WheelLog zeroes them; we read stale value |
| Roll @72 zeroed for V10 family | OK |
| Slow-info model decode (bytes 104/107 as 3-digit decimal) | MISMATCH **(HIGH IMPACT)** — we read as packed BCD nibbles; V10/V10F/V10S/V10T/V10FT and R-series never resolve from telemetry, fall back to BLE-name only |
| Serial number byte order | MISMATCH — ours iterates 0..7, WheelLog iterates 7..0; **serial reads backwards** |
| Firmware decode | OK |
| Pedal-tilt encode byte order | MISMATCH **(HIGH IMPACT)** — WheelLog uses BE-in-LE-slot pattern `t[3],t[2],t[1],t[0]`; we use LE. **Sets the wrong tilt angle when the rider adjusts pedals.** |
| Max-speed encode byte order | OK |
| Pedal-sensitivity encode / decode | OK |
| Volume encode / decode | OK |
| Pedal-tilt readback (offset 56) | MISSING (parser) |
| Light state, LED, handle button, ride mode readback | OK |
| Work mode (legacy + modern) | OK (modern variant drops the "Engine off" sub-state — cosmetic) |
| Frame framing, CRC, escape rules | OK |
| PIN handshake | MISMATCH (weaker) — we send once, WheelLog retries up to 6× until `PinCode` ack. Flaky-link reliability suffers. |
| Alert frame `0x0F780101` | MISSING (parser) — 7 alert codes never dispatched (tilt start, tiltback, fall, low-battery, speed cut-off, high load, bad cell) |
| Keep-alive poll cadence | OK (host-driven) |

### Model registry

| Model | Status |
|---|---|
| R0 | OK |
| V5, V5PLUS, V5F, V5D | OK |
| L6, Lively | OK |
| V8, V8F, V8S, Glide3 | OK |
| V10S, V10SF, V10, V10F, V10T, V10FT | OK |
| R1N, R1S, R1CF, R1AP, R1EX, R1Sample, R1T, R10 | MISSING (model) — 8 entries |
| V3, V3C, V3PRO, V3S | MISSING (model) — 4 entries |
| R2, R2N, R2S, R2Sample, R2EX | MISSING (model) — 5 entries |

---

## InMotion V2 — V12 + P6 — `InMotionV2Parser.kt`, `InMotionV2ParserV12.kt`, `InMotionV2Model.kt`, `InMotionV2Commands.kt`, `InMotionV2LegacyCommands.kt`

Reference: `InmotionAdapterV2.java`. V14 deliberately skipped (verified path
per rider). P6 has **no WheelLog reference at all** — we are the only public
implementation; only internal consistency can be audited.

### V12 (HS / HT / PRO / S) telemetry

| Field | Status |
|---|---|
| Speed (i16 LE @ 4 / 100) | OK |
| Current battery (i16 LE @ 2 / 100) | OK |
| PWM (i16 LE @ 8 / 100) | OK |
| Voltage (u16 LE @ 0 / 100) | OK |
| Battery % (u16 LE @ 24 / 100) | OK |
| Temperatures (mos / mot / board / cpu / imu, formula `byte-176`) | OK |
| Trip distance (u16 LE @ 22 in units of 10 m) | OK (algebraically equivalent) |
| Pitch / roll @16 / @20 | OK |
| State byte (pcMode / light bits) | OK |
| Power (i16 LE @ 10 * 100) | OK |
| Settings: maxSpeed @9, alarm1 @11, pedalAdj @15 | OK |
| Settings: alarm2 @13 | MISSING (parser) |
| Settings: classicMode @ byte19 bit0 | MISMATCH — we parse it then route `offroadMode=false`; WheelLog sets `setRideMode(classicMode)` |
| Settings: mute / transport / handle / autoLight / soundWave / splitMode / volume / beamBrightness / splitAccel / splitBreak | MISSING (parser) — mostly low-priority polish, but mute/transport are user-visible |
| Light command | MISMATCH **(HIGH IMPACT)** — we send V14 single-byte `[0x4B, on]`; V12 needs `[0x50, low, high]`. **V12 light toggles probably don't work.** |
| Alarm-speed write | MISSING (parser, command) — WheelLog sends `[0x3e, lo1, hi1, lo2, hi2]` for V12; we return null |
| Light-brightness write `[0x2b, low, high]` | MISSING (command) |
| Horn (`[0x51, 0x18, 0x01]`) | OK |
| setMaxSpeed byte order | OK |

### P6

| Field | Status |
|---|---|
| Everything | UNVERIFIABLE — WheelLog has no P6 entry. Internal consistency is OK (labelled captures match dashboard for speed, voltage, total odometer, max-speed, alarm, light, horn, pcMode). PWM and IMU temperature have only idle samples and need riding captures. |

### Model registry

| Model | Status |
|---|---|
| V12 HS / HT / PRO / S | OK |
| P6 | OK (no reference but our own labelling) |
| V14 (deliberately skipped per user) | — |

---

## Veteran (Sherman family) — `VeteranParser.kt`, `VeteranModel.kt`, `VeteranAdapter.kt`

Reference: `VeteranAdapter.java`.

### Telemetry fields

| Field | Status |
|---|---|
| Speed (i16 BE @ 6, /10) | OK |
| Voltage (u16 BE @ 4, /100) | OK |
| `current` field — actually phase current, not bus | MISMATCH — WheelLog stores it via `setPhaseCurrent` and derives bus via `wd.calculateCurrent()`. Our downstream UI labels it as bus current. |
| Battery (BMS pnum 0/4 @69, @71 /100) | OK |
| PWM (u16 BE @ 34, gated by `hasValidPwm(model)`) | MISMATCH — we gate by enum-from-name; WheelLog gates by `mVer >= 2` from offset 28. Generic-named wheels mis-routed. |
| Battery % | MISMATCH — branches on enum-from-name only, single linear segment. WheelLog branches on `mVer` and has multi-segment curves with upper clamps. Sherman L (mVer 6, 151 V class) currently routed through Sherman 100 V curve. |
| Temperature (i16 BE @ 18 / 100) | OK |
| Trip distance (rev BE u32 @ 8) | OK |
| Total odometer (rev BE u32 @ 12) | OK |
| `mVer` byte at offset 28 — `getUint16BE / 1000` | MISSING (parser) **(ROOT CAUSE — high leverage)**. Drives battery curve, PWM gate, beep variant, getModel, cell count. Single fix unlocks 4+ downstream bugs. |
| Charging mode @ 22 | MISSING (parser) |
| Auto-off / sleep timer @ 20 | MISSING (parser) |
| Speed alert @ 24 (* 10) | MISSING (parser) |
| Speed tiltback @ 26 (* 10) | MISSING (parser) |
| Firmware version string @ 28 | MISSING (parser) — adapter doc-comment claims wired |
| Pedals-mode echo @ 30 | MISSING (parser) |
| Pitch (i16 BE @ 32 / 100) | OK |
| Beep / horn variant (mVer < 3 → ASCII "b"; else 14-byte blob) | MISMATCH — we always default to v3, require manual diagnostic for legacy. WheelLog auto-picks. |
| Trip reset / pedals stiffness / light | OK |
| Lock / volume / max speed / DRL / tiltback writes | OK (both sides intentionally absent) |
| Frame validity `DC 5A 5C` + CRC32 sticky | MISMATCH — our `usingCrc` is not sticky between short and long frames; WheelLog keeps it once set |
| BMS pnum 3/7 cell decode signed-vs-unsigned | MISMATCH (low risk) — we use signed; WheelLog uses unsigned. Realistic cell voltages don't flip sign so no observable effect today. |
| `getCellsForWheel` total / min / max / cell-diff aggregation | MISSING (parser) — once mVer is in, expose for BMS panel |
| Imperial flag | Not in Veteran protocol (both sides) |

### Model registry

| Model | Status |
|---|---|
| SHERMAN (mVer 0/1, 100 V) | OK |
| ABRAMS — currently 168 V | MISMATCH — should be 100 V class (mVer 2 in WheelLog) |
| SHERMAN_S (mVer 3, 134 V) | OK |
| SHERMAN_MAX | MISMATCH — no WheelLog equivalent. Drop, or pin to mVer 1 |
| PATTON (mVer 4, 134 V) | OK |
| LYNX (mVer 5, 151 V) | OK |
| SHERMAN_L (mVer 6, 151 V) | MISSING (model) |
| PATTON_S (mVer 7, 134 V) | MISSING (model) |
| ORYX (mVer 8, 218 V dual-pack) | MISSING (model) |
| LYNX_S (mVer 9, 151 V) | MISSING (model) |
| NOSFET_APEX (mVer 42, 151 V) | MISSING (model) |
| NOSFET_AERO (mVer 43, 134 V) | MISSING (model) |
| NOSFET_AEON (mVer 44, 151 V) | MISSING (model) |

---

## Ninebot — `NinebotAdapter.kt`, `NinebotParser.kt`, `NinebotCommands.kt`, `NinebotModel.kt`

Reference: `NinebotAdapter.java` + `NinebotZAdapter.java`.

### Telemetry fields

| Field | Status |
|---|---|
| Speed Z (u16 LE @ 10 / 100) | OK |
| Speed Legacy default / Mini (i16 LE @ 10 / 10) | MISMATCH (minor) — WheelLog wraps in `abs()`, we keep sign |
| Speed Legacy S2 (i16 LE @ 28 / 100) | OK (we use signed, WheelLog unsigned — no real-world impact) |
| Current (i16 LE @ 26 / 100) | OK |
| PWM / Power | MISSING (parser) — WheelLog calls `calculatePwm` + `calculatePower` after parse; trivial: `power = V * I` |
| Voltage (u16 LE @ 24 / 100) | OK |
| Mini voltage zero-out | MISSING (parser) — WheelLog forces 0 for `legacyVariant == MINI` (BMS doesn't report) |
| Battery % (u16 LE @ 8) | OK |
| Temperature (i16 LE @ 22 / 10) | OK |
| Trip distance (u16 LE @ 18 * 10 m) | OK |
| Total odometer (u32 LE @ 14 m) | OK |
| Light command | MISMATCH **(HIGH IMPACT for Z riders)** — `setLight` writes param `0xC6` (LED preset mode); should be `DriveFlags 0xD3` bit 2 with read-modify-write. **Z headlight toggle does nothing useful.** |
| Tail-light command (DriveFlags bit 1) | MISSING (command) |
| DriveFlags caching from poll | MISSING (parser) — code TODO already noted. Without it, any DRL / light / tail-light write clobbers the other bits. |
| Pedal sensitivity encode / decode | OK |
| Volume (param 0xF5, value << 3) | OK |
| Lock read / write | OK |
| Horn | OK (both sides absent) |
| Framing Z + CRC | OK |
| Framing Legacy + CRC | OK (parser comment says `len + 6`, actual frame is `len + 8` — typo only) |
| Z encryption (XOR keystream after GetKey 0x5B addr 0x16) | OK |
| Serial number assembly (Legacy) | MISSING (parser) — WheelLog stitches 3 params (0x10, 0x11, 0x12); we stop at 0x10 |
| Firmware nibble decode (Z) | MISMATCH — WheelLog splits high/low nibbles; we treat as bytes |
| Avg speed / op time / error / alarm / escstatus | MISSING (parser) — informational, not user-facing |

### Model registry

| Model | Status |
|---|---|
| Ninebot Z (single hardcoded in WheelLog) | OK |
| Ninebot Z6, Z10, Mini Plus | OK (richer than WheelLog) |
| Ninebot Z8, Z11 | MISSING (model) — no WheelLog data either, but real wheels exist |
| Legacy One E / E+ / S2 / Mini / Mini Pro | OK |
| One T / One S / Z10 variants | MISSING (model) — completeness |

---

## Priority for the follow-up fix session

1. **HIGH IMPACT — visible wrong readings:**
   - KingSong: trip / total odometer semantically swapped at parse time
   - InMotion V1: V10 family never resolves from telemetry (slow-info BCD decode)
   - InMotion V1: pedal-tilt write byte order wrong (sets wrong angle)
   - InMotion V1: serial reads backwards
   - InMotion V1: legacy R / V3 mileage divisor missing (total odo garbage)
   - InMotion V1: R0 / L6 read u32 (should be u64) at offset 44
   - InMotion V2 (V12): light command broken (sends V14 form)
   - InMotion V2 (V12): alarm-speed write missing
   - Veteran: ABRAMS at 168 V (should be 100 V class)
   - Veteran: `mVer` byte not parsed (root cause of 4+ downstream bugs)
   - Veteran: `current` is phase, downstream labels it bus
   - Ninebot Z: `setLight` writes the wrong param
   - Begode: 0x07 battery current sign inverted
   - Begode: total odometer never parsed
   - Begode: alert byte (8 alarm bits) never parsed

2. **MEDIUM — missing models that route into wrong battery class:**
   - KingSong: KS-16X, KS-18L (+ imperial scaler), KS-18LH, KS-S16P, RockWheel
   - InMotion V1: V3 / R1 / R2 series (16 models, 3 different speed factors)
   - Veteran: Sherman L / Patton S / Oryx / Lynx S / Nosfet ×3 (mVer-keyed, unlocked by item 1)
   - Begode: Nikola, Monster, Hero HS/HT split, RS HS/HT split

3. **LOW — additional fields and polish:**
   - Begode: LED mode @13, power-off time @8..9, settings bitfield (pedals / speed-alarms / roll-angle), MPU6500 temp formula, gating BMS voltage override behind `autoVoltage`, smart-BMS cells / temps / semi-V parsing
   - KingSong: CPU load @14, fan flag @12, immediate 0xA4 echo, BMS pages, better-percents curve
   - InMotion V1: alert frame `0x0F780101`, roll zero-out for V8F/V8S, PIN retry, pedal-tilt readback, PWM/power derivation
   - InMotion V2 (V12): alarmSpeed2, mute / transport / handle button / autoLight / soundWave / splitMode settings, light-brightness write
   - Veteran: charge mode / sleep timer / speedAlert / speedTiltback / firmware string / pedalsMode echo, sticky CRC, BMS aggregates
   - Ninebot: power = V * I, Mini voltage zero-out, DriveFlags caching, tail-light setter, serial stitching, Z firmware nibble decode, signed-speed `abs()`

This document is fix-list, not action-yet. Tackle in priority order.
