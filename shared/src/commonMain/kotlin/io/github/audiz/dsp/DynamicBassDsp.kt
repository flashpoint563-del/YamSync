package io.github.audiz.dsp

import kotlin.math.*

class DynamicBassDsp {
    private var sampleRate = 48000.0f
    private var gainPct = 33.0f
    private var x1 = 100.0f
    private var x2 = 5600.0f
    private var y1 = 40.0f
    private var y2 = 80.0f
    private var sgx = 50.0f
    private var sgy = 50.0f

    private var bp_b0 = 0f; private var bp_b1 = 0f; private var bp_b2 = 0f
    private var bp_a1 = 0f; private var bp_a2 = 0f
    private val bp_z = Array(2) { FloatArray(2) }

    private var sub_b0 = 0f; private var sub_b1 = 0f; private var sub_b2 = 0f
    private var sub_a1 = 0f; private var sub_a2 = 0f
    private val sub_z = FloatArray(2)

    private var hpf_b0 = 0f; private var hpf_b1 = 0f; private var hpf_b2 = 0f
    private var hpf_a1 = 0f; private var hpf_a2 = 0f
    private val hpf_z = Array(2) { FloatArray(2) }

    fun setSampleRate(fs: Float) {
        sampleRate = if (fs < 8000.0f) 48000.0f else fs
        apply()
    }

    fun setPresetAndGain(presetIndex: Int, mGainPct: Float) {
        gainPct = mGainPct
        when (presetIndex) {
            1 -> { x1=180f; x2=5800f; y1=55f; y2=80f; sgx=10f; sgy=70f }
            2 -> { x1=120f; x2=6000f; y1=40f; y2=100f; sgx=20f; sgy=80f }
            3 -> { x1=300f; x2=5600f; y1=60f; y2=100f; sgx=50f; sgy=20f }
            4 -> { x1=90f;  x2=6200f; y1=45f; y2=85f; sgx=15f; sgy=10f }
            else -> { x1=100f; x2=5600f; y1=40f; y2=80f; sgx=50f; sgy=50f }
        }
        apply()
    }

    fun reset() {
        for (i in 0..1) {
            hpf_z[i][0] = 0f; hpf_z[i][1] = 0f
            bp_z[i][0] = 0f; bp_z[i][1] = 0f
        }
        sub_z[0] = 0f; sub_z[1] = 0f
        apply()
    }

    private fun processBiquad(x: Float, ch: Int, b0: Float, b1: Float, b2: Float, a1: Float, a2: Float, z: Array<FloatArray>): Float {
        val y = x * b0 + z[ch][0]
        z[ch][0] = x * b1 - y * a1 + z[ch][1]
        z[ch][1] = x * b2 - y * a2
        return y
    }

    private fun processMonoBiquad(x: Float, b0: Float, b1: Float, b2: Float, a1: Float, a2: Float, z: FloatArray): Float {
        val y = x * b0 + z[0]
        z[0] = x * b1 - y * a1 + z[1]
        z[1] = x * b2 - y * a2
        return y
    }

    fun processSample(x: Float, ch: Int): Float {
        throw UnsupportedOperationException("Call processStereo for DynamicBass")
    }

    // Returning Pair of Left and Right
    fun processStereo(l: Float, r: Float): Pair<Float, Float> {
        if (gainPct <= 0.1f) return Pair(l, r)

        val boost = (gainPct / 100.0f) * 1.8f

        val dl = processBiquad(l, 0, hpf_b0, hpf_b1, hpf_b2, hpf_a1, hpf_a2, hpf_z)
        val dr = processBiquad(r, 1, hpf_b0, hpf_b1, hpf_b2, hpf_a1, hpf_a2, hpf_z)

        val bpl = processBiquad(dl, 0, bp_b0, bp_b1, bp_b2, bp_a1, bp_a2, bp_z)
        val bpr = processBiquad(dr, 1, bp_b0, bp_b1, bp_b2, bp_a1, bp_a2, bp_z)

        val monoSub = processMonoBiquad(0.5f * (dl + dr), sub_b0, sub_b1, sub_b2, sub_a1, sub_a2, sub_z)

        val outL = dl + (bpl + monoSub) * boost
        val outR = dr + (bpr + monoSub) * boost

        return Pair(outL, outR)
    }

    private fun apply() {
        designHPF(sampleRate, 28.0f, 0.7071f).let {
            hpf_b0 = it[0]; hpf_b1 = it[1]; hpf_b2 = it[2]; hpf_a1 = it[3]; hpf_a2 = it[4]
        }

        val centerFreq = (y1 + y2) * 0.5f
        val q = 0.65f + (sgx + sgy) * 0.004f
        designBPF(sampleRate, centerFreq, q).let {
            bp_b0 = it[0]; bp_b1 = it[1]; bp_b2 = it[2]; bp_a1 = it[3]; bp_a2 = it[4]
        }

        designLPF(sampleRate, x1 * 0.5f, 0.7071f).let {
            sub_b0 = it[0]; sub_b1 = it[1]; sub_b2 = it[2]; sub_a1 = it[3]; sub_a2 = it[4]
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

    private fun designBPF(fs: Float, fc: Float, q: Float): FloatArray {
        val w0 = (2.0 * PI * fc / fs).toFloat()
        val alpha = sin(w0) / (2.0f * q)
        val cosw0 = cos(w0)
        val a0 = 1.0f + alpha
        val b0 = alpha / a0
        val b1 = 0.0f
        val b2 = -alpha / a0
        val a1 = (-2.0f * cosw0) / a0
        val a2 = (1.0f - alpha) / a0
        return floatArrayOf(b0, b1, b2, a1, a2)
    }
}
