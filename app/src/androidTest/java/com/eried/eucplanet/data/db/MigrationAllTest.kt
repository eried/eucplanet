package com.eried.eucplanet.data.db

import android.database.Cursor
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.model.WheelProfile
import com.eried.eucplanet.di.AppModule
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Every Room migration, run against a database that looks like the one a
 * rider on the oldest supported version (v44) still carries.
 *
 * Schemas before v61 were never exported, so v44 is built by hand from raw
 * SQL. The DDL is derived, not remembered: it is the current exported schema
 * (`schemas/.../61.json`) with every column that a migration adds taken back
 * out, plus the `app_settings` table that v44->45 drops. [STEPS] is that list
 * of migrations, and [v44SchemaIsTheCurrentSchemaMinusEveryMigration] checks
 * the hand-written DDL against the derivation so neither can drift alone.
 *
 * Only one migration does something other than ADD COLUMN or DROP TABLE:
 * 52->53 is an UPDATE that resets `beepModulation` and `beepVolumeModulation`
 * to 100, so a rule seeded at v44 ends with 100 in both, not the 0 the ADD
 * COLUMN at 48->49 and 49->50 gave it. The expectations below spell that out.
 */
@RunWith(AndroidJUnit4::class)
class MigrationAllTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation,
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Before
    fun wipe() = deleteDb(TEST_DB).also { deleteDb(FRESH_DB) }

    @After
    fun cleanup() = deleteDb(TEST_DB).also { deleteDb(FRESH_DB) }

    // ---- The migration table -------------------------------------------------

    /** What one migration in [AppModule] does, keyed by the version it lands on. */
    private data class Step(
        val to: Int,
        /** Table -> columns added by ALTER TABLE ADD COLUMN. */
        val adds: Map<String, List<String>> = emptyMap(),
        /** Tables removed by DROP TABLE. */
        val drops: Set<String> = emptySet(),
        /** Anything else, called out by hand. */
        val other: String? = null,
    )

    private val STEPS = listOf(
        Step(45, drops = setOf("app_settings")),
        Step(46, adds = mapOf("wheel_profile" to listOf("reverseSpeedDirection"))),
        Step(47, adds = mapOf("trips" to listOf(
            "tripUuid", "eucstatsStatus", "eucstatsUploadedAt", "eucstatsValidation",
            "isMockLocation", "sampleCount", "wheelMetaJson",
        ))),
        Step(48, adds = mapOf("alarm_rules" to listOf("leadTimeMs"))),
        Step(49, adds = mapOf("alarm_rules" to listOf("beepModulation"))),
        Step(50, adds = mapOf("alarm_rules" to listOf("beepGapMs", "beepVolume", "beepVolumeModulation"))),
        Step(51, adds = mapOf("alarm_rules" to listOf("beepModulationReachPct"))),
        Step(52, adds = mapOf("alarm_rules" to listOf("beepVolumeReachPct"))),
        Step(53, other = "UPDATE alarm_rules SET beepModulation = 100, beepVolumeModulation = 100"),
        Step(54, adds = mapOf("alarm_rules" to listOf("beepTransitionPct"))),
        Step(55, adds = mapOf("alarm_rules" to listOf("beepWaveform", "beepEffect"))),
        Step(56, adds = mapOf("trips" to listOf("customName"))),
        Step(57, adds = mapOf("wheel_profile" to listOf("seriesCells"))),
        Step(58, adds = mapOf("wheel_profile" to listOf("batteryMode"))),
        Step(59, adds = mapOf("wheel_profile" to listOf("batteryCapacityWh"))),
        Step(60, adds = mapOf("trips" to listOf("dropboxStatus", "dropboxUploadedAt"))),
        Step(61, adds = mapOf("alarm_rules" to listOf("wheelAddress", "wheelName"))),
    )

    // ---- v44 by hand ---------------------------------------------------------

    /**
     * Room's own CREATE TABLE shape (column affinity, NOT NULL, DEFAULT where
     * the entity declares one, PRIMARY KEY placement), minus every column in
     * [STEPS]. `app_settings` is the legacy settings row v44->45 drops; its
     * shape does not matter beyond existing, so it is kept minimal.
     */
    private val V44_DDL = listOf(
        "CREATE TABLE IF NOT EXISTS `trips` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`startTime` INTEGER NOT NULL, `endTime` INTEGER, `fileName` TEXT NOT NULL, " +
            "`distanceKm` REAL NOT NULL, `uploadStatus` INTEGER NOT NULL, `uploadedAt` INTEGER)",
        "CREATE TABLE IF NOT EXISTS `alarm_rules` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, " +
            "`metric` TEXT NOT NULL, `comparator` TEXT NOT NULL, `threshold` REAL NOT NULL, " +
            "`beepEnabled` INTEGER NOT NULL, `beepFrequency` INTEGER NOT NULL, " +
            "`beepDurationMs` INTEGER NOT NULL, `beepCount` INTEGER NOT NULL, " +
            "`voiceEnabled` INTEGER NOT NULL, `voiceText` TEXT NOT NULL, " +
            "`vibrateEnabled` INTEGER NOT NULL, `vibrateDurationMs` INTEGER NOT NULL, " +
            "`vibrateTarget` TEXT NOT NULL, `cooldownSeconds` INTEGER NOT NULL, " +
            "`repeatWhileActive` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `wheel_profile` (" +
            "`bleName` TEXT NOT NULL, `tiltbackSpeedKmh` REAL NOT NULL, `alarmSpeedKmh` REAL NOT NULL, " +
            "`safetyTiltbackKmh` REAL NOT NULL, `safetyAlarmKmh` REAL NOT NULL, " +
            "`speedCalibrationOffsetPct` REAL NOT NULL DEFAULT 0, `lastConnectedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`bleName`))",
        "CREATE TABLE IF NOT EXISTS `app_settings` (" +
            "`id` INTEGER PRIMARY KEY NOT NULL, `speedUnit` TEXT NOT NULL, `voiceEnabled` INTEGER NOT NULL)",
    )

    /** One row per table, every value distinctive so a swap or reset shows. */
    private val V44_SEED = listOf(
        "INSERT INTO `trips` (id, startTime, endTime, fileName, distanceKm, uploadStatus, uploadedAt) " +
            "VALUES (7, 1700000000000, 1700003600000, '2023-11-14_ride.csv', 12.5, 2, 1700004000000)",
        "INSERT INTO `alarm_rules` (id, name, enabled, sortOrder, metric, comparator, threshold, " +
            "beepEnabled, beepFrequency, beepDurationMs, beepCount, voiceEnabled, voiceText, " +
            "vibrateEnabled, vibrateDurationMs, vibrateTarget, cooldownSeconds, repeatWhileActive) " +
            "VALUES (3, 'Overspeed 40', 1, 5, 'SPEED', 'GREATER_EQUAL', 40.0, " +
            "1, 1500, 250, 2, 1, 'Slow down', 1, 700, 'PHONE', 9, 0)",
        "INSERT INTO `wheel_profile` (bleName, tiltbackSpeedKmh, alarmSpeedKmh, safetyTiltbackKmh, " +
            "safetyAlarmKmh, speedCalibrationOffsetPct, lastConnectedAt) " +
            "VALUES ('V14-ABC123', 45.0, 50.0, 55.0, 60.0, 3.0, 1700000000123)",
        "INSERT INTO `app_settings` (id, speedUnit, voiceEnabled) VALUES (1, 'KMH', 1)",
    )

    /** The seeded trip after every migration: its values intact, each new column at its default. */
    private val EXPECTED_TRIP = TripRecord(
        id = 7, startTime = 1700000000000, endTime = 1700003600000, fileName = "2023-11-14_ride.csv",
        distanceKm = 12.5f, uploadStatus = 2, uploadedAt = 1700004000000,
        tripUuid = null, eucstatsStatus = 0, eucstatsUploadedAt = null, eucstatsValidation = null,
        isMockLocation = false, sampleCount = 0, wheelMetaJson = null, customName = null,
        dropboxStatus = 0, dropboxUploadedAt = null,
    )

    /** beepModulation / beepVolumeModulation are 100 because of the 52->53 UPDATE, not an ADD COLUMN default. */
    private val EXPECTED_RULE = AlarmRule(
        id = 3, name = "Overspeed 40", enabled = true, sortOrder = 5,
        metric = "SPEED", comparator = "GREATER_EQUAL", threshold = 40f,
        beepEnabled = true, beepFrequency = 1500, beepDurationMs = 250, beepCount = 2,
        beepModulation = 100, beepGapMs = 100, beepTransitionPct = 12, beepWaveform = 0, beepEffect = 0,
        beepVolume = 100, beepVolumeModulation = 100, beepModulationReachPct = 50, beepVolumeReachPct = 50,
        voiceEnabled = true, voiceText = "Slow down",
        vibrateEnabled = true, vibrateDurationMs = 700, vibrateTarget = "PHONE",
        cooldownSeconds = 9, repeatWhileActive = false, leadTimeMs = 0,
        wheelAddress = null, wheelName = null,
    )

    private val EXPECTED_WHEEL = WheelProfile(
        bleName = "V14-ABC123", tiltbackSpeedKmh = 45f, alarmSpeedKmh = 50f,
        safetyTiltbackKmh = 55f, safetyAlarmKmh = 60f, speedCalibrationOffsetPct = 3f,
        reverseSpeedDirection = false, seriesCells = 20, batteryMode = "WHEEL", batteryCapacityWh = 0,
        lastConnectedAt = 1700000000123,
    )

    // ---- Tests ----------------------------------------------------------------

    @Test
    fun migrationTableMatchesAppModule() {
        val migrations = AppModule.ALL_MIGRATIONS
        assertEquals("one Step per migration", STEPS.size, migrations.size)
        var from = FIRST
        for (step in STEPS) {
            val m = migrations.single { it.startVersion == from && it.endVersion == step.to }
            assertEquals("${m.startVersion}->${m.endVersion}", from + 1, step.to)
            from = step.to
        }
        assertEquals("the last migration lands on the current version", LATEST, from)
    }

    @Test
    fun v44SchemaIsTheCurrentSchemaMinusEveryMigration() {
        createV44()
        val expected = currentColumns().mapValues { (table, cols) ->
            cols.keys - STEPS.flatMap { it.adds[table].orEmpty() }.toSet()
        }
        openRaw(FIRST).use { db ->
            assertEquals(
                "tables at v44",
                expected.keys + STEPS.flatMap { it.drops },
                db.tables(),
            )
            for ((table, cols) in expected) {
                assertEquals("columns of $table at v44", cols, db.columns(table).keys)
                assertColumnShapes(db, table, currentColumns().getValue(table))
            }
        }
    }

    @Test
    fun migratesFrom44To61InOneGo() {
        createV44()
        val db = helper.runMigrationsAndValidate(TEST_DB, LATEST, true, *AppModule.ALL_MIGRATIONS)
        assertSeededRowsSurvived(db)
        db.close()
        assertSeededRowsReadThroughRoom()
    }

    @Test
    fun migratesOneStepAtATime() {
        createV44()
        val migrations = AppModule.ALL_MIGRATIONS
        openRaw(FIRST).use { db ->
            var from = FIRST
            for (step in STEPS.dropLast(1)) {
                val m = migrations.single { it.startVersion == from && it.endVersion == step.to }
                db.beginTransaction()
                try {
                    m.migrate(db)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                db.version = step.to
                assertSchemaAt(db, step.to)
                from = step.to
            }
            assertEquals(LATEST - 1, from)
        }
        // The last step goes through Room's own validator against 61.json.
        val last = STEPS.last()
        val db = helper.runMigrationsAndValidate(
            TEST_DB, LATEST, true,
            migrations.single { it.startVersion == last.to - 1 && it.endVersion == last.to },
        )
        assertSchemaAt(db, LATEST)
        assertSeededRowsSurvived(db)
        db.close()
        assertSeededRowsReadThroughRoom()
    }

    /**
     * A database built from the exported schema file and one built by Room
     * from the entities must have the same tables and the same columns. The
     * identity hash check that Room does on open only says the file was
     * exported from these entities; this compares the actual SQL shape.
     */
    @Test
    fun freshV61FromSchemaFileMatchesTheEntities() {
        helper.createDatabase(TEST_DB, LATEST).close()
        val fromSchema = openRaw(LATEST).use { db -> db.tables().associateWith { db.columns(it) } }

        val room = Room.databaseBuilder(context, AppDatabase::class.java, FRESH_DB)
            .addMigrations(*AppModule.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        val fromEntities = room.openHelper.writableDatabase.let { db ->
            assertEquals(LATEST, db.version)
            db.tables().associateWith { db.columns(it) }
        }
        room.close()

        assertEquals(fromEntities, fromSchema)
        assertEquals(setOf("trips", "alarm_rules", "wheel_profile"), fromSchema.keys)

        // And the schema-file database opens through Room, which checks its identity hash.
        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(*AppModule.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        assertEquals(LATEST, reopened.openHelper.readableDatabase.version)
        runBlocking { assertTrue(reopened.alarmDao().getAll().isEmpty()) }
        reopened.close()
    }

    // ---- Helpers ----------------------------------------------------------------

    /** Builds the v44 file at the path [helper] will look for, seeded. */
    private fun createV44() {
        deleteDb(TEST_DB)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(FIRST) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    V44_DDL.forEach(db::execSQL)
                    V44_SEED.forEach(db::execSQL)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    error("v44 file must be created fresh, found version $oldVersion")
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { it.writableDatabase }
    }

    /** Opens the test file with no migration logic attached, at whatever version it says. */
    private fun openRaw(expectedVersion: Int): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(expectedVersion) {
                override fun onCreate(db: SupportSQLiteDatabase) = error("file must already exist")
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    error("unexpected upgrade $oldVersion -> $newVersion")
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    private fun deleteDb(name: String) {
        val f = context.getDatabasePath(name)
        listOf("", "-journal", "-wal", "-shm").forEach { File(f.path + it).delete() }
    }

    private data class Column(val type: String, val notNull: Boolean, val default: String?, val pk: Boolean)

    /** Table -> column -> shape, straight from the exported schema of [LATEST]. */
    private fun currentColumns(): Map<String, Map<String, Column>> {
        val path = "${AppDatabase::class.java.canonicalName}/$LATEST.json"
        val json = instrumentation.context.assets.open(path).bufferedReader().use { it.readText() }
        val entities = JSONObject(json).getJSONObject("database").getJSONArray("entities")
        return (0 until entities.length()).associate { i ->
            val e = entities.getJSONObject(i)
            val pkCols = e.getJSONObject("primaryKey").getJSONArray("columnNames")
                .let { a -> (0 until a.length()).map(a::getString) }.toSet()
            val fields = e.getJSONArray("fields")
            e.getString("tableName") to (0 until fields.length()).associate { j ->
                val f = fields.getJSONObject(j)
                val name = f.getString("columnName")
                name to Column(
                    type = f.getString("affinity"),
                    notNull = f.getBoolean("notNull"),
                    default = f.optString("defaultValue").takeIf { f.has("defaultValue") },
                    pk = name in pkCols,
                )
            }
        }
    }

    private fun SupportSQLiteDatabase.tables(): Set<String> =
        query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet()
        } - setOf("android_metadata", "sqlite_sequence", "room_master_table")

    private fun SupportSQLiteDatabase.columns(table: String): Map<String, Column> =
        query("PRAGMA table_info(`$table`)").use { c ->
            generateSequence {
                if (!c.moveToNext()) null else {
                    c.getString(1) to Column(
                        type = c.getString(2),
                        notNull = c.getInt(3) != 0,
                        default = if (c.isNull(4)) null else c.getString(4),
                        pk = c.getInt(5) != 0,
                    )
                }
            }.toMap()
        }

    /** Every column present in [table] has the affinity, nullability, key and (where declared) default of [expected]. */
    private fun assertColumnShapes(db: SupportSQLiteDatabase, table: String, expected: Map<String, Column>) {
        for ((name, actual) in db.columns(table)) {
            val want = expected[name] ?: error("$table.$name is not in the current schema")
            assertEquals("$table.$name type", want.type, actual.type)
            assertEquals("$table.$name notNull", want.notNull, actual.notNull)
            assertEquals("$table.$name pk", want.pk, actual.pk)
            if (want.default != null) assertEquals("$table.$name default", want.default, actual.default)
        }
    }

    /** Tables and columns after every migration up to and including [version]. */
    private fun assertSchemaAt(db: SupportSQLiteDatabase, version: Int) {
        val current = currentColumns()
        val applied = STEPS.filter { it.to <= version }
        val pending = STEPS.filter { it.to > version }
        val dropped = STEPS.flatMap { it.drops }.toSet()
        val expectedTables = current.keys + (dropped - applied.flatMap { it.drops }.toSet())
        assertEquals("tables at v$version", expectedTables, db.tables())
        for ((table, cols) in current) {
            val expectedCols = cols.keys - pending.flatMap { it.adds[table].orEmpty() }.toSet()
            assertEquals("columns of $table at v$version", expectedCols, db.columns(table).keys)
            assertColumnShapes(db, table, cols)
        }
    }

    private fun SupportSQLiteDatabase.row(sql: String): Map<String, Any?> = query(sql).use { c ->
        assertTrue("a row for: $sql", c.moveToFirst())
        assertEquals("exactly one row for: $sql", 1, c.count)
        (0 until c.columnCount).associate { i ->
            c.getColumnName(i) to when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                Cursor.FIELD_TYPE_STRING -> c.getString(i)
                else -> error("blob in ${c.getColumnName(i)}")
            }
        }
    }

    /** Raw SQL view of the three seeded rows: every column, added ones at their declared default. */
    private fun assertSeededRowsSurvived(db: SupportSQLiteDatabase) {
        assertEquals(
            mapOf<String, Any?>(
                "id" to 7L, "startTime" to 1700000000000L, "endTime" to 1700003600000L,
                "fileName" to "2023-11-14_ride.csv", "distanceKm" to 12.5, "uploadStatus" to 2L,
                "uploadedAt" to 1700004000000L,
                "tripUuid" to null, "eucstatsStatus" to 0L, "eucstatsUploadedAt" to null,
                "eucstatsValidation" to null, "isMockLocation" to 0L, "sampleCount" to 0L,
                "wheelMetaJson" to null, "customName" to null, "dropboxStatus" to 0L,
                "dropboxUploadedAt" to null,
            ),
            db.row("SELECT * FROM trips"),
        )
        assertEquals(
            mapOf<String, Any?>(
                "id" to 3L, "name" to "Overspeed 40", "enabled" to 1L, "sortOrder" to 5L,
                "metric" to "SPEED", "comparator" to "GREATER_EQUAL", "threshold" to 40.0,
                "beepEnabled" to 1L, "beepFrequency" to 1500L, "beepDurationMs" to 250L, "beepCount" to 2L,
                "voiceEnabled" to 1L, "voiceText" to "Slow down",
                "vibrateEnabled" to 1L, "vibrateDurationMs" to 700L, "vibrateTarget" to "PHONE",
                "cooldownSeconds" to 9L, "repeatWhileActive" to 0L,
                "leadTimeMs" to 0L,
                "beepModulation" to 100L,
                "beepGapMs" to 100L, "beepVolume" to 100L, "beepVolumeModulation" to 100L,
                "beepModulationReachPct" to 50L, "beepVolumeReachPct" to 50L,
                "beepTransitionPct" to 12L, "beepWaveform" to 0L, "beepEffect" to 0L,
                "wheelAddress" to null, "wheelName" to null,
            ),
            db.row("SELECT * FROM alarm_rules"),
        )
        assertEquals(
            mapOf<String, Any?>(
                "bleName" to "V14-ABC123", "tiltbackSpeedKmh" to 45.0, "alarmSpeedKmh" to 50.0,
                "safetyTiltbackKmh" to 55.0, "safetyAlarmKmh" to 60.0, "speedCalibrationOffsetPct" to 3.0,
                "lastConnectedAt" to 1700000000123L,
                "reverseSpeedDirection" to 0L, "seriesCells" to 20L, "batteryMode" to "WHEEL",
                "batteryCapacityWh" to 0L,
            ),
            db.row("SELECT * FROM wheel_profile"),
        )
    }

    /** The same rows through the app's own DAOs, so the entity mapping is part of the check. */
    private fun assertSeededRowsReadThroughRoom() {
        val room = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(*AppModule.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(LATEST, room.openHelper.readableDatabase.version)
            runBlocking {
                assertEquals(listOf(EXPECTED_RULE), room.alarmDao().getAll())
                assertEquals(EXPECTED_TRIP, room.tripDao().getById(7))
                assertEquals(EXPECTED_WHEEL, room.wheelProfileDao().getByName("V14-ABC123"))
            }
        } finally {
            room.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val FRESH_DB = "migration-test-fresh.db"
        /** Oldest version a migration path starts from. */
        const val FIRST = 44
        /** Must match the @Database version; the exported schema of this version is what every check reads. */
        const val LATEST = 61
    }
}
