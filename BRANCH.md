# next-experimental

New features before they settle. Ride it only when you can cut the ride short.

## Worked on

- ENGO 2 and ENGO 3 glasses as a HUD (Settings, Integration, Customize): fonts, layouts, numbers, bars and graphs.
- HUD: opens by itself after the Motoeye powers on, turn arrow for the whole route, route line on every map, custom overlay over the map.
- HUD map comes from the phone (online, cached or offline squares); HUD app installs and updates from the phone.
- Offline areas: street map and routing with no signal. Quieter bike routes and faster reroutes.
- Map: heading-up, closer zoom near turns, shortest stop order, GPX tracks, reverse route.
- Trip compare, navigator view with your tiles.
- Engine sounds: recorded and synthesised engines.
- Volume keys work with the screen off. New actions: volume, mute, flashlight, engine sound.
- Units: altitude as its own setting, UK starts on miles.
- Better translations, and new languages like Portuguese (Portugal), Indonesian, Thai and English (UK).
- InMotion V6, V8S and Begode Panther fixes. Watch: new layout.
- Reset buttons grey out when there is nothing to reset.
- Offline maps: a refresh button checks for newer squares now.
- Android Auto is gone for now: Play would not pass it.
- Phones without Wi-Fi can install it again (Wi-Fi is only for the HUD and Amazfit links).
- Two crash fixes from Play: reconnecting to the last wheel, and a memory leak on long rides when the phone clock jumps.
- More crash fixes: full phone storage ends the recording with a warning, damaged settings start from defaults, background starts keep the wheel link.
- Full phone: opening the app no longer wipes alarms and wheel profiles, and saving a setting no longer closes the app.
- Crew pass: plain approval screen that shows who is asking and for what access.
- Smaller crash fixes: KingSong speed setting, a damaged saved wheel address, chart and Battery Reset, long group rides with the map closed.
- ENGO: the glasses should no longer fall behind or freeze on a slow link.
- Resets: Reset metrics, the trip meter Reset and radar Disconnect can no longer close the app or be undone by an update in progress.
- Lighter on the battery: less work per wheel frame, and no background upload checks when no backup or leaderboard is set up.
- The newest strings (storage full, crew pass, offline updates) reread in every language.
- This build is the shrunk one: about a fifth of the download, and as fast as the Play build.
- Dashboard: half the drawing while riding, same look. Stat chips and the live trip screen do far less work.
- Parked or charging: GPS slows down after two minutes, full rate at the first movement (Advanced, Parked GPS delay).
- Voice keeps speaking when the app is swiped away mid-ride.
- Helmet HUD: the custom overlay is sent when it changes, not in every frame.
- Backups: a folder with many trips is checked much faster. The background check runs every six hours.
- Fixes: alarm tone after a disconnect, navigation after Stop, a leaderboard rename undone by an upload, a quick reconnect dropped, P6 and V6 lock right after connecting, chart axis labels.
- Engine list: some recorded engines are gone; a rider who had one gets the closest engine.
- Engine pops: letting off sharply gives one pop on every wheel (it repeated, longer on wheels that report slowly). A little louder on the V8, V-twin, inline-4, V12 and small dirt bike. The decel preview in Settings plays its pop. A preview no longer silences the engine sound of a connected wheel.
- No more silent work when Android starts the app in the background: no map warm-up, no watch app opening by itself.
- Full phone: saving an alarm no longer closes the app, and the app no longer closes on every start. It says the storage is full and reopens by itself once there is room.
- Damaged settings file: the app says the settings were reset and points to Backups.
- iPhone (TestFlight): the same app from shared code: dashboard, alarms, voice reports and commands, settings, themes, trips and trip tools, leaderboards and crew pass, Dropbox and the backup folder, navigation with offline areas, the Overlay Studio, motor sound, the MotoEye HUD, ENGO glasses, the radar, TPMS caps, GPS sensors, Flic buttons and home screen widgets, in all 31 languages.
- iPhone: left out because iOS does not allow it: volume keys, starting or skipping music, a window over other apps, sending the HUD its app, the watches. It never had Android Auto.
- iPhone: reconnects, records, speaks and beeps with the phone locked, and uploads when iOS gives it time. ENGO glasses are not tried with real glasses on an iPhone yet.
- iPhone: built with Xcode 27 (iOS 27 SDK), which needs the scene start-up: the app no longer closes at launch with it.
- iPhone: the same crash fixes as Android (storage, settings, resets, ENGO on a slow link), and the same crew pass screen.
- iPhone: the same optimization round as Android (dashboard pacing, live trip screen, parked GPS, engine pops, the settings reset notice), and the welcome clip as a video.

## Please test

- **ENGO glasses:** pairing, reconnect, and does the glasses page match the phone preview? Does it ever freeze or lag?
- **Motoeye:** power it on: does the HUD app show once and stay?
- **Offline:** airplane mode in a downloaded square: route, turn cues, map?
- **Engine sound:** which engine sounds wrong, and at what speed?
- **Engine pops:** decel on Standard or Backfire, let off sharply: one clean pop? Loud enough? Say the engine.
- **Volume keys, screen off:** do click and hold fire? Say your phone model.
- **Your language:** anything odd or cut off? Say the screen.
- **Crew pass:** scan a code from eucstats: does the screen read clearly in your language?
- **Reset metrics, trip meter Reset:** do the numbers go to zero and stay there?
- **Backups and leaderboards:** does a trip still upload by itself after a ride, with the app closed?
- **Dashboard:** does the speed dial still feel smooth? Any tile that lags behind?
- **After a long stop or a charge:** does the trip record your position from the first metres?
- **Voice:** swipe the app away mid-ride: do alarms and announcements still speak?
- **Helmet HUD, custom overlay:** does it show, and follow a change of preset?
- **P6, V6:** tap Lock right after connecting.
- **Battery monitor:** does the liquid still move with the phone?
- **Overlay Studio:** record a take with a stopwatch or G-force element, and one without.
- **Watch:** the phone battery shown can be half a minute old. Any other value late?
- **Phone nearly full:** does the app say "Phone storage is full" in your language, and carry on once you free space?
- **"Settings were reset":** if you ever see it, tell us what happened before. Is the text right in your language?
- **Any wheel:** anything acting differently from the last build is a bug.

**iPhone** (TestFlight)
- **ENGO glasses:** pair them from the iPhone (once on each phone) and ride: does the glasses page match the preview?
- **Offline:** the refresh button on "Update weekly on Wi-Fi", as on Android.
- **Links:** tap a weather widget or open a crew pass link: does the app open on the right screen?
- **Locked in your pocket:** do voice reports, alarm beeps and the recording carry on? Turn the wheel off and on: does it reconnect?
- **Navigation, locked:** turn cues at the right corners, and a reroute when you miss one?
- **Backup folder:** pick a folder in Files and record a trip: is it there? Point Android at the same synced folder: do trips and themes show on both?
- **HUD:** same Wi-Fi or Personal Hotspot, Settings, Integration, MotoEye HUD: does Auto find it?
- **Uploads with the app closed:** finish a ride offline, go to the Home Screen, turn the connection back on: is the trip on Dropbox and the leaderboard before you open the app?

## Reporting back

Open an issue with your wheel model and firmware. A Service Mode log beats a
description; after a crash, About, Share crash log.
