package com.blackcloudgroup.binaural.audio

import com.blackcloudgroup.binaural.SoundMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random

class ToneGeneratorTest {

    private val sampleRate = 44_100

    /** Renders the whole session (or [maxFrames]) into separate L/R arrays. */
    private fun renderAll(gen: ToneGenerator, maxFrames: Int, chunk: Int = 4096): Pair<ShortArray, ShortArray> {
        val left = ShortArray(maxFrames)
        val right = ShortArray(maxFrames)
        val buf = ShortArray(chunk * 2)
        var pos = 0
        while (pos < maxFrames) {
            val n = gen.render(buf, minOf(chunk, maxFrames - pos))
            if (n == 0) break
            for (i in 0 until n) {
                left[pos + i] = buf[i * 2]
                right[pos + i] = buf[i * 2 + 1]
            }
            pos += n
        }
        return left.copyOf(pos) to right.copyOf(pos)
    }

    /** Largest sample-to-sample step. A pure sine at f Hz and amplitude A never steps more than 2*pi*f/sr*A. */
    private fun maxStep(s: ShortArray, from: Int = 0): Int {
        var m = 0
        for (i in maxOf(1, from) until s.size) m = maxOf(m, abs(s[i] - s[i - 1]))
        return m
    }

    private fun upwardZeroCrossings(s: ShortArray, from: Int, to: Int): Int {
        var count = 0
        for (i in from + 1 until to) if (s[i - 1] < 0 && s[i] >= 0) count++
        return count
    }

    @Test
    fun parameterChangeMidStream_isPhaseContinuous() {
        val gen = ToneGenerator(ToneParams(200.0, 6.0, soundMode = SoundMode.BINAURAL), sampleRate)
        gen.setVolume(1f)
        val first = renderAll(gen, sampleRate) // 1 s: gain has settled
        gen.loadParams(ToneParams(480.0, 30.0, soundMode = SoundMode.BINAURAL))
        val second = renderAll(gen, sampleRate / 10)

        // Stitch last frame of the first block to the second block and look for a discontinuity.
        val stitched = ShortArray(second.first.size + 1)
        stitched[0] = first.first.last()
        second.first.copyInto(stitched, 1)
        val bound = (2 * PI * 500.0 / sampleRate * Short.MAX_VALUE * 1.1).toInt()
        assertTrue("Step ${maxStep(stitched)} exceeds sine bound $bound (click)", maxStep(stitched) <= bound)
    }

    @Test
    fun ramp_producesTheRequestedBeatAtTheEnd() {
        // Scaled-down version of "Deep Sleep Ramp": 10 Hz -> 2.5 Hz. The pre-rewrite exporter ended near -5 Hz.
        val seconds = 120
        val gen = ToneGenerator(
            ToneParams(174.0, 10.0, 2.5, seconds, SoundMode.BINAURAL), sampleRate
        )
        gen.setVolume(1f)
        val (l, r) = renderAll(gen, seconds * sampleRate)

        // Window 10 s before the end fade starts; expected beat there ~2.5 + 7.5 * (13/120) ~= 3.3 Hz.
        val end = l.size - (ToneGenerator.SESSION_END_FADE_SECONDS * sampleRate).toInt()
        val start = end - 10 * sampleRate
        val beatCycles = upwardZeroCrossings(r, start, end) - upwardZeroCrossings(l, start, end)
        val measuredBeat = beatCycles / 10.0
        val midProgress = ((start + end) / 2.0) / (seconds * sampleRate)
        val expected = 10.0 + (2.5 - 10.0) * midProgress
        assertEquals("Effective beat near end of ramp", expected, measuredBeat, 0.25)
    }

    @Test
    fun durationSession_endsExactlyOnTime_withFadeOut() {
        val gen = ToneGenerator(ToneParams(200.0, 6.0, durationSeconds = 5, soundMode = SoundMode.BINAURAL), sampleRate)
        val (l, _) = renderAll(gen, 10 * sampleRate)
        assertEquals(5 * sampleRate, l.size)
        assertTrue(gen.isFinished)
        assertEquals(0, gen.render(ShortArray(8), 4))
        assertTrue("Last sample should be faded to near-silence", abs(l.last().toInt()) < 50)
    }

    @Test
    fun requestStop_fadesOutAndFinishes() {
        val gen = ToneGenerator(ToneParams(200.0, 6.0, soundMode = SoundMode.BINAURAL), sampleRate)
        gen.setVolume(1f)
        renderAll(gen, sampleRate)
        gen.requestStop(fadeMillis = 250)
        val (l, _) = renderAll(gen, sampleRate)
        assertEquals(sampleRate / 4, l.size)
        assertTrue(gen.isFinished)
        assertTrue(abs(l.last().toInt()) < 200)
    }

    @Test
    fun pause_fadesToSilenceThenResumesSmoothly() {
        val gen = ToneGenerator(ToneParams(200.0, 6.0, soundMode = SoundMode.BINAURAL), sampleRate)
        gen.setVolume(1f)
        val before = renderAll(gen, sampleRate)
        gen.setPaused(true)
        val fadeOut = renderAll(gen, (ToneGenerator.PAUSE_FADE_SECONDS * sampleRate).toInt() + 10)
        assertTrue("Should report silenced after the pause fade", gen.isSilencedForPause)
        assertTrue("Tail of pause fade should be silent", abs(fadeOut.first.last().toInt()) == 0)

        gen.setPaused(false)
        assertFalse(gen.isSilencedForPause)
        val resumed = renderAll(gen, sampleRate / 2)
        val bound = (2 * PI * 205.0 / sampleRate * Short.MAX_VALUE * 1.2).toInt()
        val stitched = ShortArray(resumed.first.size + 1).also { fadeOut.first.last().let { v -> it[0] = v }; resumed.first.copyInto(it, 1) }
        assertTrue("Resume should not click", maxStep(stitched) <= bound)
        assertTrue("Should be audible again after resume", resumed.first.takeLast(1000).any { abs(it.toInt()) > 10_000 })
        assertFalse(before.first.isEmpty())
    }

    @Test
    fun hemiSyncAtFullVolume_clampsInsteadOfWrapping() {
        val gen = ToneGenerator(
            ToneParams(200.0, 6.0, soundMode = SoundMode.HEMI_SYNC, pinkNoise = true),
            sampleRate, Random(42)
        )
        gen.setVolume(1f)
        val (l, _) = renderAll(gen, 20 * sampleRate)
        // Integer wrap-around shows up as a ~65k jump between neighbouring samples.
        assertTrue("Max step ${maxStep(l)} looks like wrap-around", maxStep(l) < 20_000)
    }

    @Test
    fun isochronicEnvelope_hasNoHardEdges() {
        val gen = ToneGenerator(ToneParams(200.0, 10.0, soundMode = SoundMode.ISOCHRONIC), sampleRate)
        gen.setVolume(1f)
        val (l, _) = renderAll(gen, 2 * sampleRate)
        // Pure 200 Hz sine bound plus the envelope slope contribution (~5 ms edges).
        val bound = (2 * PI * 205.0 / sampleRate * Short.MAX_VALUE * 1.6).toInt()
        assertTrue("Step ${maxStep(l, sampleRate)} exceeds bound $bound", maxStep(l, sampleRate) <= bound)
    }

    @Test
    fun pinkNoise_onlyWhenEnabled() {
        fun render(mode: SoundMode, pink: Boolean): ShortArray {
            val gen = ToneGenerator(ToneParams(200.0, 6.0, soundMode = mode, pinkNoise = pink), sampleRate, Random(1))
            gen.setVolume(1f)
            return renderAll(gen, sampleRate).first
        }
        assertFalse(render(SoundMode.HEMI_SYNC, true).contentEquals(render(SoundMode.HEMI_SYNC, false)))
        // Pink noise is a Hemi-Sync layer; the flag is ignored in the other modes.
        assertTrue(render(SoundMode.BINAURAL, true).contentEquals(render(SoundMode.BINAURAL, false)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidParams_areRejected() {
        ToneParams(carrierHz = 10.0, startBeatHz = 40.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun volumeOutOfRange_isRejected() {
        ToneGenerator(ToneParams(200.0, 6.0), sampleRate).setVolume(1.5f)
    }
}
