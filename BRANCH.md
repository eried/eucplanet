# hud-fixes

HUD link fixes, branched from next-experimental so everything there is in this
build too. For riders with a HUD (glasses, Motoeye, a second phone) who can
tell us what the phone said while the HUD was not connecting.

## What to check in this build

**HUD not found, and why.** From the 2026-09-11 shop capture: the phone sat on
the shop's WiFi with its hotspot off for 21 minutes and only said "Searching".
Now, with the link on and nothing found, Settings, Integration, HUD companion
says "Still searching. The phone is on WiFi and its hotspot is off" (or "no
WiFi and no hotspot"), and after the second empty search a toast says "HUD not
found. Phone hotspot is off." Turn the hotspot on: the card goes back to the
normal hint once the HUD pairs. A diagnostics capture now names the interface
too ("Phone networks: wlan0 10.250.3.26/24 (WiFi), hotspot off"). Not fixed
here: the 75 s the HUD's own radio took to find the hotspot once it was up.
That is the HUD's Android scanning, and the next step there is on the HUD app.

**Tyre sensors.** Settings, Integration, TPMS sensors, Scan for sensors. A
screw-on valve cap should be found within a minute and stay in the list after
the scan stops. Its pressure should now reach the dashboard tile, an alarm
rule, the trip graphs, the overlay and the HUD, not just the settings row. Let
some air out: the reading should fall and reach 0 on a flat tyre, and a low
pressure alarm should fire there. The wheel's own sensor (InMotion P6) still
works and steps aside when a cap is talking.

**Battery (est).** New dashboard tile, and an alarm metric filed under
Battery. It is the battery percentage with the load taken out, so on an 84 V
pack it should sit still while the plain Battery number dives under
acceleration and comes back when you coast. Two rules to hold it to: it never
goes up while you ride (only on the charger), and it only steps down once two
half minutes in a row agree the pack really dropped. If you see it climb
mid-ride, or dive on a launch, that is the bug to report. Turn on Settings,
Wheel parameters, Override the wheel's percentage: both numbers should follow
the override together. Also available on overlay elements and, with an updated
HUD build, on the glasses.

No wheel handy? Service Mode (hold the logo on the About screen) adds a
"Virtual Begode Master (sagging pack)" to the wheel picker: a pack that sags
ten points on the throttle, jitters, and loses a point a minute. A plain
Battery alarm at 30 % fires on the first burst; Battery (est) at 30 % should
fire only once the resting level is actually there, about seven minutes in.

**Speed splits button.** Settings, Dashboard layout, drag the Speed splits
action onto a slot. Each tap walks Splits off, accel, brake, both, and the tile
says which; it works with no wheel connected. Off pauses: the session's best
and last times stay, and Settings, Voice, Speed splits now lists them with a
Reset. A different wheel connecting clears them; a reconnect of the same wheel
does not.

**Pressure units.** Pick psi, bar, kPa, kgf/cm2 or MPa and check every screen
agrees: the tile, its graph, the alarm threshold, the settings row and the
overlay.

These three are in the Play beta as 0.20.3 (272) as well, so a rider who does
not want a CI build can test the same things from there.

## Reporting back

Say which wheel, which firmware and what you expected instead. A trip
recording is worth more than a description, and if it crashed, Share crash log
on the About screen.

---

Each branch keeps its own BRANCH.md, and this one changes as the work does.
