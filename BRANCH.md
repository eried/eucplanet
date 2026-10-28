# organicrouting

next-experimental with routing that keeps riders off main roads, inspired by
how Organic Maps weighs roads.

## Worked on

- Bike routes now come from Valhalla, which prefers quiet streets and paths. In tests, the share of a trip on big roads fell from 25% to 15% on average (86% to 45% in Jakarta).
- Walking with an avoid option on now keeps to footways instead of main roads.
- A new route after a wrong turn comes in about 8 s instead of 22 s, and it continues in the direction you're riding, so no U-turn.
- Routes started from the car screen or the navigation overlay now keep your avoid settings.
- Settings, Navigation, Routing services: the Valhalla server now has its own URL beside OSRM, so you can point either one at your own server.

## Please test

**Everyone who navigates by bike or wheel**
- Plan a route you know well: does it pick the streets you would ride yourself?
- Miss a turn on purpose: does a new route come within about 10 s, continuing the way you're going?

**Anyone running their own routing server**
- Put your Valhalla server in Routing URL (Valhalla): do bike routes come from it?

## Reporting back

Say the city and the start and end points. A screenshot of a bad route helps most.

---

Each branch keeps its own BRANCH.md, and this one changes as the work does.
