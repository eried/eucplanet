# feature/engo-hud

ENGO 2 and ENGO 3 smart glasses as a HUD, over Bluetooth. Built on
next-experimental, so everything in that build is here too. Ride it only when
you can cut the ride short.

## Worked on

- Settings, Integration, ENGO glasses HUD: turn on Enable data link; the glasses connect on their own over Bluetooth.
- Shows speed and PWM, plus two numbers you pick under Glasses pages (battery, temperature, voltage, amps or trip).
- The next turn takes the screen while a route is guided; Show turns while navigating switches that off.
- Off by default, and it does not touch the Motoeye HUD.

## Please test

**ENGO 2 or ENGO 3**
- Pick your model under Model (ENGO 2 or ENGO 3) before the first test. ENGO 3: is the PWM bar green, then yellow, then red as it climbs?
- Do the glasses connect and stay connected all ride?
- Switch the glasses off and on again mid-ride: do they come back by themselves within about 10 s? The line under the switch says Searching, Connecting or Connected.
- Are the numbers readable and up to date while riding?
- With a route, does the next turn show, and the ride page return after?
- Change the two numbers under Glasses pages: do the glasses follow within a second or two?

## Reporting back

Open an issue with your glasses model and phone. A Service Mode log beats a
description; if it crashed, Share crash log on the About screen.
