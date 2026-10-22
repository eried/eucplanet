# next-version

The next release, settling. Features arrive here once they have been ridden on
next-experimental and stopped changing shape. Settings stay where they are put
and a build here is meant to get you home.

This is what the Play beta is cut from. If you want to help without gambling a
ride, this is the branch.

## Worked on

- 0.22.0: everything from next-experimental, now the release. The full list is on the GitHub release page.
- Map: a shared `geo:` link or Maps place keeps your planned stops and asks first; a named place keeps its name.
- Map: while navigating, the stop list keeps each stop's distance and the total, not just the next leg.
- Lights: walking the wheel at a brisk pace no longer turns the light back on. It returns after 2 seconds of riding.

## Please test

**Any wheel**
- Update over 0.21.0 from Play or the APK: your settings, trips and paired wheel are still there?
- Walk the wheel after a ride with "Turn the light off when slowing down" on: the light stays off, and comes back a moment after you ride off?

**Map / navigation**
- Share a place from Google Maps with stops planned: it asks New route or Add as next, and the stop has the place's name?
- Navigate a route with three or more stops: the stop list distances and total stay the same as before you started?

**Android Auto** (Play install only)
- The car screen opens, speed and battery match the phone, Horn, Light and Record work?

## Reporting back

Say which wheel, which firmware and what you expected instead. A trip
recording is worth more than a description, and if it crashed, Share crash log
on the About screen.

---

Each branch keeps its own BRANCH.md, and this one changes as the work does.
