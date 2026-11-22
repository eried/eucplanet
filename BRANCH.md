# next-version

The next release, settling. Features arrive here once they have been ridden on
next-experimental and stopped changing shape. Settings stay where they are put
and a build here is meant to get you home.

This is what the Play beta is cut from. If you want to help without gambling a
ride, this is the branch.

## Worked on

- 0.25.0 beta: everything from next-experimental, on the Play beta track and as a GitHub release.
- Android Auto is gone for now. Play rejected 0.24.0 over it, so 0.24.0 never reached the beta.
- ENGO 2 and ENGO 3 glasses as a HUD (Settings, Integration, Customize): pick a layout, fonts, and what each place shows as a number, a bar or a graph, with the next turn while you navigate.
- HUD (MotoEye): opens by itself after the HUD powers on, turn arrow for the whole route, route line on every map, your own overlay over the map.
- HUD map comes from the phone (online, cached or offline squares). The phone installs and updates the HUD app.
- Offline areas: street map and routing with no signal, quieter bike routes, reroutes in about 8 s. A refresh button checks for newer squares.
- Map: heading-up while navigating, closer zoom near turns, shortest stop order, GPX tracks ridden as drawn, reverse route.
- Navigator view with your own tiles, and Trip compare beside another ride.
- Engine sounds: eight recorded engines, the synthesised ones rebuilt.
- Volume keys work with the screen off. New actions: volume up and down, mute, flashlight, engine sound.
- Units: altitude as its own setting, UK starts on miles.
- 30 languages, all reread by native speakers, English (UK) new.
- InMotion V6, V8S and Begode Panther fixes. Watch: new layout.
- Reset buttons grey out when there is nothing to reset.
- Release builds are obfuscated (Play asks for it by Feb 2027).

## Please test

- **ENGO glasses:** pairing, reconnect, and does the glasses page match the phone preview?
- **MotoEye:** power it on: does the HUD app show once and stay? Does the map follow the route?
- **Offline:** airplane mode in a downloaded square: route, turn cues, map? Tap the refresh button on "Update weekly on Wi-Fi": does it say your areas are up to date?
- **Map:** navigate a two-stop route and a GPX track: turn cues at real turns, zoom right near turns?
- **Engine sound:** which engine sounds wrong, and at what speed?
- **Volume keys, screen off:** do click and hold fire? Say your phone model.
- **Your language:** anything odd or cut off? Say the screen.
- **Any wheel:** anything acting differently from the last build is a bug.

## Reporting back

Open an issue with your wheel model and firmware. A Service Mode log beats a
description; if it crashed, Share crash log on the About screen.
