package com.blackcloudgroup.binaural.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PresetEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {

    abstract fun presetDao(): PresetDao

    private class DatabaseCallback : RoomDatabase.Callback() {

        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            populateDefaultPresets(db)
        }

        private fun populateDefaultPresets(db: SupportSQLiteDatabase) {
            val insertSql = """
                INSERT INTO presets (title, startBeatHz, targetBeatHz, carrierHz, durationMinutes, soundMode, enablePinkNoise)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()

            db.beginTransaction()
            try {
                val defaults = listOf(
                    arrayOf("Theta Deep Meditation", 6.0, 6.0, 136.1, 20, "HEMI_SYNC", 1),
                    arrayOf("Schumann Earth Resonance", 7.83, 7.83, 144.0, 30, "HEMI_SYNC", 1),
                    arrayOf("Deep Sleep Ramp (Alpha → Delta)", 10.0, 2.5, 174.0, 45, "HEMI_SYNC", 1),
                    arrayOf("Lucid Dreaming Gateway", 4.0, 4.0, 210.0, 30, "BINAURAL", 0),
                    arrayOf("Flow State / Active Focus", 14.0, 14.0, 200.0, 25, "ISOCHRONIC", 0)
                )

                for (preset in defaults) {
                    db.execSQL(insertSql, preset)
                }
                db.setTransactionSuccessful()
                Log.i("AppDatabase", "Successfully seeded default meditation presets into SQLite table.")
            } catch (e: Exception) {
                Log.e("AppDatabase", "Failed to seed default presets during onCreate", e)
            } finally {
                db.endTransaction()
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Step 1: Create new table without DEFAULT clauses to match PresetEntity Room hash exactly
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `presets_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `startBeatHz` REAL NOT NULL,
                        `targetBeatHz` REAL NOT NULL,
                        `carrierHz` REAL NOT NULL,
                        `durationMinutes` INTEGER NOT NULL,
                        `soundMode` TEXT NOT NULL,
                        `enablePinkNoise` INTEGER NOT NULL
                    )
                """.trimIndent())

                // Step 2: Migrate data and transform legacy boolean columns to soundMode
                db.execSQL("""
                    INSERT INTO `presets_new` (`id`, `title`, `startBeatHz`, `targetBeatHz`, `carrierHz`, `durationMinutes`, `soundMode`, `enablePinkNoise`)
                    SELECT `id`, `title`, `startBeatHz`, `targetBeatHz`, `carrierHz`, `durationMinutes`,
                        CASE 
                            WHEN `isIsochronic` = 1 THEN 'ISOCHRONIC'
                            WHEN `enablePinkNoise` = 1 THEN 'HEMI_SYNC'
                            ELSE 'BINAURAL'
                        END AS `soundMode`,
                        `enablePinkNoise`
                    FROM `presets`
                """.trimIndent())

                // Step 3: Drop deprecated legacy table
                db.execSQL("DROP TABLE `presets`")

                // Step 4: Rename reconstructed table to active entity name
                db.execSQL("ALTER TABLE `presets_new` RENAME TO `presets`")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "black_cloud_binaural_db"
                )
                .addMigrations(MIGRATION_1_2)
                .addCallback(DatabaseCallback())
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
