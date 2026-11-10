# next-experimental

New features land here first. They work on the wheels they were written for
and may not work on yours yet, and settings can move between builds. Ride it
only when you can cut the ride short.

## Worked on

- Merged the latest next-experimental (reroute wait, offline-map question before a ride) into the iPhone branch.
- iPhone app (TestFlight): connects to the wheel, with the same dashboard, alarms, voice reports, settings, themes, Flic buttons, Dropbox sync, trip recording and trip list as Android.
- iPhone app: leaderboards and crew pass, trip details with the map, trip meter, metric details and the charging monitor with charge alerts.
- iPhone app: trip tools (rename, change wheel, split, extend), and the wheel reconnects by itself after a drop, as on Android.
- iPhone app: Legal Mode, the quick-action buttons on the lock screen (iOS 17), and messages like "Battery data exported" show at the bottom of the screen.
- iPhone app: Service Mode log and crash log to send, trips name the wheel, GPS saves power like Android, and the wheel link writes like Android's (fixes for InMotion V1 drops and repeated commands).
- iPhone app: automatic lights at sunset, Lock when I walk away, auto-record on connect, and the wheel connected and lights announcements, from the same code as Android.
- iPhone app: the Needs attention warnings (location, Bluetooth, notifications, backups), and a recording cut short by closing the app is saved or dropped at the next start, as on Android.
- Inside: the dashboard, settings, alarms, trips, trip details, trip tools, Legal Mode, the notification buttons, the reconnect timing, leaderboards, Dropbox sync, the wheel link, the automations and what runs on connect and every wheel update moved to a library shared with the iPhone. Nothing should change on screen.
- Settings, Dashboard layout no longer closes the app.
- Settings search no longer finds rows a phone does not show.
- Release builds are obfuscated (Play asks for it by Feb 2027). Branch APKs are debug builds and do not change.
- Navigator view: turn card, speed, arrival time and your own tiles on the map while navigating. Pick the tiles in Settings.
- Map: while navigating it faces where you ride (heading-up), turning on curves from the wheel's speed. Tap the compass for north-up.
- Map: zooms closer under 10 km/h, and near a turn keeps 15 s of road ahead in view.
- Map: Solve shortest route and Optimize stop order put the stops in the shortest riding order. Up to 49 stops.
- Map: a GPX track (Organic Maps, BRouter...) opens locked and is ridden as drawn, with cues from its bends. Convert to stops makes it editable.
- Map: Reverse route turns the stops round for the way back. Start far from a track rides you to its start first.
- Menus: options that cannot run grey out instead of disappearing, and delete rows are red with a bin.
- Android Auto: navigation on the car screen, with your four numbers beside it. Planned route rides a loaded track.
- HUD map: keeps working with the phone app in the background and no wheel connected. It now says when it is waiting for the phone GPS.
- HUD map: no more white "Map data not yet available" screen. Where the map has no detail that deep it shows the closest zoom it has.
- HUD camera: let go of the camera when you leave the Camera screen, so the MotoEye recorder can start.
- HUD camera: UP picks the next camera, DOWN turns the picture 90 degrees. Each camera keeps its own turn, and the HUD remembers them.
- HUD camera: the MotoEye camera button frees the camera, so its rear view and recorder work from any screen. The picture comes back by itself a few seconds after you stop recording or close the rear view. UP or DOWN also brings it back.
- HUD camera: opening a camera screen while the MotoEye records no longer takes its camera and freezes the clip. It says the camera is in use and comes back once the recording stops.
- HUD: the branch HUD APK is now a release build and starts two to three times faster.
- Voice alarms: a PWM alarm says "PWM" again in Italian, Norwegian and Chinese, it was left out.
- InMotion V8S and other V1 wheels: commands go out in small pieces like EUC World does, so the link should stop dropping.
- InMotion V8S: the light button and auto-lights follow the wheel's real light state, and Charging shows (also V8F, V10).
- InMotion V6: the light button sends the two-byte form the P6 uses. The V14 form did nothing on a V6.
- Lights: a reconnect while walking the wheel no longer turns the light on and off again.
- Dropbox: a few trips no longer upload again on every sync when nothing changed.
- Trip Details: Compare (the two-arrow icon) puts another trip beside this one, like eucviewer. Auto lines them up, tiles and charts show both or the differences, the map marks the other trip.
- Routing: bike routes come from Valhalla, which prefers quiet streets and paths (big-road share fell from 25% to 15% in tests). Walking with an avoid option keeps to footways.
- Routing: a new route after a wrong turn comes in about 8 s instead of 22 s and continues the way you ride, no U-turn. Routes from the car screen or the navigation overlay keep your avoid settings.
- Routing services: the Valhalla server has its own URL beside OSRM.
- Offline areas (Settings, Navigation & weather): each 5 by 5 degree square brings a street map and routing, so routes, reroutes and turn cues work with no signal. By default they are the backup when the servers or the signal fail; "Prefer offline map and routing" uses them first. The map's Select chip picks Around me (about 50 km), Around me, wider (about 200 km) or My route, and asks first with the size. Squares come from our mirror on Hugging Face (routing and a map for every square), with GitHub as the backup and brouter.de last (routing only). Squares downloaded before their map existed get it from "Update all".
- Offline map: sits under the map you picked (navigator, trip details, HUD, watch, Android Auto, video overlay) and shows through where online tiles fail, with "Offline" in bold at the top of the credit line and the map style button greyed; the online map returns by itself when the signal does. With "Prefer offline map and routing" on, downloaded squares show their own map even with signal (not on the HUD yet). With no signal, search says so, and a tapped stop is named from the map.
- Messages no longer hide under the keyboard, like "Search needs signal" or one that comes while typing in a number field.
- Languages: 7 new (Portuguese for Portugal, Indonesian, Vietnamese, Thai, Greek, Bulgarian, Slovak), 30 in all. The other 22 were reviewed to read like a native app: one word per term, no word-for-word phrases, leftover English translated.
- Inside: wiring moved to a library that also runs on iPhone, build tools updated (Kotlin 2.3.21, Koin 4.2.2). Nothing should change on screen.

## Please test

**iPhone** (TestFlight)
- Connect your wheel, ride with alarms and voice on, record a trip: does it match the Android app?
- Open the trip: map, tiles and charts right? Link Dropbox and sync: does the trip show up on your Android phone?
- Leaderboards (Settings, Backups): join, upload a ride, scan a crew pass QR.
- Charge the wheel with the Battery monitor open: do the 80% and full alerts come?
- Tap the warning icon on the dashboard: does each Fix take you to the right place?
- Legal Mode: arm it, lock, close and reopen the app, unlock with your code. Lock screen buttons (Settings, General, quick actions): do they act on the wheel?
- Your wheel brand: does it connect, show live numbers, and do light, horn and lock work? If not, a Service Mode log (hold the logo on About) helps most.
- Turn the wheel off mid-ride and on again: does the iPhone reconnect by itself? Rename and split a trip: does Android see it after a sync?
- Automations (Settings): set lights to follow sunset, and turn on Lock when I walk away. Does the light switch and the wheel lock as on Android?

**Android, after the move to the shared library**
- Legal Mode and the ride notification buttons: same as the last build?
- Turn the wheel off and on, and Bluetooth off and on: does it reconnect as before? Trip tools (rename, split, extend) work as before?
- Auto lights, volume by speed, music pause, walk-away lock, auto-record, charge alerts and the connect and lights announcements: same as the last build?
- Dashboard, Settings, alarm editor, Scan, the trip list, trip details, trip meter, metric details, battery monitor and leaderboards: anything that looks or acts differently from the last build is a bug.

**Map / navigation**
- Navigate a 2-stop route in Full path and Next segment: turn card, arrival time, the next stop counting down and ticked off, End navigation?
- Is the zoom right near turns and when riding slowly?
- Load a GPX track and ride it: cues at real turns, named places announced? Convert to stops works?
- After arriving, Reverse route: does it take you back a sensible way?
- Landscape and north-up: everything readable and reachable?

**Routing and offline areas**
- Plan a route you know well: does it pick the streets you would ride yourself? Miss a turn on purpose: does a new route come within about 10 s?
- Download the squares you ride in, turn on airplane mode, plan a route: does it come, do turn cues play, and does the map still show with street names? Airplane mode off: does your normal map come back within half a minute?
- Turn on "Prefer offline map and routing" with signal on: does the navigator show the offline map in your squares, and is the route as good as the online one?
- Offline areas, Select, Around me: does it pick the squares you expect, and say the size before downloading?

**Android Auto** (install from the Play internal sharing link, a sideloaded APK never shows in the car)
- Navigate from the car screen: turn-by-turn shows, and your four numbers beside it?
- Load a GPX track on the phone, then Places, Planned route in the car: does it follow the track?

**HUD (MotoEye)**
- Install the phone and the HUD APK from this build. Open the HUD Map screen, leave the phone app, no wheel connected: does the map stay? And with the wheel connected, on any map style and zoom?
- Camera screen: turn the front camera, switch to the rear one and back. Does each keep its own turn, also after restarting the HUD?
- MotoEye, without pressing UP or DOWN: 1) Camera screen, hold to start recording, then hold again to stop. Does the picture come back about 5 s after the stop? 2) Short press for the rear view and close it, does it come back? 3) Dashboard, start recording, go to the Camera screen (it should say "in use"), back, stop: does that file play to the end? If the picture stays off, send a photo of the small grey line.

**InMotion V8S (or any V1 wheel)**
- Ride as usual: how many disconnects, compared with EUC World? A Service Mode log covering any drop helps most.

**InMotion V6** (issue #33)
- Tap Light in the app: does the wheel light switch on and off?

**Trip compare**
- Open a trip, tap Compare, pick a ride over the same road: does Auto line them up? Try Stacked and Overlay, Show differences, and scrub the charts.

**Native speakers, any language**
- Use the app in your language for a ride: anything that sounds stiff, wrong for riders, or does not fit its button? Say the screen and the better words.

**Any wheel**
- Ride with voice alarms on, open the main screens, sync, widgets and the watch: anything that acts differently from the last build is a bug.

## Reporting back

Open an issue with your wheel model and firmware (for Android Auto, the car
screen model and a photo). A Service Mode log beats a description; if it
crashed, send the EUCPlanet-crashlog file from Downloads (iPhone: Share crash log on the About screen).
