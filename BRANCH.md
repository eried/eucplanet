# p6-multiwheel (v0.4.0-preview2)

Combines two preview branches into one APK pair:

- **`p6-fixes`** — InMotion P6 protocol research (telemetry, settings, motor
  temp, lock cooldown), watch on-screen buttons with click + hold actions
  + haptic + toast, Service Mode upgrades (Inspect tab, Attach picker,
  `/euc/watch_info` from the watch), big localization push (13+ locales,
  km/t and friends, system-locale auto-pick), gesture / dial / Spanish-copy
  fixes, and the Wheel report issue template fix.

- **`more-wheels-support`** — five new BLE families: KingSong, Veteran,
  Begode / Gotway, Ninebot, and the original InMotion V1. Clean-room from
  public docs under `docs/protocols/` (one per family). Telemetry and
  controls compile and pass static audits; **none have been ridden on real
  hardware yet** — they graduate from Preview to Verified through wheel
  reports.

The merge auto-resolved everything except the version bump (now
0.4.0-preview2, code 28) and a credits-line / branch-doc text overlap.

## Service Mode now spans every wheel family

Service Mode used to be P6-only in practice — Commands tab listed P6
queries, the Inspect tab knew only `P6 realtime` / `P6 detailed`. On
this branch it works for V14, P6, KingSong, Veteran, Begode, Ninebot,
and V1, regardless of what's connected. Per-family presets are picked
from a dropdown so a user with a V14 in front of them can still poke at
the KingSong command catalogue for research.

## What's verified

- V14, V12 family, P6: all production paths still work; preview18 of the
  P6 work has been bench-tested end to end.
- Watch app: on-screen buttons configurable, hold-toast + optional
  haptic, no crashes on the Wear emulator after the VIBRATE permission
  fix.

## What needs riding

The five new wheel families. If you're on a KingSong S22 / S20 / S18,
Veteran Sherman / Patton / Lynx, Begode / Gotway, Ninebot Z / E /
ONE, or InMotion V1 / V3 / V5 / V8, please pair, ride a careful first
loop, and file a Wheel report (`/issues/new/choose`).
