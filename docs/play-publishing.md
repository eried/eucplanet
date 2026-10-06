# Publishing to Google Play

Everything that reaches a rider through Play goes through
`.github/workflows/play-publish.yml`. Three destinations, no credentials stored
anywhere, and production needs a human to approve it.

| Destination | Who sees it | How it runs |
| --- | --- | --- |
| Internal app sharing | anyone you send the link to | manual |
| Beta (open testing) | anyone who joins the public opt-in page | automatic on next-version, or manual |
| Production | everyone | manual, and a reviewer has to approve |

Android Auto is the reason this exists at all. Android Auto will not list a
sideloaded Car App Library app, whatever the rider toggles, so a GitHub APK can
never show the car screen. Internal app sharing is the cheapest route that
counts as "installed from Play".

## Running it

Actions tab, **Play publish**, **Run workflow**, pick a destination.

- **internal-sharing** prints an install link in the run summary. It defaults to
  the **debug** build on purpose: internal app sharing accepts any signing key,
  so testers can have the build that has actually been run rather than a release
  build whose R8 pass a new feature has never been through. Pick `release` when
  the point of the round is to rehearse what beta will ship.
- **beta** also runs by itself when `app/build.gradle.kts` or anything under
  `app/src/main/play/` changes on `next-version`. That filter exists because
  Play rejects a version code it already has, so publishing on every commit
  would fail on most of them.
- **production** parks the run until someone approves it in the Actions tab.

Testers have to turn internal app sharing on once, on their phone:
Play Store, Settings, tap the Play Store version seven times, then
**Internal app sharing**. Each link allows 100 downloads, expires after 60 days,
and every upload mints a new one.

## Where the text lives

### Release notes

```
app/src/main/play/release-notes/<locale>/<track>.txt
```

Today that is only `en-US/default.txt`. `default.txt` covers every track that
has no file of its own, so one file is enough until you want the beta notes to
say something different from the production notes:

```
release-notes/en-US/default.txt      <- used by every track
release-notes/en-US/beta.txt         <- overrides default.txt for beta
release-notes/en-US/production.txt   <- overrides default.txt for production
release-notes/nb-NO/default.txt      <- a second language, if you want one
```

**Play truncates at 500 characters** per language, so check the length before
pushing. The current file is about 420. Write it as reviewed prose, not
generated from commit messages.

### Store listing: title, descriptions, screenshots

**Not in the repo, and deliberately so.** The plugin only touches the listing if
an `app/src/main/play/listings/` directory exists. There isn't one, so the title,
the descriptions, the screenshots and the feature graphic stay exactly as Play
Console has them and a publish can never overwrite them by accident.

If you ever do want them version controlled, the layout is:

```
app/src/main/play/listings/en-US/
├── title.txt               (30 chars)
├── short-description.txt   (80 chars)
├── full-description.txt    (4000 chars)
└── graphics/
    ├── icon/
    ├── feature-graphic/
    └── phone-screenshots/
```

Adding that directory makes the repo the source of truth and whatever is in
Console gets replaced on the next publish, so it is a deliberate move, not a
thing to do by halves.

### Version

`app/build.gradle.kts`:

```kotlin
versionCode = 273      // Play sorts and deduplicates on this. Must go UP.
versionName = "0.21.0" // the string riders see
```

Play rejects a version code it has already seen, on any track, forever. So bump
`versionCode` for every build that goes to beta or production. Internal app
sharing is the exception: it reuses version codes happily, which is another
reason it suits tester rounds.

The usual rhythm is to bump both in the same commit that rewrites the release
notes, on `next-version`, which is also what triggers the automatic beta run.

### Release behaviour

`play { }` in `app/build.gradle.kts` sets `releaseStatus = COMPLETED`, meaning
the upload goes for review and publishes itself on approval. Two overrides worth
knowing, both per run:

```bash
./gradlew :app:publishReleaseBundle --track production --release-status draft
./gradlew :app:publishReleaseBundle --track production --user-fraction 0.1
```

`draft` holds it in Console for you to press publish. `--user-fraction` does a
staged rollout to that share of users.

## One-time setup

Two pieces, and they only have to be done once.

### 1. Keyless authentication (Workload Identity Federation)

The org enforces `iam.disableServiceAccountKeyCreation`, so there is no service
account key and nothing to leak. Instead GitHub mints a short-lived token per
run and Google exchanges it.

The service account already exists:
`play-publisher@euc-planet.iam.gserviceaccount.com`, in project `euc-planet`
(number `603782981905`), with the Play Developer API enabled.

**Create the pool.** Cloud Console, IAM & Admin, **Workload Identity Federation**,
Create pool:

- Name: `github-pool`
- Add a provider:
  - Type **OpenID Connect (OIDC)**
  - Provider name: `github-provider`
  - Issuer URL: `https://token.actions.githubusercontent.com`
  - Audience: default
  - Attribute mapping:
    - `google.subject` = `assertion.sub`
    - `attribute.repository` = `assertion.repository`
    - `attribute.repository_owner` = `assertion.repository_owner`
  - Attribute condition: `assertion.repository_owner == 'eried'`

The attribute condition is not optional. Without it any GitHub repository in the
world could ask for these credentials.

**Let the repo impersonate the account.** IAM & Admin, Service Accounts,
`play-publisher`, **Permissions** tab, Grant access:

- Principal:
  `principalSet://iam.googleapis.com/projects/603782981905/locations/global/workloadIdentityPools/github-pool/attribute.repository/eried/eucplanet`
- Role: **Workload Identity User** (`roles/iam.workloadIdentityUser`)

That binding is what says "only this repository, and only through that pool".

### 2. Play Console permissions

Play Console, **Users and permissions**, invite
`play-publisher@euc-planet.iam.gserviceaccount.com`, add EUC Planet under App
permissions, and grant:

- View app information and download bulk reports
- Release apps to testing tracks
- Release to production, exclude devices, and use Play App Signing

The last one is only needed once you want the production destination. Leave it
off while this is just for tester rounds: the workflow cannot reach production
without it, which is a useful brake rather than a limitation.

### 3. The production gate

GitHub, repo **Settings**, **Environments**, open `production` (the first
production run creates it), and add yourself under **Required reviewers**.
Until you do, a production run goes straight through with nothing asking.

## Local publishing

Still works, and takes precedence: drop a `play-service-account.json` at the repo
root (gitignored) and the plugin uses it instead of federation. Without that file
it falls back to Application Default Credentials, which is what CI provides.

```bash
./gradlew :app:uploadDebugPrivateApk                     # internal app sharing
./gradlew :app:publishReleaseBundle                      # beta
./gradlew :app:publishReleaseBundle --track production   # production
```
