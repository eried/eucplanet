# Location share ("Share my ride") - design

**Date:** 2026-08-29
**Branch:** `feature/location-share` off next-experimental. Ships to next-experimental
after the rider tests. Do NOT push until then.

## Goal

From the navigator map a rider taps Share and gets a link (QR + text). Anyone with
the link sees the riders in that group live on a map, with a fading trail, their
chosen identity, and (optionally) their stats. App users open the link straight
into the app and join the group as a rider; people without the app open the same
link in a browser and watch. Riders can see the group as a list, tap a peer to fly
the map to them, and leave from the same button.

## Decisions made with the rider

- **Transport is a tiny relay**, not phone-to-phone. Phones on mobile data sit
  behind carrier NAT and cannot accept inbound connections, and a browser cannot
  open a socket to a phone at all, so pure P2P cannot meet the requirements.
  The relay is a new service at **`eucshare.ried.no`** on the existing droplet.
  The rider sets up the Cloudflare DNS record; Claude deploys and verifies with
  emulators + Playwright.
- **Room lifetime:** a room is deleted **1 hour after its last connection
  closes** (server-side, configurable `ROOM_TTL_S`, default 3600).
- **Identity is chosen per share session**, three modes (section 4).
- **Web viewer shows everything** a rider chose to share (map, names, stats,
  trails). Browser viewers are **watch-only**: they never appear as a rider and
  send no data.
- **Staleness is handled explicitly** in both the app and the web viewer (section 6).
- **While joined, the Share button becomes the group view**: peer list with
  stats, tap-to-locate, and Leave (section 5).

## Architecture

```
 app (Kotlin)  --WebSocket-->  eucshare.ried.no  <--WebSocket--  eucplanet.ried.no/share
   ShareSession                 (relay: rooms,       (static page on GitHub Pages;
   + friend markers on the       fan-out, 1h TTL;     MapLibre; watch-only)
     navigator map               forwards ciphertext)
```

Three independent parts. The relay is deliberately dumb: it knows room ids and
forwards opaque messages. All position/identity/stats content is **end-to-end
encrypted** with a key that only the link carries, so the relay (and GitHub
Pages) never learn where anyone is.

### 1. Relay service (`eucshare`)

- **Own service, own process.** A separate FastAPI app run by **uvicorn as a
  single process** under its own systemd unit (`eucshare`), in its own directory
  (`/opt/eucshare`). It must NOT live inside eucstats: eucstats runs under
  gunicorn with multiple workers, and in-memory rooms cannot span worker
  processes (rider A on worker 1 would never see rider B on worker 2). A single
  process also means no Redis/database. nginx routes `eucshare.ried.no` to it.
- **Protocol (WebSocket, `wss://eucshare.ried.no/ws/{roomId}`):**
  - A client connects to a room id. The relay creates the room on first connect.
  - Every message a client sends is a small JSON envelope the relay does not
    inspect: `{ "from": <senderId>, "ct": <base64 ciphertext> }`. The relay
    fans it out to every other connection in the room.
  - On connect, the relay replays each peer's **last envelope** (one per sender)
    so a joiner immediately sees everyone's latest position instead of waiting
    for the next tick. The relay stores at most one latest envelope per sender.
  - The relay tracks a `lastSeen` per sender and sends a periodic
    `{"type":"peers","seen":{senderId: ageSeconds}}` control frame so clients
    can age out silent peers even if they never send a leave.
  - A `{"type":"leave"}` control frame from a client removes its stored envelope
    and broadcasts a leave to the room.
- **Room TTL:** when a room's last connection closes, a timer starts;
  `ROOM_TTL_S` (default 3600) later the room and its stored envelopes are
  deleted. A reconnect within the window cancels the timer. This is what lets a
  rider drop and rejoin over a bad-signal gap without the group vanishing.
- **Limits (abuse hygiene):** max connections per room (32), max message size
  (2 KB), per-connection rate limit (4 msg/s), room id must be the expected
  22-char base64url shape. Health endpoint `/health` for the deploy script. No
  auth: possession of the room id is the only credential to connect, and
  possession of the key is the only credential to read.
- **Config:** `ROOM_TTL_S`, `MAX_PEERS`, `PORT`, via env / a small config file,
  mirroring eucstats `config.py`.
- **Deploy:** a `scripts/deploy.sh` mirroring eucstats (git archive over ssh to
  `/opt/eucshare`, pip install, `systemctl restart eucshare`, curl `/health`).
  Code lives in its own repo `eucshare` (same pattern as eucstats), NOT inside
  the app repo.

### 2. The link, the room key, and privacy

One URL, shown as a QR and as copyable text:

```
https://eucplanet.ried.no/share#<roomId>.<key>
```

- `roomId`: 16 random bytes, base64url (22 chars). Public - it is what the relay
  sees.
- `key`: 16 random bytes, base64url. It lives ONLY in the URL **fragment**.
  Browsers never send the fragment to any server, so GitHub Pages serves the
  page without ever seeing it, and the relay only ever sees `roomId`.
- **End-to-end encryption:** every envelope's `ct` is AES-256-GCM of the JSON
  payload, key = HKDF(key) (same derivation in Kotlin `javax.crypto` and the
  browser WebCrypto). A random 12-byte nonce per message is prepended. Anyone
  without the fragment gets ciphertext only.
- **App Link:** the app registers an Android App Link for
  `https://eucplanet.ried.no/share` (`autoVerify`), backed by
  `/.well-known/assetlinks.json` on the Pages site. Opening the link on a phone
  with the app installed opens the app and joins the room as a rider; without
  the app the same link shows the web viewer. The QR therefore works for
  everyone. The app must read the fragment from the intent data (Android keeps
  it in the `Uri`).

### 3. App: `ShareSession`

- A singleton `ShareSession` (Hilt) owns: the room id + key, the rider's chosen
  identity, the WebSocket (OkHttp, already a dependency), reconnect with backoff,
  and the map of peers `senderId -> PeerState`.
- **Publishing:** while joined, publish the rider position every
  `SHARE_INTERVAL_S` (default 3 s) or on more than 10 m of movement, whichever
  first, and only when the position actually changed. Payload (before
  encryption): `{ v:1, id, name, mode, color, icon?, avatarUrl?, flag?, lat,
  lng, heading?, t (unix ms), stats?: { speedKmh, batteryPct, tempC } }`.
  `stats` is present only if the rider enabled "Share my stats".
- **Runs when the screen is off:** publishing happens from the existing
  foreground `WheelService` (it already runs during a ride), not from the
  Composable, so sharing continues with the phone in a pocket. Leaving the
  navigator screen does not leave the room.
- **Receiving:** incoming envelopes are decrypted and folded into `PeerState`
  (latest position, a trail ring buffer, `lastSeenMs`, identity, stats).
- **Trail:** per peer, keep positions from the last `trailMinutes` (default 5,
  settings). Drawn as a polyline whose opacity fades from the head to the tail.
- **Leave:** sends leave, closes the socket, clears peers. Also on app
  "Stop all".

### 4. Identity (per share session)

Chosen in the share dialog, remembered as the default for next time:

1. **Anonymous:** `Rider #NNNN` (random 4 digits per session), auto-assigned
   distinct color from a fixed palette. Sends no store id, no name, no photo.
2. **This session:** a name typed now (prefilled with the last one used) + the
   navigator customized user icon and accent color (the custom marker the
   navigator already renders).
3. **Leaderboard profile:** only offered when the rider has an eucstats profile
   (`readRiderIdFile()` non-null and `getProfile()` returns one). Uses its
   displayName, flag and avatar URL (the avatar URL is already public on
   eucstats, so the web viewer can load it).

Plus **"Share my stats"** (default ON). Peers without a shared icon/photo render
as their color; with one, as the icon/photo. Distinct colors are assigned from
a palette by join order so two friends never look alike.

### 5. Share button states (navigator map)

- **Not joined:** tapping Share opens the **share dialog**: identity picker (the
  three modes), name field, share-stats toggle, then **Start sharing**. Starting
  creates a new room (the rider is the first member) and shows the QR + link with
  Copy and Share-sheet buttons, and **Stop sharing**.
- **Joined (host or joiner alike):** the button shows a small badge with the
  peer count, and tapping it opens the **group view** instead: the QR/link at
  the top (so anyone can invite more), then a **peer list** - one row per peer
  with color/icon, name, flag, age ("2 s ago" / "stale"), and their stats if
  shared. **Tapping a row flies the map to that peer.** A **Leave** button at the
  bottom exits the group and returns the button to the not-joined state.
- Joining via a link (App Link) skips room creation: it opens the identity
  picker first (you choose how you appear), then joins the existing room and
  lands in the group view.

### 6. Staleness and dead locations (app + web viewer, same rules)

Positions arrive irregularly (bad signal, app backgrounded, rider stopped). Both
clients apply the same policy so a friend never sits frozen at a wrong spot:

- **Fresh (under 15 s):** full-color marker, trail live.
- **Stale (15 s to 2 min):** marker dims to about 50% and shows the age
  ("45 s ago"); no interpolation or dead-reckoning, the marker stays where it
  was last seen.
- **Lost (over 2 min):** marker greys out with a "lost" label; trail stops
  growing but is kept. Removed after the relay age report says they are gone,
  or on an explicit leave.
- Ages come from the message timestamp `t` compared to the receiver clock,
  cross-checked with the relay `peers` control frame so a client with a wrong
  clock still ages peers correctly.
- Thresholds are constants shared by both clients (15 s / 120 s), not settings.

### 7. Web viewer (`eucplanet.ried.no/share`)

A single static page on GitHub Pages (`docs/share.html` + assets, so it deploys
with the existing site). MapLibre GL (the same library eucstats uses). It:
reads `roomId.key` from the fragment, derives the AES key with WebCrypto,
connects to `wss://eucshare.ried.no/ws/{roomId}`, decrypts envelopes, draws
peers with the same identity/stats/trail/staleness rules, and shows the peer
list. It is **watch-only**: it never sends an envelope and never appears as a
rider. If the room does not exist (expired), it says so. No cookies, no
analytics, nothing stored.

### 8. Settings

One nested group `ShareSettings` on `AppSettings` (mandatory: AppSettings is at
248 of 255 arg slots, so this costs exactly one slot):

```kotlin
data class ShareSettings(
    val trailMinutes: Int = 5,          // 1..30
    val shareStatsDefault: Boolean = true,
    val lastIdentityMode: String = "ANON",   // ANON | SESSION | PROFILE
    val lastSessionName: String = "",
    val relayUrl: String = "wss://eucshare.ried.no",  // advanced
)
```

UI: a small **"Shared location"** block under **Navigation & weather** with trail
length (`NumberUpDown`), the share-stats default, and the relay URL. Serialized
in `SettingsJson` (nested, so it gets its own round-trip test, like
`mediaControl`).

## Global constraints (bind the implementation)

- No em-dashes anywhere. Rider terminology (wheel / rider). Localize all
  user-facing strings to every locale; keep labels short.
- Colors via `MaterialTheme.appColors.*`; explicit on-colors. Peer palette colors
  are the one deliberate exception (they are data, a fixed set, and must also
  match the web viewer).
- Settings through `SettingsRepository`; `ShareSettings` in both `toJson` and
  `fromJson` with a round-trip test. Bump the arg-limit tripwire (248 -> 249).
- Verify builds by grepping `BUILD SUCCESSFUL`.
- The relay must never be able to read positions (E2E encryption is not optional).

## Error handling

- Relay unreachable: the dialog shows "Cannot reach the share service" and keeps
  retrying with backoff while joined; the map shows the last known peers as stale.
- Bad/expired link: "This share link has expired or is invalid."
- Room full: relay refuses; app shows "This group is full."
- App Link opened while already in another room: ask "Leave current group and
  join this one?".
- Clock skew: handled by the relay age report (section 6).

## Testing

- **Relay (pytest):** fan-out to N peers, latest-envelope replay on join, leave
  removes the envelope, TTL deletes the room after the last disconnect and a
  reconnect cancels it, rate/size limits reject.
- **App (JUnit):** envelope codec + AES-GCM round-trip (must interoperate with
  a fixed test vector the web viewer also asserts); trail ring buffer and fade
  math; staleness classifier (fresh/stale/lost) - pure functions.
- **Web viewer (Playwright):** loads a share link, decrypts a known test
  envelope, renders a peer, ages it to stale/lost, expired-room message.
- **End to end:** two emulators in one room (host + App-Link joiner) plus the
  Playwright web viewer against the real `eucshare.ried.no`; screenshots of all
  three showing each other, tap-to-locate, leave, and the 1 h TTL (shortened via
  config for the test). Conclusions written up with the screenshots.

## Out of scope (YAGNI)

- Chat, voice, or any non-position messaging.
- Server-side history, accounts, or persistence (rooms are in-memory, TTL 1 h).
- Interpolation / dead-reckoning of stale peers (explicitly not done; they stay
  where last seen).
- Browser viewers joining as riders (watch-only by decision).
- Hotspot/LAN-only mode.
