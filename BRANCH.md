# ci/play-publish

Getting builds to riders through Play, because Android Auto will not accept them
any other way.

## Worked on

- New `Play publish` workflow. Manual runs go to Internal app sharing (prints
  the install link) or production (held for a reviewer); beta runs by itself when
  a version bump lands on next-version.
- Keyless: no Play credential is stored anywhere. GitHub mints an OIDC token per
  run and Workload Identity Federation exchanges it, so the org policy that
  blocks service account keys is respected rather than worked around.
- `docs/play-publishing.md` says where release notes, listing text and the
  version live, and what the one-time setup is.

## Please test

- Nothing for riders. CI only, and it stays dormant until the workload identity
  pool exists. See `docs/play-publishing.md`.
