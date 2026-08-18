# HUD discovery: works without internet

Fixes discovery needing an active internet connection - reported as reliable
only outside the "Faraday cage" shop, slow or failing inside.

## What was wrong

The phone finds the HUD over the local network via mDNS, but it let the OS
choose which network to send that query on. When your WiFi has no internet,
Android quietly makes **cellular** the default network, so the query went out
the cellular radio and never reached the HUD on the local WiFi. With internet,
WiFi was the default and it worked - which is exactly why discovery was reliable
only once you got outside the shop.

## What changed

- mDNS is now bound directly to the phone's WiFi / hotspot interface instead of
  the default route, so discovery no longer depends on internet.
- The broadcast listener also recycles cleanly when you join a no-internet AP
  (the shop, or the phone's own hotspot).

## Please test

From a cold start **inside the shop** (no internet), navigate the HUD to EUC
Planet. It should discover and connect quickly - the same as it does outside.

**If it still fails inside, this build can tell us why.** Before the failing
attempt, turn on Service Mode (About -> press and hold the logo -> Enter), then
reproduce, then send the diagnostics log. It now records which networks the
phone could see, whether the beacon listener was bound and whether any beacon
ever arrived - which separates "the HUD is on another network" from "the signal
never made it through the walls". Both look identical without it.

Also worth checking on the HUD itself when it fails: which WiFi network does it
say it is on? An Android device prefers a network that has internet, so it can
quietly jump off the hotspot back to the shop WiFi. Nothing on the phone can see
that happen.

## Install

Only the phone app changed - the HUD device firmware is unchanged. Install
`phone-debug.apk` from this release (uninstall the Play Store version first: a
debug build can't update it in place).
