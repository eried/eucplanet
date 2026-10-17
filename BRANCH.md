# feature/route-solver

Puts a route's stops in the shortest riding order. Built on
next-experimental, so everything in that build is here too. Ride it only when
you can cut the ride short.

## Worked on

- Map menu: Solve shortest route reorders the stops, shows before and after (e.g. 254 km to 63 km), and changes nothing until Apply.
- Effort: Quick uses straight lines (instant, offline); Best uses road distances for your travel mode.
- Ends: Anywhere, Loop (back to the start) or Fixed end (the last stop stays last).
- Start from my position: off by itself with no GPS fix or when every stop is past Max start distance.
- Reached stops stay first and are never moved.
- A route now holds 50 stops instead of 25. Stops after the eighth read "Stop 9", not "Destination".

## Please test

**Solve shortest route**
- Load a GPX with stops out of order, then Menu, Solve shortest route, Best: is the new order sensible on the map?
- Does the route total after Apply match the "to" number in the dialog?
- Try Loop and Fixed end: does the route come back to the start, or keep its last stop?
- Turn off mobile data and solve on Best: does it say it used straight lines?
- A route with 30 to 50 stops: does the stop list, the map and navigation behave normally?
