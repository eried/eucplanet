# next-version

The next release, settling. Features arrive here once they have been ridden on
next-experimental and stopped changing shape. Settings stay where they are put
and a build here is meant to get you home.

This is what the Play beta is cut from. If you want to help without gambling a
ride, this is the branch.

## What to check in this build

**Watch map (Wear OS).** Contributed by ZiraiMode (PR #25). Settings, Watch,
Watch map on: the watch gets a third page with your position, the route while
navigating, and minus and plus zoom buttons on the dial. Map orientation
(north up or heading up) and the telemetry strip along the top are settings on
the phone. Tiles come from the phone over the Data Layer, so it works with no
watch data plan; the first tiles take a few seconds. Advanced, Map cache, sets
how much the phone keeps. Report if the map lags the wheel, if the route is
missing while the phone shows one, or if the watch drains noticeably faster
with the page open.

**KingSong lock.** The Lock Wheel tile and the horn now work on KingSong,
from a tester's captures of the official app on a KS-18XL and confirmed by
him: nothing to type, the wheel takes the default six digits. If you set a
password in the KingSong app, the wheel ignores lock and unlock until the app
has sent it, so enter the same four digits in Settings, Advanced, Wheel
controls, KingSong app password; leave 0000 if you never set one. If yours
refuses, say which model and firmware, and whether a password is set.

**Begode PWM.** On a T4 (or any Begode whose speed used to read negative
before the app fixed that) the PWM tile reads a positive number under load,
and a PWM alarm set at, say, 60 % fires when you push past it (issue #24). On
a Master with stock firmware the PWM and amps tiles no longer flash to zero
once a second (issue #26). Both tiles should read steady while you ride.

**Flic 2 buttons.** They were dead in the last builds of next-experimental
(no scan, no forget, no presses). Scan, pair, forget and every action should
work again; your paired buttons should come back without re-pairing.

**HUD map.** The HUD's map screen drew "Map data not yet available" tiles at
its default zoom, and the Map style picker on the phone changed nothing on
the HUD. It draws the map now, and light, dark, OSM, topo and the others
follow the picker within a few seconds.

**Voice: weather and the list.** "Weather" fetches a forecast when there is
none cached ("Checking the weather" first), and answers with the score,
temperature, wind and humidity; each of those can be asked alone. "What can
I say" lists Wheel, Actions and Around you as three groups.

**Smaller things.** A `geo:` link or a Maps share into a closed app opens the
route instead of crashing. After a wheel disconnects the notification reads
Disconnected instead of the last speed. Settings backup keeps every alarm's
beep ramp, waveform and effect. NOSFET Aeon shows the measured headlight
level. Segmented settings labels wrap at the word in every language.

## Reporting back

Say which wheel, which firmware and what you expected instead. A trip
recording is worth more than a description, and if it crashed, Share crash log
on the About screen.

---

Each branch keeps its own BRANCH.md, and this one changes as the work does.
