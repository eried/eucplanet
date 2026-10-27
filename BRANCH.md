# feature/koin

Only swaps how the app wires its parts together inside, a first step towards
an iPhone version. Nothing should look or behave differently. Built on
next-experimental, so everything in that build is here too. Ride it only when
you can cut the ride short.

## Worked on

- Internal wiring moved to a library that also runs on iPhone. No new features, no changes on screen.
- Build tools updated (Kotlin 2.3.21, Koin 4.2.2). No new features, no changes on screen.

## Please test

**Any wheel**
- Open every main screen, connect your wheel and ride with voice alarms on: anything that crashes or acts differently from next-experimental is a bug.
- Background sync (Dropbox, online uploads), home screen widgets, the watch, and Android Auto if handy: same as before?

## Reporting back

Open an issue with your wheel model and firmware (for Android Auto, the car
screen model and a photo). A Service Mode log beats a description; if it
crashed, Share crash log on the About screen.
