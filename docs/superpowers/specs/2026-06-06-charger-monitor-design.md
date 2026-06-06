# Charging Monitor — design spec (simple approach)

Branch: `feat/charger-monitor` (off `next-version`). Supersedes the earlier,
heavier charger branch. Key simplification: **everything is driven by the
battery %-climb rate, which is measurable on every wheel — so there is no pack
capacity guessing, no per-wheel capacity settings, no cross-session learning,
no triggers, and no charge logs.**

## Scope

In:
- Detect charging state per wheel and expose it app-wide.
- A **spark icon** on the dashboard top bar (left of Bluetooth) that hints
  charging / not, and opens the screen.
- A **dedicated Charging Monitor screen**: a big line-drawing of the wheel with
  a rising 2-color "liquid" fill (current charge + added-this-session), a cool
  gradient + drifting dots, a big % on top, ETA to 80% and to 100%, and a small
  tabbed info area with measurable graphs.
- A **within-session ETA** based purely on observed %/min, pessimistic after 80%.

Out (deliberately): charge history/logs, user-defined triggers/actions,
per-wheel capacity overrides, power-from-capacity derivation, cross-session
learning curves.

## Detection (from `docs/.../about_EUC_charging.txt`)

- **InMotion V14 / P6 (`InMotionV2Parser`)**: state byte `data[74]` bit 7 = charging.
- **InMotion V12 family (`InMotionV2ParserV12`)**: state byte offset 54 bit 7.
- **KingSong**: `KingsongParser.isCharging()` on the 0xB9 frame (when present).
- **Begode / Veteran / Ninebot / InMotion V1**: inference — sustained negative
  current.

New plumbing:
- `WheelData.charging: Boolean = false` (set by the explicit-bit parsers /
  KingSong adapter).
- `WheelRepository.chargeStatus: StateFlow<ChargeStatus>` where
  `ChargeStatus = Disconnected | Idle | Charging | Full`, derived as:
  - not connected → Disconnected
  - batteryPercent >= 100 → Full
  - `charging` flag true, OR sustained current < ~ -0.3 A → Charging
  - else Idle
  Gated by `speed == 0` so a moving wheel never reads as charging.

## Estimator (`service/ChargingEstimator.kt`, pure Kotlin, TDD)

Stateless-ish helper fed `(timestampMs, percent)` samples during a session:
- Tracks `sessionStartPercent` (first sample) → `addedPercent` for the 2nd liquid color.
- **Warm-up**: no ETA until we've seen ≥ ~2% climb AND ≥ ~30 s of samples (so
  plugging in at 79% still waits a couple % before predicting).
- **Rate**: least-squares slope of % over a rolling ~2-min window → `pctPerMin`
  (clamped to > 0; flat/negative ⇒ no ETA).
- **ETA to 80%**: `(80 - pct) / pctPerMin` when pct < 80.
- **ETA to 100%**: CC part `(min(80,target)-pct)/rate` + CV part `80→100`
  modeled pessimistically — the taper above ~80% slows charging, so the CV
  minutes are estimated as a tuned multiple of the observed CC rate (errs long
  so we under-promise). Tunable constant `CV_TAPER_FACTOR`.
- Output: minutes + absolute finish clock time (locale 12/24h via the caller's
  formatter), e.g. "22 minutes to 80% (at 21:04)".

## UI

- `ui/charging/ChargingMonitorScreen.kt` + `ChargingMonitorViewModel.kt`.
- **Liquid wheel** (`WheelFillGraphic`): a `WheelSilhouette` provides
  `(fillPath, outlinePath)` in a normalized viewBox. Placeholder = circle +
  square top, drawn procedurally — the whole pipeline is built/tested against it.
  Real per-wheel `VectorDrawable`s drop in later via a model→drawable map with
  the generic silhouette as fallback. Z-order: fill silhouette → 2-color liquid
  (current vs added) + vertical gradient + upward-drifting dots (clipped to
  fill) → outline strokes on top, always visible.
- Big % number on top; ETA lines under it; below, a `PrimaryTabRow` of info
  tabs reusing the dashboard sparkline canvas:
  - **Charge curve** — % over time.
  - **Voltage** — pack voltage over time.
  - **Packs / temp** — battery1 vs battery2 (balance) + max temperature.
  (Answer to "what can the tabs show when current is unknown?": all of the above
  are measured directly; charge **current/power** appears as an extra tab only
  for wheels that actually report current — V12, Begode, Veteran, Ninebot,
  KingSong-0xB9.)
- Spark icon: new `IconButton` left of the Bluetooth one in `DashboardScreen`
  actions; tint/animation from `chargeStatus`; `onClick = onNavigateToCharging`.
- Colors via new theme tokens `chargingAccent`, `chargingFillCurrent`,
  `chargingFillAdded` (no hardcoded colors, per CLAUDE.md).

## Testability

- `V14VirtualWheel` gains a charging simulation (battery % ramps, bit 7 set,
  speed 0) so the icon + screen + ETA can be verified on emulator-5588 without a
  real charger.
- Unit tests cover the estimator math (slope, warm-up, ETA-80, taper, added-%).
