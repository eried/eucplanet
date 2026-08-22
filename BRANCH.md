# HUD: a half-open link is nobody's job to notice

Fixes the slow re-pair the tester reported on 2026-08-22: "seemed to take a
minute to connect but still connected before the timeout".

**HUD APK: updated, 0.1.12 -> 0.1.13. The tester needs both sides.**

## The problem

The capture (`diagnostics-20260822-151316.txt`, app 0.16.3/265, HUD 0.1.12,
SM-F766U1 / Android 16) shows the whole incident in three numbers:

```
15:12:47.745  Beacon RX from 10.222.65.125 -> 10.222.65.125:28080 (#1)
              ... 11.3 s, roughly six more beacons, not one line of trace ...
15:12:59.060  Phone networks / Searching / UDP beacon: 10.222.65.125:28080
15:12:59.633  Connected ✓
```

The phone knew exactly where the HUD was from 47.745 onward and did nothing
with it for 11.3 s. Once it did look, the whole re-pair took **573 ms**.
Discovery was never slow. Noticing the dead socket was.

The HUD's Wi-Fi had re-formed on a new subnet (the stale saved address in
settings still read `10.240.`), which left the phone holding a **half-open**
socket. A TCP connection whose peer went away without a FIN stays open on both
ends, so neither side could tell it from a live one, and every fast-re-pair
mechanism on both sides turned out to be gated behind a heartbeat expiring:

- HUD: Ktor ping 5 s, peer declared dead after 12 s. The 11.3 s is that timeout.
- HUD watchdog: `verdict = if (status == CONNECTED) HEALTHY else assess(health)`.
  A stale CONNECTED short-circuits it, so it never looked at the radio at all.
  The capture proves it: the `HUD paired` line carries no `HUD self-heal:` note,
  meaning no off-air episode was ever recorded.
- Phone: OkHttp ping 5 s foreground, 15 s background, so up to 30 s. And
  `webSocket.send()` keeps returning true for the better part of an hour,
  because OkHttp buffers to 16 MiB and a frame is about a KB.
- Phone: a fresh beacon kicks `delayOrKick`, which cancels a BACKOFF SLEEP. It
  could never preempt an established socket.
- Phone: `probePeerAfterNetworkLoss`, the one path that cuts a zombie, hung off
  a `NetworkRequest` requiring `NET_CAPABILITY_INTERNET`. The HUD link rides a
  network that by definition has none, so the rescue written for exactly this
  case could never fire for it.

Which end noticed first was a race between two timeouts. Nothing was watching
the evidence both ends already had.

## What changed

Three layers, each acting on evidence rather than on a timer.

- **The HUD hangs up when its own address moves** (`refreshIpAndMdns`). A socket
  the phone opened on the old address cannot still be good, so this is proof,
  not suspicion. It already detected the change to refresh mDNS and kick the
  beacon; now it also drops the connection. This is the root-cause fix.
- **The phone checks when a beacon disagrees with its peer.** A beacon naming an
  address we are not connected to is the earliest evidence available, and it
  arrives every 2 s. It does not cut on the beacon alone: two HUD interfaces or
  a misread packet must never cost a healthy link, so the disagreement triggers
  the same TCP probe a network loss does, and only an unreachable peer is cut.
- **The phone watches every Wi-Fi network, not just internet-capable ones.**
  `NET_CAPABILITY_INTERNET` is dropped from the request, so `onLost` for the
  HUD's own network now reaches the peer probe. The interference detector still
  wants only real STA transitions, so internet-capable networks are tracked in
  `staNetworks` from `onCapabilitiesChanged` and it is fed from there.
  `HudUdpListener` already omitted the capability; these two now agree.

Plus the two reasons this was nearly undiagnosable:

- **The link trace records whether or not anyone is watching.**
  `DiagnosticsLogger.note` is a no-op until Service Mode opens, so a capture
  only ever began when the rider went looking, and the run-up, where the answer
  was, had never been recorded. `HudLinkTrace` keeps the last 600 link lines
  always (well under 100 KB, and nothing at all while a link is healthy) and
  replays them into the capture with their original timestamps. Raw BLE stays
  opt-in; that is the firehose this ring is deliberately not.
- **`onClosing` now writes to the trace.** The HUD hanging up was the one way
  this link could end leaving no line at all, which is exactly how the report
  arrived: eleven silent seconds and then a search out of nowhere.

`StaleLink` in `hud-protocol` holds both predicates, pure and shared, next to
`LinkWatchdog`.

## Verified on device

HTC U23 pro (Android 13), both APKs on the one device so the beacon, the
discovery and the WebSocket are all real. `setprop debug.eucplanet.hud.ip
<addr>` (new, follows the existing `HudDebug` pattern) simulates the HUD moving
between APs, which is otherwise only reproducible by carrying it between two.

Forcing the address change on a link whose socket was perfectly healthy, which
under the old code would never have recovered at all:

```
23:06:14.549 phone  HUD announces .99:28080 but we hold .75:28080 - checking it
                    -> probes .75, finds it REACHABLE, correctly does NOT cut
23:06:22.378 HUD    local IP changed 192.168.212.75 -> 192.168.212.99
23:06:22.381 HUD    dropping the phone connection: HUD address changed        (+3 ms)
23:06:22.405 phone  HUD closed the link: 1001 HUD address changed             (+27 ms)
23:06:22.423 phone  Searching (all channels in parallel)  - no backoff        (+45 ms)
23:06:24.869 phone  Connected ✓                                              (2.49 s total)
```

The trace replay, entering Service Mode at 23:10:07:

```
104 entries, oldest 23:05:35.551 NOTE hud_link: Link enabled, starting discovery
```

4.5 minutes of history that under the old code would not have existed.

## Tests

- `:hud-protocol:testDebugUnitTest` - `StaleLinkTest`, both predicates including
  every "do not cut" case (agreeing beacon, no peer, unparseable peer, first
  address of a session, lost lease).
- `:app:testDebugUnitTest` - `HudLinkTraceTest`, the ring and its replay:
  recorded-before-open reaches the capture, timestamps and ordering survive,
  bounded, and reopening Service Mode never duplicates a line.
- `app/build.gradle.kts` gains `testOptions.unitTests.isReturnDefaultValues`, so
  a JVM test crossing `android.util.Log` no-ops instead of throwing. Full app
  suite re-run green with it.

## Still open, deliberately not in this branch

Found while reading, each real and each its own change:

- `HudUdpListener.runListenLoop` ends on `while (job?.isCancelled == false)`,
  but `onAvailable` has already reassigned `job` to the replacement, so the old
  loop parks forever in an uncancellable `socket.receive()` and never closes its
  socket. One `Dispatchers.IO` slot and one FD leaked per Wi-Fi transition.
  Visible in the device log above as two `bound to 0.0.0.0:28079` lines.
- `HudSubnetProbe` runs 64 concurrent blocking connects on `Dispatchers.IO`,
  whose total parallelism is 64, so a sweep can starve every other IO coroutine
  in the app. Wants its own `limitedParallelism`.
- The malformed saved address `"10.240."` in the tester's settings. AUTO ignores
  it, but BOTH mode would race it as a peer after 1.5 s.
- `hud/HUD_TESTING.md` still documents the pre-0.1.4 architecture where the
  phone was the server.
