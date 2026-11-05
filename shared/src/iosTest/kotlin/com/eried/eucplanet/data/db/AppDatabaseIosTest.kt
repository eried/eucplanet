package com.eried.eucplanet.data.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.data.model.WheelProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared Room database opens on iOS's bundled SQLite and keeps rows. */
class AppDatabaseIosTest {
    private val path = NSTemporaryDirectory() + "app-db-test.db"

    private fun open(): AppDatabase = Room.databaseBuilder<AppDatabase>(name = path)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    @AfterTest fun cleanUp() {
        listOf("", "-wal", "-shm").forEach { NSFileManager.defaultManager.removeItemAtPath(path + it, null) }
    }

    @Test fun `alarm rules and wheel profiles survive a reopen`() = runBlocking {
        cleanUp()
        val db = open()
        db.alarmDao().insert(AlarmRule(name = "Fast", threshold = 42f))
        db.wheelProfileDao().upsert(WheelProfile(
            bleName = "Master_1234", tiltbackSpeedKmh = 50f, alarmSpeedKmh = 45f,
            safetyTiltbackKmh = 25f, safetyAlarmKmh = 20f,
        ))
        db.close()

        val again = open()
        assertEquals(listOf("Fast"), again.alarmDao().getAll().map { it.name })
        assertEquals("Master_1234", again.wheelProfileDao().getByName("Master_1234")?.bleName)
        again.close()
    }
}
