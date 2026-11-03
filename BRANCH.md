# organicrouting

next-experimental with routing that keeps riders off main roads, inspired by
how Organic Maps weighs roads.

## Worked on

- Bike routes now come from Valhalla, which prefers quiet streets and paths. In tests, the share of a trip on big roads fell from 25% to 15% on average (86% to 45% in Jakarta).
- Walking with an avoid option on now keeps to footways instead of main roads.
- A new route after a wrong turn comes in about 8 s instead of 22 s, and it continues in the direction you're riding, so no U-turn.
- Routes started from the car screen or the navigation overlay now keep your avoid settings.
- Settings, Navigation, Routing services: the Valhalla server now has its own URL beside OSRM, so you can point either one at your own server.
- Offline areas: Settings, Navigation, Offline areas. Each 5 by 5 degree square brings a street map (paths and house numbers) and routing together, so routes, reroutes and turn cues work with no signal, from a profile made for wheels (no steps, off rough ground). By default they are the backup when the servers or the signal fail; the "Prefer offline map and routing" switch uses them first. Offline turns have no street names. The squares come from our own mirror on Hugging Face, which has routing and a map for every square, with its copy on GitHub as the backup and brouter.de last (routing only). Maps always come from our mirror, even with brouter.de picked as the server. Squares downloaded before their map existed get it from "Update all".
- The offline map sits under the map you picked, in the navigator, trip details, the HUD, the watch, Android Auto and the video overlay. Where online tiles fail it shows through, with "Offline" in bold at the top of the credit line, and the map style button greys out (styles and overlays cannot load then). When the signal comes back, the online map returns by itself. With "Prefer offline map and routing" on, downloaded squares show their own map even with signal (not on the HUD yet).
- With no signal, search says so, and a stop you tap is named from the map ("Strandgata 31").
- Messages no longer hide under the keyboard, like "Search needs signal" or one that comes while typing in a number field.
- The areas screen has one small Select chip: Around me (about 50 km), Around me, wider (about 200 km) and My route (greyed until a route is planned). Each asks first, with the total size.

## Please test

**Everyone who navigates by bike or wheel**
- Plan a route you know well: does it pick the streets you would ride yourself?
- Miss a turn on purpose: does a new route come within about 10 s, continuing the way you're going?

**Anyone running their own routing server**
- Put your Valhalla server in Routing URL (Valhalla): do bike routes come from it?

**Riders who go where signal is weak**
- Download the squares you ride in, turn on airplane mode, plan a route: does it come, and do turn cues play?
- Turn on "Prefer offline map and routing" with signal on: does the navigator show the offline map in your squares, and is the route as good as the online one, off gravel and stairs?
- Offline areas, Select, Around me: does it pick the squares you expect, and say the size before downloading?
- With the squares downloaded and airplane mode on: does the navigator still show a map, with street names? Tap a spot: does the stop get a street name? Turn airplane mode off: does your normal map come back within half a minute?

## Reporting back

Say the city and the start and end points. A screenshot of a bad route helps most.

---

Each branch keeps its own BRANCH.md, and this one changes as the work does.
