# feature/navigator

In-app navigation for unicycle riders: a route builder with a map, live
turn-by-turn guidance, and a playful Treasure Hunt proximity mode — plus an
optional mirror of the navigation popup on the Wear OS watch.

## What's new

**Route Builder (map button on the dashboard dial, center-left)**
- Full-screen Leaflet map (WebView, reusing the existing trip-map approach —
  no MapLibre, no 16 KB page-size issue).
- Tap the map to drop destinations; drag pins to move them; drag the stop
  list to reorder.
- Address / place search via Nominatim (OpenStreetMap).
- Auto-routing for **Cycle / Car / Walk** via Valhalla, or **Direct**
  straight lines with no network. Falls back to straight lines if the
  routing service is unreachable.
- "My location" to drop the rider's current position as the start.
- Save / load routes as **GPX**, clear, and exit — from the top-left
  hamburger menu.

**Live turn-by-turn navigation**
- A floating popup over the whole app: a big direction arrow, the next move,
  and the distance to it. Minimize to a pill, or end (with confirmation).
- Voice cues — "Start riding", "In 200 meters, turn left", "Wroooong way!",
  "You have arrived" — gated by the Navigator voice setting.
- Heading is derived from the rider's recent *moving* GPS trace (not the
  phone compass), so left/right is relative to actual travel direction.
- Off-route detection with automatic re-routing.
- The next maneuver also shows in the foreground-service notification.

**Treasure Hunt mode**
- Set one or more goals; instead of street-by-street directions the app
  points the way (left / right / ahead / behind) with the distance and
  warmer / colder proximity cues.

**Wear OS mirror**
- Optional, **off by default** — enable under Settings → Watch → "Show
  navigation". The watch shows a simplified popup (big arrow + text) that
  tracks the phone: minimizing the phone popup hides it on the watch too.
  The wrist buzzes on each new instruction.

**Settings → Navigator**
- Voice guidance toggle, default travel mode, arrival radius, off-route
  tolerance, and (advanced) configurable routing/geocoding endpoints.

## Privacy note

The Navigator is the only part of the app that makes outbound requests, and
only while the rider is actively building a route or navigating. It contacts
just the configured OpenStreetMap-based services (Nominatim + Valhalla, both
free and key-less; map tiles from CartoDB). No accounts, no telemetry. The
endpoints are configurable for riders who want to self-host.

## How to install

Debug-signed CI build. Uninstall any Play Store install first, then install
this APK. Reinstalling from Play later overwrites this build.

## Feedback

Open an issue at https://github.com/eried/eucplanet/issues tagged
`branch:navigator`.
