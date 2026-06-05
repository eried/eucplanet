# Ride Stats — Design

> **Status:** Design approved 2026-06-04 (brainstorming), pending spec review.
> Repo: `eried/eucplanet`. Companion server-side contract for the eucstats team:
> `eried/eucstats:docs/integration/eucplanet-metrics-additions.md`.

---

## 1. Summary

Surface the rich telemetry the app already records (and already uploads) as actual stats:
a richer **trip viewer** (elevation profile, efficiency, power/thermal peaks, motion, battery)
and a new **lifetime stats screen** (totals, personal bests, per-wheel breakdown). All of it is
**computed locally** from the rider's own recorded trip CSVs — so it works offline and covers
every trip, uploaded or not. The public/online standing stays in the separate eucstats card.

A small client change also **enriches the upload `meta` envelope** with device/GPS provenance so
the server can collect richer metrics; the matching server work is handed off as a contract doc.

## 2. Goals / Non-goals

**Goals**
- Compute per-trip metrics from the CSV: climb/descent, efficiency (Wh/km), energy (Wh),
  max PWM, max temperature, max G-force, avg & median speed, moving time, battery used %.
- Show them in the trip detail screen + an **elevation profile** chart.
- A lifetime stats screen aggregating across all recorded trips: totals, bests, per-wheel.
- Cache per-trip stats so the lifetime screen is instant (no re-parsing on every open).
- Enrich the upload `meta` envelope with device + GPS-quality fields.

**Non-goals**
- No new telemetry capture — the CSV already has everything (incl. Altitude, G-Force, PWM).
- No importing arbitrary external CSVs (that's the separate backfill feature).
- No server-side implementation here — only the client `meta` change + a documented contract.
- No new charting dependency — reuse the app's Canvas drawing (as the gauges do).

## 3. Data source & scope

- **Source:** the rider's recorded trips — every `TripRecord` in the local Room DB, each backed
  by its CSV in `TripRepository.getTripsDir()`.
- **Schemas vary** (`eucplanet-v1/v2/v3-gforce`, `darknessbot-v1`): the calculator maps columns
  **by header name**. Absent columns → that metric is omitted for that trip (0 climb if no
  Altitude, no G if no G-Force column, etc.). Never fabricate.
- **Missing CSV** (file deleted, row kept): fall back to the `TripRecord`'s stored
  `distanceKm`/times; skip CSV-derived metrics.

## 4. Components

### 4.1 `TripStats` calculator (pure, JVM-testable)
`util/TripStats.kt` — `fun compute(csvText: String): TripStats` returning a value object:
```
data class TripStats(
  val sampleCount: Int,
  val climbM: Float, val descentM: Float,           // sum of +/- altitude deltas (smoothed)
  val maxSpeedKmh: Float, val avgMovingSpeedKmh: Float, val medianSpeedKmh: Float,
  val movingSeconds: Int, val totalSeconds: Int,
  val maxPwm: Float, val maxTempC: Float, val maxGforce: Float,
  val whPerKm: Float, val energyWh: Float,           // from V*A over time / distance
  val batteryUsedPct: Float,                          // start - end battery level
  val distanceKm: Float,                              // GPS-derived, falls back to mileage delta
)
```
- Header-driven column index lookup; tolerant of both date formats; clamps implausible deltas
  (reuse the haversine + accuracy gating already in `SyncManager.parseCsvMeta`).
- Altitude smoothing: small moving-average / deadband so GPS noise doesn't inflate climb.
- Pure function → unit tests over fixture CSVs (full v3 schema, an old altitude-less schema,
  empty/short files).

### 4.2 Per-trip stats cache (Room side table)
`data/db/TripStatsEntity.kt` — one-to-one with `TripRecord` by `tripId`, holding the computed
fields above + a `statsVersion: Int` (bump to force recompute when the calculator changes).
- DAO: `getByTripId`, `upsert`, `observeAll` (for the lifetime aggregator).
- **Compute timing — at trip-add time** (the rider's preference): compute & cache the moment a
  trip is **saved** (`TripRepository.finalizePendingTrip()` / recording stop) and, in future, the
  moment a trip is **imported**. Computation is a single reusable step (`computeAndCache(trip)`)
  that every trip-add path calls, so import and recording share it.
- **Pre-existing trips** (recorded before this feature): a **one-time background backfill** parses
  their CSVs once and upserts — run on first open of the stats screen (with a progress indicator),
  guarded so it never re-runs once `statsVersion` matches.
- Room migration adds the table (version bump).

### 4.3 Trip viewer additions (`ui/recording/TripDetailScreen.kt`)
Add a stats block (themed via `MaterialTheme.appColors.*`): distance, moving/total time,
avg & max speed, **Wh/km + energy**, **climb/descent**, max PWM / temp / G, battery used. Plus an
**elevation profile** sparkline/area chart drawn on a Compose `Canvas` (altitude vs distance/time).
Reads from the cache (computing lazily if missing).

### 4.4 Lifetime stats screen (new)
`ui/stats/RideStatsScreen.kt` + `RideStatsViewModel`:
- **Totals:** trips, distance, climb, energy, ride time.
- **Personal bests:** top speed, max G, biggest climb, best single day, longest streak.
- **Per-wheel breakdown:** group by `TripRecord.wheelMetaJson` model/serial → distance/trips each.
- Aggregator sums the cached `TripStatsEntity` rows (fast); triggers lazy backfill for any
  uncomputed trips with a progress indicator on first run.
- Entry point: a row/button in the existing stats/trip area (and/or a settings entry).

### 4.5 `meta` envelope enrichment (`data/eucstats/MetaBuilder.kt` + capture)
Add to the uploaded `meta`:
- `device`: `{ manufacturer, model, sdk_int, screen_resolution, app_build, locale }`
  (`Build.MANUFACTURER/MODEL/VERSION.SDK_INT`, `DisplayMetrics`, `BuildConfig.BUILD_STAMP`,
  `Locale.getDefault()`).
- `gps`: `{ avg_accuracy_m, good_fix_pct, external_gps }` — accumulate accuracy stats during
  recording (in `TripRepository`'s location callback) + the external-GPS flag from settings;
  store on `TripRecord` (small JSON) so the worker can include them.
- `sample_interval_ms` (the recording loop interval).
- Keep the envelope **float-free where hashed** (these are ints/strings/bools → safe for the
  canonical `request_hash`).

## 5. Server-side contract (hand-off, not built here)
Documented in `eucstats:docs/integration/eucplanet-metrics-additions.md`:
- Persist `app_version` + the new `device`/`gps` fields on the Trip (currently dropped).
- Compute & store per trip: climb/descent, max PWM, max temp, battery used, moving time,
  avg moving speed. (`wh_per_km` already computed — expose it.)
- Add **climb** and **efficiency** leaderboards (data already arrives in the CSV).

## 6. Testing
- `TripStats` unit tests over fixture CSVs (full v3, altitude-less old schema, darknessbot,
  short/empty) — assert climb, Wh/km, maxPWM, moving-time, battery-used.
- `TripStatsEntity` DAO + migration test.
- Aggregator test: sum cached rows → totals/bests; per-wheel grouping.
- `MetaBuilder` test: new device/gps fields present; canonical hash still stable.
- Manual: trip viewer chart + lifetime screen on the emulator.

## 7. Open items
- Elevation chart style (sparkline vs filled area) — pick during implementation, keep it simple.
- Median-speed cost on huge trips — compute from a capped sample if needed.
- Whether the lifetime screen lives under Settings or the dashboard/trip area — decide in plan.
