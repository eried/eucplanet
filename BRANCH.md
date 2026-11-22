# next-experimental

New features land here first. They work on the wheels they were written for
and may not work on yours yet, and settings can move between builds. Ride it
only when you can cut the ride short.

## Worked on

- 0.25.0 beta: everything from next-experimental, on the Play beta track and as a GitHub release.
- Android Auto is gone for now. Play rejected 0.24.0 over it, so 0.24.0 never reached the beta.
- ENGO smart glasses supported (ENGO 2 and 3), in Settings, Integration, Customize: pick a layout, fonts, and what each place shows as a number, a bar or a graph, with the next turn while you navigate.
- HUD (MotoEye): opens by itself after the HUD powers on, turn arrow for the whole route, route line on every map, your own overlay over the map.
- HUD map comes from the phone (online, cached or offline squares). The phone installs and updates the HUD app.
- Offline areas: street map and routing with no signal, quieter bike routes, reroutes in about 8 s. A refresh button checks for newer squares.
- Map: heading-up while navigating, closer zoom near turns, shortest stop order, GPX tracks ridden as drawn, reverse route.
- Navigator view with your own tiles, and Trip compare beside another ride.
- Engine sounds: eight recorded engines, the synthesised ones rebuilt.
- Volume keys work with the screen off. New actions: volume up and down, mute, flashlight, engine sound.
- Units: altitude as its own setting, UK starts on miles.
- Better translations, and new languages like Portuguese (Portugal), Indonesian, Thai and English (UK).
- InMotion V6, V8S and Begode Panther fixes. Watch: new layout.
- Reset buttons grey out when there is nothing to reset.
- Release builds are obfuscated (Play asks for it by Feb 2027).
- iPhone (TestFlight): the same app from shared code: dashboard, alarms, voice reports and commands, settings, themes, trips and trip tools, leaderboards and crew pass, Dropbox and the backup folder, navigation with offline areas, the Overlay Studio, motor sound, the MotoEye HUD, ENGO glasses, the radar, TPMS caps, GPS sensors, Flic buttons and home screen widgets, in all 31 languages.
- iPhone: left out because iOS does not allow it: volume keys, starting or skipping music, a window over other apps, sending the HUD its app, the watches. It never had Android Auto.
- iPhone: reconnects, records, speaks and beeps with the phone locked, and uploads when iOS gives it time. ENGO glasses are not tried with real glasses on an iPhone yet.
- iPhone: 0.25.0 merged from next-version: the offline refresh button, the native language review, and the ENGO editor without the "Element N" label.

## Please test

- **ENGO glasses:** pairing, reconnect, and does the glasses page match the phone preview?
- **MotoEye:** power it on: does the HUD app show once and stay? Does the map follow the route?
- **Offline:** airplane mode in a downloaded square: route, turn cues, map? Tap the refresh button on "Update weekly on Wi-Fi": does it say your areas are up to date?
- **Map:** navigate a two-stop route and a GPX track: turn cues at real turns, zoom right near turns?
- **Engine sound:** which engine sounds wrong, and at what speed?
- **Volume keys, screen off:** do click and hold fire? Say your phone model.
- **Your language:** anything odd or cut off? Say the screen.
- **Any wheel:** anything acting differently from the last build is a bug.

**iPhone** (TestFlight)
- **ENGO glasses:** pair them from the iPhone (once on each phone) and ride: does the glasses page match the preview?
- **Offline:** the refresh button on "Update weekly on Wi-Fi", as on Android.
- **Locked in your pocket:** do voice reports, alarm beeps and the recording carry on? Turn the wheel off and on: does it reconnect?
- **Navigation, locked:** turn cues at the right corners, and a reroute when you miss one?
- **Backup folder:** pick a folder in Files and record a trip: is it there? Point Android at the same synced folder: do trips and themes show on both?
- **HUD:** same Wi-Fi or Personal Hotspot, Settings, Integration, MotoEye HUD: does Auto find it?
- **Uploads with the app closed:** finish a ride offline, go to the Home Screen, turn the connection back on: is the trip on Dropbox and the leaderboard before you open the app?

## Reporting back

Open an issue with your wheel model and firmware. A Service Mode log beats a
description; if it crashed, Share crash log on the About screen.
