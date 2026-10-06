package com.blackcloudgroup.binaural.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets ORDER BY title ASC")
    fun getAllPresets(): Flow<List<PresetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: PresetEntity)

    @Delete
    suspend fun deletePreset(preset: PresetEntity)

    @Query("SELECT title FROM presets")
    suspend fun getAllTitles(): List<String>

    @Insert
    suspend fun insertAll(presets: List<PresetEntity>)

    /**
     * Re-adds built-in presets whose title isn't present. Never touches or duplicates
     * existing rows, so user-edited or user-created presets are safe.
     * @return how many presets were added
     */
    @Transaction
    suspend fun restoreMissingDefaults(defaults: List<PresetEntity>): Int {
        val existing = getAllTitles().toSet()
        val missing = defaults.filter { it.title !in existing }
        if (missing.isNotEmpty()) insertAll(missing)
        return missing.size
    }
}
