# fix/inmotion-v6

A test build for the InMotion V6 (issue #33), on top of the 0.22.0 release.
Once a V6 rider confirms it, it ships as 0.22.1.

## Worked on

- InMotion V6: the light button sends the two-byte form the P6 uses. The V14 form did nothing on a V6.

## Please test

**InMotion V6**
- Tap Light in the app: does the wheel light switch on and off?
- If not: in Service Mode, try each Light command and say which one switches it.
- Charge the wheel with the app connected: what amps does the app show, and what does the charger say? Note the battery % too, a nearly full wheel takes very little current. A Service Mode log of a few minutes of charging helps most.

## Reporting back

Comment on issue #33 with what you saw. A Service Mode log beats a
description; if it crashed, Share crash log on the About screen.
