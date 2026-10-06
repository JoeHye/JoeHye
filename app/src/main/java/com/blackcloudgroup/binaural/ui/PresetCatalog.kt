package com.blackcloudgroup.binaural.ui

import com.blackcloudgroup.binaural.data.PresetEntity
import kotlin.math.roundToInt

enum class Goal(val label: String) {
    SLEEP("Sleep"),
    FOCUS("Focus"),
    MEDITATE("Meditate"),
    ENERGIZE("Energize"),
    MINE("My presets")
}

/** How the UI groups and describes presets. Keyed by title, so user presets fall into [Goal.MINE]. */
object PresetCatalog {

    private class Info(val goal: Goal, val description: String)

    private val byTitle = mapOf(
        "Wind-Down Before Bed" to Info(Goal.SLEEP, "Ease from alert to drowsy while you get ready"),
        "Deep Sleep Ramp (Alpha → Delta)" to Info(Goal.SLEEP, "Alpha down to delta as you fall asleep"),
        "Long Deep Sleep" to Info(Goal.SLEEP, "Stays low and slow through the first hour"),
        "Power Nap" to Info(Goal.SLEEP, "A short drift down for a daytime rest"),
        "Lucid Dreaming Gateway" to Info(Goal.SLEEP, "Steady theta at the edge of sleep"),
        "Study Session" to Info(Goal.FOCUS, "Steady low beta for reading and work"),
        "Flow State / Active Focus" to Info(Goal.FOCUS, "Light, alert focus for creative work"),
        "40 Hz Gamma" to Info(Goal.FOCUS, "The rate used in sensory-stimulation research"),
        "Theta Deep Meditation" to Info(Goal.MEDITATE, "Settle into a quiet, inward state"),
        "Deep Meditation" to Info(Goal.MEDITATE, "Light theta easing into deep theta"),
        "Calm / Anxiety Relief" to Info(Goal.MEDITATE, "Steady alpha with a soft noise bed"),
        "Schumann Earth Resonance" to Info(Goal.MEDITATE, "A steady 7.83 Hz pulse"),
        "Morning Wake-Up" to Info(Goal.ENERGIZE, "From drowsy up to alert")
    )

    fun goalOf(preset: PresetEntity): Goal = byTitle[preset.title]?.goal ?: Goal.MINE

    fun descriptionOf(preset: PresetEntity): String? = byTitle[preset.title]?.description

    /** "10 → 5 Hz · 30 min" or "16 Hz · 45 min". */
    fun metaOf(preset: PresetEntity): String {
        val beats = if (preset.startBeatHz == preset.targetBeatHz) "${formatHz(preset.startBeatHz)} Hz"
        else "${formatHz(preset.startBeatHz)} → ${formatHz(preset.targetBeatHz)} Hz"
        return "$beats · ${preset.durationMinutes} min"
    }
}

data class Band(val name: String, val feel: String)

/** Standard EEG bands, used to describe a beat in words instead of only a number. */
fun bandOf(hz: Double): Band = when {
    hz < 4 -> Band("Delta", "deep sleep")
    hz < 8 -> Band("Theta", "deep relaxation")
    hz < 13 -> Band("Alpha", "calm and relaxed")
    hz < 30 -> Band("Beta", "alert focus")
    else -> Band("Gamma", "high focus")
}

/** 6 → "6", 7.83 → "7.8", 2.5 → "2.5". */
fun formatHz(hz: Double): String {
    val tenths = (hz * 10).roundToInt()
    return if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
}
