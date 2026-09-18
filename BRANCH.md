# inmotion-v6

An experiment for the InMotion V6 (the 2025 commuter wheel), branched from
next-experimental so everything there is in this build too. For the rider
whose V6 connected and then showed nothing.

## What happened

Your diagnostics showed the app connected to the V6, asked it for data the
way it asks a V14, and got no reply at all for 15 seconds, then let go on
purpose. The V6 is a 2025 wheel on the same radio as the V14 and P6, but it
does not answer the V14 questions. The P6 was the same story last year until
its own command set was captured, so this build tries the P6 command set on
the V6.

## What to check in this build

1. Turn on Service Mode first: About screen, hold the logo, Enter. Then
   connect to the V6 from the Connection screen.
2. Wait 20 seconds. If numbers appear on the dashboard, even wrong ones, that
   is a result. Voltage will read strangely: the V6 has a 26 V pack and the
   app does not know its layout yet.
3. Either way, share the diagnostics file (Service Mode, Share). This time it
   will contain the connect itself, the wheel's services, which adapter was
   chosen and every reply, if any.

If the file shows replies (RECV lines), the rest is a parsing job and no
capture is needed. If it shows only SEND lines again, the V6 speaks something
neither the V14 nor the P6 speaks, and the only way forward is a Bluetooth
capture of the official InMotion app talking to the wheel for about a minute:
see docs/BLE_CAPTURE_GUIDE.md. Please add the exact wheel name from the scan
list and the firmware version the InMotion app shows.

## Reporting back

Say which wheel, which firmware and what you expected instead. A trip
recording is worth more than a description, and if it crashed, Share crash log
on the About screen.

---

Each branch keeps its own BRANCH.md, and this one changes as the work does.
