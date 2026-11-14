# next-experimental

New features land here first. They work on the wheels they were written for
and may not work on yours yet, and settings can move between builds. Ride it
only when you can cut the ride short.

## Worked on

- Merged the latest next-experimental (HUD app from the phone, recorded engines, volume keys with the screen off, a way out of every window, the wheel icon, and the code and string clean-up) into the iPhone branch.
- iPhone app (TestFlight): connects to the wheel, with the same dashboard, alarms, voice reports, settings, themes, Flic buttons, Dropbox sync, trip recording and trip list as Android.
- iPhone app: leaderboards and crew pass, trip details with the map, trip meter, metric details and the charging monitor with charge alerts.
- iPhone app: trip tools (rename, change wheel, split, extend), and the wheel reconnects by itself after a drop, as on Android.
- iPhone app: Legal Mode, the quick-action buttons on the lock screen (iOS 17), and messages like "Battery data exported" show at the bottom of the screen.
- iPhone app: Service Mode log and crash log to send, trips name the wheel, GPS saves power like Android, and the wheel link writes like Android's (fixes for InMotion V1 drops and repeated commands).
- iPhone app: automatic lights at sunset, Lock when I walk away, auto-record on connect, and the wheel connected and lights announcements, from the same code as Android.
- iPhone app: real wheels now connect (iOS held the wheel but the app kept searching), and the scan lists a wheel the iPhone already holds. The welcome tour plays its clip, About shows the logo, and the voice says E U C. What the iPhone lacks yet (navigation, GPS sensors, radar, TPMS, camera, motor sound, widget) shows greyed with "Not on iPhone yet", and Enable Bluetooth asks iOS to turn it on. The battery monitor no longer flickers on the iPhone, and a found wheel reconnects within seconds.
- iPhone app: the theme editor and colour identifier, the top bar follows light and dark themes, custom tiles show their QR code, and the picked voice speaks every report.
- iPhone app: music no longer stays quieter after an alarm beep, and While speaking (lower, pause or leave other audio) works as on Android.
- iPhone app: Pause and Play/Pause pause your music and let it resume (Next, Previous and Start music are not offered, iOS does not allow them). Weather settings open under Navigation and weather. Permission prompts are in your language. Voice and alarms keep sounding with the screen locked.
- iPhone app: the Needs attention warnings (location, Bluetooth, notifications, backups), and a recording cut short by closing the app is saved or dropped at the next start, as on Android.
- Inside: the dashboard, settings, alarms, trips, trip details, trip tools, Legal Mode, the notification buttons, the reconnect timing, leaderboards, Dropbox sync, the wheel link, the theme editor, the automations and what runs on connect and every wheel update moved to a library shared with the iPhone. Nothing should change on screen.
- iPhone app: a swipe in from the left edge is back, on every screen and in every dialog, and the crew pass has the X in every step.
- iPhone app: Volume up, Volume down and Mute are no longer offered (iOS cannot set the volume), and the tour's last page names only Flic buttons.
- Settings, Dashboard layout no longer closes the app.
- Settings search no longer finds rows a phone does not show.
- Every window has a visible way out: an X on the crew pass (scanner and Enter manually), the studio side panels and the HUD preset picker, and Cancel on the ride-without-offline-maps question.
- A wheel icon (shell, tyre, pedals and a cushioned seat) replaces the bicycle on the studio Wheel name element, the motorcycle on the Legal Mode Wheel button and the scooter on the watch's wheel battery.
- 0.24.0 beta: everything below from next-experimental, on the Play beta track. New since 0.23.0: the engine sounds, volume keys with the screen off, and five new actions.
- Release builds are obfuscated (Play asks for it by Feb 2027). Branch APKs are debug builds and do not change.
- Navigator view: turn card, speed, arrival time and your own tiles on the map while navigating. Pick the tiles in Settings.
- Map: while navigating it faces where you ride (heading-up), turning on curves from the wheel's speed. Tap the compass for north-up.
- Map: zooms closer under 10 km/h, and near a turn keeps 15 s of road ahead in view.
- Map: Solve shortest route and Optimize stop order put the stops in the shortest riding order. Up to 49 stops.
- Map: a GPX track (Organic Maps, BRouter...) opens locked and is ridden as drawn, with cues from its bends. Convert to stops makes it editable.
- Map: Reverse route turns the stops round for the way back. Start far from a track rides you to its start first.
- Menus: options that cannot run grey out instead of disappearing, and delete rows are red with a bin.
- Android Auto: navigation on the car screen, with your four numbers beside it. Planned route rides a loaded track.
- HUD app update from the phone: the phone app carries the HUD app and sends it over the link on a tap, and the HUD goes straight to Android's install prompt, with no question of its own. It asks first when the HUD already runs that build, and leaves a newer HUD alone. The card and that question show both versions lined up, Installed (on the HUD) and Available (in the phone). Uninstall from the same card. The card also shows the address the HUD was reached at and whether it was found by Auto or Fixed. It slides in when the HUD links, and the discovery mode and address rows fold away while it is linked. The first install is still by cable.
- HUD map: keeps working with the phone app in the background and no wheel connected. It now says when it is waiting for the phone GPS.
- HUD map: no more white "Map data not yet available" screen. Where the map has no detail that deep it shows the closest zoom it has.
- HUD map: the route is drawn on the map while navigating. Needs this phone APK and this HUD APK together.
- HUD map: keeps facing the way you were riding when you stop, instead of swinging back to north.
- HUD camera: let go of the camera when you leave the Camera screen, so the MotoEye recorder can start.
- HUD camera: UP picks the next camera, DOWN turns the picture 90 degrees. Each camera keeps its own turn, and the HUD remembers them.
- HUD camera: the MotoEye camera button frees the camera, so its rear view and recorder work from any screen. The picture comes back by itself a few seconds after you stop recording or close the rear view. UP or DOWN also brings it back.
- HUD camera: opening a camera screen while the MotoEye records no longer takes its camera and freezes the clip. It says the camera is in use and comes back once the recording stops.
- HUD: the branch HUD APK is now a release build and starts two to three times faster.
- Voice alarms: a PWM alarm says "PWM" again in Italian, Norwegian and Chinese, it was left out.
- InMotion V8S and other V1 wheels: commands go out in small pieces like EUC World does, so the link should stop dropping.
- InMotion V8S: the light button and auto-lights follow the wheel's real light state, and Charging shows (also V8F, V10).
- InMotion V6: the light button sends the two-byte form the P6 uses. The V14 form did nothing on a V6. The button now stays lit and switches the light off again; before, it went dark after a moment and every tap sent "on".
- InMotion V6: a V6 whose Bluetooth name has no "V6" in it (firmware 2.2.4) showed its motor duty as speed and 0 % battery. It now reads speed, battery, PWM, trip and temperature.
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
- Engine sound: eight engines are real recordings, not synthesis: V8 (an AC Cobra), V-twin (a Ducati), Inline-4, V12 (an Aston Martin), Diesel truck, Small dirt bike, Steam locomotive, Broken exhaust. The app plays short pieces of the real engine's cycle and lays them closer together as the wheel speeds up, so the firing rate follows the wheel while the exhaust keeps its real voice. They start with the recording's own start-up and pop with its own backfires. The old "Sampled" engines are gone from the list (their keys still work and play the same recordings).
- Engine sound: the recorded engines keep their low end now and no longer hiss when you shut the throttle: letting off at speed makes them quieter and duller like a real engine, and a pop is a bang, not a burst of noise. The steam locomotive whistles when it comes on and toots when you let off, and blows off steam while it stands.
- Engine sound: the synthesised engines stay for what nobody has recorded: 4-stroke single, 2-stroke, Quad bike, Tractor, Boxer 6, Inline-6, V16, and the fantasy set (Jet turbine, Pod racer, Hover bike, Tron whine, UFO hover, Digital hum). The hover bike is now the Jetsons car, a bubbling run of blips that comes faster with speed.
- Engine sound: the synthesised engines breathe. Their intake and exhaust air used to run as a steady hiss under the bangs; now each firing draws a breath that dies away before the next one at idle, so the 4-stroke single and the 2-stroke idle without the "shhh" behind them. At speed nothing changes.
- Engine sound: Reset all to defaults at the bottom of the section, greyed until you change something, and it tells you what it will change before it does. The Advanced reset moved to the left to sit where every other reset in the app is.
- The APK is about 5 MB smaller: the recordings are shipped as grain banks instead of whole clips.
- Volume keys: "Also when the screen is off" under the volume key actions. The app claims the buttons through a media session, so a click or hold fires its action with the screen off or another app in front. It asks before turning on, because the buttons then stop changing the phone volume. It keeps the buttons while music plays and when the song changes.
- Voice: pressing Listen without the microphone permission now asks for it, from any button or tile. With the screen off it says so and adds a Needs attention entry. Needs attention also says when Bluetooth is not allowed.
- Actions: Volume up, Volume down, Mute / unmute, Phone flashlight and Engine sound on/off can be bound to a Flic, the volume keys, the watch or a dashboard tile.
- Backup conflicts: the warning goes as soon as you settle them with Sync all, says where that button is, and Fix opens Trips backup. It also goes when you unlink the folder or delete the trips.

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
- Display: turn on the theme widget, change a colour, and use the picker to identify one. Voice: pick a voice, does every report use it? Play music with alarms and reports on: is it lowered or paused only while the app speaks?
- Automations (Settings): set lights to follow sunset, and turn on Lock when I walk away. Does the light switch and the wheel lock as on Android?
- Ride with the iPhone locked in your pocket: do the voice reports and alarm beeps still sound, and does the trip keep recording?
- Music: bind Pause or Play/Pause to a Flic button or a tile and play Spotify or Music. Does it pause, and does it come back on the next press? Which apps do not resume?
- Swipe in from the left edge on Settings, a trip, the battery monitor and in any dialog: does it go back or close it?

**Android, after the move to the shared library**
- Legal Mode and the ride notification buttons: same as the last build?
- Turn the wheel off and on, and Bluetooth off and on: does it reconnect as before? Trip tools (rename, split, extend) work as before?
- Auto lights, volume by speed, music pause, walk-away lock, auto-record, charge alerts and the connect and lights announcements: same as the last build?
- Dashboard, Settings, alarm editor, Scan, the trip list, trip details, trip meter, metric details, battery monitor and leaderboards: anything that looks or acts differently from the last build is a bug.

**Closing windows**
- Crew pass, studio panels, HUD preset picker: does the X close them? Anywhere else you can only leave with the back gesture?

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

**Engine sound** (Settings, search "engine", switch it on)
- The recorded engines (V8, V-twin, Inline-4, V12, Diesel truck, Small dirt bike, Steam locomotive, Broken exhaust): does each start like an engine, idle like the real thing, and rev with the wheel from a stop to 30 km/h? Does a hard pull still sound like that engine, or like a buzz? Say which engine and at what speed.
- Ride at 30 km/h and let off completely: does the engine go quiet and dull like a real one, or do you hear hiss, a whistle or clicks? Which engine?
- Do you hear a loop or a stutter when standing still with the engine idling? Which engine?
- Synthesised engines (4-stroke single, 2-stroke, Quad bike, Tractor, Boxer 6, Inline-6, V16): accelerate hard from 15 to 40 km/h. Engine or electronic whine? Which one and at what speed.
- 4-stroke single and 2-stroke standing still: is there still a steady hiss behind the bangs, or does the air puff with each one?
- Steam locomotive: do you hear the whistle when the sound comes on and a toot when you let off at speed? Hover bike: does it sound like the Jetsons car to you?
- On the phone speaker, not only headphones: is it loud enough at idle and not harsh at speed? Are the recorded and the synthesised engines about as loud as each other?

**Volume keys with the screen off** (Settings, search "volume")
- Turn on the volume key actions, then "Also when the screen is off" and accept the warning. Lock the phone and press volume up: does the click action fire (horn by default)? Hold it: the hold action (voice report by default)? Does it work with another app in front? Say your phone model.
- Start music in another app and let it change song a few times: do the buttons keep firing their actions, screen on and off? Does the phone volume ever move by itself?
- Turn the option off: do the buttons change the phone volume again at once?
- Take the microphone permission away in Android settings, press a button bound to Listen with the app open: does Android ask? Deny it: is it spoken, and is it in Needs attention?
- Bind Volume up to a click and the horn to a hold on the same button: do both work with the screen off? Try Phone flashlight and Engine sound from a Flic too.

**Android Auto** (install from the Play internal sharing link, a sideloaded APK never shows in the car)
- Navigate from the car screen: turn-by-turn shows, and your four numbers beside it?
- Load a GPX track on the phone, then Places, Planned route in the car: does it follow the track?

**HUD (MotoEye)**
- With this HUD app on the HUD and a later build on the phone: Settings, HUD shows a HUD app card with Send to HUD. Tap it and confirm Android's prompt on the HUD. Does it install, and can you work the prompt with the remote? The first time Android asks you to allow installs from the HUD app: can you get through that, and does the install go on afterwards or do you have to send again?
- Same card, Uninstall: does the HUD show Android's uninstall prompt?
- Start a route on the phone and open Map on the HUD: is the route line there, on the right streets, and does it keep up as you ride?
- Stop at a junction: does the map stay facing your direction of travel?
- Install the phone and the HUD APK from this build. Open the HUD Map screen, leave the phone app, no wheel connected: does the map stay? And with the wheel connected, on any map style and zoom?
- Camera screen: turn the front camera, switch to the rear one and back. Does each keep its own turn, also after restarting the HUD?
- MotoEye, without pressing UP or DOWN: 1) Camera screen, hold to start recording, then hold again to stop. Does the picture come back about 5 s after the stop? 2) Short press for the rear view and close it, does it come back? 3) Dashboard, start recording, go to the Camera screen (it should say "in use"), back, stop: does that file play to the end? If the picture stays off, send a photo of the small grey line.

**InMotion V8S (or any V1 wheel)**
- Ride as usual: how many disconnects, compared with EUC World? A Service Mode log covering any drop helps most.

**InMotion V6** (issue #33)
- If your V6 showed a speed while standing still or 0 % battery: connect and check speed, battery percent and temperature against the wheel's own app.
- Tap Light in the app: does the wheel light switch on, the button stay lit, and a second tap switch it off?

**Trip compare**
- Open a trip, tap Compare, pick a ride over the same road: does Auto line them up? Try Stacked and Overlay, Show differences, and scrub the charts.

**Native speakers, any language**
- Use the app in your language for a ride: anything that sounds stiff, wrong for riders, or does not fit its button? Say the screen and the better words.

**Any wheel**
- Had a Backup conflicts warning that would not go away? Tap Fix, then Sync all, pick a side: is it gone at once?
- Ride with voice alarms on, open the main screens, sync, widgets and the watch: anything that acts differently from the last build is a bug.

## Reporting back

Open an issue with your wheel model and firmware (for Android Auto, the car
screen model and a photo). A Service Mode log beats a description; if it
crashed, send the EUCPlanet-crashlog file from Downloads (iPhone: Share crash log on the About screen).
