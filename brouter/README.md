# brouter

BRouter 1.7.10 (https://github.com/abrensch/brouter, tag v1.7.10, MIT, see
LICENSE), the offline router: brouter-util, brouter-codec,
brouter-expressions, brouter-mapaccess and brouter-core in one module.

It is Kotlin Multiplatform (JVM for the phone app, iosArm64 and
iosSimulatorArm64 for the iPhone), converted from the upstream Java, so both
phones run one copy and plan the same routes. Package and class names are
upstream's (`btools.*`).

## Local changes

- Kotlin in `src/commonMain`, converted file by file from the Java in
  `reference/upstream`. Only the routing EUC Planet reaches is converted:
  routing mode only (no round trips, elevation or info modes), no output
  formats (GPX, JSON, KML, CSV), no raw track or area files, no debug log
  or stack sampler, no map-building or diff tools (Rd5DiffTool and friends),
  no kinematic path model (the app's profiles use the standard one).
- Files are reached through `btools.util.BFiles` (java.io on the JVM, POSIX
  on iOS) and a path string replaces java.io.File; see
  `src/commonMain/kotlin/btools/util/Platform.kt`.
- VoiceHint's command constants are public and it has getters (command,
  index in track, exit number, distance to next, angle), and VoiceHintList
  has getList(), so the app can read turn hints.
- `RoutingEngine.terminate()` also stops the reading of map segments
  (`NodesCache.terminated`), not only the search loop, so a stopped run ends
  while it matches its stops too. Only a stopped run sees it; routes are the
  same.
- Profile switches are numbers: a key value like `avoid_unsafe=1`, not `true`
  (upstream behaviour, unchanged).

## The Java reference and the equivalence test

`reference/` is the upstream Java as it was vendored, test-only (Gradle
project `:brouter-reference`, compiled under the package `reference.btools`
so both copies load together). `RouteEquivalenceTest` (jvmTest) routes a few
hundred start and end pairs through both, with each profile and switch the
app sends, and asserts the same track points, elevations, distance, ascend,
cost and turn hints, or the same error. It also writes the Java's answers
for the first 40 routes to `build/brouter-golden.txt`, which
`RouteGoldenTest` (iosTest) checks the Kotlin/Native build against on the
iOS simulator. `allTests` runs the JVM test first.

The tests need one routing segment (65 MB, rebuilt weekly upstream, not in
the repo) and skip without it:

    mkdir -p brouter/testdata/segments
    curl -o brouter/testdata/segments/E10_N55.rd5 https://brouter.de/brouter/segments4/E10_N55.rd5
    sh gradlew :brouter:allTests

`BROUTER_SEGMENTS` (environment) or `-PbrouterSegments=<folder>` points at
another folder holding `E10_N55.rd5`; `BROUTER_CASES` sets how many routes
the JVM test asks for (default 300). The profiles are read from
`app/src/main/assets/brouter`.
