package com.blackcloudgroup.binaural.audio

import com.blackcloudgroup.binaural.SoundMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Everything needed to synthesize one session. Immutable so it can be handed across threads safely.
 *
 * @param durationSeconds 0 = open-ended: no ramp, no automatic end. When > 0 the beat ramps linearly
 *   from [startBeatHz] to [targetBeatHz] over the duration and the session fades out and ends.
 */
data class ToneParams(
    val carrierHz: Double,
    val startBeatHz: Double,
    val targetBeatHz: Double = startBeatHz,
    val durationSeconds: Int = 0,
    val soundMode: SoundMode = SoundMode.HEMI_SYNC,
    val pinkNoise: Boolean = true
) {
    init {
        require(carrierHz > 0.0 && carrierHz < 20_000.0) { "carrierHz out of range: $carrierHz" }
        require(startBeatHz > 0.0 && targetBeatHz > 0.0) {
            "Beat frequencies must be positive (start=$startBeatHz, target=$targetBeatHz)"
        }
        require(carrierHz - maxOf(startBeatHz, targetBeatHz) / 2.0 > 0.0) {
            "Beat too wide for carrier: carrier=$carrierHz, beat up to ${maxOf(startBeatHz, targetBeatHz)}"
        }
        require(durationSeconds >= 0) { "durationSeconds must be >= 0, was $durationSeconds" }
    }
}

/**
 * Stereo 16-bit PCM synthesizer shared by live playback and WAV export.
 *
 * Every oscillator keeps its own phase accumulator, so frequency changes (slider moves, ramps,
 * preset switches) are phase-continuous instead of jumping by `sampleIdx * deltaFreq`.
 *
 * Threading: [render] must only be called from one thread (the audio or export thread).
 * [loadParams], [setVolume] and [requestStop] may be called from any thread; they take effect at
 * the start of the next [render] call.
 */
class ToneGenerator(
    initialParams: ToneParams,
    private val sampleRate: Int = 44_100,
    private val random: Random = Random.Default
) {
    @Volatile private var pendingParams: ToneParams = initialParams
    @Volatile private var pendingTimelineRestart = false
    @Volatile private var targetGain = 0.2
    @Volatile private var stopFadeFramesRequested = -1L

    // ---- Audio-thread-only state below ----
    private var params = initialParams
    private var totalFrames = framesFor(initialParams)
    private var elapsedFrames = 0L

    // Phases are in cycles [0, 1) to keep precision constant however long the session runs.
    private var phaseL1 = 0.0
    private var phaseR1 = 0.0
    private var phaseL2 = 0.0
    private var phaseR2 = 0.0
    private var gatePhase = 0.0

    // Starts at 0 so every session fades in; also smooths volume/duck changes (no zipper noise).
    private var gain = 0.0
    private val gainSmoothing = 1.0 - kotlin.math.exp(-1.0 / (0.05 * sampleRate)) // ~50 ms time constant

    private var stopFadeTotal = -1L
    private var stopFadeRemaining = -1L
    private val endFadeFrames = (SESSION_END_FADE_SECONDS * sampleRate).toLong()

    // Paul Kellet pink noise filter state (same coefficients as the original service).
    private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0
    private var b3 = 0.0; private var b4 = 0.0; private var b5 = 0.0; private var b6 = 0.0

    /** Samples that exceeded full scale and were clamped since creation. Read after rendering for logging. */
    var clippedSamples = 0L
        private set

    var isFinished = false
        private set

    val framesRendered: Long get() = elapsedFrames

    /**
     * Swap in new parameters. With [restartTimeline] the ramp and duration start over (e.g. a new
     * preset was picked); otherwise the session keeps its elapsed time (e.g. a slider moved).
     */
    fun loadParams(newParams: ToneParams, restartTimeline: Boolean = false) {
        pendingParams = newParams
        if (restartTimeline) pendingTimelineRestart = true
    }

    /** Linear output gain, 0..1. Values outside the range are rejected rather than silently clamped. */
    fun setVolume(volume: Float) {
        require(volume in 0f..1f) { "volume must be within 0..1, was $volume" }
        targetGain = volume.toDouble()
    }

    /** Fade out over [fadeMillis] and then finish. Safe to call repeatedly; the first call wins. */
    fun requestStop(fadeMillis: Int) {
        require(fadeMillis >= 0) { "fadeMillis must be >= 0" }
        if (stopFadeFramesRequested < 0) {
            stopFadeFramesRequested = maxOf(1L, fadeMillis.toLong() * sampleRate / 1000)
        }
    }

    /**
     * Fill [out] with up to [maxFrames] interleaved stereo frames (L, R, L, R, ...).
     * @return frames actually written; fewer than [maxFrames] (possibly 0) once the session finished.
     */
    fun render(out: ShortArray, maxFrames: Int): Int {
        require(out.size >= maxFrames * 2) { "Buffer holds ${out.size / 2} frames, asked for $maxFrames" }
        if (isFinished) return 0
        applyPendingState()

        val p = params
        val isHemiSync = p.soundMode == SoundMode.HEMI_SYNC
        val isIsochronic = p.soundMode == SoundMode.ISOCHRONIC
        val addPink = isHemiSync && p.pinkNoise
        val gainTarget = targetGain
        val sr = sampleRate.toDouble()
        val harmonicCarrier = p.carrierHz * 1.5

        var frame = 0
        while (frame < maxFrames) {
            if (totalFrames > 0 && elapsedFrames >= totalFrames) { isFinished = true; break }
            if (stopFadeRemaining == 0L) { isFinished = true; break }

            val beat = currentBeat(p)
            val halfBeat = beat / 2.0

            var left = sin(TWO_PI * phaseL1)
            var right = sin(TWO_PI * phaseR1)
            phaseL1 = advance(phaseL1, (p.carrierHz - halfBeat) / sr)
            phaseR1 = advance(phaseR1, (p.carrierHz + halfBeat) / sr)

            if (isHemiSync) {
                val left2 = sin(TWO_PI * phaseL2) * 0.5
                val right2 = sin(TWO_PI * phaseR2) * 0.5
                phaseL2 = advance(phaseL2, (harmonicCarrier - halfBeat) / sr)
                phaseR2 = advance(phaseR2, (harmonicCarrier + halfBeat) / sr)
                left = (left + left2) / 1.5
                right = (right + right2) / 1.5
                if (addPink) {
                    val pink = nextPink()
                    left += pink
                    right += pink
                }
            } else if (isIsochronic) {
                val env = isochronicEnvelope(gatePhase, beat)
                gatePhase = advance(gatePhase, beat / sr)
                left *= env
                right *= env
            }

            gain += (gainTarget - gain) * gainSmoothing
            var envelope = gain
            if (totalFrames > 0) {
                val remaining = totalFrames - elapsedFrames
                if (remaining < endFadeFrames) envelope *= remaining.toDouble() / endFadeFrames
            }
            if (stopFadeRemaining > 0) {
                envelope *= stopFadeRemaining.toDouble() / stopFadeTotal
                stopFadeRemaining--
            }

            out[frame * 2] = toPcm(left * envelope)
            out[frame * 2 + 1] = toPcm(right * envelope)
            frame++
            elapsedFrames++
        }
        return frame
    }

    private fun applyPendingState() {
        val incoming = pendingParams
        if (incoming !== params) {
            params = incoming
            totalFrames = framesFor(incoming)
        }
        if (pendingTimelineRestart) {
            pendingTimelineRestart = false
            elapsedFrames = 0L
        }
        val stopFrames = stopFadeFramesRequested
        if (stopFrames > 0 && stopFadeTotal < 0) {
            stopFadeTotal = stopFrames
            stopFadeRemaining = stopFrames
        }
    }

    private fun currentBeat(p: ToneParams): Double {
        if (totalFrames <= 0 || p.startBeatHz == p.targetBeatHz) return p.startBeatHz
        val progress = min(1.0, elapsedFrames.toDouble() / totalFrames)
        return p.startBeatHz + (p.targetBeatHz - p.startBeatHz) * progress
    }

    private fun nextPink(): Double {
        val white = random.nextDouble() * 2.0 - 1.0
        b0 = 0.99886 * b0 + white * 0.0555179
        b1 = 0.99332 * b1 + white * 0.0750759
        b2 = 0.96900 * b2 + white * 0.1538520
        b3 = 0.86650 * b3 + white * 0.3104856
        b4 = 0.55000 * b4 + white * 0.5329522
        b5 = -0.7616 * b5 - white * 0.0168980
        val pink = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362) * PINK_LEVEL
        b6 = white * 0.115926
        return pink
    }

    private fun toPcm(sample: Double): Short {
        val clamped = when {
            sample > 1.0 -> { clippedSamples++; 1.0 }
            sample < -1.0 -> { clippedSamples++; -1.0 }
            else -> sample
        }
        return (clamped * Short.MAX_VALUE).roundToInt().toShort()
    }

    private fun framesFor(p: ToneParams): Long = p.durationSeconds.toLong() * sampleRate

    companion object {
        private const val TWO_PI = 2.0 * PI
        private const val PINK_LEVEL = 0.02
        const val SESSION_END_FADE_SECONDS = 3.0

        /** Edge fade for isochronic pulses. Hard 0/1 gating produces a broadband click on every edge. */
        private const val ISOCHRONIC_EDGE_SECONDS = 0.005

        private fun advance(phase: Double, increment: Double): Double {
            val next = phase + increment
            return next - kotlin.math.floor(next)
        }

        /** 50% duty pulse with raised-cosine edges. [cyclePhase] is in cycles [0, 1). */
        internal fun isochronicEnvelope(cyclePhase: Double, beatHz: Double): Double {
            val edge = min(ISOCHRONIC_EDGE_SECONDS * beatHz, 0.125) // edge width in cycles
            return when {
                cyclePhase >= 0.5 -> 0.0
                cyclePhase < edge -> 0.5 - 0.5 * cos(PI * cyclePhase / edge)
                cyclePhase > 0.5 - edge -> 0.5 - 0.5 * cos(PI * (0.5 - cyclePhase) / edge)
                else -> 1.0
            }
        }
    }
}
