package com.blackcloudgroup.binaural.util

import android.content.Context
import android.util.Log
import com.blackcloudgroup.binaural.audio.ToneGenerator
import com.blackcloudgroup.binaural.audio.toToneParams
import com.blackcloudgroup.binaural.data.PresetEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavExporter {

    private const val TAG = "WavExporter"
    private const val SAMPLE_RATE = 44_100
    private const val NUM_CHANNELS = 2
    private const val BITS_PER_SAMPLE = 16
    private const val EXPORT_VOLUME = 0.2f
    private const val CHUNK_FRAMES = 4096
    private const val STALE_EXPORT_AGE_MS = 60 * 60 * 1000L

    /** Cache-dir file for [preset]; the title is sanitized so it can't escape the directory or break share targets. */
    fun exportFileFor(context: Context, preset: PresetEntity): File {
        val safeTitle = preset.title
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_', '.')
            .take(60)
            .ifEmpty { "preset" }
        return File(context.cacheDir, "${safeTitle}_${preset.id}.wav")
    }

    /**
     * Renders [preset] to a 16-bit stereo WAV using the same [ToneGenerator] as live playback
     * (ramp, Hemi-Sync harmonic layer, pink noise, isochronic envelope, end fade).
     *
     * Writes to a `.part` file and renames on success, so a failed or cancelled export never leaves
     * a truncated WAV behind. Cancellation is checked between chunks.
     *
     * @throws IllegalArgumentException if the preset can't be rendered (invalid values, zero duration, too large)
     * @throws IOException on write/rename failure
     */
    suspend fun exportToWav(preset: PresetEntity, outputFile: File): File = withContext(Dispatchers.IO) {
        val params = preset.toToneParams()
        require(params.durationSeconds > 0) { "Preset '${preset.title}' has no duration to export" }

        val totalFrames = SAMPLE_RATE.toLong() * params.durationSeconds
        val bytesPerFrame = NUM_CHANNELS * BITS_PER_SAMPLE / 8
        val dataSize = totalFrames * bytesPerFrame
        require(dataSize <= Int.MAX_VALUE - 36) {
            "Preset '${preset.title}' is too long for a WAV file (${params.durationSeconds}s)"
        }

        val dir = outputFile.parentFile ?: throw IOException("Output file has no parent directory: $outputFile")
        deleteStaleExports(dir, keep = outputFile)

        val partFile = File(dir, outputFile.name + ".part")
        try {
            val generator = ToneGenerator(params, SAMPLE_RATE).apply { setVolume(EXPORT_VOLUME) }
            val samples = ShortArray(CHUNK_FRAMES * NUM_CHANNELS)
            val bytes = ByteArray(samples.size * 2)
            val byteView = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            var framesWritten = 0L

            BufferedOutputStream(FileOutputStream(partFile)).use { out ->
                out.write(createWavHeader(dataSize.toInt(), SAMPLE_RATE, NUM_CHANNELS, BITS_PER_SAMPLE))
                while (framesWritten < totalFrames) {
                    ensureActive()
                    val frames = generator.render(samples, CHUNK_FRAMES)
                    if (frames == 0) break
                    byteView.clear()
                    byteView.put(samples, 0, frames * NUM_CHANNELS)
                    out.write(bytes, 0, frames * bytesPerFrame)
                    framesWritten += frames
                }
            }

            if (framesWritten != totalFrames) {
                throw IOException("Rendered $framesWritten frames but header declares $totalFrames")
            }
            if (generator.clippedSamples > 0) {
                Log.w(TAG, "Export '${preset.title}': ${generator.clippedSamples} samples clamped at full scale")
            }
            if (outputFile.exists() && !outputFile.delete()) {
                throw IOException("Could not replace existing export ${outputFile.name}")
            }
            if (!partFile.renameTo(outputFile)) {
                throw IOException("Could not rename ${partFile.name} to ${outputFile.name}")
            }
            Log.i(TAG, "Exported '${preset.title}' to ${outputFile.name} (${outputFile.length()} bytes)")
            outputFile
        } catch (e: Throwable) {
            // Includes CancellationException: clean up, then propagate unchanged.
            if (partFile.exists() && !partFile.delete()) {
                Log.w(TAG, "Could not delete partial export ${partFile.name}")
            }
            throw e
        }
    }

    /** Exports can be hundreds of MB; drop old ones instead of letting the cache grow unbounded. */
    private fun deleteStaleExports(dir: File, keep: File) {
        val cutoff = System.currentTimeMillis() - STALE_EXPORT_AGE_MS
        dir.listFiles { f -> (f.name.endsWith(".wav") || f.name.endsWith(".wav.part")) && f != keep }
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { f ->
                if (!f.delete()) Log.w(TAG, "Could not delete stale export ${f.name}")
            }
    }

    private fun createWavHeader(dataSize: Int, sampleRate: Int, channels: Int, bits: Int): ByteArray {
        val totalSize = dataSize + 36
        val byteRate = sampleRate * channels * (bits / 8)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        header.put("RIFF".toByteArray())
        header.putInt(totalSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * (bits / 8)).toShort())
        header.putShort(bits.toShort())
        header.put("data".toByteArray())
        header.putInt(dataSize)

        return header.array()
    }
}
