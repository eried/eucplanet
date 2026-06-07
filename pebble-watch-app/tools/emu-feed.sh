#!/usr/bin/env bash
#
# Dev demo: stream simulated EUC telemetry into the RUNNING Pebble emulator via
# `pebble send-app-message` (the SDK's AppMessage injector). Drives the
# watchapp's real AppMessage receive path with no phone and no hardware — a
# "fake phone" feeding the dial, mirroring what PebbleBridge sends on a real
# Pebble. Keys 0..9 match src/c/eucplanet.c / PebbleProtocol.kt.
#
# Usage (from WSL, with the emulator already up):
#   pebble build && pebble install --emulator emery
#   tools/emu-feed.sh [frames]      # default 40 frames, ~1 Hz
#
set -uo pipefail

UUID=71cc8578-8aad-4179-8d5c-98bb0b13c2e1
PEBBLE="${PEBBLE:-$HOME/.local/bin/pebble}"
FRAMES="${1:-40}"

for ((i=1; i<=FRAMES; i++)); do
  s=$(( 100 + ((i*43) % 290) ))          # speed km/h *10  (10.0 .. 38.x)
  bat=$(( 90 - i/2 ))                     # battery %, draining
  pwm=$(( 28 + s/12 )); (( pwm > 99 )) && pwm=99
  volt=$(( 846 - (90 - bat) ))            # volts *10, sagging under load
  temp=$(( 280 + i ))                     # temp C *10, warming up
  "$PEBBLE" send-app-message --emulator emery --app-uuid "$UUID" \
    --int 0=1 1=$s 2=$bat 3=$volt 4=60 5=$pwm 6=$temp \
    --string 7=kmh 8=C >/dev/null 2>&1 || true
  sleep 1
done
echo "fed $FRAMES frames to the emulator"
