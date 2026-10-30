package io.github.audiz.dsp

import kotlin.math.*

/**
 * ViPER Bass DSP Engine ported to pure Kotlin.
 * Supports Natural, Pure, and Subwoofer modes.
 */
class ViperBassDsp {
    private var mode = 0
    private var sampleRate = 48000f
    private var cutoffFreq = 60f
    private var gainDb = 0f
    private var boostLinear = 0f

    // Filters states
    private val lpf_b0 = FloatArray(2)
    private val lpf_b1 = FloatArray(2)
    private val lpf_b2 = FloatArray(2)
    private val lpf_a1 = FloatArray(2)
    private val lpf_a2 = FloatArray(2)
    private val lpf_z = Array(2) { Array(2) { FloatArray(2) } }

    private var hpf_b0 = 0f; private var hpf_b1 = 0f; private var hpf_b2 = 0f
    private var hpf_a1 = 0f; private var hpf_a2 = 0f
    private val hpf_z = Array(2) { FloatArray(2) }

    private var apf_b0 = 0f; private var apf_b1 = 0f; private var apf_b2 = 0f
    private var apf_a1 = 0f; private var apf_a2 = 0f
    private val apf_z = Array(2) { FloatArray(2) }

    private val sub_lpf_b0 = FloatArray(2)
    private val sub_lpf_b1 = FloatArray(2)
    private val sub_lpf_b2 = FloatArray(2)
    private val sub_lpf_a1 = FloatArray(2)
    private val sub_lpf_a2 = FloatArray(2)
    private val sub_lpf_z = Array(2) { Array(2) { FloatArray(2) } }

    fun setSampleRate(fs: Float) {
        sampleRate = if (fs < 8000f) 48000f else fs
        updateFilters()
    }

    fun setBand1(mMode: Int, mCutoff: Float, mGainDb: Float) {
        mode = mMode
        cutoffFreq = mCutoff
        gainDb = mGainDb
        boostLinear = (gainDb / 15.0f) * 2.2f
        if (boostLinear < 0f) boostLinear = 0f
        updateFilters()
    }

    private fun updateFilters() {
        designHPF(sampleRate, 28.0f, 0.7071f).let { 
            hpf_b0 = it[0]; hpf_b1 = it[1]; hpf_b2 = it[2]; hpf_a1 = it[3]; hpf_a2 = it[4]
        }
        
        val q = if (mode == 1) 1.05f else 0.7071f
        designLPF(sampleRate, cutoffFreq, q).let {
            lpf_b0[0] = it[0]; lpf_b1[0] = it[1]; lpf_b2[0] = it[2]; lpf_a1[0] = it[3]; lpf_a2[0] = it[4]
            lpf_b0[1] = it[0]; lpf_b1[1] = it[1]; lpf_b2[1] = it[2]; lpf_a1[1] = it[3]; lpf_a2[1] = it[4]
        }

        designAPF(sampleRate, cutoffFreq, 0.7071f).let {
            apf_b0 = it[0]; apf_b1 = it[1]; apf_b2 = it[2]; apf_a1 = it[3]; apf_a2 = it[4]
        }

        if (mode == 2) {
            designLPF(sampleRate, cutoffFreq * 2.0f, 0.7071f).let {
                sub_lpf_b0[0] = it[0]; sub_lpf_b1[0] = it[1]; sub_lpf_b2[0] = it[2]; sub_lpf_a1[0] = it[3]; sub_lpf_a2[0] = it[4]
                sub_lpf_b0[1] = it[0]; sub_lpf_b1[1] = it[1]; sub_lpf_b2[1] = it[2]; sub_lpf_a1[1] = it[3]; sub_lpf_a2[1] = it[4]
            }
        }
    }

    fun reset() {
        for (i in 0..1) {
            for (j in 0..1) {
                lpf_z[i][j][0] = 0f; lpf_z[i][j][1] = 0f
                sub_lpf_z[i][j][0] = 0f; sub_lpf_z[i][j][1] = 0f
            }
            hpf_z[i][0] = 0f; hpf_z[i][1] = 0f
            apf_z[i][0] = 0f; apf_z[i][1] = 0f
        }
    }

    private fun processBiquad(x: Float, ch: Int, stage: Int, b0: FloatArray, b1: FloatArray, b2: FloatArray, a1: FloatArray, a2: FloatArray, z: Array<Array<FloatArray>>): Float {
        val y = x * b0[stage] + z[ch][stage][0]
        z[ch][stage][0] = x * b1[stage] - y * a1[stage] + z[ch][stage][1]
        z[ch][stage][1] = x * b2[stage] - y * a2[stage]
        return y
    }

    private fun processBiquad1(x: Float, ch: Int, b0: Float, b1: Float, b2: Float, a1: Float, a2: Float, z: Array<FloatArray>): Float {
        val y = x * b0 + z[ch][0]
        z[ch][0] = x * b1 - y * a1 + z[ch][1]
        z[ch][1] = x * b2 - y * a2
        return y
    }

    fun processSample(x: Float, ch: Int): Float {
        if (boostLinear <= 0.001f) return x

        val dry = processBiquad1(x, ch, hpf_b0, hpf_b1, hpf_b2, hpf_a1, hpf_a2, hpf_z)
        var bass = processBiquad(dry, ch, 0, lpf_b0, lpf_b1, lpf_b2, lpf_a1, lpf_a2, lpf_z)

        return when (mode) {
            0 -> {
                dry + bass * boostLinear * 0.7f
            }
            1 -> {
                bass = processBiquad(bass, ch, 1, lpf_b0, lpf_b1, lpf_b2, lpf_a1, lpf_a2, lpf_z)
                val phaseShiftedDry = processBiquad1(dry, ch, apf_b0, apf_b1, apf_b2, apf_a1, apf_a2, apf_z)
                phaseShiftedDry + bass * boostLinear
            }
            2 -> {
                bass = processBiquad(bass, ch, 1, lpf_b0, lpf_b1, lpf_b2, lpf_a1, lpf_a2, lpf_z)
                val phaseShiftedDry = processBiquad1(dry, ch, apf_b0, apf_b1, apf_b2, apf_a1, apf_a2, apf_z)

                val drive = bass * (1.5f + 0.1f * gainDb)
                var harm = tanh(drive) + 0.25f * drive * drive * if (drive >= 0.0f) 1.0f else -1.0f
                harm = processBiquad(harm, ch, 0, sub_lpf_b0, sub_lpf_b1, sub_lpf_b2, sub_lpf_a1, sub_lpf_a2, sub_lpf_z)
                harm = processBiquad(harm, ch, 1, sub_lpf_b0, sub_lpf_b1, sub_lpf_b2, sub_lpf_a1, sub_lpf_a2, sub_lpf_z)

                phaseShiftedDry + (bass * 0.75f + harm * 0.45f) * boostLinear
            }
            else -> dry
        }
    }

    private fun designLPF(fs: Float, fc: Float, q: Float): FloatArray {
        val w0 = (2.0 * PI * fc / fs).toFloat()
        val alpha = sin(w0) / (2.0f * q)
        val cosw0 = cos(w0)
        val a0 = 1.0f + alpha
        val b0 = ((1.0f - cosw0) * 0.5f) / a0
        val b1 = (1.0f - cosw0) / a0
        val b2 = b0
        val a1 = (-2.0f * cosw0) / a0
        val a2 = (1.0f - alpha) / a0
        return floatArrayOf(b0, b1, b2, a1, a2)
    }

    private fun designHPF(fs: Float, fc: Float, q: Float): FloatArray {
        val w0 = (2.0 * PI * fc / fs).toFloat()
        val alpha = sin(w0) / (2.0f * q)
        val cosw0 = cos(w0)
        val a0 = 1.0f + alpha
        val b0 = ((1.0f + cosw0) * 0.5f) / a0
        val b1 = -(1.0f + cosw0) / a0
        val b2 = b0
        val a1 = (-2.0f * cosw0) / a0
        val a2 = (1.0f - alpha) / a0
        return floatArrayOf(b0, b1, b2, a1, a2)
    }

    private fun designAPF(fs: Float, fc: Float, q: Float): FloatArray {
        val w0 = (2.0 * PI * fc / fs).toFloat()
        val alpha = sin(w0) / (2.0f * q)
        val cosw0 = cos(w0)
        val a0 = 1.0f + alpha
        val b0 = (1.0f - alpha) / a0
        val b1 = (-2.0f * cosw0) / a0
        val b2 = (1.0f + alpha) / a0
        val a1 = b1
        val a2 = b0
        return floatArrayOf(b0, b1, b2, a1, a2)
    }
}
