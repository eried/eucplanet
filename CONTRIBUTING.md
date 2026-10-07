# Contributing

Thanks for helping. A few things keep the branches tidy.

## Which branch

Open pull requests against `next-experimental`. That is where new work is
tested first, and it builds a pre-release on every push so testers can try
your change right away.

`next-version` is the staging branch for the next release and `main` is the
released app. Do not open pull requests against them. GitHub picks `main` by
default, so change the base branch when you open the PR.

## Before you open a PR

- Build it: `gradlew.bat :app:assembleDebug` on Windows, `./gradlew` elsewhere.
- Run the tests for what you touched, for example
  `gradlew.bat :app:testDebugUnitTest --tests "com.eried.eucplanet.ble.*"`.
- Say which wheel and firmware you tested on, or say it was the emulator only.
- For wheel protocol work, attach a btsnoop capture from the manufacturer app.
  See `docs/BLE_CAPTURE_GUIDE.md`. A capture settles more than a description.
- Text the rider sees goes in `strings.xml`, in every locale under
  `app/src/main/res`.
- No em-dashes in code, comments or docs. Use a comma or a full stop.

The rest of the house rules are in `CONVENTIONS.md`.

## Reporting a wheel problem

Open an issue with the wheel report template and attach the capture. If you
can, add a diagnostics log from the app: hold the logo on the About screen to
enter Service Mode, then share the log.
