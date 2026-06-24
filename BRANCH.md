# Garmin watch app — automatic launch from the phone

This branch makes the **EUC Planet Garmin watch app start by itself** when you
open the phone app — no more opening it by hand on the watch at the start of
every ride. (We previously told users this was a Garmin limitation; it isn't —
the Connect IQ Mobile SDK has had the launch call all along, we just weren't
calling it.)

## Who should test this

- **Garmin watch / Edge owners** running the EUC Planet Connect IQ watch app.
- Anyone on Wear OS who wants to confirm the watch auto-start still behaves.

## What changed

- The phone now calls Connect IQ's `openApplication()` to launch the watch app,
  driven by the **Watch -> Auto-start** toggle and re-fired every time the phone
  app comes to the foreground.
- The **first** time, your watch shows a one-time **"Launch EUC Planet?"**
  prompt — tap **Always**, and from then on it opens automatically at the start
  of each trip. (Normal Connect IQ behaviour: first-run consent, not a per-ride
  prompt.)
- The "GARMIN — not supported" badge is gone from the Auto-start row, and
  Auto-start now appears for Garmin-only setups too (it used to be hidden unless
  a Wear OS watch was also paired). The genuine Garmin limits (telemetry rate
  cap, no dial rotation) keep their badge.

## How to test

1. Install this build's **phone APK** (uninstall the Play version first — it's a
   different signature; this wipes local app data). If you don't already have
   the watch app, install it from the `garmin-*.iq` (Connect IQ bundle) or your
   device's `.prg` in the `garmin-*-prgs.zip` asset below.
2. Pair your Garmin (watch or Edge) and confirm EUC Planet is installed on it.
3. Phone -> Settings -> **Watch** -> turn **Auto-start** on. Confirm there is
   **no "GARMIN" badge** on that row.
4. Fully close the EUC Planet app on the watch. Then open (or
   background -> foreground) the phone app. The watch should show
   **"Launch EUC Planet?"** -> tap **Always**.
5. Do it again: now the watch app should open **automatically with no prompt**,
   and the dial should start showing live telemetry.

## Reporting back

Open an issue at https://github.com/eried/eucplanet/issues or message in the
project's testing channel. Include:

- Your **watch / Edge model + firmware** — especially if the "Launch EUC Planet?"
  prompt never appears (a few Edge units on old firmware render a black dialog
  instead, an acknowledged Garmin bug; on those you can still open it by hand).
- Phone model + Android version.
