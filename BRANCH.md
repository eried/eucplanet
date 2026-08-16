# Garmin watch: lag + crash fix (round 2)

Follow-up on issues #13 (crash) and #14 (delayed data). The previous build
reduced both; this one adds end-to-end pacing and extra crash guards.

## Install BOTH sides

The fix only fully works with the phone app and the watch app updated together:

- **Phone:** install `phone-debug.apk` from this release. Uninstall the Play
  Store version first - a debug build can't update it in place.
- **Watch:** sideload the `.iq` for your device from this release (copy it to
  `GARMIN/APPS/` on the watch over USB, or load it with the Connect IQ tools).

Updating only one side is safe - it falls back to the old behaviour - but you
won't get the lag fix until both are on this build.

## What changed

- The phone now paces itself to what the watch actually receives: the watch
  echoes a sequence number on its heartbeat, and the phone stops sending once
  it's a few frames ahead. Data can no longer fall minutes behind; it stays
  within a few seconds.
- The watch's message handling and drawing are wrapped so a transient hiccup
  skips one frame instead of taking the whole dial down.

## If it still crashes: please grab the crash log

Garmin records Connect IQ crashes to a file on the watch. To send it over:

1. Ride / use the dial until it crashes (the "IQ!" error icon, or a frozen dial).
2. Connect the watch to a computer with the USB cable. It shows up as a drive
   (on Mac or MTP-only watches, use Android File Transfer or Garmin Express file
   access).
3. Open `GARMIN\APPS\LOGS\` on the watch's storage.
4. Copy **`CIQ_LOG.YML`** (older firmware: `CIQ_LOG.TXT`, plus any `.BAK`) and
   send it back - ideally with the rough time the crash happened.

That log names the error type (out of memory, unhandled exception, watchdog,
and so on) and pins down what's left to fix.
