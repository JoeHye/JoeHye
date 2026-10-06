package com.blackcloudgroup.binaural.data

/**
 * Built-in presets. Seeded on first install and re-added (missing ones only) by
 * "Restore default presets". Titles are the identity for restore, so renaming one here
 * makes it appear as a new preset on existing installs.
 *
 * Values must stay inside the UI ranges: carrier 100–500 Hz, beat 0.5–40 Hz, 1–60 minutes
 * (enforced by DefaultPresetsTest).
 */
object DefaultPresets {

    val all: List<PresetEntity> = listOf(
        // Original set
        preset("Theta Deep Meditation", 6.0, 6.0, 136.1, 20, "HEMI_SYNC", pinkNoise = true),
        preset("Schumann Earth Resonance", 7.83, 7.83, 144.0, 30, "HEMI_SYNC", pinkNoise = true),
        preset("Deep Sleep Ramp (Alpha → Delta)", 10.0, 2.5, 174.0, 45, "HEMI_SYNC", pinkNoise = true),
        preset("Lucid Dreaming Gateway", 4.0, 4.0, 210.0, 30, "BINAURAL", pinkNoise = false),
        preset("Flow State / Active Focus", 14.0, 14.0, 200.0, 25, "ISOCHRONIC", pinkNoise = false),

        // Added set
        preset("Wind-Down Before Bed", 10.0, 5.0, 200.0, 30, "HEMI_SYNC", pinkNoise = true),
        preset("Power Nap", 10.0, 4.0, 180.0, 20, "HEMI_SYNC", pinkNoise = true),
        preset("Long Deep Sleep", 4.0, 1.5, 150.0, 60, "HEMI_SYNC", pinkNoise = true),
        preset("Morning Wake-Up", 6.0, 14.0, 250.0, 15, "BINAURAL", pinkNoise = false),
        preset("Calm / Anxiety Relief", 10.0, 10.0, 200.0, 20, "HEMI_SYNC", pinkNoise = true),
        preset("Deep Meditation", 7.0, 4.5, 200.0, 30, "BINAURAL", pinkNoise = false),
        // Isochronic works over speakers, and binaural beats above ~30 Hz are hard to perceive.
        preset("Study Session", 16.0, 16.0, 220.0, 45, "ISOCHRONIC", pinkNoise = false),
        preset("40 Hz Gamma", 40.0, 40.0, 300.0, 20, "ISOCHRONIC", pinkNoise = false)
    )

    private fun preset(
        title: String,
        startBeatHz: Double,
        targetBeatHz: Double,
        carrierHz: Double,
        durationMinutes: Int,
        soundMode: String,
        pinkNoise: Boolean
    ) = PresetEntity(
        title = title,
        startBeatHz = startBeatHz,
        targetBeatHz = targetBeatHz,
        carrierHz = carrierHz,
        durationMinutes = durationMinutes,
        soundMode = soundMode,
        enablePinkNoise = pinkNoise
    )
}
