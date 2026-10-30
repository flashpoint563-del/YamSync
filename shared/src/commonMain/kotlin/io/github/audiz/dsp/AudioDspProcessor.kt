package io.github.audiz.dsp

import kotlin.math.*

/**
 * Независимый инстанс фильтров DSP для обработки одного аудиопотока.
 * Каждый плеер/процессор должен иметь свой собственный [AudioDspProcessor],
 * чтобы фильтры разных треков не смешивались и не вызывали треск во время кроссфейда.
 */
class AudioDspProcessor {
    private var sampleRate: Float = 44100f

    private val hpfFilter = BiquadFilter()
    private val bandFilters = Array(10) { BiquadFilter() }
    private val lpfFilter = BiquadFilter()
    private val viperBassDsp = ViperBassDsp()
    private val dynamicBassDsp = DynamicBassDsp()
    private var envState = 0f

    private var lastStateHash = 0

    private fun smoothLimit(x: Float): Float {
        val t = 0.90f
        val ax = abs(x)
        if (ax <= t) return x
        val sign = if (x >= 0f) 1f else -1f
        return sign * (t + (1f - t) * tanh((ax - t) / (1f - t)))
    }

    fun setSampleRate(rate: Float) {
        if (rate <= 8000f) return
        if (rate != sampleRate) {
            sampleRate = rate
            lastStateHash = 0
        }
    }

    private fun updateFromState(state: EqualizerEngine.State) {
        val currentHash = state.hashCode() xor sampleRate.hashCode()
        if (currentHash == lastStateHash) return
        lastStateHash = currentHash

        if (!state.isEnabled && !state.viperBassEnabled && !state.dynBassEnabled) {
            hpfFilter.setBypass()
            for (f in bandFilters) f.setBypass()
            lpfFilter.setBypass()
            viperBassDsp.setBand1(0, 60f, 0f)
            dynamicBassDsp.setPresetAndGain(0, 0f)
            return
        }

        hpfFilter.configureHighPass(sampleRate, state.hpfHz)

        val gains = state.gains
        for (i in 0..9) {
            val freq = EqualizerEngine.STANDARD_FREQUENCIES[i]
            val gain = gains[i]
            bandFilters[i].configurePeaking(sampleRate, freq, gain)
        }

        lpfFilter.configureLowPass(sampleRate, state.lpfHz)

        viperBassDsp.setSampleRate(sampleRate)
        if (state.viperBassEnabled) {
            viperBassDsp.setBand1(state.viperBassMode, state.viperBassCutoffHz, state.viperBassGainDb)
        } else {
            viperBassDsp.setBand1(0, 60f, 0f)
        }

        dynamicBassDsp.setSampleRate(sampleRate)
        if (state.dynBassEnabled) {
            dynamicBassDsp.setPresetAndGain(state.dynBassPreset, state.dynBassGainPct)
        } else {
            dynamicBassDsp.setPresetAndGain(0, 0f)
        }
    }

    fun processPcm(buffer: ByteArray, bytesRead: Int, state: EqualizerEngine.State) {
        updateFromState(state)

        if (!state.isEnabled && !state.viperBassEnabled && !state.dynBassEnabled) return

        var i = 0
        while (i < bytesRead - 3) {
            val leftRaw = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            val rightRaw = (buffer[i + 3].toInt() shl 8) or (buffer[i + 2].toInt() and 0xFF)

            var l = leftRaw / 32768f
            var r = rightRaw / 32768f

            if (state.isEnabled) {
                // 1. Срез НЧ (HPF)
                l = hpfFilter.processLeft(l)
                r = hpfFilter.processRight(r)

                // 2. 10 полос эквалайзера
                for (k in 0..9) {
                    val filter = bandFilters[k]
                    l = filter.processLeft(l)
                    r = filter.processRight(r)
                }

                // 3. Срез ВЧ (LPF)
                l = lpfFilter.processLeft(l)
                r = lpfFilter.processRight(r)
            }

            if (state.viperBassEnabled) {
                l = viperBassDsp.processSample(l, 0)
                r = viperBassDsp.processSample(r, 1)
            }

            if (state.dynBassEnabled) {
                val stereo = dynamicBassDsp.processStereo(l, r)
                l = stereo.first
                r = stereo.second
            }

            // Защита от клипинга (Peak Limiter & Soft-Knee Clipper)
            if (state.limiterEnabled) {
                val attackCoef = exp(-1.0f / (0.0005f * sampleRate))
                val releaseCoef = exp(-1.0f / (0.080f * sampleRate))

                val peak = maxOf(abs(l), abs(r))
                if (peak > envState) {
                    envState = attackCoef * envState + (1.0f - attackCoef) * peak
                } else {
                    envState = releaseCoef * envState + (1.0f - releaseCoef) * peak
                }

                if (envState > 0.92f) {
                    val envGain = 0.92f / envState
                    l *= envGain
                    r *= envGain
                }

                l = smoothLimit(l)
                r = smoothLimit(r)
            }

            // Ограничение амплитуды (soft clamp)
            val outL = (l * 32767f).roundToInt().coerceIn(-32768, 32767)
            val outR = (r * 32767f).roundToInt().coerceIn(-32768, 32767)

            buffer[i] = (outL and 0xFF).toByte()
            buffer[i + 1] = ((outL shr 8) and 0xFF).toByte()
            buffer[i + 2] = (outR and 0xFF).toByte()
            buffer[i + 3] = ((outR shr 8) and 0xFF).toByte()

            i += 4
        }
    }
}
