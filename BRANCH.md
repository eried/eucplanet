# next-experimental

Where new features are built first. Things here work on the wheels they were
written against and may not work on yours yet, and settings can move or reset
between builds. Fine for a ride you are happy to cut short, not for one you
need to get home from.

For riders who are happy to report what broke. Everyone else wants the Play
Store build.

## What to check in this build

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
acceleration and comes back when you coast. Turn on Settings, Wheel
parameters, Override the wheel's percentage: both numbers should follow the
override together. Also available on overlay elements and, with an updated HUD
build, on the glasses.

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
