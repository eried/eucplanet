# The horn that never went out

A rider reported that the wheel stops answering Lights and Horn, or answers
late, or turns the lights off when they asked for on, while the dashboard keeps
reading perfectly normally. They guessed the app was writing before the previous
packet had finished arriving, and asked whether raising the Wheel poll rate
would help.

Close. It is the previous *write* that has not finished, and their two
diagnostics logs say so in as many words.

## What the logs show

`diagnostics-20260831-211054.txt`, a V8S over about twenty minutes:

| | |
| --- | --- |
| writes queued | 4199 |
| refused by the stack, `code=201` busy | 2403 |
| given up on entirely after 4 attempts | 390 |
| of those writes, ones the rider pressed | 8 lights |

Two of the eight light presses were thrown away. This is one of them:

```
21:08:26.852 SEND     aa aa 0d 01 ... 01   <- light ON
21:08:26.930 REJECTED code=201
21:08:27.011 REJECTED code=201
21:08:27.093 REJECTED code=201
21:08:27.175 REJECTED code=201
21:08:27.175 DROPPED after 4 attempts      <- never went over the air
21:08:30.908 SEND     aa aa 0d 01 ... 00   <- rider taps again, app now sends OFF
```

Nothing reached the wheel, so nothing happened, so the rider pressed again. The
app had already flipped its own idea of the light to on, so the second press
asked for off. That is "the lights turn off when they're supposed to turn on",
exactly.

## Why the stack was busy

`code=201` is `ERROR_GATT_WRITE_REQUEST_BUSY`: a previous write is still in
flight. The link paced itself by assuming a write had landed 200 ms after the
stack accepted it, whether or not `onCharacteristicWrite` had said so. Measured
off the rider's own log, the stack stays busy for up to about 300 ms at a time
while it streams a reply, so the next write went out on top of the last one.
Median of a busy stretch is 82 ms, p90 164 ms, worst 246 ms.

The retry schedule then missed by a hair: four attempts 80 ms apart cover 240 ms
and give up just short of the tail.

And everything shared one FIFO. A horn queued behind a wall of telemetry polls,
four a second, and was dropped by the same rule that drops a poll, even though
another poll is along in 250 ms and nothing brings back a horn.

## What changed

- **The link waits for the write callback** instead of assuming after 200 ms.
  That is the fix; the timeout is only a backstop for a callback that never
  comes. In the ordinary case this makes writes *faster*, since the median ack
  is well inside the old 200 ms guess.
- **Backoff escalates** past the measured busy window rather than stopping 60 ms
  short of it.
- **`BleWriteQueue` splits the two lanes.** Commands queue and go first; polls
  do not queue, they replace, so a backlog of stale telemetry requests cannot
  fill the queue and start refusing the rider. A command gets 8 attempts, a poll
  gets 2.
- **The queue empties on a link teardown**, so a horn from the last ride does
  not fire on the next connection.

The no-response families (KingSong, Begode, Veteran) are on that profile
*because* their HM-10 modules do not ack reliably, so they keep the old short
wait. Waiting on a callback that never arrives is how a link that works today
would stall a second and a half per write.

## About raising the poll rate

It does help, and it is a reasonable thing to try while this is in review: fewer
writes means fewer collisions. It is a workaround, not the fix, and it costs
dashboard responsiveness. With this branch it should not be needed.

## Verified

`./gradlew :app:testDebugUnitTest`: 1007 tests, 0 failures, 9 of them new around
the queue's ordering rules and its retry budget against the measured busy window.
`:app:assembleDebug` BUILD SUCCESSFUL.

On a Pixel-class AVD against the virtual V8S: connect, telemetry streaming, and
a Light press landing through the new command lane with the poll loop running.

**Not verified against a real wheel**, because there is none here. The case this
fixes is a busy GATT link, and the virtual wheel bypasses GATT entirely. What
would settle it is the reporter running the same diagnostics again: the numbers
to watch are `code=201` and `DROPPED`, which should fall to near zero, and no
`DROPPED` at all on a write the rider pressed.

## Still open

When a command is dropped the app keeps the optimistic state it flipped, which
is what turns a lost "on" into a press that asks for "off". Rare once writes
stop being dropped, but it is the difference between a command that fails and a
command that fails backwards. Reverting the flip, and telling the rider, needs a
failure signal from the BLE layer back to the repository.
