# Weather / ridability forecast

This branch adds an opt-in weather module to the dashboard: a small
sun-behind-cloud icon above the map button that answers one question, is it a
good moment to ride.

Everything ships OFF. Enable it in Settings, Navigation & weather, "Weather on
the dashboard".

## What it does

- The icon tints GPS-style: lit while the forecast is inside its half-hour
  validity, dim otherwise.
- Tap it for the forecast panel: a ridability score from +5 (drop everything
  and ride) through 0 (fine with the right gear) to -5 (leave the wheel home),
  drawn light blue to magenta across the chosen window, with faces at the
  moments the ride character changes. Tap or drag on the curve for the exact
  score; tap a face for the read in rider terms.
- Hold the icon for the other windows (6 h / 24 h / 3 d / 1 week) and the
  settings shortcut.
- The chevron expands details: temperature, humidity and precipitation in one
  chart, wind and gusts in another, one finger-followed cursor across both,
  plus generated one-liners (rain or snow and when, strong gusts, near
  freezing, heat, golden hour).
- With an active navigator route the location chip swaps to the final stop:
  its forecast becomes the curve and the "here" curve rides along dashed, so
  you see whether the score improves toward the destination.
- Six keyless forecast sources: Open-Meteo (default), MET Norway (the yr.no
  backend), ECMWF, NOAA GFS, DWD ICON and Meteo-France.

## The score

Every hour starts at +5 and loses points only for real discomforts - wind
(gusts included), rain, snow, ice, cold, heat, dark - so a genuinely nice
local day tops out anywhere on Earth, not only at one perfect temperature.
Comfort preferences (Dislike / Neutral / Like per condition, including golden
hour) tune it to you; rain, snow and wind ship disliked. Real hazards are
preference-proof: wet pavement caps the score, freezing rain and near-gale
gusts pin it to the bottom. The comfort thresholds behind it live in Advanced
settings, Weather score.

## What to look for

- Does the icon appear immediately at app start (no pop-in), dim until the
  first fetch, then lit?
- Does the score match your gut for your local weather, with your preferences
  set honestly? Which hours does it get wrong, and which way?
- Switch sources: do the curves differ sensibly, and does every source fetch?
- Set a route with stops and swap the chip to the destination: is the
  comparison readable?
- Non-metric riders: are the detail readouts in your units?

## Reporting

https://github.com/eried/eucplanet/issues or the testing channel. Include
your rough location or climate, the source selected, and a screenshot of the
panel next to what the sky actually did.
