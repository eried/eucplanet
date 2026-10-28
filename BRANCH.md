# next-experimental

New features land here first. They work on the wheels they were written for
and may not work on yours yet, and settings can move between builds. Ride it
only when you can cut the ride short.

## Worked on

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
- HUD camera: the MotoEye camera button frees the camera, so its rear view and recorder work from any screen, and the HUD no longer takes the camera back after the recorder stops. UP or DOWN brings the picture back.
- HUD: the branch HUD APK is now a release build and starts two to three times faster.
- Voice alarms: a PWM alarm says "PWM" again in Italian, Norwegian and Chinese, it was left out.
- InMotion V8S and other V1 wheels: commands go out in small pieces like EUC World does, so the link should stop dropping.
- InMotion V8S: the light button and auto-lights follow the wheel's real light state, and Charging shows (also V8F, V10).
- InMotion V6: the light button sends the two-byte form the P6 uses. The V14 form did nothing on a V6.
- Lights: a reconnect while walking the wheel no longer turns the light on and off again.
- Trip Details: Compare (the two-arrow icon) puts another trip beside this one, like eucviewer. Auto lines them up, tiles and charts show both or the differences, the map marks the other trip.
- Inside: wiring moved to a library that also runs on iPhone, build tools updated (Kotlin 2.3.21, Koin 4.2.2). Nothing should change on screen.

## Please test

**Map / navigation**
- Navigate a 2-stop route in Full path and Next segment: turn card, arrival time, the next stop counting down and ticked off, End navigation?
- Is the zoom right near turns and when riding slowly?
- Load a GPX track and ride it: cues at real turns, named places announced? Convert to stops works?
- After arriving, Reverse route: does it take you back a sensible way?
- Landscape and north-up: everything readable and reachable?

**Android Auto** (install from the Play internal sharing link, a sideloaded APK never shows in the car)
- Navigate from the car screen: turn-by-turn shows, and your four numbers beside it?
- Load a GPX track on the phone, then Places, Planned route in the car: does it follow the track?

**HUD (MotoEye)**
- Install the phone and the HUD APK from this build. Open the HUD Map screen, leave the phone app, no wheel connected: does the map stay? And with the wheel connected, on any map style and zoom?
- Camera screen: turn the front camera, switch to the rear one and back. Does each keep its own turn, also after restarting the HUD?
- MotoEye: on a camera screen, hold the camera button to start the recorder, stop it, and start it again: does it work every time?

**InMotion V8S (or any V1 wheel)**
- Ride as usual: how many disconnects, compared with EUC World? A Service Mode log covering any drop helps most.

**InMotion V6** (issue #33)
- Tap Light in the app: does the wheel light switch on and off?

**Trip compare**
- Open a trip, tap Compare, pick a ride over the same road: does Auto line them up? Try Stacked and Overlay, Show differences, and scrub the charts.

**Any wheel**
- Ride with voice alarms on, open the main screens, sync, widgets and the watch: anything that acts differently from the last build is a bug.

## Reporting back

Open an issue with your wheel model and firmware (for Android Auto, the car
screen model and a photo). A Service Mode log beats a description; if it
crashed, Share crash log on the About screen.
