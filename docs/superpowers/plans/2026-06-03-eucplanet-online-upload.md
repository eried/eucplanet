# eucplanet Online Upload — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let riders opt in (in-app) to auto-upload each recorded trip to `eucstats.ried.no`, with a server-backed profile card, Play-Integrity attestation, retry queue, and full profile/GDPR management.

**Architecture:** A dedicated `eucstats` subsystem parallel to the existing SAF folder-sync. New Room columns track per-trip eucstats state; an `EucStatsApi` (OkHttp) + `EucStatsRepository` build the `meta` envelope, gzip the CSV, attach an attestation token, and POST. Enqueued from `TripRepository.finalizePendingTrip()` (already folder-gated) via `EucStatsUploadWorker`. UI lives in the existing Cloud-trips settings tab.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Room, WorkManager, OkHttp, kotlinx/org.json. Tests: JUnit (JVM) + OkHttp `MockWebServer`; Robolectric where a Context is needed; Compose/manual verification for pure UI.

**Backend:** done & runnable locally (`../eucstats`, `feat/rider-card-and-integrity`) — run `./.venv/Scripts/python.exe -m uvicorn main:app --host 0.0.0.0 --port 8000`. Debug builds target `http://10.0.2.2:8000/api/v1`. (Port 5556 is taken by the Android emulator console — do NOT use it.)

**Spec:** `../specs/2026-06-03-eucstats-online-upload-design.md`. Work from `d:\Downloads\eucplanet-leaderboard report\eucplanet`. Build/test with the Gradle wrapper: `./gradlew :app:testDebugUnitTest` (and `:app:assembleDebug` for the APK).

**Naming:** the existing folder-sync uses "upload"/`uploadStatus`/`TripUploadWorker` — leave it untouched; everything new is `eucstats*`.

---

## Phase A — Data foundation

### Task A1: TripRecord eucstats columns + Room migration 46→47

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/data/model/TripRecord.kt`
- Modify: `app/src/main/java/com/eried/eucplanet/data/db/AppDatabase.kt` (version 46→47)
- Modify: `app/src/main/java/com/eried/eucplanet/di/AppModule.kt` (add `MIGRATION_46_47`)
- Modify: `app/src/main/java/com/eried/eucplanet/data/db/TripDao.kt` (pending-eucstats query)
- Test: `app/src/test/java/com/eried/eucplanet/data/EucStatsTripFieldsTest.kt`

- [ ] **Step 1: Add fields to `TripRecord`** (append to the data class, after `uploadedAt`):

```kotlin
    // --- eucstats online upload (separate from folder-sync uploadStatus above) ---
    val tripUuid: String? = null,            // UUIDv4 minted at trip save (live-only)
    val eucstatsStatus: Int = 0,             // 0=n/a 1=pending 2=uploaded 3=failed(terminal)
    val eucstatsUploadedAt: Long? = null,
    val eucstatsValidation: String? = null,  // "validated" | "flagged"
    val isMockLocation: Boolean = false,     // any fix during recording was mock
    val sampleCount: Int = 0,                // CSV data rows
    val wheelMetaJson: String? = null        // {brand,model,serial,ble_mac,ble_name,firmware}
```

- [ ] **Step 2: Bump DB version** in `AppDatabase.kt`: change `version = 46` to `version = 47`.

- [ ] **Step 3: Add the migration** in `di/AppModule.kt` (next to `MIGRATION_45_46`):

```kotlin
private val MIGRATION_46_47 = object : Migration(46, 47) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE trips ADD COLUMN tripUuid TEXT")
        db.execSQL("ALTER TABLE trips ADD COLUMN eucstatsStatus INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE trips ADD COLUMN eucstatsUploadedAt INTEGER")
        db.execSQL("ALTER TABLE trips ADD COLUMN eucstatsValidation TEXT")
        db.execSQL("ALTER TABLE trips ADD COLUMN isMockLocation INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE trips ADD COLUMN sampleCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE trips ADD COLUMN wheelMetaJson TEXT")
    }
}
```
Add `MIGRATION_46_47` to the `.addMigrations(MIGRATION_44_45, MIGRATION_45_46, MIGRATION_46_47)` call in `buildDb`.

- [ ] **Step 4: Add the pending-eucstats DAO query** to `TripDao.kt`:

```kotlin
    /** Trips needing eucstats upload (pending or failed) with a finished recording, newest first. */
    @Query("SELECT * FROM trips WHERE endTime IS NOT NULL AND tripUuid IS NOT NULL AND eucstatsStatus IN (1, 3) ORDER BY startTime DESC")
    suspend fun getPendingEucstatsUploads(): List<TripRecord>
```

- [ ] **Step 5: Write the migration test** — `app/src/test/java/com/eried/eucplanet/data/EucStatsTripFieldsTest.kt`:

```kotlin
package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EucStatsTripFieldsTest {
    @Test fun newTripRecord_hasEucstatsDefaults() {
        val t = TripRecord(fileName = "trip_x.csv")
        assertEquals(0, t.eucstatsStatus)
        assertEquals(0, t.sampleCount)
        assertEquals(false, t.isMockLocation)
        assertNull(t.tripUuid)
        assertNull(t.wheelMetaJson)
        assertNull(t.eucstatsValidation)
    }
}
```

- [ ] **Step 6: Run** `./gradlew :app:testDebugUnitTest --tests "com.eried.eucplanet.data.EucStatsTripFieldsTest"` → PASS. Then `./gradlew :app:compileDebugKotlin` to confirm the DAO/DB compile.

- [ ] **Step 7: Commit**
```bash
git add app/src/main/java/com/eried/eucplanet/data/model/TripRecord.kt app/src/main/java/com/eried/eucplanet/data/db/AppDatabase.kt app/src/main/java/com/eried/eucplanet/di/AppModule.kt app/src/main/java/com/eried/eucplanet/data/db/TripDao.kt app/src/test/java/com/eried/eucplanet/data/EucStatsTripFieldsTest.kt
git commit -m "feat: TripRecord eucstats columns + Room migration 46->47"
```

---

### Task A2: AppSettings eucstats fields + settings-backup round-trip

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/data/model/AppSettings.kt`
- Modify: `app/src/main/java/com/eried/eucplanet/data/store/SettingsJson.kt`
- Test: `app/src/test/java/com/eried/eucplanet/data/SettingsJsonEucstatsTest.kt`

- [ ] **Step 1: Add fields to `AppSettings`** (near the `syncFolderUri` block):

```kotlin
    // --- eucstats online upload ---
    val onlineUploadEnabled: Boolean = false,
    val eucstatsStoreId: String? = null,        // UUIDv4; survives reinstall via the settings backup
    val eucstatsDisplayName: String? = null,
    val eucstatsFlag: String? = null,
    val eucstatsConsentPublic: Boolean = false,
    val eucstatsRegisteredAt: Long? = null,
```

- [ ] **Step 2: Serialize them** in `SettingsJson.toJson` (add `put(...)` lines) and `fromJson` (add reads using the file's existing `optStringOrNull`/`optBoolean` helpers):

`toJson`:
```kotlin
    put("onlineUploadEnabled", s.onlineUploadEnabled)
    put("eucstatsStoreId", s.eucstatsStoreId)
    put("eucstatsDisplayName", s.eucstatsDisplayName)
    put("eucstatsFlag", s.eucstatsFlag)
    put("eucstatsConsentPublic", s.eucstatsConsentPublic)
    s.eucstatsRegisteredAt?.let { put("eucstatsRegisteredAt", it) }
```
`fromJson` (inside the `copy(...)`/builder, matching the file's style):
```kotlin
    onlineUploadEnabled = j.optBoolean("onlineUploadEnabled", base.onlineUploadEnabled),
    eucstatsStoreId = j.optStringOrNull("eucstatsStoreId") ?: base.eucstatsStoreId,
    eucstatsDisplayName = j.optStringOrNull("eucstatsDisplayName") ?: base.eucstatsDisplayName,
    eucstatsFlag = j.optStringOrNull("eucstatsFlag") ?: base.eucstatsFlag,
    eucstatsConsentPublic = j.optBoolean("eucstatsConsentPublic", base.eucstatsConsentPublic),
    eucstatsRegisteredAt = if (j.has("eucstatsRegisteredAt")) j.optLong("eucstatsRegisteredAt") else base.eucstatsRegisteredAt,
```
(Match the EXACT helper names in the file — read it first; adapt if `optStringOrNull` takes a default arg.)

- [ ] **Step 3: Do NOT add these to `stripDeviceBindings`** — the store_id and profile must survive a device→device restore. (Verify `stripDeviceBindings` is unchanged.)

- [ ] **Step 4: Write the test** — `app/src/test/java/com/eried/eucplanet/data/SettingsJsonEucstatsTest.kt`:

```kotlin
package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.store.SettingsJson
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsJsonEucstatsTest {
    @Test fun eucstatsFields_roundTripThroughBackupJson() {
        val s = AppSettings(
            onlineUploadEnabled = true,
            eucstatsStoreId = "uuid-123",
            eucstatsDisplayName = "Erwin",
            eucstatsFlag = "NO",
            eucstatsConsentPublic = true,
            eucstatsRegisteredAt = 1_700_000_000_000L,
        )
        val restored = SettingsJson.fromJson(SettingsJson.toJson(s), AppSettings())
        assertEquals("uuid-123", restored.eucstatsStoreId)
        assertEquals("Erwin", restored.eucstatsDisplayName)
        assertEquals("NO", restored.eucstatsFlag)
        assertEquals(true, restored.onlineUploadEnabled)
        assertEquals(true, restored.eucstatsConsentPublic)
    }

    @Test fun stripDeviceBindings_keepsStoreId() {
        val s = AppSettings(eucstatsStoreId = "uuid-123", lastDeviceAddress = "AA:BB")
        val stripped = SettingsJson.stripDeviceBindings(s)
        assertEquals("uuid-123", stripped.eucstatsStoreId) // store_id survives
        assertEquals(null, stripped.lastDeviceAddress)      // device binding removed
    }
}
```

- [ ] **Step 5: Run** `./gradlew :app:testDebugUnitTest --tests "com.eried.eucplanet.data.SettingsJsonEucstatsTest"` → PASS.

- [ ] **Step 6: Commit**
```bash
git add app/src/main/java/com/eried/eucplanet/data/model/AppSettings.kt app/src/main/java/com/eried/eucplanet/data/store/SettingsJson.kt app/src/test/java/com/eried/eucplanet/data/SettingsJsonEucstatsTest.kt
git commit -m "feat: eucstats AppSettings fields + reinstall-safe settings backup"
```

---

## Phase B — Networking core (pure logic, JVM-testable)

### Task B1: Canonical JSON + request_hash (parity with the Python server)

**Files:**
- Create: `app/src/main/java/com/eried/eucplanet/data/eucstats/CanonicalJson.kt`
- Test: `app/src/test/java/com/eried/eucplanet/data/eucstats/CanonicalJsonTest.kt`

The server computes `sha256( json.dumps(meta_without_attestation, sort_keys=True, separators=(",",":"), ensure_ascii=False) )`. The client MUST produce byte-identical output.

- [ ] **Step 1: Write the test (with a fixed vector that must match Python).** The Python side, for `{"a":2,"b":1}`, serializes to `{"a":2,"b":1}` → known sha256. Compute the expected hash once with the server venv (`./.venv/Scripts/python.exe -c "import hashlib,json;print(hashlib.sha256(json.dumps({'a':2,'b':1},sort_keys=True,separators=(',',':')).encode()).hexdigest())"` in ../eucstats) and paste it below.

```kotlin
package com.eried.eucplanet.data.eucstats

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalJsonTest {
    @Test fun matchesPythonVector_simpleObject() {
        val meta = JSONObject().put("b", 1).put("a", 2)
        // sha256 of '{"a":2,"b":1}' — fill from the server venv (see step 1).
        assertEquals("4caf6b4f...PASTE_REAL_HEX...", CanonicalJson.requestHash(meta))
    }

    @Test fun stripsAttestationAndSortsNested() {
        val meta = JSONObject()
            .put("z", 1)
            .put("attestation", JSONObject().put("token", "x"))
            .put("wheel", JSONObject().put("model", "Master").put("brand", "Begode"))
        // attestation removed; nested keys sorted; equals the same object without attestation
        val expected = JSONObject().put("z", 1)
            .put("wheel", JSONObject().put("brand", "Begode").put("model", "Master"))
        assertEquals(CanonicalJson.requestHash(expected), CanonicalJson.requestHash(meta))
    }
}
```

- [ ] **Step 2: Run** → FAIL (class missing).

- [ ] **Step 3: Implement `CanonicalJson`** (recursively emit sorted-key, compact JSON; this mirrors Python's `json.dumps` for the value types we send: objects, arrays, strings, ints, doubles that are whole→int, booleans, null):

```kotlin
package com.eried.eucplanet.data.eucstats

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Serializes a meta envelope to the SAME bytes as the server's
 *  json.dumps(sort_keys=True, separators=(",",":"), ensure_ascii=False),
 *  with the `attestation` key removed, then SHA-256 (lowercase hex). */
object CanonicalJson {

    fun requestHash(meta: JSONObject): String {
        val stripped = JSONObject(meta.toString())
        stripped.remove("attestation")
        val canon = canonical(stripped)
        val digest = MessageDigest.getInstance("SHA-256").digest(canon.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { k ->
            "${quote(k)}:${canonical(value.get(k))}"
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> quote(value)
        is Boolean -> if (value) "true" else "false"
        is Int, is Long -> value.toString()
        is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        else -> quote(value.toString())
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\"); '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.append("\"").toString()
    }
}
```
Note: only send JSON-safe scalar types in `meta` (ints for counts/offsets, strings elsewhere) so the number formatting matches Python. Keep `display_name`/`flag` ASCII-safe is NOT required (`ensure_ascii=False` on both sides; Kotlin emits raw UTF-8, Python too).

- [ ] **Step 4: Run** → PASS (after pasting the real hex in step 1).

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/eried/eucplanet/data/eucstats/CanonicalJson.kt app/src/test/java/com/eried/eucplanet/data/eucstats/CanonicalJsonTest.kt
git commit -m "feat: canonical meta hashing matching the eucstats server"
```

---

### Task B2: Attestation interface + stub (+ Play Integrity client, config-gated)

**Files:**
- Create: `app/src/main/java/com/eried/eucplanet/data/eucstats/Attestation.kt`
- Modify: `app/build.gradle.kts` (add `com.google.android.play:integrity`)
- Test: `app/src/test/java/com/eried/eucplanet/data/eucstats/AttestationTest.kt`

- [ ] **Step 1: Test** — `AttestationTest.kt`:

```kotlin
package com.eried.eucplanet.data.eucstats

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AttestationTest {
    @Test fun stub_returnsEmptyTokenAndEchoesHash() = runBlocking {
        val a = StubAttestation()
        val res = a.token("deadbeef")
        assertEquals("", res.token)
        assertEquals("deadbeef", res.requestHash)
    }
}
```

- [ ] **Step 2: Run** → FAIL.

- [ ] **Step 3: Implement** `Attestation.kt`:

```kotlin
package com.eried.eucplanet.data.eucstats

/** One attestation result: the token to send + the request hash it was bound to. */
data class AttestationToken(val type: String, val token: String, val requestHash: String)

/** Swappable attestation provider (Play Integrity today; iOS App Attest later). */
interface Attestation {
    suspend fun token(requestHash: String): AttestationToken
}

/** Stub used until Play Integrity is configured; the server accepts it in stub mode. */
class StubAttestation : Attestation {
    override suspend fun token(requestHash: String) =
        AttestationToken("play_integrity", "", requestHash)
}
```

- [ ] **Step 4: Add the Play Integrity dependency** to `app/build.gradle.kts` dependencies:
```kotlin
    implementation("com.google.android.play:integrity:1.4.0")
```

- [ ] **Step 5: Implement `PlayIntegrityAttestation`** in the same file (used only when a cloud project number is configured; falls back is handled by the DI provider in Task D1):

```kotlin
// Appended to Attestation.kt
import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PlayIntegrityAttestation(
    private val context: Context,
    private val cloudProjectNumber: Long,
) : Attestation {
    @Volatile private var provider: StandardIntegrityTokenProvider? = null

    override suspend fun token(requestHash: String): AttestationToken {
        val p = provider ?: prepareProvider().also { provider = it }
        val token = suspendCancellableCoroutine { cont ->
            p.request(StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build())
                .addOnSuccessListener { cont.resume(it.token()) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        return AttestationToken("play_integrity", token, requestHash)
    }

    private suspend fun prepareProvider(): StandardIntegrityTokenProvider =
        suspendCancellableCoroutine { cont ->
            IntegrityManagerFactory.createStandard(context)
                .prepareIntegrityToken(
                    PrepareIntegrityTokenRequest.builder()
                        .setCloudProjectNumber(cloudProjectNumber).build())
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
}
```
(If the exact Play Integrity API surface differs for the resolved library version, adjust to the StandardIntegrityManager API; the DI provider only constructs this when a non-zero `EUCSTATS_GCP_PROJECT_NUMBER` is set, so the stub path is unaffected.)

- [ ] **Step 6: Run** `./gradlew :app:testDebugUnitTest --tests "com.eried.eucplanet.data.eucstats.AttestationTest"` → PASS, then `./gradlew :app:compileDebugKotlin`.

- [ ] **Step 7: Commit**
```bash
git add app/src/main/java/com/eried/eucplanet/data/eucstats/Attestation.kt app/build.gradle.kts app/src/test/java/com/eried/eucplanet/data/eucstats/AttestationTest.kt
git commit -m "feat: swappable Attestation (stub + Play Integrity client)"
```

---

### Task B3: Meta envelope builder

**Files:**
- Create: `app/src/main/java/com/eried/eucplanet/data/eucstats/MetaBuilder.kt`
- Test: `app/src/test/java/com/eried/eucplanet/data/eucstats/MetaBuilderTest.kt`

- [ ] **Step 1: Test** — verifies field mapping, ISO-8601 UTC, tz offset, sha256 of CSV:

```kotlin
package com.eried.eucplanet.data.eucstats

import com.eried.eucplanet.data.model.TripRecord
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class MetaBuilderTest {
    private val trip = TripRecord(
        id = 1, startTime = 1_717_273_471_000L, endTime = 1_717_276_202_000L,
        fileName = "trip_x.csv", distanceKm = 18.59f, tripUuid = "uuid-1",
        isMockLocation = false, sampleCount = 2731,
        wheelMetaJson = """{"brand":"Begode","model":"Master","serial":"F4E0","ble_mac":"F4:E0","ble_name":"GW","firmware":"1.07"}""",
    )

    @Test fun buildsEnvelopeWithAllRequiredFields() {
        val csv = "Date,Speed\n01.06.2026 20:24:31.204,5\n".toByteArray()
        val meta = MetaBuilder.build(
            trip, storeId = "store-1", appVersion = "3.4.1", osVersion = "16",
            tz = TimeZone.getTimeZone("Europe/Oslo"), csvBytes = csv,
        )
        assertEquals("store-1", meta.getString("store_id"))
        assertEquals("google_play", meta.getString("platform"))
        assertEquals("uuid-1", meta.getString("trip_uuid"))
        assertEquals("eucplanet", meta.getString("source_app"))
        assertEquals("eucplanet-v3-gforce", meta.getString("schema_version"))
        assertTrue(meta.getString("start_utc").endsWith("Z"))
        assertEquals(true, meta.getBoolean("tz_known"))
        assertEquals(false, meta.getBoolean("is_mock_location"))
        assertEquals(2731, meta.getInt("sample_count"))
        assertEquals(64, meta.getString("file_sha256").length) // hex sha256
        assertEquals("Begode", meta.getJSONObject("wheel").getString("brand"))
        assertEquals("Master", meta.getJSONObject("wheel").getString("model"))
    }
}
```

- [ ] **Step 2: Run** → FAIL.

- [ ] **Step 3: Implement `MetaBuilder`:**

```kotlin
package com.eried.eucplanet.data.eucstats

import com.eried.eucplanet.data.model.TripRecord
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object MetaBuilder {
    private fun iso(ms: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(ms))
    }

    private fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Build the meta envelope WITHOUT the attestation field (added later). */
    fun build(
        trip: TripRecord, storeId: String, appVersion: String, osVersion: String,
        tz: TimeZone, csvBytes: ByteArray,
    ): JSONObject {
        val start = trip.startTime
        val offsetMin = tz.getOffset(start) / 60000
        val meta = JSONObject()
        meta.put("store_id", storeId)
        meta.put("platform", "google_play")
        meta.put("trip_uuid", trip.tripUuid)
        meta.put("source_app", "eucplanet")
        meta.put("schema_version", "eucplanet-v3-gforce")
        meta.put("start_utc", iso(start))
        meta.put("end_utc", iso(trip.endTime ?: start))
        meta.put("tz", tz.id)
        meta.put("tz_offset_min", offsetMin)
        meta.put("tz_known", true)
        meta.put("is_mock_location", trip.isMockLocation)
        meta.put("app_version", appVersion)
        meta.put("os_version", osVersion)
        meta.put("sample_count", trip.sampleCount)
        meta.put("file_sha256", sha256Hex(csvBytes))
        meta.put("distance_km_client", trip.distanceKm.toDouble())
        meta.put("wheel", trip.wheelMetaJson?.let { JSONObject(it) } ?: JSONObject())
        return meta
    }
}
```

- [ ] **Step 4: Run** → PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/eried/eucplanet/data/eucstats/MetaBuilder.kt app/src/test/java/com/eried/eucplanet/data/eucstats/MetaBuilderTest.kt
git commit -m "feat: eucstats meta envelope builder"
```

---

### Task B4: EucStatsApi (OkHttp) + response/error mapping

**Files:**
- Create: `app/src/main/java/com/eried/eucplanet/data/eucstats/EucStatsApi.kt`
- Test (MockWebServer): `app/src/test/java/com/eried/eucplanet/data/eucstats/EucStatsApiTest.kt`
- Modify: `app/build.gradle.kts` test deps (add `testImplementation("com.squareup.okhttp3:mockwebserver")` matching the okhttp version)

- [ ] **Step 1: Test** with MockWebServer covering register, getCard, uploadTrip (201/200/duplicate/4xx). Example:

```kotlin
package com.eried.eucplanet.data.eucstats

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EucStatsApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: EucStatsApi

    @Before fun setup() {
        server = MockWebServer(); server.start()
        api = EucStatsApi(OkHttpClient()) { server.url("/api/v1").toString().trimEnd('/') }
    }
    @After fun teardown() { server.shutdown() }

    @Test fun uploadTrip_201_returnsValidated() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"trip_uuid":"t1","validation_status":"validated","duplicate":false}"""))
        val r = api.uploadTrip("{\"store_id\":\"s\"}", ByteArray(10))
        assertTrue(r is UploadResult.Ok); r as UploadResult.Ok
        assertEquals("validated", r.validationStatus); assertFalse(r.duplicate)
    }

    @Test fun uploadTrip_duplicate200_isOk() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"duplicate":true}"""))
        assertTrue(api.uploadTrip("{}", ByteArray(1)) is UploadResult.Ok)
    }

    @Test fun uploadTrip_422_isPermanentFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(422).setBody("unparseable"))
        val r = api.uploadTrip("{}", ByteArray(1))
        assertTrue(r is UploadResult.PermanentFailure)
    }

    @Test fun uploadTrip_503_isRetryable() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(api.uploadTrip("{}", ByteArray(1)) is UploadResult.Retry)
    }

    @Test fun getCard_parsesFields() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"display_name":"Erwin","flag":"NO","total_km":1284.0,"trips":12,"top_speed_kmh":62.0,"mileage_rank":7,"country":"NO","has_avatar":true,"avatar_url":"/api/v1/riders/s/avatar"}"""))
        val c = api.getCard("s")!!
        assertEquals("Erwin", c.displayName); assertEquals(7, c.mileageRank); assertEquals(12, c.trips)
    }
}
```

- [ ] **Step 2: Run** → FAIL.

- [ ] **Step 3: Implement `EucStatsApi`** with a `baseUrl: () -> String` provider, result/value types, and code→result mapping:

```kotlin
package com.eried.eucplanet.data.eucstats

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class RiderCard(
    val displayName: String?, val flag: String?, val hasAvatar: Boolean, val avatarUrl: String?,
    val totalKm: Double, val trips: Int, val topSpeedKmh: Double, val maxGforce: Double,
    val mileageRank: Int?, val country: String?,
)

sealed interface UploadResult {
    data class Ok(val validationStatus: String?, val duplicate: Boolean) : UploadResult
    data class PermanentFailure(val code: Int, val body: String) : UploadResult  // 400/413/422
    data class AuthFailure(val code: Int) : UploadResult                          // 401 (re-mint)
    data class Retry(val code: Int, val retryAfterSec: Long?) : UploadResult      // 429/5xx/network
}

class EucStatsApi(
    private val client: OkHttpClient,
    private val baseUrl: () -> String,
) {
    private val json = "application/json; charset=utf-8".toMediaType()

    fun registerRider(payload: JSONObject): JSONObject? {
        val req = Request.Builder().url("${baseUrl()}/riders")
            .post(payload.toString().toRequestBody(json)).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            return if (resp.isSuccessful && body.isNotEmpty()) JSONObject(body) else null
        }
    }

    fun getCard(storeId: String): RiderCard? {
        val req = Request.Builder().url("${baseUrl()}/riders/$storeId/card").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val o = JSONObject(resp.body?.string().orEmpty())
            return RiderCard(
                displayName = o.optString("display_name").ifEmpty { null },
                flag = o.optString("flag").ifEmpty { null },
                hasAvatar = o.optBoolean("has_avatar"),
                avatarUrl = o.optString("avatar_url").ifEmpty { null },
                totalKm = o.optDouble("total_km", 0.0), trips = o.optInt("trips", 0),
                topSpeedKmh = o.optDouble("top_speed_kmh", 0.0), maxGforce = o.optDouble("max_gforce", 0.0),
                mileageRank = if (o.isNull("mileage_rank")) null else o.optInt("mileage_rank"),
                country = o.optString("country").ifEmpty { null },
            )
        }
    }

    fun patchRider(storeId: String, payload: JSONObject): Int {
        val req = Request.Builder().url("${baseUrl()}/riders/$storeId")
            .patch(payload.toString().toRequestBody(json)).build()
        client.newCall(req).execute().use { return it.code }
    }

    fun deleteRider(storeId: String): Boolean {
        val req = Request.Builder().url("${baseUrl()}/riders/$storeId").delete().build()
        client.newCall(req).execute().use { return it.isSuccessful }
    }

    fun exportRider(storeId: String): String? {
        val req = Request.Builder().url("${baseUrl()}/riders/$storeId/export").get().build()
        client.newCall(req).execute().use { return if (it.isSuccessful) it.body?.string() else null }
    }

    /** POST /trips multipart: meta (JSON string) + trip (gzipped CSV). */
    fun uploadTrip(metaJson: String, gzippedCsv: ByteArray): UploadResult {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("meta", metaJson)
            .addFormDataPart("trip", "trip.csv.gz",
                gzippedCsv.toRequestBody("application/gzip".toMediaType()))
            .build()
        val req = Request.Builder().url("${baseUrl()}/trips").post(body).build()
        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when (resp.code) {
                    200, 201, 202 -> {
                        val o = runCatching { JSONObject(text) }.getOrNull()
                        UploadResult.Ok(o?.optString("validation_status")?.ifEmpty { null },
                            o?.optBoolean("duplicate") ?: false)
                    }
                    409 -> UploadResult.Ok(null, true)
                    401 -> UploadResult.AuthFailure(401)
                    400, 413, 422 -> UploadResult.PermanentFailure(resp.code, text)
                    429 -> UploadResult.Retry(429, resp.header("Retry-After")?.toLongOrNull())
                    else -> UploadResult.Retry(resp.code, null)
                }
            }
        } catch (e: Exception) {
            UploadResult.Retry(0, null) // network error
        }
    }
}
```

- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest --tests "com.eried.eucplanet.data.eucstats.EucStatsApiTest"` → PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/eried/eucplanet/data/eucstats/EucStatsApi.kt app/src/test/java/com/eried/eucplanet/data/eucstats/EucStatsApiTest.kt app/build.gradle.kts
git commit -m "feat: EucStatsApi (OkHttp) with response/error mapping"
```

---

### Task B5: EucStatsRepository (orchestration)

**Files:**
- Create: `app/src/main/java/com/eried/eucplanet/data/eucstats/EucStatsRepository.kt`
- Test: `app/src/test/java/com/eried/eucplanet/data/eucstats/EucStatsRepositoryTest.kt`

Responsibilities: `register(name, flag, avatarPngBase64)`, `fetchCard()`, `uploadTrip(trip)` (gzip CSV → build meta → attest → POST → map to status update), `editProfile`, `deleteAccount`, `exportData`. Hold the latest card in a `StateFlow<RiderCard?>`. Inject `EucStatsApi`, `Attestation`, `SettingsRepository`, `TripDao`, `TripRepository` (for `getTripFile`), and a `clock`/`appVersion` provider.

- [ ] **Step 1: Test** the upload orchestration with fakes — verify it gzips, builds meta, binds the attestation hash, and maps `UploadResult.Ok` → `eucstatsStatus=2`; `PermanentFailure` → 3; `Retry` → leaves 1/3 and signals retry. (Use a fake `EucStatsApi` subtype via an interface, or refactor `EucStatsApi` calls behind a small interface `EucStatsApiContract` for testability. Prefer extracting an interface so the repo test doesn't need a live server.) Provide the full fake + assertions in the test file.

- [ ] **Step 2–4:** Implement to pass; the `uploadTrip` flow:
  1. `val csv = tripRepository.getTripFile(trip).readBytes()`
  2. `val meta = MetaBuilder.build(trip, storeId, appVersion, osVersion, TimeZone.getDefault(), csv)`
  3. `val hash = CanonicalJson.requestHash(meta)`
  4. `val att = attestation.token(hash)` → `meta.put("attestation", JSONObject().put("type", att.type).put("token", att.token).put("request_hash", att.requestHash))`
  5. `val gz = gzip(csv)`
  6. `when (api.uploadTrip(meta.toString(), gz)) { Ok -> tripDao.update(status=2,...); PermanentFailure -> status=3; AuthFailure -> re-mint once then Retry; Retry -> return needsRetry }`
  7. on `Ok`, refresh the card.
  Return a small enum so the worker knows whether to `Result.retry()`.

- [ ] **Step 5: Run** the repository test → PASS.

- [ ] **Step 6: Commit** `feat: EucStatsRepository orchestrates register/card/upload/profile`.

---

## Phase C — Worker + recording hooks

### Task C1: EucStatsUploadWorker

**Files:** Create `app/src/main/java/com/eried/eucplanet/data/sync/EucStatsUploadWorker.kt`; Test `.../EucStatsUploadWorkerTest.kt` (Robolectric `TestListenableWorkerBuilder`, fake repo). Mirror `TripUploadWorker`: `@HiltWorker`, drain `tripDao.getPendingEucstatsUploads()`, call `eucStatsRepository.uploadTrip(trip)`, return `Result.retry()` if any returned needs-retry else `Result.success()`. Commit `feat: EucStatsUploadWorker drains pending eucstats uploads`.

### Task C2: Recording capture + enqueue hook

**Files:** Modify `data/repository/TripRepository.kt`; inject `WheelRepository` access already present. Test: extend an existing TripRepository test or add a focused unit test for the capture helpers.
- `startRecording()`: set `tripUuid = UUID.randomUUID().toString()` on the inserted `TripRecord`; reset a `tripHadMockFix` flag.
- location callback: `tripHadMockFix = tripHadMockFix || (if SDK>=31 loc.isMock else loc.isFromMockProvider)`.
- `stopRecording()`: build `wheelMetaJson` from `wheelRepository.connectedBrand/modelName/firmwareVersion/connectedDeviceName` + `settings.lastDeviceAddress` (ble_mac); set `sampleCount` (expose `CsvWriter.rowCount`), `isMockLocation`.
- `finalizePendingTrip()`: after the existing folder-sync enqueue, if `settings.onlineUploadEnabled && settings.eucstatsStoreId != null`, `tripDao.update(trip.copy(eucstatsStatus = 1))` and enqueue `EucStatsUploadWorker` (unique work `eucstats_upload`).
Commit `feat: capture eucstats trip metadata + enqueue online upload on finalize`.

### Task C3: Expose CsvWriter.rowCount

**Files:** Modify `util/CsvWriter.kt` to expose `val rows: Int get() = rowCount`. Trivial; covered by C2's test. (Fold into C2 commit if small.)

---

## Phase D — DI + base URL

### Task D1: build.gradle base URL + Hilt providers

**Files:** Modify `app/build.gradle.kts` (buildConfig + fields), `di/AppModule.kt`.
- In `android { buildFeatures { buildConfig = true } }` (confirm it's on), add to debug/release variants:
  - debug: `buildConfigField("String","EUCSTATS_API_BASE_URL","\"http://10.0.2.2:8000/api/v1\"")`, `buildConfigField("long","EUCSTATS_GCP_PROJECT_NUMBER","0L")`
  - release: `"\"https://eucstats.ried.no/api/v1\""`, and the real project number when known (0L until then).
- In `AppModule.kt` add `@Provides @Singleton` for: `OkHttpClient` (timeouts), `EucStatsApi(client) { BuildConfig.EUCSTATS_API_BASE_URL }`, `Attestation` (`if (BuildConfig.EUCSTATS_GCP_PROJECT_NUMBER != 0L) PlayIntegrityAttestation(ctx, n) else StubAttestation()`), and `EucStatsRepository(...)`.
Commit `feat: DI wiring + debug/prod base URL + attestation provider`.

---

## Phase E — UI (Compose; ViewModel logic unit-tested, visuals verified manually)

### Task E1: SettingsViewModel eucstats methods + UI state

**Files:** Modify `ui/settings/SettingsViewModel.kt`; Test the pure logic (state transitions) where feasible.
Add: `onlineUploadEnabled` passthrough; `enableOnlineUpload()` (gated: requires `syncFolderUri != null`, opens onboarding); `registerRider(name, flag, avatarBase64)`; `card: StateFlow<RiderCard?>` + `refreshCard()`; `retryEucstatsUploads()`; `editProfile(...)`; `deleteEucstatsAccount()`; `exportEucstatsData()`. These call `EucStatsRepository` on `viewModelScope`. Commit.

### Task E2: Onboarding flow (consent → form → register)

**Files:** Create `ui/settings/eucstats/OnlineUploadOnboarding.kt` (Compose). Consent sheet (spec §8 copy → add strings), then a form: display name (`OutlinedTextField`), country flag (reuse any existing country picker, else a simple text field validated to 2-letter ISO), **required** avatar via `UserMarkerCropDialog` (reuse) — block "Register" until a photo is cropped. On register success show the card. Verify manually on device. Commit.

### Task E3: Card + toggle in CloudTab

**Files:** Modify `ui/settings/SettingsScreen.kt` `CloudTab()` (after the `section_cloud_trips` block). Add an "Upload my stats online" `Switch` (disabled with hint when `syncFolderUri == null`); when on and not registered → launch E1 onboarding; when registered → render a `RiderCardView` composable (avatar via Coil from `baseUrl + avatarUrl`, name+flag, total_km, `#rank`, top speed, trips, country) with a "View on eucstats.ried.no" tap and a "Retry failed uploads" affordance when any `eucstatsStatus==3`. Add strings. Verify manually. Commit.

### Task E4: Online Profile / GDPR panel

**Files:** Create `ui/settings/eucstats/OnlineProfileScreen.kt` reached from CloudTab. Edit name/flag/avatar with each control enabled per `can_change_*` (from `GET /riders/{id}`); friendly "changeable again on <date>" text; `PATCH` on save (handle 429). Delete account (confirm dialog → `DELETE` → clear local eucstats settings + toggle off). Export (`GET /export` → save into the sync folder / share sheet). Add strings. Verify manually. Commit.

---

## Phase F — Integration & verification

### Task F1: Build, unit tests, live integration on :8000

- [ ] `./gradlew :app:testDebugUnitTest` → all green (the new JVM tests + existing).
- [ ] `./gradlew :app:assembleDebug` → APK builds.
- [ ] Start the backend: in `../eucstats`, `./.venv/Scripts/python.exe -m uvicorn main:app --host 0.0.0.0 --port 8000` (unsandboxed so the emulator can reach it).
- [ ] On the emulator (debug build → `http://10.0.2.2:8000/api/v1`): set a sync folder, toggle online upload, complete onboarding (name/flag/photo) → rider appears in `GET /admin` riders list. Record a short trip → after the 15s grace, `EucStatsUploadWorker` POSTs → trip shows in admin "Recent trips"; the card refreshes with `trips:1`. Toggle admin attestation to `enforce` → next upload returns 401 (stub token) and the trip shows as failed/retry; toggle back to `stub` → retry succeeds.
- [ ] Commit any fixes found.

---

## Self-Review

**Spec coverage:** §5.1 data model → A1/A2. §5.2 components → B2/B4/B5/C1. §5.3 onboarding → E1/E2. §5.4 per-trip flow → C2/C1/B5. §5.5 envelope → B3. §5.6 request_hash → B1. §5.7 mock-location → C2. §5.8 response/retry → B4/B5/C1. §5.9 card UI → E3. §5.10 profile/GDPR → E4. DI/base-URL/§7 attestation → B2/D1. Testing §9 → per-task + F1.

**Placeholder scan:** B1 step 1 requires pasting the real sha256 vector from the server venv (instructed inline — not a placeholder in shipped code). B5/E* tasks describe the implementation with concrete signatures; B5 needs an extracted `EucStatsApiContract` interface for fakeability (called out). No TODO/TBD in shipped code.

**Type consistency:** `AttestationToken{type,token,requestHash}`, `RiderCard`, `UploadResult` sealed types used consistently across B2/B4/B5/C1. `eucstatsStatus` codes (0/1/2/3) consistent A1↔B5↔C1↔C2. `getPendingEucstatsUploads()` defined A1, used C1.

> **Note:** Phases A–D are strict TDD (JVM-testable). Phase E (Compose UI) ships ViewModel logic with unit tests where logic exists and is otherwise verified manually + in F1's live integration — UI rendering is not faked with unit tests.
