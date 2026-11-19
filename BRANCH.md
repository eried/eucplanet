# next-version

The next release, settling. Features arrive here once they have been ridden on
next-experimental and stopped changing shape. Settings stay where they are put
and a build here is meant to get you home.

This is what the Play beta is cut from. If you want to help without gambling a
ride, this is the branch.

## Worked on

- Languages: a phone set to Traditional Chinese without a Taiwan, Hong Kong or Macau region now starts the app in 中文 (繁體) instead of Simplified, as the web sites do.
- Headroom tile removed: it was offered as a dashboard tile but never showed a value. A dashboard that had it loses that tile; nothing else moves.
- Two hints corrected, in every language: the settings backup says it holds your settings and alarm rules (not "everything"), and volume keys with the screen off says it works while EUC Planet is running.
- Crew pass: scanning one from the Share screen now points you to the right place, Settings, Online leaderboards. It named a Crews section that Settings does not have. In every language.
- Leaderboard: when the server limits your uploads or does not know your rider, the app stops sending for now instead of trying every waiting ride. Rides stay waiting and go up on a later try.
- Shorter hints: 18 setting descriptions lose their extra sentence, in every language. Three said something untrue and are fixed: forgetting a Flic keeps its actions, the radar lane draws squares with the distance, and the weather widget fetches on its own.
- Languages: English (UK) is new, with British spelling (tyre, colour, kilometres). The list now reads English (US), English (UK), Español (ES) and Español (LA), the same names as the trip viewer and the leaderboard.
- Android Auto (MotoEye HUD): every firing alarm shows as a card on the HUD. Only the GPS alarms did; wheel and radar alarms never got there. No card on the phone when Android Auto is not connected, and no second chime.
- Units: a fresh install in the UK, the Crown Dependencies or Myanmar starts on miles, Celsius and altitude in metres; US territories start imperial like the US. Riders who already have settings are not changed.
- Units: Altitude is its own setting now (Settings, Display & appearance, Units, Custom): metres or feet, whatever your distance unit is. Until you pick one you keep what you had, feet with miles and metres with kilometres. Metric sets metres, Imperial sets feet.
- Motoeye HUD: only one phone links at a time. A second phone dialing the same HUD made the screen flicker; it now waits until the first has been quiet for 3 s.
- Leaderboard: a ride that was removed from eucstats is no longer asked about every 15 minutes forever. The app asks once, gets "not there", and stops.
- Settings backup: "Last backup" is read from the backup file itself, so it is right after a reinstall. Android's own cloud backup of the app is off, so a reinstall starts clean.
- Offline routing: the first route after opening the app comes on the road instead of as straight lines, and routes with an avoid switched on now route offline.
- InMotion V6: counts as rider-tested now, so the "preliminary wheel" banner is gone. Thanks Klaus, you are in the thanks list.
- InMotion V6: 20 km/h tilt-back, normal and Legal mode, now reaches the wheel. It was the one value the wheel dropped.
- Speed limits: a typed speed applies on Done or when you leave the field, not on each digit. Typing 19 used to set 1 on the way and pull Legal mode down to 0.
- Watch: a dark accent (black, dark blue) keeps the button icons readable. Pick the colour in the phone app's theme.
- InMotion V6: the tilt-back speed is read back from the wheel, so it shows what the InMotion app set. The limit is 25 km/h like the InMotion app, and the alarm speed no longer gets a wrong 7 km/h from the wheel.
- Watch: new layout. Bigger speed and batteries, one battery shown biggest, two or three sit centred above the buttons. Prioritize PWM shows a big PWM number and a thicker bar. Sizes follow the watch's screen size.
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
- HUD: new screen Custom overlay + map, your preset drawn over the live map, with the turn cue while a route runs and without the zoom and speed boxes. UP and DOWN zoom. Switch it on in Settings, HUD, screens.
- HUD map: every map picture now comes from the phone, so the HUD shows what the phone would: the online map, tiles the phone already has, or your offline squares, and it follows "Prefer offline map and routing". Needs this phone APK and this HUD APK together.
- HUD map: arrival time and distance left at the top while a route runs, on Map + Nav and Custom overlay + map. Needs this phone APK and this HUD APK together.
- HUD: the yellow update notice shows for 20 seconds after connecting, then goes away. The red one, when phone and HUD are too far apart to talk, stays.
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
- Offline map: sits under the map you picked (navigator, trip details, HUD, watch, Android Auto, video overlay) and shows through where online tiles fail, with "Offline" in bold at the top of the credit line and the map style button greyed; the online map returns by itself when the signal does. With "Prefer offline map and routing" on, downloaded squares show their own map even with signal. With no signal, search says so, and a tapped stop is named from the map.
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
- Begode: a wheel whose name the app does not know (the Panther, issue #34) connected and showed no speed. It is now recognised from its first data frame.
- Actions: Volume up, Volume down, Mute / unmute, Phone flashlight and Engine sound on/off can be bound to a Flic, the volume keys, the watch or a dashboard tile.
- Backup conflicts: the warning goes as soon as you settle them with Sync all, says where that button is, and Fix opens Trips backup. It also goes when you unlink the folder or delete the trips.
- Alarms: a fired alarm speaks the same number as the preview, in your units: 3.0 bar says "3.0", 1.5 g says "1.5", a GPS speed rule speaks mph, a motor or battery temperature rule speaks °F. The help lines and the restore chips in the editor show the real values.
- Fixes: a friend's label opens on tap on the map, the widget's Phone battery slot shows the phone battery, the charging screen uses your target percent, factory reset keeps TPMS pairings, Veteran Sherman S is 100 V.
- HUD: the Custom overlay draws energy, consumption, range, altitude and torque (they showed speed). Map + Nav is in the default screens, and reordering screens reaches the HUD without toggling one.
- Garmin and Amazfit: the demo wheel no longer shows on a normal start, and "mil" is 10 km.
- Studio: a video pane with no clip exports the "No media" placeholder the preview shows, and a pane set to Black exports black past the clip.
- Languages, second pass: one reviewer per language went through all 29 for consistency, about 2,000 texts. One word per thing, texts that name a button use that button's label, wrong meanings fixed (the Norwegian spoken trip in miles said "mil").
- Wording: the metric is PWM everywhere (it was "Load" in the alarm list, the detail screen and the voice report). Labels are sentence case ("Stop all", "Trip details"). "Avoid cover screen cameras" for flip phones, "Reached stop 2" in navigation, "(none)" and "(empty)" for the side values of a tile, and the leaderboard address sits inside its sentence.
- Removed: Media speed control (it never worked reliably), the one-time places hint, and the unused in-app eucviewer screen. Inspect online is unchanged.

## Please test

**Texts** (anyone, in your language)
- Settings backup hint and "Also when the screen is off" under volume keys: do both read naturally in your language?
- Anyone who had the Headroom tile: is it gone from the dashboard, with the other tiles in their places?
- Settings hints for rotation, radar, speed splits, Android Auto, keep alive, settings backup, weather source and Advanced rates: does each still read as a whole sentence in your language?

**InMotion V6** (Klaus)
- Set tilt-back to 20 km/h in EUC Planet, normal and in Legal mode: does the wheel beep like for 19 and 21?
- Watch: are speed, PWM and batteries readable now without glasses? Try one, two and three batteries and Prioritize PWM. Install both the phone and the watch APK.
- Type a tilt-back like 19 and press Done: does Legal mode keep its value? If yours shows 0, set it again once.
- Change Primary / accent in the phone's theme: does the watch follow? Pick a light colour, a dark one is hard to read on the black watch face.

**Closing windows**
- Crew pass, studio panels, HUD preset picker: does the X close them? Anywhere else you can only leave with the back gesture?

**Map / navigation**
- Navigate a 2-stop route in Full path and Next segment: turn card, arrival time, the next stop counting down and ticked off, End navigation?
- Is the zoom right near turns and when riding slowly?
- Load a GPX track and ride it: cues at real turns, named places announced? Convert to stops works?
- After arriving, Reverse route: does it take you back a sensible way?
- Landscape and north-up: everything readable and reachable?

**Routing and offline areas**
- Airplane mode, open the app, plan a route in a downloaded square within a few seconds, then again with Avoid unpaved on: does each follow the roads?
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
- With the MotoEye running Android Auto, trip a wheel alarm (speed or PWM): does a card show over the map, and is the only sound the alarm's own beep?
- Navigate from the car screen: turn-by-turn shows, and your four numbers beside it?
- Load a GPX track on the phone, then Places, Planned route in the car: does it follow the track?

**HUD (MotoEye)**
- Motoeye with two phones that both have the HUD link on: is the flicker gone, and does the second phone take over a few seconds after the first one's link is switched off?
- With this HUD app on the HUD and a later build on the phone: Settings, HUD shows a HUD app card with Send to HUD. Tap it and confirm Android's prompt on the HUD. Does it install, and can you work the prompt with the remote? The first time Android asks you to allow installs from the HUD app: can you get through that, and does the install go on afterwards or do you have to send again?
- Same card, Uninstall: does the HUD show Android's uninstall prompt?
- Start a route on the phone and open Map on the HUD: is the route line there, on the right streets, and does it keep up as you ride?
- Stop at a junction: does the map stay facing your direction of travel?
- Install the phone and the HUD APK from this build. Open the HUD Map screen, leave the phone app, no wheel connected: does the map stay? And with the wheel connected, on any map style and zoom?
- Camera screen: turn the front camera, switch to the rear one and back. Does each keep its own turn, also after restarting the HUD?
- MotoEye, without pressing UP or DOWN: 1) Camera screen, hold to start recording, then hold again to stop. Does the picture come back about 5 s after the stop? 2) Short press for the rear view and close it, does it come back? 3) Dashboard, start recording, go to the Camera screen (it should say "in use"), back, stop: does that file play to the end? If the picture stays off, send a photo of the small grey line.
- Custom overlay + map: is your preset readable over the map? Start a route: do the turn cue and the arrival line at the top sit clear of your preset?
- Map + Nav with a route: does the arrival time match the phone's navigator, and does it stay between turns?
- HUD map, any style: does it fill in as fast as before? Then with mobile data off inside a downloaded square, and with "Prefer offline map and routing" on: does the HUD show the offline map?
- If your phone and HUD builds differ: does the yellow update notice leave after about 20 seconds, and come back only when the HUD reconnects?

**InMotion V8S (or any V1 wheel)**
- Ride as usual: how many disconnects, compared with EUC World? A Service Mode log covering any drop helps most.

**Begode Panther, or any Begode the app lists under another name** (issue #34)
- Connect and ride: do speed, battery, PWM and temperature show? Say the name the wheel has in the scan list, and whether the battery percent looks right.

**InMotion V6** (issue #33)
- If your V6 showed a speed while standing still or 0 % battery: connect and check speed, battery percent and temperature against the wheel's own app.
- Tap Light in the app: does the wheel light switch on, the button stay lit, and a second tap switch it off?

**Trip compare**
- Open a trip, tap Compare, pick a ride over the same road: does Auto line them up? Try Stacked and Overlay, Show differences, and scrub the charts.

**Alarms**
- Make a tyre pressure or G-force alarm with a voice line that uses {value} or {threshold}, play the preview, then let it fire: same number, in your units? With mph or °F set, do a GPS speed alarm and a motor temperature alarm speak mph and °F?
- The voice report now says "PWM" instead of "load": does your voice say it clearly in your language?

**Studio**
- Replay a trip with a video pane, remove or move the clip file, export: does the export show "No media" like the preview, not a see-through hole?

**HUD and watch**
- HUD Custom overlay with energy, consumption, range, altitude or torque elements: right numbers? Reorder the HUD screens on the phone without switching any off: does the HUD follow?
- Garmin and Amazfit: start the watch app with the phone app closed: no "Demo V14"? If you use mil, is the distance right?

**Native speakers, any language**
- UK, Irish, Australian, New Zealand, South African and Indian riders: on a fresh install, does the app start in English (UK)? Anything still spelled the American way?
- Use the app in your language for a ride: anything that sounds stiff, wrong for riders, or does not fit its button? Say the screen and the better words. Many words changed in this build (speed, metric, overlay, scan, lock), so a second look is welcome.
- Flip phone owners: Settings, Display & appearance, "Avoid cover screen cameras": is that what your phone calls the outer screen in your language?

**Any wheel**
- UK riders, fresh install only (or clear the app's data): does it start on mph, miles, Celsius and altitude in metres? Your SIM and your phone's region must both be UK.
- Settings, Units, Custom: set Altitude to feet while distance stays on kilometres. Do the altitude tile, its detail graph, a Studio altitude element and the same element on the HUD all say ft? Set it back to metres: all m?
- After updating without touching units: is your altitude still in the unit you had before?
- Make a settings backup, uninstall, install again, link the same folder: does "Last backup" show the time you just made it?
- Had a Backup conflicts warning that would not go away? Tap Fix, then Sync all, pick a side: is it gone at once?
- Ride with voice alarms on, open the main screens, sync, widgets and the watch: anything that acts differently from the last build is a bug.

## Reporting back

Open an issue with your wheel model and firmware (for Android Auto, the car
screen model and a photo). A Service Mode log beats a description; if it
crashed, Share crash log on the About screen.
