package com.blackcloudgroup.binaural.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val startBeatHz: Double,
    val targetBeatHz: Double,
    val carrierHz: Double,
    val durationMinutes: Int,
    val soundMode: String = "HEMI_SYNC",
    val enablePinkNoise: Boolean = true
)
