# next-experimental

New features land here first. They work on the wheels they were written for
and may not work on yours yet, and settings can move between builds. Ride it
only when you can cut the ride short.

## Worked on

- Map: arriving at the last stop shows one X that ends navigation, instead of minimize next to a dead red X.
- Map: adding a stop after the route is finished asks to start a New route (or Cancel). Reached stops can no longer be reordered around.
- Map: new style Satellite hybrid (road and place names over the imagery), also in trip details.
- Map: Share route sends your stops as a QR code, a link or a QR picture. Nothing is uploaded, the route is inside the code.
- Map: Scan QR (in Share) opens a route or joins a group ride. A route asks before replacing yours.
- Map: friends without the app open the link in a browser: the stops, the distances, Download GPX.
- Map: the stop list shows each stop's distance from the one before and the running total.
- Begode: Motor temp shows the motor sensor, not the board. Board temperature is now Controller temp.
- Begode Master is rider-tested: no more preliminary banner (the Master Pro keeps it).
- InMotion X1: shows its name, reads tiltback and alarms from its own settings page, all 56 cells per pack, 2 packs, no empty temperature sensors.
- Begode: an Auto off tile counts down to the wheel's auto power-off (m:ss).
- Fixed a crash on every launch for riders with a backup folder set (a start-up race).
- Crews pairing: scan or type a code from eucstats and the browser flies your colours.
- It lives in Settings then EUC Stats then Crews, and on the `eucplanet://pair` link.
- A code from anywhere but eucstats is refused unless you turned developer mode on yourself.
- Needs eucstats to have crews switched on; the web side says so if it does not.
- InMotion V6 support: connects, live telemetry, PWM, lights and horn.
- Writing to the wheel follows what its Bluetooth allows. Mostly the V6.
- Switch the light or lock by hand and that automation pauses. Fix in Needs attention hands it back.
- Branch builds install over each other. One last uninstall if you have an older one.
- Service Mode logs GPS speed next to the wheel's.
- Begode: current no longer gets stuck at 0 A after one noisy reading (#26).
- KingSong: a wheel locked before power-off shows Locked on reconnect, not Lock (#19).
- The lock tile's slow-down limit follows Lock max speed in Advanced.
- Stats length below 5 min now works: 30 sec shows 30 sec of stats, not 5 min.
- Long-press the Speed splits tile to open its settings.
- Headset voice button is one choice: Listen, Announce (speaks the voice report) or Off.
- Customize voice report is two pill lists, Periodic and Trigger. A pill can say Now, Max, Min, Avg or Peak, and Message pills speak your own words.
- Add offers every metric the dashboard can show (Phase amps, motor temperature, torque...), in a searchable list.
- Voice volume, in Voice, Speech, Advanced: only the voice gets quieter, music and alarms keep their level.
- Begode beep volume by speed, under Horn: quiet when stopped, loud while riding.
- Dropbox syncs both ways: settings, themes and overlays now come down too, not only trips.
- A trip renamed or split on one phone arrives on the other with no prompt. Only a trip changed on both asks.
- Archived (or split) on one phone, a trip stays archived on the others instead of coming back.
- Backup folder: no more endless "Checking backup folder", and the "trip (1).csv" copies it left are cleaned up.
- Fresh rides no longer show "not backed up to Dropbox" when they are.
- Settings search finds buttons and every row, like "Crew", "Scan" or "Pair", and lands on the match.
- Voice: one message per question. Listening, what you said and the answer replace each other instead of stacking.
- Voice menu: one row, Listen for a voice command, always starts a fresh listen. Tap the tile to stop.
- Music: Start music and Pause music for Flic, volume keys, HUD, watch and tiles. Start music wakes your last music app, or opens your music app and starts it.
- Music: Play / pause starts music when nothing plays. Start music wakes a closed player in the background first, opening it only if that fails.
- Voice: Music while listening (Pause, Lower, Keep playing) under Cue when listening. Pause by default.
- Weather: says Waiting for location while the phone finds a fix, and loads the forecast by itself once it has one.
- Voice: say play music, pause music, next song or previous song.
- Voice: Reset metrics after triggered report (Voice, off by default) resets like the Reset button once the report is read out.
- Charging: a third alert for storage. Notify me at... asks for a level (5 to 95, not 80), tap again to turn it off.
- Settings search: a card inside a section shows how many matches it hides, and the best one opens by itself.
- Settings search: a match on a button gets a band like everywhere else, not bold.
- Android Auto: your stats and wheel buttons on the car screen, beside a music app in a split. Opening Google Maps closes it (one navigation app at a time).
- Android Auto: a firing alarm shows on the car screen. Pick the stats and buttons in Settings, Integration, Android Auto.
- Battery: with no wheel, the phone stops waking for the watch, scans for the lost wheel slowly after 10 min, and refreshes the notification less.
- Battery: an idle phone no longer ticks for G-force, settings or absent watches; with no ride and no HUD, Wi-Fi may doze; tire pressure listens for the paired caps only.
- Battery: riding with the app in the background, the hidden dashboard stops redrawing and the widget is checked once a second, not every frame.
- Battery: with the HUD link on and no HUD around, the search no longer keeps half a core busy; with no wheel and the app closed it looks every 2 min.
- Battery: riding with the screen off uses less CPU, and the notification updates every 10 s until you look.
- Battery: the speaker rests after 2 quiet minutes instead of staying on all ride (Advanced, Speaker ready time).
- Navigation: route avoidances (tolls, ferries...) now hold on routes with 10 or more stops; before, they were silently dropped.
- Share route: the card's stop distances and total match the stop list. In Next segment it could show about 0 m.
- Map and navigation follow Distance unit (Settings, Units). With miles picked there, the map used to stay in km.
- Navigation: a navigator view on the map while you ride: next turn, arrival time, distance left, speed and 4 numbers you pick (long-press to change). Start now stays on the map.

## Please test

**Battery**
- Wheel off, watch paired, phone left overnight: cooler and less drain than before?
- Next morning, open the app and turn the wheel on: it connects within seconds?
- While riding: G-force, signal strength and range still update, auto-record still stops after the ride, the HUD connects?
- HUD switched off and on mid-ride: it reconnects within seconds? Two wheels with a tire cap each: both pressures show?
- HUD link on, HUD off, app closed for an hour: cool phone? Then turn the HUD on: found within seconds, or as soon as you open the app?
- A long quiet stretch, then an alarm or voice line: does the first beep pop or lose its start? With Bluetooth headphones too?
- Screen off mid-ride, then on: the notification shows current numbers right away?

**Android Auto** (install from the Play internal sharing link, a sideloaded APK never shows in the car)
- The car screen opens, and speed and battery match the phone?
- Horn, Light and Record work from the car screen?
- Music app, then EUC Planet, then the layout button: EUC Planet beside the music?
- An alarm while Maps navigates: does the card show over the map?

**Map / navigation**
- Ride to the last stop: one X closes the arrival banner and ends navigation?
- After arriving, add a stop: New route clears the old stops, Cancel adds nothing?
- Satellite hybrid: road names readable over the imagery?
- Share, Share route on a planned route: the stops and distances right? Scan it with a second phone: same route?
- Send the link to someone without the app: does the page show the route and download a GPX?
- Stop list: the last running total matches the total at the top? Numbers hide while you drag a stop?
- An avoidance on (Navigation & weather) and 10+ stops: does the route still avoid it?
- Distance unit set to miles: the stop list, voice and Navigation & weather tolerances use mi and ft?
- Navigate a 2-stop route from the map, in Full path and Next segment: turn card, arrival time, the next stop counting down and ticked off when passed, End navigation.
- Landscape and north-up (tap the compass while navigating): everything readable and reachable?

**InMotion X1**
- Tiltback and alarm speeds match the InMotion app?
- Change tiltback in EUC Planet, then check the InMotion app shows the new value.
- Battery screen: 2 packs of 56 cells, matching the InMotion app?

**Crews pairing**
- Camera at the QR on the eucstats Crews panel: does the app open on the pass screen?
- App did not open by itself? Settings, EUC Stats, Crews, type the six characters in.
- Does the browser flip to your crew within a couple of seconds of you approving?
- A QR from anywhere else should refuse and name the host it pointed at.
- The pass screen should say what it grants BEFORE you approve anything.

**InMotion V6**
- Speed matches GPS? Service Mode on, ride 2 minutes at a steady speed, share the log.
- Stays connected all ride? Lights and horn work?

**Begode**
- Wheel with a motor sensor (Xmax, Falcon Pro / +): Motor temp matches the Begode app? Without one, Motor temp shows --?
- Auto off tile (Settings, Dashboard layout): matches the wheel, and jumps back up when the wheel moves?
- Current (A) stays live all ride instead of dropping to 0 A? Master v3 especially.
- Beep volume by speed on: quiet beeps when stopped, loud once riding?

**KingSong**
- Lock, turn the wheel off, reconnect later: the tile says Locked, one tap unlocks?

**Dropbox, two phones on one account**
- Link a second phone: trips come down by themselves, Sync all asks once which settings to use?
- Change a setting and save a theme on one, Sync all on the other: do both arrive?
- Rename a trip on one phone, Sync all on the other: new name, no prompt?
- Split a trip on one phone: the other ends with the pieces, not the original as well?
- After a pull: still paired to your wheel, Dropbox still linked?

**Backup folder**
- Had "trip (1).csv" files or a backup check that never ended? Both should be gone after a ride.

**Any wheel**
- Settings search: "scan" lands on a Start scan button, "crew" on Crew pass (or says why it is not there)?
- Voice tile, hold, Listen for a voice command, speak: one message that changes, nothing stacked behind it?
- While it listens or answers, hold, Listen for a voice command: it starts over and hears you?
- Music closed, press a button set to Start music: your music app opens and plays, with one press or one "play music"?
- Phone in pocket, music app closed, Start music from Flic or the HUD: it plays without opening? (Share the log if not.)
- Nothing playing, press Play / pause: music starts? Press again: it pauses?
- Music playing, ask a voice question: the music pauses while listening and answering, and comes back after? Try Lower and Keep playing too.
- Say "pause music" and "play music" with Music while listening on Pause: paused stays paused, play starts it after the answer?
- Open the weather panel right after starting the app, before a GPS fix: Waiting for location, then the forecast on its own?
- With music on, say pause music, then play music, then next song: each one does it, after the answer is spoken?
- Reset metrics after triggered report on: press the voice report button, the trip resets after it speaks?
- Charging monitor menu, Notify me at...: 80 or 99 refuses with a small red line? A level above your charge notifies once when reached?
- Settings search "rese": Integration shows 2, inside it HUD screens shows 2 and holds the match?
- With a backup folder set, the app opens and stays open on every launch.
- Stats length 30 sec or 1 min: tile stats and the detail screen cover only that much?
- Headset button set to Announce: one press reads the voice report?
- Voice report: the lists match what you heard before? A Max pill and a Message are spoken where you put them?
- Connects and responds like before: lights, horn, lock, alarms.
- Light or lock by hand: the automation stays out until you tap Fix?

## Reporting back

Open an issue with your wheel model and firmware (for Android Auto, the car
screen model and a photo). A Service Mode log beats a description; if it
crashed, Share crash log on the About screen.
