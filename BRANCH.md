# organicrouting

next-experimental with routing that keeps riders off main roads, inspired by
how Organic Maps weighs roads.

## Worked on

- Bike routes now come from Valhalla, which prefers quiet streets and paths. In tests, the share of a trip on big roads fell from 25% to 15% on average (86% to 45% in Jakarta).
- Walking with an avoid option on now keeps to footways instead of main roads.
- A new route after a wrong turn comes in about 8 s instead of 22 s, and it continues in the direction you're riding, so no U-turn.
- Routes started from the car screen or the navigation overlay now keep your avoid settings.
- Settings, Navigation, Routing services: the Valhalla server now has its own URL beside OSRM, so you can point either one at your own server.
- Offline routing: Settings, Navigation, Offline routing areas. Download 5 by 5 degree squares (Oslo's is 65 MB) and routes, reroutes and turn cues work with no signal, from a profile made for wheels (no steps, off rough ground). Online first (the default) uses them only when the servers fail; Offline first uses them whenever the square is there. Offline turns have no street names. The map background still needs signal. The squares come from our own mirror (github.com/eried/eucplanet-maps, refreshed monthly), with brouter.de as the backup.

## Please test

**Everyone who navigates by bike or wheel**
- Plan a route you know well: does it pick the streets you would ride yourself?
- Miss a turn on purpose: does a new route come within about 10 s, continuing the way you're going?

**Anyone running their own routing server**
- Put your Valhalla server in Routing URL (Valhalla): do bike routes come from it?

**Riders who go where signal is weak**
- Download the squares you ride in, turn on airplane mode, plan a route: does it come, and do turn cues play?
- Try Offline first with signal on: is the route as good as the online one, and does it keep you off gravel and stairs?

## Reporting back

Say the city and the start and end points. A screenshot of a bad route helps most.

---

Each branch keeps its own BRANCH.md, and this one changes as the work does.
