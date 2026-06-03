# eucplanet → eucstats Online Upload — Design

> **Status:** Design approved 2026-06-03, pending spec review. Spans **two repos**:
> `eried/eucplanet` (branch `feat/eucstats-online-upload` off `next-version`) and
> `eried/eucstats` (branch `feat/rider-card-and-integrity` off `main`).
> Backend contract: `eried/eucstats:docs/integration/eucplanet-android-instructions.md`
> and `…/eucplanet-upload-contract.md`.

---

## 1. Summary

Let riders opt in to **automatically publish their trips to `eucstats.ried.no`**. After a
trip's CSV is saved, the app POSTs it (plus a metadata envelope the CSV can't carry) to the
public stats backend, with retry on failure. Onboarding is **entirely in-app**: a one-time
consent + profile step (name, country flag, **required** avatar photo) registers the rider,
after which a small **profile card** (avatar + total km, mileage rank, top speed, trips,
country) is fetched from the server and shown in settings. Riders can edit their profile and
exercise GDPR delete/export, all in-app.

Uploads are authenticated with **Play Integrity** (anti-fraud attestation — *not* Play Games;
no sign-in). The server's enforcement is a **runtime stub/enforce toggle in the admin panel**
so the maintainer can develop with it off and flip it on once Google Cloud credentials are in
place.

## 2. Goals / Non-goals

**Goals**
- One-time, in-app onboarding → rider registered on eucstats.
- Auto-upload of each newly recorded trip, with a durable retry queue.
- A server-backed profile card surfaced in settings.
- In-app profile editing (name/flag/avatar, rate-limited per the server) + GDPR delete/export.
- Full Play Integrity attestation (client + server), maintainer-controlled enforcement.

**Non-goals**
- **No backfill** of historical CSVs. New trips only — old CSVs can't reproduce the live
  context the envelope needs (`is_mock_location`, live wheel/BLE details, timezone).
- No Play Games Services, no Google account sign-in, no web/OAuth flow.
- No iOS work now (the attestation module is designed to be swappable for App Attest later).
- No changes to the existing SAF "sync folder / settings backup" feature beyond reusing it as
  a precondition and as the persistence home for the rider's `store_id`.

## 3. Locked decisions

| Topic | Decision |
|---|---|
| **Card data source** | New backend endpoint `GET /riders/{id}/card` (reads `RiderStat` + computes mileage rank). |
| **`store_id`** | App-generated **UUIDv4**, persisted locally and in `eucplanet_settings.json` (reinstall-proof via the required folder). No SDK. |
| **Attestation** | Full Play Integrity (client SDK + server `PlayIntegrityVerifier`). Enforcement is a **runtime admin-panel toggle**, default `stub`. |
| **Backfill** | Live-only (trips recorded after opt-in). |
| **Coupling** | Online upload **requires** a configured sync folder. |
| **Profile/GDPR** | Full: edit name/flag/avatar (gated by `can_change_*`) + delete + export. |
| **Avatar** | **Required** at onboarding. |
| **Onboarding UI** | Fully in-app (native Compose). |
| **Architecture** | Dedicated parallel subsystem; existing folder-sync code untouched. |

## 4. Architecture overview

```
eucplanet (app)                                  eucstats (backend)
─────────────────                                ──────────────────
Recording → CsvWriter → trip CSV on disk
        │
        ▼ (15s grace, folder-gated)
TripRepository.finalizePendingTrip()
        ├── enqueue folder sync   (existing, unchanged)
        └── enqueue EucStatsUploadWorker  (NEW)
                 │
                 ├─ EucStatsRepository.buildMeta(trip)
                 ├─ Attestation.token(requestHash)  ──► Play Integrity API (Google)
                 └─ EucStatsApi (OkHttp)
                        POST /api/v1/trips  ─────────►  upload_trip()  →  verifier(mode)
                        GET  /api/v1/riders/{id}/card ─► get_card()    →  RiderStat + rank
                        POST /api/v1/riders          ─► register_rider()
                        PATCH/DELETE /riders, /export
                                                         admin panel: stub/enforce toggle
```

The new subsystem is isolated from the existing `SyncManager`/`TripUploadWorker` (which only
copy CSVs into a SAF folder). **Naming caveat:** existing code already says "upload"
(`uploadCsv`, `TripRecord.uploadStatus`, `TripUploadWorker`) for *folder copy*. We keep that as
is and name everything new `eucstats*` / "online upload" to keep the two concepts distinct.

---

## 5. App-side design (`eried/eucplanet`)

### 5.1 Data model changes

**`TripRecord`** (Room entity — needs a migration; DB already uses Room):
```kotlin
// existing: id, startTime, endTime, fileName, distanceKm, uploadStatus, uploadedAt
val tripUuid: String? = null            // UUIDv4 minted at trip save
val eucstatsStatus: Int = 0             // 0=n/a 1=pending 2=uploaded 3=failed(terminal)
val eucstatsUploadedAt: Long? = null
val eucstatsValidation: String? = null  // "validated" | "flagged"
val isMockLocation: Boolean = false     // any fix during recording was mock
val sampleCount: Int = 0                // CSV data rows
val wheelMetaJson: String? = null       // captured wheel snapshot (brand/model/serial/ble_mac/ble_name/firmware)
```
`eucstatsStatus` is **separate** from `uploadStatus` (folder sync) — a trip can be in the
folder but not on eucstats and vice versa.

**`AppSettings`** (one-line DataStore JSON fields, no migration):
```kotlin
val onlineUploadEnabled: Boolean = false
val eucstatsStoreId: String? = null        // UUIDv4; included in eucplanet_settings.json backup
val eucstatsDisplayName: String? = null
val eucstatsFlag: String? = null           // ISO-3166-1 alpha-2
val eucstatsConsentPublic: Boolean = false
val eucstatsRegisteredAt: Long? = null
```
`eucstatsStoreId` is added to `SettingsJson` so it rides the settings backup (reinstall-proof).
It is **not** stripped by `stripDeviceBindings` (it must survive device→device migration).

### 5.2 New components

- **`EucStatsApi`** (OkHttp): typed calls for `POST /riders`, `GET /riders/{id}`,
  `GET /riders/{id}/card`, `PATCH /riders/{id}`, `DELETE /riders/{id}`,
  `GET /riders/{id}/export`, `POST /trips` (multipart). Base URL
  `https://eucstats.ried.no/api/v1`. Reuses the existing OkHttp dependency.
- **`EucStatsRepository`**: orchestrates registration, profile edits, card fetch+cache
  (exposed as a `StateFlow` for the UI), and per-trip envelope construction. Injected with
  `SettingsRepository`, `TripDao`, `TripRepository`, `WheelRepository`, the `Attestation`
  module and `EucStatsApi`.
- **`EucStatsUploadWorker`** (`HiltWorker`, like `TripUploadWorker`): drains pending trips,
  builds the envelope, gzips the CSV, attaches an attestation token, POSTs, maps the response
  to `eucstatsStatus`. Enqueued as unique work `eucstats_upload` with a network constraint and
  exponential backoff.
- **`Attestation`** interface + **`PlayIntegrityAttestation`** impl + **`StubAttestation`**
  (returns empty token for stub-mode dev). `token(requestHash: String): String`. iOS App
  Attest can implement the same interface later.

### 5.3 Onboarding flow (in-app)

Entry point: the **Cloud Trips** settings section (`R.string.section_cloud_trips`, currently in
`SettingsScreen.kt`). The "Upload my stats online" toggle is **only enabled once a sync folder
is set** (`settings.syncFolderUri != null`); otherwise it's shown disabled with a hint to set a
folder first.

When the rider enables it (and isn't registered yet):
1. **Consent sheet** (one-time): the §8 text — *name, flag, avatar, aggregate stats, and
   clustered (~10 km) locations become public; raw GPS tracks are never published.* Decline →
   toggle stays off.
2. **Profile form**: display name (text), country flag (picker, default from device locale),
   **required** avatar via the **existing 64×64 crop dialog** (the one already used for the nav
   user-marker photo). Cannot continue without a photo.
3. **Register**: `POST /riders` with `{store_id (new UUIDv4), platform:"google_play",
   display_name, flag, avatar_png_base64, consent_public:true, attestation}`. On `200`: persist
   `store_id` + `can_change_*` dates, set `onlineUploadEnabled=true`, write the settings backup
   (so `store_id` lands in the folder).
4. **Show the card**: `GET /riders/{id}/card`.

Disabling the toggle later stops future uploads (does not delete the server account; that's the
explicit GDPR Delete action).

### 5.4 Per-trip upload flow

Hook point — **`TripRepository.finalizePendingTrip()`** (runs after the 15s discard-grace window
and is **already gated on `syncFolderUri != null`**, matching the folder requirement):
```
if (onlineUploadEnabled && eucstatsStoreId != null && trip.tripUuid != null) {
    tripDao.update(trip.copy(eucstatsStatus = 1))   // pending
    enqueue EucStatsUploadWorker
}
```
`tripUuid` (UUIDv4) is minted in `startRecording()` when the `TripRecord` is created, so it's
stable for retries.

**`EucStatsUploadWorker.doWork()`** — for each trip with `eucstatsStatus IN (1,3)` and
`endTime != null`, newest-first:
1. Build `meta` (§5.5). Gzip the CSV from `getTripsDir()/{fileName}`.
2. `requestHash = sha256(canonical meta without attestation)` (§5.6). Get an attestation token
   bound to that hash. Set `meta.attestation = {type, token, request_hash}`.
3. `POST /trips` multipart: `meta` (JSON string field) + `trip` (gzipped CSV,
   `application/gzip`).
4. Map response → status (§5.8). On success, trigger a card refresh.

### 5.5 `meta` envelope → source mapping

| Field | Source |
|---|---|
| `store_id`, `platform` | `AppSettings.eucstatsStoreId`, `"google_play"` |
| `trip_uuid` | `TripRecord.tripUuid` |
| `source_app` | `"eucplanet"` |
| `schema_version` | `"eucplanet-v3-gforce"` (matches `CsvWriter` header) |
| `start_utc`, `end_utc` | `TripRecord.startTime`/`endTime` (epoch ms) → ISO-8601 `Z` |
| `tz` | `TimeZone.getDefault().id` (IANA) |
| `tz_offset_min` | zone offset at `start_utc`, in minutes |
| `tz_known` | `true` (live trips) |
| `is_mock_location` | `TripRecord.isMockLocation` |
| `wheel` | parsed from `TripRecord.wheelMetaJson` `{brand,model,serial,ble_mac,ble_name,firmware}` |
| `app_version` | `BuildConfig.VERSION_NAME` |
| `os_version` | `Build.VERSION.RELEASE` |
| `sample_count` | `TripRecord.sampleCount` |
| `file_sha256` | SHA-256 of the **uncompressed** CSV bytes |
| `distance_km_client` | `TripRecord.distanceKm` |
| `attestation` | `{type:"play_integrity", token, request_hash}` (§5.6) |

### 5.6 `request_hash` canonicalization (pinned, both sides)

`request_hash` and the Play Integrity Standard request's `requestHash` are the **hex SHA-256 of
the canonical `meta` JSON with the `attestation` key removed**, serialized as **UTF-8, keys
sorted, compact separators** (`,` and `:`, no whitespace).

- **Client (Kotlin):** build the meta map without `attestation`, serialize with sorted keys and
  no spaces, UTF-8 → SHA-256 → lowercase hex. Use that as the PI `requestHash` *and* as
  `attestation.request_hash`, then attach `attestation` to the meta that's sent.
- **Server (Python):** parse `meta`, `pop("attestation")`, `json.dumps(meta, sort_keys=True,
  separators=(",", ":"))`, SHA-256, compare to the decoded token's `requestHash` and to
  `attestation.request_hash`.

The wire form of the full meta is irrelevant since the server recomputes from the parsed object.
Nested objects (`wheel`) are serialized with the same canonical rules.

### 5.7 Mock-location tracking

In `TripRepository`'s `locationCallback.onLocationResult`, set a volatile
`tripHadMockFix = true` when `Build.VERSION.SDK_INT >= 31 ? loc.isMock : loc.isFromMockProvider`.
Reset in `startRecording()`; read in `stopRecording()` and store on `TripRecord.isMockLocation`.
`CsvWriter` exposes its `rowCount` so `stopRecording()` can persist `sampleCount`.

### 5.8 Response / error handling

| HTTP | Action |
|---|---|
| `200`/`201` | `eucstatsStatus=2`, store `validation_status` (`validated`/`flagged`); refresh card |
| `409` / `{duplicate:true}` | treat as success → `eucstatsStatus=2` |
| `400` / `413` / `422` | `eucstatsStatus=3` **terminal** (no retry); log |
| `401` | re-mint attestation token, retry **once** in-worker; still failing → retry later |
| `429` | honor `Retry-After`; `Result.retry()` |
| `5xx` / network | `Result.retry()` with WorkManager exponential backoff |

Failed (`eucstatsStatus=3`) trips are surfaced in the Cloud Trips section ("N trips failed to
upload — Retry") and per-trip in the trip detail screen; **Retry** re-enqueues the worker.

### 5.9 Card UI

In the Cloud Trips settings section, below the toggle: avatar + name + flag, then `total_km`,
`#rank mileage`, `top NN km/h`, `N trips`, country. Fetched via `GET /riders/{id}/card`,
cached in `EucStatsRepository`, refreshed on screen open and after each successful upload.
Tapping opens the rider's public page on `eucstats.ried.no` in the browser.

### 5.10 Profile / Privacy panel (Full scope)

A dedicated **"Online profile"** sub-screen reached from the Cloud Trips section (Full scope is
too large to inline):
- Edit display name / flag / avatar. Each control is enabled/disabled per the `can_change_*`
  dates from `GET /riders/{id}` with friendly messaging ("changeable again on Jul 1"); `PATCH`
  on save; handle `429` if the rider races the limit.
- **Delete account** (`DELETE /riders/{id}`) — clears local eucstats settings + disables upload.
- **Export data** (`GET /riders/{id}/export`) — saved to the sync folder / shared via the system
  sheet.

---

## 6. Backend-side design (`eried/eucstats`)

### 6.1 `GET /riders/{store_id}/card`

New route in `web/api.py`, backed by a `services/stats.py` helper. Reads the existing
materialized `RiderStat` + `Rider`; computes mileage rank:
```python
def rider_card(db, store_id):
    r = db.get(Rider, store_id);  rs = db.get(RiderStat, store_id)
    if r is None or r.deleted_at is not None: return None
    rank = db.query(func.count()).select_from(RiderStat).join(Rider, ...)\
             .filter(Rider.deleted_at.is_(None), RiderStat.total_km > (rs.total_km or 0)).scalar() + 1
    country = latest-trip country for store_id
    return {display_name, flag, has_avatar, avatar_url, total_km, trips, top_speed_kmh,
            max_gforce, longest_trip_km, current_streak, longest_streak, mileage_rank, country}
```
Returns `404` for unknown/deleted riders, `{...stats all 0, mileage_rank:null}` for a registered
rider with no validated trips yet. Covered by a new pytest in `tests/test_api.py`.

### 6.2 `PlayIntegrityVerifier`

Implement the stubbed class in `ingest/attestation.py` to decode the token via Google
(`playintegrity.googleapis.com/.../decodeIntegrityToken`) using a service account, and verify:
`requestPackageName == config.ANDROID_PACKAGE`, `requestHash` == server-recomputed hash (§5.6),
`appRecognitionVerdict == PLAY_RECOGNIZED`, device verdict meets policy. Returns
`AttestationResult(ok, reason)`; reasons map to `401`. Unit-tested with a decoded-payload
fixture (no live Google call in tests).

### 6.3 Runtime attestation-mode toggle (admin panel)

Today `config.ATTESTATION_MODE` is a load-once env var. Change to a **runtime** value:
- `web/admin.py`: add `attestation_mode` to the persisted admin state
  (`_load_state`/`_save_state` → `admin.json`); helper `get_attestation_mode()` returns the
  stored value or falls back to `config.ATTESTATION_MODE` (env default).
- Dashboard (`_dash_html`): show current mode + a `POST /admin/attestation` form to switch
  `stub`⇄`enforce` (auth-gated like the other admin actions).
- Ingest path (`services/ingest.py` / `web/api.py:upload_trip`): select the verifier with
  `get_verifier(get_attestation_mode(), config.ANDROID_PACKAGE)` **per request** (not at import),
  so flipping the toggle takes effect with no restart. When `enforce` and the service account is
  configured, `get_verifier` returns `PlayIntegrityVerifier`; otherwise it falls back to
  `EnforceStubVerifier` (any non-empty token) so the path is testable before creds exist.
- Covered by `tests/test_admin.py` (toggle persists, auth required) + `tests/test_attestation.py`.

---

## 7. Play Integrity end-to-end + maintainer steps

**Code we write:** client `PlayIntegrityAttestation` (Standard request with `requestHash`,
`cloudProjectNumber`), behind the swappable `Attestation` interface; server
`PlayIntegrityVerifier` + the admin toggle.

**Manual steps only the maintainer can do** (blocking *enforced* go-live, **not** dev/QA against
stub):
1. Play Console → **App integrity**: link a Google Cloud project, enable the Play Integrity API.
2. Provide the app's **GCP cloud project number** (compiled into the Standard request).
3. Create a **service account** with `decodeIntegrityToken` access; provide its credentials to
   the eucstats deployment.
4. Flip the admin toggle to **enforce** when ready.

**QA reality:** under `enforce`, attestation only passes from a **Play-Store-installed build**
(`appRecognitionVerdict == PLAY_RECOGNIZED`); a sideloaded debug APK fails. So onboarding/upload
QA runs against **stub** until a Play track build + creds exist.

## 8. Privacy / consent

One-time opt-in before any upload (§5.3 step 1). Only consenting riders upload. Raw GPS tracks
are never published by the backend (it stores clustered cells + downsampled tracks per its own
design); the app communicates this in the consent copy. GDPR delete/export exposed in the
Online Profile panel (§5.10).

## 9. Testing strategy

**App (JVM unit + `MockWebServer`):**
- Envelope construction: field mapping, epoch→ISO-8601 `Z`, tz/offset, `file_sha256`.
- `request_hash` canonicalization: sorted-keys/compact bytes; nested `wheel`; matches a known
  vector shared with the Python side.
- Response→`eucstatsStatus` mapping incl. `409` duplicate = success, `4xx` terminal vs `401`
  re-mint vs `5xx` retry.
- `StubAttestation` returns empty token; worker handles stub-mode `200`.
- Room migration adds the new columns without data loss.

**Backend (pytest, extends existing suites):**
- `/riders/{id}/card`: known stats, rank computation, 0-trip rider, 404 deleted.
- `PlayIntegrityVerifier` against decoded-payload fixtures (package/hash/verdict pass+fail).
- Admin toggle persists in `admin.json`, requires auth, changes verifier selection.

**End-to-end:** against live **stub-mode** API — register, upload a real CSV, fetch card,
edit profile, delete/export.

## 10. Branches & workspace

- `eried/eucplanet` → `feat/eucstats-online-upload` (off `next-version`) — cloned to
  `./eucplanet`.
- `eried/eucstats` → `feat/rider-card-and-integrity` (off `main`) — cloned to `./eucstats`.
- This spec lives in `eucplanet/docs/superpowers/specs/`; the eucstats changes are summarized
  in §6 and will get their own implementation tasks.

## 11. Open items / risks

- **Wheel snapshot fields:** exact sources for `brand/model/serial/firmware` come from
  `WheelRepository`/`WheelProfile`; `ble_mac`/`ble_name` from `AppSettings.lastDeviceAddress`/
  `lastDeviceName`. Resolved in planning. (Chosen: snapshot at record start into
  `wheelMetaJson` so a mid-trip BLE drop doesn't lose it.)
- **Canonical JSON parity** between Kotlin and Python is correctness-critical for attestation;
  guarded by a shared test vector.
- **Trip without a wheel** (phone-only GPS recording): `wheel` fields null — allowed by the
  contract (all optional).
- **`country`/rank for brand-new riders**: card returns zeros / null rank until the first
  validated trip aggregates.
