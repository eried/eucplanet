# BLE capture guide for new wheels

EUC Planet learns each wheel's BLE protocol from real captures of the
manufacturer's app talking to the wheel. If your wheel isn't supported yet
(or works but with missing data), a 5 to 10 minute capture from you is what
unlocks it.

## What you'll send back

1. The Bluetooth snoop log (`btsnoop_hci.log` and `.cfa` files)
2. Your notes from the labeled session below
3. The screenshots you took during the ride

You can email everything to the maintainer or attach to a GitHub issue.

## Before you start

A clean trace is much more useful than a long messy one. A 30-second checklist:

- Phone fully charged or on a cable. The session is short but the bug-report
  pulls afterward are CPU-heavy and will throttle on a hot phone.
- Disconnect every *other* BLE device for the duration of the capture:
  smartwatch, earbuds, fitness trackers, second EUC. The snoop log records
  every paired device on the radio, and unrelated traffic makes the wheel's
  packets harder to pick out.
- Have a stopwatch ready (a separate phone, a kitchen timer, or just use
  the lock-screen clock).
- Put the phone's clock somewhere visible in your screenshots; same time
  reference everywhere makes correlation trivial.

## 1. Turn on Bluetooth logging

1. Settings, About phone, tap **Build number** seven times until it says
   you are now a developer
2. Settings, System, **Developer options**, turn on
   **Enable Bluetooth HCI snoop log**
   - Some OEM ROMs label it slightly differently: **Enable Bluetooth HCI
     snoop logging**, **Bluetooth packet log**, or hide it under a
     **Wireless debugging** sub-menu. The setting is always in Developer
     options though.
3. Toggle Bluetooth off, then back on (this starts a fresh log)
4. Force-stop your wheel's manufacturer app, then reopen it

## 2. Run the labeled session

Connect to your wheel in the manufacturer's app. Open a notes app or
stopwatch alongside, and write down rough seconds when you do each step.
Approximate is fine, within five seconds is plenty.

```
0:00  idle 10 s
0:10  SCREENSHOT the dashboard (note voltage and battery percent)
0:15  headlights on, off
0:25  DRL on, off
0:35  horn (2 quick presses)
0:45  lock the wheel, then unlock
1:00  change tiltback to a specific value, then back
      (write down old and new value)
1:20  change beep alarm speed similarly
1:40  change volume to 50 percent (write old and new)
1:50  ride 30 to 60 s, vary the speed: walk, slow, medium, faster,
      then brake firmly to stop
2:50  SCREENSHOT mid-ride or right after stopping
2:55  idle 30 s
3:25  SCREENSHOT (note any temperature shown)
```

Skip anything that feels unsafe. Just note which step you skipped.

## 3. What to write down

```
Wheel model and firmware:
Battery percent at start:
Battery percent at end:
Voltage at start:
Voltage at end:
Approximate top speed during ride:
Tiltback was __, changed to __, then back
Alarm was   __, changed to __, then back
Volume was  __, changed to 50, then back to __
Anything you skipped:
```

The screenshots are important. They let us match what the manufacturer's
app is showing to the bytes the wheel sent at that moment.

## 4. Get the log file

### Standard Android (Pixel and most non-Samsung)

1. Settings, System, Developer options, **Take bug report**, choose
   **Interactive**
2. Wait for the notification, then share the resulting zip
3. The file we need is inside the zip at
   `FS/data/misc/bluetooth/logs/btsnoop_hci.log`. On Android 14+ the same
   file sometimes shows up directly at `data/misc/bluetooth/logs/` (no
   `FS/` prefix); both work. If you can't find it, just send the whole
   zip; it's only a few MB.

### Samsung phones

Thanks to a tester who figured this out, here's the Samsung path:

To get the `.cfa` file:

1. After running the session, go back to Developer options and turn off
   Bluetooth HCI snoop log
2. Wait 5 minutes
3. Open the **phone dialer** and type `*#9900#`
4. Tap **Run dumpstate / logcat** (takes 3 to 4 minutes)
5. Tap **Copy to sdcard (include CP Ramdump)**
6. Open the Files app, internal storage, open the **logs** folder
7. Open the **Bluetooth** folder. The `.cfa` file is there.

To get the `.log` file:

1. Same steps 1 to 5 as above
2. In the same `logs` folder, find the **dumpstate** zip and unzip it
3. Inside the unzipped folder, open `FS`, then `logs`, then `Bluetooth`
4. The `.log` file is there

## 5. Turn the snoop log off

Go back to Developer options and turn **Bluetooth HCI snoop log** off when
you're done. You only need it during the capture.

## 6. Quick sanity check before sending

Before you bundle and send, eyeball the `.log` / `.cfa` file size. A real
capture of a five-minute session with the wheel app actively connected is
usually between **500 KB and 10 MB**. Anything under ~50 KB means the
snoop log toggle didn't take effect or Bluetooth was never restarted.
Re-do step 1 and try again. Anything over ~50 MB usually means there's
a lot of unrelated BLE traffic in the trace; turn off other paired
devices and re-capture, or just send what you have and note it.

## 7. Send everything back

Bundle the `.log` / `.cfa`, your notes, and the screenshots. A zip via
email or a GitHub issue works. If the bundle is over ~25 MB GitHub will
reject the attachment; use any cloud share you trust and link to it.

Thanks for helping out.
