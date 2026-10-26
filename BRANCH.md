# feature/navigator-view

The navigator view on the map and on the car screen, on top of
next-experimental. Ride it only when you can cut the ride short.

## Worked on

- Navigator view: turn card, speed, arrival time and your own tiles on the map while navigating. Pick the tiles in Settings.
- Map: the camera follows you low on screen, heading-up turns the map with you on curves (from the wheel's speed when connected), north-up from the compass.
- Map: zooms closer under 10 km/h, and near a turn keeps 15 s of road ahead in view.
- Map: Solve shortest route and Optimize stop order put the stops in the shortest riding order.
- Map: a GPX track (Organic Maps, BRouter...) opens locked and is ridden as drawn, with cues from its bends. Convert to stops turns it into editable stops.
- Map: Reverse route turns the stops round for the way back. Start far from a track rides you to its start first.
- Map menu: Load sits above Save, options that cannot run grey out, and delete rows are red with a bin.
- Android Auto: navigation on the car screen, with your four numbers beside it. Planned route rides a loaded track.
- Also everything on next-experimental: the HUD map in the background, no white "Map data not yet available", the HUD camera released for the MotoEye recorder.

## Please test

**Map / navigation**
- Navigate a 2-stop route in Full path and Next segment: turn card, arrival time, the next stop counting down and ticked off, End navigation?
- Heading-up: does the map face where you ride, also on curves and slowly? Is the zoom right near turns?
- Load a GPX track and ride it: cues at real turns, named places announced? Convert to stops works?
- After arriving, Reverse route: does it take you back a sensible way?
- Landscape and north-up: everything readable and reachable?

**Android Auto** (install from the Play internal sharing link, a sideloaded APK never shows in the car)
- Start navigation in the car: turn-by-turn shows, and your four numbers beside it?
- Load a GPX track on the phone, then Places, Planned route in the car: does it follow the track?

## Reporting back

Tell us in Telegram or open an issue, with a screenshot or a car screen photo.
A Service Mode log beats a description; if it crashed, Share crash log on the
About screen.
