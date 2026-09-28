package com.blackcloudgroup.binaural.util

import android.content.Context
import com.blackcloudgroup.binaural.data.PresetEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

object WavExporter {

    suspend fun exportToWav(
        context: Context,
        preset: PresetEntity,
        outputFile: File
    ) = withContext(Dispatchers.IO) {
        val sampleRate = 44100
        val totalSeconds = preset.durationMinutes * 60
        val numSamples = sampleRate * totalSeconds
        val numChannels = 2
        val bitsPerSample = 16
        val dataSize = numSamples * numChannels * (bitsPerSample / 8)

        FileOutputStream(outputFile).use { fos ->
            fos.write(createWavHeader(dataSize, sampleRate, numChannels, bitsPerSample))

            val bufferSize = 4096
            val buffer = ShortArray(bufferSize * numChannels)
            var sampleIdx = 0L

            while (sampleIdx < numSamples) {
                val progress = sampleIdx.toDouble() / numSamples
                val currentBeat = preset.startBeatHz + (preset.targetBeatHz - preset.startBeatHz) * progress
                val leftFreq = preset.carrierHz - (currentBeat / 2.0)
                val rightFreq = preset.carrierHz + (currentBeat / 2.0)

                val isochronicPeriod = sampleRate / currentBeat

                var i = 0
                while (i < bufferSize && sampleIdx < numSamples) {
                    var sampleL = sin(2.0 * Math.PI * sampleIdx * leftFreq / sampleRate)
                    var sampleR = sin(2.0 * Math.PI * sampleIdx * rightFreq / sampleRate)

                    if (preset.soundMode == "ISOCHRONIC") {
                        val phase = (sampleIdx % isochronicPeriod) / isochronicPeriod
                        val gain = if (phase < 0.5) 1.0 else 0.0
                        sampleL *= gain
                        sampleR *= gain
                    }

                    buffer[i * 2] = (sampleL * Short.MAX_VALUE * 0.2).toInt().toShort()
                    buffer[i * 2 + 1] = (sampleR * Short.MAX_VALUE * 0.2).toInt().toShort()
                    i++
                    sampleIdx++
                }

                val byteBuffer = ByteBuffer.allocate(i * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (j in 0 until i * 2) {
                    byteBuffer.putShort(buffer[j])
                }
                fos.write(byteBuffer.array())
            }
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
