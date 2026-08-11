# Garmin Instinct sub-screen layout - design

Date: 2026-08-08
Branch: `garmin-instinct-subscreen` (off `next-experimental`)

## Problem

The Garmin companion dial (`garmin-watch-app`) is drawn assuming a symmetric
round face: every element in `EucPlanetView.mc` / `SpeedGauge.mc` is positioned
as a fraction of the full `dc` (speed at 33% height, centered, unit label
extending right, etc.).

The Garmin Instinct 2 (and Instinct 2 Tactical, which share the `instinct2`
device profile) is a **semi-octagon** 176x176 display with a **62x62 sub-circle
in the top-right** (device profile `subscreen`: display-relative x approx 113,
y approx 0, i.e. top-right ~35% of the width). The centered speed numeral and
its right-hand unit label run into that sub-circle and get clipped. Reported by
a tester on Instinct 2 Tactical (FW 17.11, CIQ 3.4.4).

## Goal

On Instinct-shaped devices only: fix the clipping, and put the PWM % in the
sub-circle (that window exists for a secondary metric). Every other watch
(round / rectangle / edge) must render exactly as it does today.

## Approach

Gate an Instinct-specific layout on
`System.getDeviceSettings().screenShape == System.SCREEN_SHAPE_SEMI_OCTAGON`.
There is no runtime API for the sub-circle rectangle, but its location is
consistent across the Instinct family (top-right, ~35% of width) and it shares
the main `dc`, so drawing there renders in the physical window. The shape gate
auto-selects the right path: Instinct MIP models report semi-octagon; a round
Instinct 3 AMOLED reports round and takes the normal path untouched.

### Sub-circle (top-right)
- Region: a square inset hugging the top-right edge, sized ~35% of width
  (matches the 62/176 profile ratio).
- Content: PWM % centered, with a thin colored ring around it filling 0..100%.
  Color on the existing thresholds (green <70, orange 70..89, red >=90; reuse
  `SpeedGauge.COLOR_SAFE/WARN/DANGER`).
- No wheel / no PWM: dim `--`, no ring fill.

### Main area (Instinct only)
- Speed numeral: keep it centered horizontally but lower its vertical center
  (~48% instead of 33%) so the numeral + unit clear the sub-circle band
  (y < ~62 on a 176 display). This is the clip fix.
- Suppress the center PWM badge (`drawPwmBadge`) on Instinct - PWM now lives in
  the sub-circle, which also frees the middle for the lowered speed.
- Gauge arc, battery row, horn/light: keep the existing fractional positions
  (the arc opens at the bottom so the octagon top is fine). Verify the battery
  row and buttons clear the flat octagon bottom in the simulator; nudge only if
  needed.

## Isolation

All Instinct branching sits behind the semi-octagon check. Round / rectangle /
edge devices take the existing code path with no change. No shared helper is
altered in a way that affects the non-Instinct path.

## Testing

- Build the `instinct2` .prg with the local CIQ SDK 9.1.0 and run it in the
  simulator (renders the exact semi-octagon + sub-circle). Verify: speed not
  clipped, PWM % + ring in the window, battery row and buttons clear. Capture a
  screenshot for the tester.
- CI already builds `instinct2` in `release-apk.yml`, so the tester build ships
  automatically once merged.

## Out of scope

- Making the sub-circle metric configurable (PWM is the chosen fixed metric).
- Non-Instinct odd shapes (none in the current target list other than the
  Instinct family, which all share the top-right sub-circle).
