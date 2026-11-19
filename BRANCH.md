# feature/engo-hud

ENGO 2 and ENGO 3 smart glasses as a HUD, over Bluetooth. Built on
next-experimental, so everything in that build is here too. Ride it only when
you can cut the ride short.

## Worked on

- Settings, Integration, ENGO glasses HUD: Start scan, tap Pair on your glasses. They are remembered and reconnect on their own; Disconnect and Forget device work like the radar.
- Shows speed and PWM, plus two numbers you pick under Glasses pages (battery, temperature, voltage, amps or trip).
- The next turn takes the screen while a route is guided; Show turns while navigating switches that off.
- Off by default, and it does not touch the Motoeye HUD.
- The glasses page reads upright, every number has room of its own, and on ENGO 3 only the PWM bar and its number change colour: green, then orange and red at your gauge thresholds, like the dashboard.

## Please test

**ENGO 2 or ENGO 3**
- Is the unit clear of the speed now, with nothing else cut off or touching? A photo helps.
- ENGO 3: is the PWM bar green, then clearly orange from your gauge orange (65% unless you changed it), then red (85%)? ENGO 2: everything readable in grey?
- After a first try, please send a Service Mode log: it has the lines starting "Engo:" that say what the glasses reported.
- Do the glasses connect and stay connected all ride?
- Do your glasses show up in the scan, and under what name?
- Switch the glasses off and on again mid-ride, also with the phone screen off: do they come back by themselves? The card says Connecting or Connected.
- Are the numbers readable and up to date while riding?
- With a route, does the next turn show, and the ride page return after?
- Change the two numbers under Glasses pages: do the glasses follow within a second or two?

## Reporting back

Open an issue with your glasses model and phone. A Service Mode log beats a
description; if it crashed, Share crash log on the About screen.
