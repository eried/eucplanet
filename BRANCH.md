# ci/play-publish

A way to get builds to testers through Play, because Android Auto will not
accept them any other way.

## Worked on

- New `Play publish` workflow. A manual run puts a branch on Play Internal app
  sharing and prints the install link; a push to next-version goes to the open
  beta track on its own. Production is not reachable from it.
- Internal app sharing defaults to the debug build, since it accepts any
  signing key and that is the build that has actually been run.

## Please test

- Nothing for riders. CI only, and it stays dormant until the
  `PLAY_SERVICE_ACCOUNT` secret exists. The header of
  `.github/workflows/play-publish.yml` says how to create it.
