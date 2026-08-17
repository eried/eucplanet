# eucstats: a held trip can stop being held

Fixes the "under review" cloud that never cleared.

## The problem

The upload response was the app's only look at a trip's verdict, and nothing ever
asked again. When a moderator approved a held trip the phone never found out, so
the rider kept a yellow "under review" cloud forever on a ride that was already
counting on the leaderboard. Re-uploading could not clear it either: the same
`trip_uuid` hits the server's dedupe and returns the same verdict.

From the rider's side that reads as "something is wrong and there is nothing I can
do about it", which is the opposite of what the state means. The upload succeeded.

## What changed

- **The app can ask again.** New `GET /trips/{uuid}` on the server returns the
  current verdict. The app reads it for any trip it is still showing as held.
- **Automatically, on the upload sweep.** The worker refreshes held trips before
  it looks at pending uploads, because a held trip has already uploaded and so is
  never in the pending set. Failures there are ignored, it is only a refresh.
  Bounded to the 20 most recent held trips: one check is a ~150 byte response, but
  a rider whose trips are never reviewed would otherwise re-ask about all of them
  every time. The sweep is event-driven (ride ends, manual retry, settings change),
  not a poll.
- **"Sync all" in settings re-checks every held trip**, with no bound, because the
  rider asked for exactly that. A sync that only cleared verdicts reports what it
  cleared instead of "nothing to sync".
- **Manually, by tapping the cloud.** Tapping a held trip now re-asks and reports
  the answer, instead of repeating the same explanation. That is the one action
  that can actually change the icon.
- **A rejected trip no longer shows as shared.** A verdict can become "rejected"
  after review. That had no branch of its own and fell through to the green tick,
  advertising a ride the leaderboard had turned down. It now shows the red cloud
  with its own message.

## Needs the server first

The endpoint ships in eucstats commit `151aea3`. Until that is deployed the app
degrades quietly: the server 404s, the app reports "could not check right now",
and nothing is written. Safe to install early, it just cannot clear anything yet.

## Testing

- `./gradlew :app:testDebugUnitTest` covers the re-check: approved, still held,
  rejected, unreachable server, and that the sweep only asks about held trips.
- End to end needs a server with the endpoint. Point the app at a dev instance,
  upload a ride, get it flagged, approve it in the admin panel, then tap the
  cloud: it should turn green and the rider's totals should include it.

## Strings

Three new strings (`online_status_checking`, `online_status_check_failed`,
`online_status_rejected`), translated into all 23 locales.
