package com.blackcloudgroup.binaural.data

import com.blackcloudgroup.binaural.SoundMode
import com.blackcloudgroup.binaural.audio.toToneParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultPresetsTest {

    @Test
    fun titlesAreUnique() {
        // Restore matches on title, so duplicates would make one of them impossible to restore.
        val titles = DefaultPresets.all.map { it.title }
        assertEquals("Duplicate titles: ${titles.groupingBy { it }.eachCount().filterValues { it > 1 }}",
            titles.size, titles.toSet().size)
    }

    @Test
    fun everyPresetIsPlayableAndWithinUiRanges() {
        for (p in DefaultPresets.all) {
            // Throws IllegalArgumentException if the engine would reject it.
            p.toToneParams()
            assertTrue("${p.title}: carrier ${p.carrierHz}", p.carrierHz in 100.0..500.0)
            assertTrue("${p.title}: start beat ${p.startBeatHz}", p.startBeatHz in 0.5..40.0)
            assertTrue("${p.title}: target beat ${p.targetBeatHz}", p.targetBeatHz in 0.5..40.0)
            assertTrue("${p.title}: duration ${p.durationMinutes}", p.durationMinutes in 1..60)
            assertTrue("${p.title}: mode ${p.soundMode}", SoundMode.values().any { it.name == p.soundMode })
        }
    }

    @Test
    fun pinkNoiseOnlyOnHemiSync() {
        // The engine ignores pink noise outside Hemi-Sync; a preset claiming it would be misleading.
        for (p in DefaultPresets.all) {
            if (p.enablePinkNoise) assertEquals(p.title, "HEMI_SYNC", p.soundMode)
        }
    }
}
