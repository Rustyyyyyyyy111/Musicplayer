package com.example.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.example.core.model.EqState
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tanh

@UnstableApi
class BiquadEqAudioProcessor : BaseAudioProcessor() {

    companion object {
        val BAND_FREQUENCIES_HZ = floatArrayOf(
            31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
        )
        private const val BAND_COUNT = 10
        private const val Q_FACTOR = 1.414
    }

    private data class BiquadCoeff(
        var b0: Double = 1.0,
        var b1: Double = 0.0,
        var b2: Double = 0.0,
        var a1: Double = 0.0,
        var a2: Double = 0.0
    )

    @Volatile
    private var eqState: EqState = EqState()

    @Volatile
    private var trackLoudnessGainDb: Float = 0f

    @Volatile
    private var coeffsDirty = true

    private val coeffs = Array(BAND_COUNT) { BiquadCoeff() }
    private var x1 = Array(0) { DoubleArray(BAND_COUNT) }
    private var x2 = Array(0) { DoubleArray(BAND_COUNT) }
    private var y1 = Array(0) { DoubleArray(BAND_COUNT) }
    private var y2 = Array(0) { DoubleArray(BAND_COUNT) }

    fun updateSettings(newState: EqState, loudnessGainDb: Float = 0f) {
        eqState = newState
        trackLoudnessGainDb = loudnessGainDb
        coeffsDirty = true
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        x1 = Array(channels) { DoubleArray(BAND_COUNT) }
        x2 = Array(channels) { DoubleArray(BAND_COUNT) }
        y1 = Array(channels) { DoubleArray(BAND_COUNT) }
        y2 = Array(channels) { DoubleArray(BAND_COUNT) }
        coeffsDirty = true
        return inputAudioFormat
    }

    override fun isActive(): Boolean {
        val state = eqState
        val active = super.isActive()
        if (!active) return false
        return state.enabled || (state.loudnessNormalization && trackLoudnessGainDb != 0f)
    }

    override fun onFlush() {
        for (ch in x1.indices) {
            x1[ch].fill(0.0)
            x2[ch].fill(0.0)
            y1[ch].fill(0.0)
            y2[ch].fill(0.0)
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val state = eqState
        if (coeffsDirty) {
            recomputeCoefficients(inputAudioFormat.sampleRate.coerceAtLeast(8000), state)
            coeffsDirty = false
        }

        val outBuffer = replaceOutputBuffer(remaining)
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val preampLinear = dbToLinear(
            (if (state.enabled) state.preampDb else 0f) +
                (if (state.loudnessNormalization) trackLoudnessGainDb else 0f)
        )
        val clippingGuard = state.clippingProtection
        val eqEnabled = state.enabled

        var channelIdx = 0
        while (inputBuffer.remaining() >= 2) {
            val sampleShort = inputBuffer.short
            var sample = (sampleShort / 32768.0) * preampLinear

            if (eqEnabled && channelIdx < x1.size) {
                for (band in 0 until BAND_COUNT) {
                    val c = coeffs[band]
                    val y = c.b0 * sample +
                        c.b1 * x1[channelIdx][band] +
                        c.b2 * x2[channelIdx][band] -
                        c.a1 * y1[channelIdx][band] -
                        c.a2 * y2[channelIdx][band]
                    x2[channelIdx][band] = x1[channelIdx][band]
                    x1[channelIdx][band] = sample
                    y2[channelIdx][band] = y1[channelIdx][band]
                    y1[channelIdx][band] = y
                    sample = y
                }
            }

            if (clippingGuard) {
                sample = softClip(sample)
            } else {
                sample = sample.coerceIn(-1.0, 1.0)
            }

            val outSample = (sample * 32767.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            outBuffer.putShort(outSample)
            channelIdx = (channelIdx + 1) % channels
        }
        outBuffer.flip()
    }

    private fun recomputeCoefficients(sampleRate: Int, state: EqState) {
        val nyquistLimit = sampleRate * 0.45
        for (i in 0 until BAND_COUNT) {
            val gainDb = state.bandGainsDb.getOrElse(i) { 0f }.toDouble().coerceIn(-12.0, 12.0)
            val freq = BAND_FREQUENCIES_HZ[i].toDouble().coerceAtMost(nyquistLimit)
            if (kotlin.math.abs(gainDb) < 0.05) {
                coeffs[i] = BiquadCoeff(1.0, 0.0, 0.0, 0.0, 0.0)
                continue
            }
            val a = 10.0.pow(gainDb / 40.0)
            val omega = 2.0 * PI * freq / sampleRate
            val sinOmega = sin(omega)
            val cosOmega = cos(omega)
            val alpha = sinOmega * sinh((kotlin.math.ln(2.0) / 2.0) * (1.0 / Q_FACTOR) * (omega / sinOmega))

            val b0 = 1.0 + alpha * a
            val b1 = -2.0 * cosOmega
            val b2 = 1.0 - alpha * a
            val a0 = 1.0 + alpha / a
            val a1 = -2.0 * cosOmega
            val a2 = 1.0 - alpha / a

            coeffs[i] = BiquadCoeff(
                b0 = b0 / a0,
                b1 = b1 / a0,
                b2 = b2 / a0,
                a1 = a1 / a0,
                a2 = a2 / a0
            )
        }
    }

    private fun softClip(x: Double): Double {
        val threshold = 0.88
        val absX = kotlin.math.abs(x)
        if (absX <= threshold) return x
        val sign = if (x >= 0.0) 1.0 else -1.0
        val excess = absX - threshold
        val compressed = threshold + (1.0 - threshold) * tanh(excess / (1.0 - threshold))
        return sign * compressed.coerceAtMost(0.999)
    }

    private fun dbToLinear(db: Float): Double = 10.0.pow(db.toDouble() / 20.0)
}
