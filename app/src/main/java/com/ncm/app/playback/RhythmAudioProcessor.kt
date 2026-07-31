package com.ncm.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Pass-through PCM processor that extracts a lightweight low-frequency envelope for player
 * artwork. It never retains audio and deliberately avoids FFT work on the playback thread.
 */
@UnstableApi
class RhythmAudioProcessor : BaseAudioProcessor() {
    @Volatile
    private var visualEnergy = 0f

    private var lowPass = 0f
    private var adaptivePeak = 0.08f
    private var previousEnergy = 0f

    fun visualEnergy(): Float = visualEnergy

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        analyzePcm16(inputBuffer)
        val outputBuffer = replaceOutputBuffer(inputBuffer.remaining())
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }

    private fun analyzePcm16(inputBuffer: ByteBuffer) {
        val sampleBuffer = inputBuffer
            .duplicate()
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
        val channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        val frameCount = sampleBuffer.remaining() / channelCount
        if (frameCount == 0) return

        val decay = exp(-2.0 * PI * 180.0 / inputAudioFormat.sampleRate).toFloat()
        var energySum = 0.0
        repeat(frameCount) {
            var mono = 0f
            repeat(channelCount) {
                mono += sampleBuffer.get() / 32768f
            }
            mono /= channelCount
            lowPass = decay * lowPass + (1f - decay) * mono
            energySum += lowPass * lowPass
        }

        val rms = sqrt(energySum / frameCount).toFloat()
        adaptivePeak = maxOf(rms, adaptivePeak * 0.992f, 0.025f)
        val normalized = (rms / adaptivePeak).coerceIn(0f, 1f)
        val onset = ((normalized - previousEnergy) * 2.4f).coerceIn(0f, 1f)
        previousEnergy = normalized
        visualEnergy = (normalized * 0.72f + onset * 0.28f).coerceIn(0f, 1f)
    }

    override fun onFlush() {
        lowPass = 0f
        previousEnergy = 0f
        visualEnergy = 0f
    }

    override fun onReset() {
        onFlush()
        adaptivePeak = 0.08f
    }
}
