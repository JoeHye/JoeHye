package com.blackcloudgroup.binaural

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blackcloudgroup.binaural.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private val testDbName = "migration-test-db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate1To2_preservesDataAndTransformsSoundMode() {
        // 1. Create Version 1 database from schemas/1.json and seed fixture rows
        helper.createDatabase(testDbName, 1).apply {
            execSQL("""
                INSERT INTO presets (id, title, startBeatHz, targetBeatHz, carrierHz, durationMinutes, isIsochronic, enablePinkNoise)
                VALUES (1, 'Legacy Isochronic Preset', 10.0, 10.0, 200.0, 15, 1, 0)
            """.trimIndent())
            execSQL("""
                INSERT INTO presets (id, title, startBeatHz, targetBeatHz, carrierHz, durationMinutes, isIsochronic, enablePinkNoise)
                VALUES (2, 'Legacy Hemi-Sync Preset', 6.0, 6.0, 136.1, 20, 0, 1)
            """.trimIndent())
            execSQL("""
                INSERT INTO presets (id, title, startBeatHz, targetBeatHz, carrierHz, durationMinutes, isIsochronic, enablePinkNoise)
                VALUES (3, 'Legacy Plain Binaural Preset', 4.0, 4.0, 210.0, 30, 0, 0)
            """.trimIndent())
            close()
        }

        // 2. Run MIGRATION_1_2 and validate schema automatically against schemas/2.json
        val db = helper.runMigrationsAndValidate(testDbName, 2, true, AppDatabase.MIGRATION_1_2)

        // 3. Verify data integrity and column transformations
        val cursor = db.query("SELECT id, title, soundMode, enablePinkNoise FROM presets ORDER BY id ASC")
        assertTrue("Cursor should return rows", cursor.moveToFirst())

        // Row 1: isIsochronic = 1 -> ISOCHRONIC
        assertEquals(1L, cursor.getLong(cursor.getColumnIndexOrThrow("id")))
        assertEquals("Legacy Isochronic Preset", cursor.getString(cursor.getColumnIndexOrThrow("title")))
        assertEquals("ISOCHRONIC", cursor.getString(cursor.getColumnIndexOrThrow("soundMode")))
        assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("enablePinkNoise")))

        // Row 2: isIsochronic = 0, enablePinkNoise = 1 -> HEMI_SYNC
        assertTrue(cursor.moveToNext())
        assertEquals(2L, cursor.getLong(cursor.getColumnIndexOrThrow("id")))
        assertEquals("Legacy Hemi-Sync Preset", cursor.getString(cursor.getColumnIndexOrThrow("title")))
        assertEquals("HEMI_SYNC", cursor.getString(cursor.getColumnIndexOrThrow("soundMode")))
        assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("enablePinkNoise")))

        // Row 3: isIsochronic = 0, enablePinkNoise = 0 -> BINAURAL
        assertTrue(cursor.moveToNext())
        assertEquals(3L, cursor.getLong(cursor.getColumnIndexOrThrow("id")))
        assertEquals("Legacy Plain Binaural Preset", cursor.getString(cursor.getColumnIndexOrThrow("title")))
        assertEquals("BINAURAL", cursor.getString(cursor.getColumnIndexOrThrow("soundMode")))
        assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("enablePinkNoise")))

        cursor.close()
        db.close()
    }
}
