package com.blackcloudgroup.binaural.audio

import android.util.Log
import com.blackcloudgroup.binaural.SoundMode
import com.blackcloudgroup.binaural.data.PresetEntity

private const val TAG = "PresetMapping"

/** Parses a stored sound mode, logging (not silently swallowing) unknown values. */
fun parseSoundMode(raw: String): SoundMode = try {
    SoundMode.valueOf(raw)
} catch (e: IllegalArgumentException) {
    Log.w(TAG, "Unknown soundMode '$raw' in stored preset; falling back to BINAURAL")
    SoundMode.BINAURAL
}

/** @throws IllegalArgumentException if the stored values can't be played (see [ToneParams] checks). */
fun PresetEntity.toToneParams(): ToneParams = ToneParams(
    carrierHz = carrierHz,
    startBeatHz = startBeatHz,
    targetBeatHz = targetBeatHz,
    durationSeconds = durationMinutes * 60,
    soundMode = parseSoundMode(soundMode),
    pinkNoise = enablePinkNoise
)
