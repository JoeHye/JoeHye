package com.blackcloudgroup.binaural.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 1 is the first shipped schema (soundMode column). Earlier development builds used a
 * different v1/v2 layout but never shipped, so there is no migration history to carry.
 * When the schema changes, bump the version and add a Migration plus a MigrationTestHelper test
 * against the exported schemas in app/schemas/.
 */
@Database(entities = [PresetEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {

    abstract fun presetDao(): PresetDao

    private class DatabaseCallback : RoomDatabase.Callback() {

        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            populateDefaultPresets(db)
        }

        /**
         * Runs inside the transaction SQLiteOpenHelper already opened for onCreate, so it must not
         * open its own: a failed nested transaction marks the outer one failed and rolls back table
         * creation too. Each insert is isolated instead, so one bad row can't break the database.
         */
        private fun populateDefaultPresets(db: SupportSQLiteDatabase) {
            val insertSql = """
                INSERT INTO presets (title, startBeatHz, targetBeatHz, carrierHz, durationMinutes, soundMode, enablePinkNoise)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()

            val defaults = DefaultPresets.all.map { p ->
                arrayOf<Any>(
                    p.title, p.startBeatHz, p.targetBeatHz, p.carrierHz, p.durationMinutes,
                    p.soundMode, if (p.enablePinkNoise) 1 else 0
                )
            }

            var seeded = 0
            for (preset in defaults) {
                try {
                    db.execSQL(insertSql, preset)
                    seeded++
                } catch (e: Exception) {
                    Log.e("AppDatabase", "Failed to seed default preset '${preset[0]}'", e)
                }
            }
            Log.i("AppDatabase", "Seeded $seeded of ${defaults.size} default presets.")
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "black_cloud_binaural_db"
                )
                // Pre-release test builds were at version 2. Installing this build over one of them
                // is a downgrade; wipe and re-seed instead of crashing. Never applies to upgrades.
                .fallbackToDestructiveMigrationOnDowngrade()
                .addCallback(DatabaseCallback())
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
