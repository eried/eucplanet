# Motor torque and phase amps as first-class metrics

Approved in chat 2026-08-26. Riders use phase amps to judge how close a wheel
is to its limit before pedal dip (a "400 A wheel" folklore number); torque is
the next best where phase amps are unavailable. Both already exist in
`WheelData` and on several live surfaces; this design closes the recording
pipeline and the remaining registries.

## Current coverage (verified in code)

| Surface | Torque | Phase amps |
|---|---|---|
| `WheelData` | field exists | field exists |
| InMotion V2 (V14 parser) | native, 0.01 Nm signed, offset 12 | derived = torque / Kt, only P6 Kt calibrated (0.586 Nm/A) |
| Begode | none | parsed but stored into `current`; `phaseCurrent` stays 0 |
| Veteran | none | wire value is phase current, exposed as `current` |
| Kingsong, InMotion V1 | none | battery current only |
| Dashboard tiles / MetricDetail | in `MetricCatalog` (AREA_BIPOLAR) | same |
| Web HUD (`HudServer`) | pushed | pushed |
| Studio overlays + Phone HUD | missing from `StudioMetric` | present |
| Trip CSV | not recorded | not recorded |
| Trip details tiles/graphs | none | none |
| Custom alarms (`AlarmMetric`) | none | none |
| Widgets (`WidgetSlotType`) | none | none |

## Decisions

1. **Phase amps is the headline metric, torque its sibling.** Each surface
   hides whichever the wheel cannot provide (NaN convention, like Current and
   PWM on trips that predate those columns).
2. **BLE layer**: Begode and Veteran mirror their phase reading into
   `phaseCurrent`, leaving `current` untouched (no behavior change; on those
   families the Current metric has always shown phase amps). InMotion V2 gets
   a per-model Kt table; models without a calibrated Kt derive no phase amps
   and show torque only. Kingsong and InMotion V1 report neither.
3. **Trip CSV**: two new columns, `Torque,Phase current`, inserted BEFORE
   `Extra`. All in-app readers are header-driven so column order is safe.
   Cells are blank when the wheel does not report the value. eucviewer is
   notified separately (prompt handed to that team).
4. **Trip details**: `torque` and `phaseCurrent` charts (bipolar, drive vs
   brake, like Current), rendered only when the trip carries data; `maxTorque`
   and `maxPhaseCurrent` KPI tiles as opt-in `EXTRA_TILE_KEYS`.
5. **Alarms**: `AlarmMetric.PHASE_CURRENT` ("A") and `AlarmMetric.TORQUE`
   ("Nm"), default comparator >=; voice labels included.
6. **Widgets**: `WidgetSlotType.TORQUE` and `WidgetSlotType.PHASE_CURRENT`.
7. **Studio**: add `TORQUE` to `StudioMetric` (bipolar-friendly formatting);
   this also covers the Phone HUD overlay and studio video overlays, which
   render the same elements.
8. **Old trips show nothing.** No estimated reconstruction from battery
   current / PWM: dividing by a small duty cycle amplifies noise, and the
   screen otherwise shows recorded data only (project rule 10).
9. **Out of scope**: periodic voice reports (alarms already speak), PIP,
   Wear OS, Garmin, Amazfit surfaces.

## Files

- `ble/BegodeParser.kt`, `ble/VeteranParser.kt`: mirror phase into
  `phaseCurrent`.
- `ble/InMotionV2Parser.kt` (+`InMotionV2Model.kt`): per-model Kt registry.
- `util/CsvWriter.kt` + `ui/recording/RecordingViewModel.kt` (legacy header):
  new columns, values from `WheelData.torque` / `.phaseCurrent`, blank when
  the source family provides none.
- `ui/recording/RecordingViewModel.kt` (`TripDataPoint`): `torque` and
  `phaseCurrent` fields, NaN when absent; CSV parse by header name.
- `ui/recording/TripDetailScreen.kt`: charts + tiles + registry keys.
- `data/model/AlarmRule.kt` + alarm evaluation: two metrics.
- `data/model/WidgetSlotType.kt` + widget renderer: two slots.
- `ui/studio/StudioMetric.kt`: TORQUE entry.
- `strings.xml` x23 locales; drift-guard tests for every touched registry.

## Testing

- Unit: CSV writer emits the new header and blank cells; TripDataPoint parses
  old headers (NaN) and new headers; source-pinning drift guards for tile,
  chart, alarm, widget, and studio registries.
- Emulator: virtual Begode Master (phase amps live), virtual V14/P6 (torque
  live); record a trip on each, verify graphs and tiles appear; verify an old
  trip shows neither. Custom alarm on phase amps fires on the virtual wheel.
