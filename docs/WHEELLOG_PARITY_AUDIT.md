# WheelLog parity audit

Cross-reference of every wheel family our parsers cover against WheelLog's
adapter implementations. Source-of-truth is `Wheellog/Wheellog.Android@master`
under `app/src/main/java/com/cooper/wheellog/utils/`. Goal of the audit is
fix planning, not implementation. Items here are deferred to a focused
follow-up session.

Last re-snapshotted 2026-05-25. Items shipped in earlier patch cycles have
been folded back into the OK rows; the "Priority" section at the bottom now
lists only the remaining work.

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
| Battery current (0x07 offset 2) | OK (sign-inverted at parse, matches WheelLog) |
| PWM from 0x00 | OK — three-way derived/hwPWM/extras dispatch via `derivedPwmPct` |
| True PWM (0x07 offset 8) | OK (raw value treated as percent directly) |
| Voltage (0x00 offset 2) | OK (raw u16 BE * scaler) |
| Voltage scaler indices 5/6 | OK (151 V → 2.25, 168 V → 2.50, matches WheelLog) |
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
| Smart-BMS frames 0x01 voltage gating | OK — `parseBmsSummary` returns null (pragmatic equivalent of WheelLog's `autoVoltage`-off default) |
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
| Battery % curve endpoints | OK — using WheelLog "classic" centivolt integer-div formula |
| "Better percents" 4-knot curve | MISSING (parser) — only "classic" linear |
| Temperature (0xA9 offset 12 + 0xB9 offset 14) | OK |
| Trip distance vs total odometer | OK — 0xA9 offset 6 → `totalDistance`, 0xB9 offset 2 → `tripDistance` |
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
| KS-16X | OK (84 V) |
| KS-16XF | MISSING (model) |
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
| Total odometer — L6 reads u64 then * 100 (we read u32 * 100) | PARTIAL — cm→m conversion fixed (u32 × 100); u64 upper bits still ignored |
| Total odometer — legacy R/V3 series `long@44 / 5.711e7` | MISSING (parser) — garbage total odo on R1*/R2*/V3* |
| Roll @72 zeroed for V8F / V8S | MISMATCH — WheelLog zeroes them; we read stale value |
| Roll @72 zeroed for V10 family | OK |
| Slow-info model decode (bytes 104/107 as 3-digit decimal) | OK — `id = high*100 + low`, V10 family + R-series now resolve from telemetry |
| Serial number byte order | OK — reads 7..0 |
| Firmware decode | OK |
| Pedal-tilt encode byte order | OK — BE-in-LE-slot slot reversal matches WheelLog (false alarm in original audit) |
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
| Settings: mute / transport | OK |
| Settings: handle / autoLight / soundWave / splitMode / volume / beamBrightness / splitAccel / splitBreak | MISSING (parser) — low-priority polish |
| Light command | OK — V12 two-beam `[0x50, v, v]` form via `InMotionV2LegacyCommands` |
| Alarm-speed write | OK — `[0x3e, …]` routed via `setAlarmSpeedCommit` |
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
| PWM (u16 BE @ 34, gated by `hasValidPwm(model)`) | OK — composite check `mVer ≥ 2 ‖ hasValidPwm(resolvedModel)` |
| Battery % | PARTIAL — now per-resolved-model classes (`mVer`-keyed), but still single linear segment per class. WheelLog has multi-segment curves with upper clamps. |
| Temperature (i16 BE @ 18 / 100) | OK |
| Trip distance (rev BE u32 @ 8) | OK |
| Total odometer (rev BE u32 @ 12) | OK |
| `mVer` byte at offset 28 — `getUint16BE / 1000` | OK — parsed and surfaced via `VeteranModel.fromMVer`; drives battery curve + PWM gate |
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
| ABRAMS (mVer 2, 100 V) | OK — corrected from 168 V |
| SHERMAN_S (mVer 3, 134 V) | OK |
| SHERMAN_MAX | MISMATCH — kept for backward compat; no WheelLog equivalent |
| PATTON (mVer 4, 134 V) | OK |
| LYNX (mVer 5, 151 V) | OK |
| SHERMAN_L (mVer 6, 151 V) | OK |
| PATTON_S (mVer 7, 134 V) | OK |
| ORYX (mVer 8, 218 V dual-pack) | OK |
| LYNX_S (mVer 9, 151 V) | OK |
| NOSFET_APEX (mVer 42, 151 V) | OK |
| NOSFET_AERO (mVer 43, 134 V) | OK |
| NOSFET_AEON (mVer 44, 151 V) | OK |

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
| Light command | OK — `setDriveFlags` with bit-2 read-modify-write |
| Tail-light command (DriveFlags bit 1) | MISSING (command) |
| DriveFlags caching from poll | OK — `lastDriveFlags` cached on each poll for RMW writes |
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
| Ninebot Z6, Z10 | OK |
| Ninebot Mini Plus | PARTIAL — routed to Z6 alias via `fromReportedName`, no dedicated enum entry |
| Ninebot Z8, Z11 | MISSING (model) — no WheelLog data either, but real wheels exist |
| Legacy One E / E+ / S2 / Mini / Mini Pro | OK |
| One T / One S / Z10 variants | MISSING (model) — completeness |

---

## Priority for the follow-up fix session

The HIGH-impact items from the first audit cycle (KingSong odometer swap,
InMotion V1 model decode + serial byte order, V12 light + alarm-speed,
Veteran `mVer` + missing models + ABRAMS voltage, Ninebot Z light, Begode
0x07 current sign, voltage scaler indices, PWM dispatch) have shipped. The
remaining list is mostly polish and longer-tail model coverage.

1. **HIGH IMPACT — still pending:**
   - InMotion V1: legacy R / V3 mileage divisor (also missing from enum)
   - InMotion V1: R0 u64 odometer encoding (need a labelled capture)
   - InMotion V1: L6 u64 odometer upper-32-bit branch
   - InMotion V1: alert frame `0x0F780101` (7 alert codes never dispatched)
   - InMotion V2 (V12): `classicMode` parsed but `offroadMode=false` hardcoded; never wires `classicMode` itself
   - InMotion V2 (V12): light-brightness write `[0x2b, low, high]` (no `0x2B` opcode)
   - Begode: MPU6500 SmirnoV temperature formula
   - Begode: alert byte (Live B offset 14, 8 alarm bits — over-V/over-T/transport never surface)
   - Begode: total odometer parse (no in-app surface yet)
   - KingSong: KS-18L imperial-mode mile scaler (`* 0.83`) — and the model itself

2. **MEDIUM — missing models that route into wrong battery class:**
   - KingSong: KS-18L, KS-18LH, KS-18LY, KS-S16P, KS-16XF, RockWheel
   - InMotion V1: V3 / R1 / R2 series (16 models, 3 different speed factors)
   - Begode: Nikola, Monster, Hero HS/HT split, RS HS/HT split
   - Ninebot: Z8, Z11, One T, One S, dedicated Mini Plus, Z10 variants
   - Veteran: SHERMAN_MAX still present without WheelLog equivalent — drop or pin to mVer 1

3. **LOW — additional fields and polish:**
   - Begode: LED mode @13, power-off time @8..9, settings bitfield (pedals / speed-alarms / roll-angle), motor-temp scale @0x07 offset 6, reverse-sign `gotwayNegative`, smart-BMS cells / temps / semi-V parsing, 0xFF Alexovik PID frame
   - KingSong: CPU load @14, fan flag @12, immediate 0xA4 echo (still queued), BMS pages, better-percents 4-knot curve, LED mode/strobe
   - InMotion V1: roll zero-out for V8F/V8S, PIN handshake retry (currently single send), pedal-tilt readback @56, PWM/power derivation hook, better-percents toggle, R1S=1000 / R1T=3810 speed factors
   - InMotion V2 (V12): alarmSpeed2 @13, mute / transport / handle button / autoLight / soundWave / splitMode / volume / beamBrightness / splitAccel / splitBreak settings
   - Veteran: `current` field is phase (label clarification), charge mode @22, sleep timer @20, speedAlert @24, speedTiltback @26, firmware string @28, pedalsMode echo @30, beep/horn variant auto-pick by mVer, sticky CRC across short/long frames, BMS aggregates (`getCellsForWheel`), battery-% multi-segment upper-clamp
   - Ninebot: `power = V * I`, Mini voltage zero-out, tail-light setter (DriveFlags bit 1), Legacy serial 3-param stitching (0x10/0x11/0x12), Z firmware nibble decode, signed-speed `abs()` for Legacy default / Mini, avg speed / op time / error / alarm / escstatus

This document is fix-list, not action-yet. Tackle in priority order.


---

## Phase-2 gaps (2026-05-25 deep audit)

Items below were surfaced by a second-pass audit that read the whole
WheelLog tree, not just the per-family parser headers. They sit *on top*
of the per-family tables above, not inside them. Most rider-visible
improvements live here.

### Cross-cutting

- **Wheel-emitted alert frames feed nothing.** Our `AlarmEngine` evaluates
  only user-defined `metric ≥ threshold` rules over telemetry. WheelLog has
  a parallel layer that fires from **wheel-emitted alert bytes** —
  Begode Live B byte 14 (8 alarm bits: over-V / over-T / transport / etc.),
  InMotion V1 frame `0x0F780101` (7 codes: tilt start, tiltback, fall,
  low-battery, speed cut-off, high load, bad cell), InMotion V2 7-byte
  error bitfield with named flags across MOS / motor / battery / board
  sensors, controller comm, motor block, output saturation, posture, lock,
  DFU, key long-press, Ninebot Z `errorcode` / `alarmcode`. Riders today
  get **no early warning** from the wheel itself — only our reactive
  thresholds. **Propose:** new `WheelAlarmRouter` that translates each
  family's "the wheel said X" into a fixed list of named alarms
  (`low-battery`, `high-temp`, `over-current`, `transport-mode`, `fall`,
  `posture`, `motor-block`, `lift`, `no-load`) the engine fires alongside
  user rules. This is the single highest-impact gap across the codebase.

- **No wheel-detection by service UUID.** Our `BleScanner.isLikelyWheel`
  (`ble/BleScanner.kt:89-141`) routes purely by name regex. WheelLog's
  `detectWheel` keys on **GATT service UUIDs** loaded from a JSON config,
  and only uses name strings for known exceptions. Riders with renamed
  wheels (common — many people put their name in the BLE name) are
  invisible to our scanner. **Propose:** add a post-connect verification
  step that reads the primary service list and reroutes if the name-based
  guess doesn't match. Also: RockWheel (name `RW` / `ROCKW*`) advertises on
  Begode's UUID but speaks the KingSong protocol — force the KingSong
  adapter regardless of UUID when the name matches.

- **Two-tier PWM alarm + warning dwell missing.** WheelLog has
  `warningPwm` independent of the speed-tier alarm and two-stage
  `alarmFactor1/2` for early advisory beep before the wheel itself tilts
  back. Our `AlarmRule` flattens to one threshold. **Propose:** PWM-band
  rules with two thresholds + dwell time at `data/model/AlarmRule.kt`.

### Begode

- **Firmware-banner tag set is wider.** Banner reply yields one of `GW`,
  `JN`, `CF`, `BF`, `NAME`, `MPU`. `BegodeParser.hwPwmFirmware`
  (`ble/BegodeParser.kt:54`) only flips on CF/BF. **Propose:** read `MPU`
  to disambiguate MPU6050 vs MPU6500 — this is what solves the SmirnoV
  temperature-formula gap at the source.
- **Alarm-mode preset write (`o`/`u`/`i`/`I`) missing.** 4-mode setting
  (Mode 0 30-35 km/h, Mode 1 35-45, Mode 2 80% PWM, Mode 3 CF custom).
  **Propose:** `BegodeCommands.setAlarmMode(0..3)`.
- **Alexovik (SmirnoV) PID surface.** Whole tuning surface (extreme mode,
  braking current, P/I/D factors, current Q-P/Q-I/D-P/D-I, dynamic comp,
  trick mode) sits behind the 0xFF frame the parser drops at
  `ble/BegodeParser.kt:157-162`. **Propose:** Service Mode "Alexovik" tab.
- **0x01 PWM-limit + BMS-ID surface.** Frame 0x01 bytes 2-3 carry the
  wheel's own PWM-tiltback threshold and byte 19 carries BMS index. We
  drop the frame at `ble/BegodeParser.kt:153`. **Propose:** parse — this
  is "what the wheel thinks its own tiltback is", useful for the Compare
  tab.
- **0x02/0x03 BMS cell pages.** Byte 18 = `0x02`/`0x03` (BMS1/2), byte
  19 = pNum 0..3, cells at `(i+1)*2` (BE u16 / 1000 V), global cell index
  `i + pNum*8`. Up to 30 cells. Currently dropped. **Propose:** stitch
  pages keyed on pNum into a 30-cell list + the 0x01 pack-V/I / two temps
  / semi-V.

### KingSong

- **F-series extended BMS (0xD0).** Structurally different layout from
  0xF1/0xF2 — cell count at byte 21, cells from byte 22, variable temp
  block, then current / voltage / remaining-% / cycles / factory-capacity
  / env temps + humidity. F18P / F22P only. **Propose:** dedicated 0xD0
  decoder keyed on F-series model.
- **Standard BMS pNum dispatch.** 0xF1/0xF2 byte 17 = pNum: `0x00`
  voltage / current / remaining / factory / cycles, `0x01` 6 temps + MOS,
  `0x02..0x05` cell blocks, `0x06` finalises with min/max/diff and
  averages. Needs a per-BMS-page state machine, not a single per-frame
  decode.
- **Commands exist but no UI surface.** `KingsongCommands` has
  `setChargeLimit` (:138), `setGyroSwitchOff` (:152), `setGyroFrontTrim`
  (:160), `powerOff` (:75). **Propose:** wire to Settings → Wheel.
- **LED-mode 0x6C + Strobe 0x53 writes missing.** Dominant differentiator
  on S22 / F22P — riders look for it. **Propose:** add builders.

### InMotion V1

- **BMS query built but never parsed.** `InMotionV1Commands.getBatteryCells`
  (`ble/InMotionV1Commands.kt:37-42`) sends the request; the response on
  CAN ID `0x0F550114` with cells-page selector is dispatched to
  `parseSlowInfo` which doesn't decode cells. **Propose:** add cells-page
  decode + plumb to BMS panel.
- **PIN handshake retry count = 6.** WheelLog literally checks
  `if (passwordSent < 6)` and retransmits per tick. Our single-send is the
  audit-flagged gap; the magic number to copy is 6.
- **`InMotionV1Commands.playSound(index)` exposed only as horn.**
  Arbitrary index supported. **Propose:** Service Mode sound-bank picker.

### InMotion V2 (V11 / V12 / V13 / V14)

- **Settings-frame fields skipped.** Confirmed V12 offsets WheelLog
  reads that we don't: alarmSpeed2 +13, fancierMode +19 bit 4, comfort
  sensitivity +20, classic sensitivity +21, speakerVolume +22,
  **lowBeamBrightness +26, highBeamBrightness +27**, **splitAccel +31,
  splitBreak +32**, mute +39 bit 0, handleButton +39 bit 2, autoLight
  +39 bit 3, transportMode +39 bit 6, soundWave +40 bit 2, splitMode +41
  bit 0. Our `InMotionV2ParserV12.parseSettings` (`:137-167`) reads only
  maxSpeed / alarm1 / pedalsAdjust / standby / classic / fancier / mute /
  transport. **Propose:** extend.
- **V13/V14 add Go-Home +32 bit 2 and Berm Angle +34 bit 5.** Our V14
  parseSettings (`ble/InMotionV2Parser.kt:91-128`) reads only DRL bit.
  **Propose:** add.
- **Many writes missing.** WheelLog has dedicated builders for
  `setAutoLight`, `setSoundWave`, `setSplitMode`, `setBermAngleMode`,
  `setFan`, `setFanQuiet`, `setGoHome`, `setHandleButton`,
  `setSplitAccelBreak`, `wheelCalibration`, `wheelCalibrationTurn`,
  `wheelCalibrationBalance`. **Calibrate alone** is a >2× ride-quality
  complaint absorber for V12 / V13 / V14 wheels with drifted pedals.
- **Light-brightness write (V12HS).** Independent low/high beam sliders
  riders use heavily. **Propose:**
  `InMotionV2LegacyCommands.setBeamBrightness(low, high)`.
- **V11 error bitfield.** 7-byte error block with per-bit decode across
  iPhase / Bus / Motor Hall / Battery / IMU / ControllerCom1/2 /
  BleCom1/2 / MosTemp / MotorTemp / BatteryTemp / BoardTemp / Fan / RTC /
  ExternalRom / VBusSensor / VBatterySensor / CantPowerOff / UnderV /
  OverV / OverBusCurrent / LowBattery / OverBoardTemp / OverSpeed /
  OutputSaturation / MotorSpin / MotorBlock / Posture / RiskBehaviour /
  NoLoad / NoSelfTest / Compatibility / PowerKeyLongPress / ForceDfu /
  Lock / CPUOverTemp / IMUOverTemp / HwCompatibility / FanLowSpeed.
  V11 owners never see fault diagnostics. **Propose:**
  `InMotionV2Parser.parseErrors(data)` → list of named flags →
  `WheelAlarmRouter`.

### Veteran

- **mVer ≥ 5 + len > 46 gate on smart-BMS long-frame.** Our
  `VeteranParser.feed` (`:115-116`) accepts any LEN > 38 as long-frame
  candidate. Narrow the gate to drop misframed garbage that aligns with
  `DC 5A 5C ... long_LEN`.
- **Cell-aggregation surface.** Compute `minCell / maxCell / cellDiff /
  total / avg` during pnum 3/7 iteration. **Dashboard "cell delta = 0.12 V"
  is the canonical pre-failure indicator for an aging cell.**
- **Extra frame-validity checks.** byte 22 == `0x00`, byte 30 ∈ {0x00,
  0x07}, byte 23 & 0xFE == `0x00`. Our `tryExtractFrame` (`:111-139`)
  checks magic + CRC only. **Propose:** add the three byte checks to drop
  bogus frames on noisy connections.
- **Beep variant by mVer.** mVer < 3 → legacy `b` byte; mVer ≥ 3 → 14-byte
  blob. `VeteranCommands.horn()` (`:37`) always sends the v3 blob.
  Original Sherman owners (mVer 0/1) silently get no horn.

### Ninebot

- **Z LiveData payload — more fields.** Confirmed offsets we ignore:
  `errorcode` @0, `alarmcode` @2, `escstatus` @4, `avgspeed` @12,
  `operatingtime` @20. Our `parseZTelemetry` (`ble/NinebotParser.kt:171-193`)
  reads only battery / speed / dists / temp / V / I. **Propose:** extend
  + feed `errorcode`/`alarmcode` to the alert router; surface `avgspeed`
  and `operatingtime` on the dashboard.
- **LED-segment colour writes (params 0xC8 / 0xCA / 0xCC / 0xCE).** Four
  separate per-segment writes. We only have `setLedMode` (0xC6 selector).
  **Propose:** `NinebotCommands.setLedSegmentColor(segment 0..3, r, g, b)`.
- **Mini voltage zero-out — actual rule.** WheelLog forces `voltage = 0`
  when `protoVersion == 2` (Mini) because its BMS doesn't report. We
  read whatever junk is at offset 24. **Propose:** force `voltage = 0f` in
  the Mini branch of `parseLegacyTelemetry`.
- **Legacy serial stitching uses 0x10 / 0x13 / 0x16** — *not* 0x10 / 0x11 /
  0x12 as the earlier audit said. Three separate reads, stitched in
  order. (Fix the audit + the planned fix together.)

### Recommended phase-2 priority (rider impact, descending)

1. **WheelAlarmRouter + wheel-emitted alert frames across all six families.**
   The single highest-impact gap. Wires Begode Live B byte 14 + InMotion V1
   `0x0F780101` + InMotion V2 7-byte error block + Ninebot Z
   `errorcode`/`alarmcode` into a common router that pushes named alarms
   into `AlarmEngine`.
2. **InMotion V2 calibrate + missing setting writes.** `wheelCalibration`,
   `setAutoLight`, `setSplitAccelBreak`, `setHandleButton`,
   `setBermAngleMode`, `setFan`, beam-brightness. V12HS riders ask for
   these constantly.
3. **InMotion V1 PIN retry (6×) + alert frame.** Single change unlocks
   robust reconnect after BLE flap, plus surfaces all 7 alert codes.
4. **Veteran cell-diff + frame-validity tightening.** Cell-diff is the
   pre-failure indicator riders monitor; the extra byte checks cut
   spurious decodes.
5. **KingSong BMS pNum dispatch (0xF1/F2 + D0 for F-series).** Per-cell
   view matches WheelLog's deepest BMS feature.
6. **Begode 0x01/0x02/0x03 BMS + 0x01 PWM-limit field.** Same rider
   value as KS BMS; PWM-limit also feeds the Compare tab.
7. **Ninebot Z extra LiveData fields.** Alert router + avg speed +
   operating time.
8. **Service-UUID detection + RockWheel rerouting.** Renamed-wheel
   riders stop being invisible.

Lower-priority polish (Alexovik PID set, KS LED/strobe, V1 sound bank,
Ninebot LED segments, Begode MPU tag, KS gyro/charge-limit UI) is fine
to ride along whenever the nearby file is open.
