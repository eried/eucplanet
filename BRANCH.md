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
- No more silent work when Android starts the app in the background: no map warm-up, no watch app opening by itself.

## Please test

- **ENGO glasses:** pairing, reconnect, and does the glasses page match the phone preview? Does it ever freeze or lag?
- **Motoeye:** power it on: does the HUD app show once and stay?
- **Offline:** airplane mode in a downloaded square: route, turn cues, map?
- **Engine sound:** which engine sounds wrong, and at what speed?
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
- **Any wheel:** anything acting differently from the last build is a bug.

## Reporting back

Open an issue with your wheel model and firmware. A Service Mode log beats a
description; after a crash, About, Share crash log.
