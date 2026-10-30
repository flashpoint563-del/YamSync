package io.github.audiz.dsp

import io.github.audiz.loadAppConfig
import io.github.audiz.saveAppConfig
import io.github.audiz.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile
import kotlin.math.*

/**
 * 🎚 Движок графического эквалайзера и частотных срезов (DSP).
 *
 * Содержит:
 * - Срез низких частот (HPF / Low-Cut): 20 Гц (выкл) .. 250 Гц
 * - 10-полосный классический эквалайзер: 31, 63, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Гц (±12 дБ)
 * - Срез высоких частот (LPF / High-Cut): 4 кГц .. 20 кГц (выкл)
 * - Набор пресетов (Rock, Pop, Bass Boost, Vocal и др.)
 * - Автосохранение в конфигурацию приложения
 */
object EqualizerEngine {

    val STANDARD_FREQUENCIES = floatArrayOf(
        31f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
    )

    data class Preset(
        val name: String,
        val gains: FloatArray,
        val hpfHz: Float = 20f,
        val lpfHz: Float = 20000f
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Preset) return false
            return name == other.name && gains.contentEquals(other.gains) && hpfHz == other.hpfHz && lpfHz == other.lpfHz
        }

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + gains.contentHashCode()
            result = 31 * result + hpfHz.hashCode()
            result = 31 * result + lpfHz.hashCode()
            return result
        }
    }

    val PRESETS = listOf(
        Preset("Flat", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        Preset("Bass Boost", floatArrayOf(6f, 5f, 3.5f, 1f, 0f, 0f, 0f, 0f, 0f, 0f)),
        Preset("Bass Reducer", floatArrayOf(-6f, -5f, -3f, -1.5f, 0f, 0f, 0f, 0f, 0f, 0f), hpfHz = 40f),
        Preset("Rock", floatArrayOf(4f, 3f, 1.5f, -0.5f, -1f, 1f, 2.5f, 4f, 4.5f, 3f)),
        Preset("Pop", floatArrayOf(-1f, 1f, 3f, 4f, 3f, 1.5f, 0.5f, 1f, 0f, -1f)),
        Preset("Jazz", floatArrayOf(3f, 2f, 1f, -1f, -1f, 0f, 1.5f, 2.5f, 3f, 2.5f)),
        Preset("Classical", floatArrayOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 2.5f)),
        Preset("Electronic", floatArrayOf(5.5f, 4.5f, 2f, 0f, -1.5f, 1.5f, 2.5f, 4f, 5f, 4f)),
        Preset("Vocal Boost", floatArrayOf(-3f, -2f, -1f, 1f, 3f, 4f, 3.5f, 2f, 0f, -2f), hpfHz = 80f),
        Preset("Treble Boost", floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5.5f, 6f)),
        Preset("Treble Tamer", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, -1f, -3f, -5f, -6f), lpfHz = 14000f),
        Preset("Subwoofer Cut", floatArrayOf(-12f, 0f, 2.5f, 2f, 0.5f, 0f, 0f, 0f, 0f, 0f), hpfHz = 60f)
    )

    data class State(
        val isEnabled: Boolean = false,
        val hpfHz: Float = 20f,
        val lpfHz: Float = 20000f,
        val gains: FloatArray = FloatArray(10) { 0f },
        val presetName: String = "Flat",
        // ViPER Bass
        val viperBassEnabled: Boolean = false,
        val viperBassMode: Int = 0,
        val viperBassCutoffHz: Float = 60f,
        val viperBassGainDb: Float = 0f,
        // Dynamic Bass
        val dynBassEnabled: Boolean = false,
        val dynBassPreset: Int = 0,
        val dynBassGainPct: Float = 33f,
        // Защита от пикового клипинга
        val limiterEnabled: Boolean = true
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is State) return false
            return isEnabled == other.isEnabled && hpfHz == other.hpfHz && lpfHz == other.lpfHz &&
                    gains.contentEquals(other.gains) && presetName == other.presetName &&
                    viperBassEnabled == other.viperBassEnabled && viperBassMode == other.viperBassMode &&
                    viperBassCutoffHz == other.viperBassCutoffHz && viperBassGainDb == other.viperBassGainDb &&
                    dynBassEnabled == other.dynBassEnabled && dynBassPreset == other.dynBassPreset &&
                    dynBassGainPct == other.dynBassGainPct && limiterEnabled == other.limiterEnabled
        }

        override fun hashCode(): Int {
            var result = isEnabled.hashCode()
            result = 31 * result + hpfHz.hashCode()
            result = 31 * result + lpfHz.hashCode()
            result = 31 * result + gains.contentHashCode()
            result = 31 * result + presetName.hashCode()
            result = 31 * result + viperBassEnabled.hashCode()
            result = 31 * result + viperBassMode.hashCode()
            result = 31 * result + viperBassCutoffHz.hashCode()
            result = 31 * result + viperBassGainDb.hashCode()
            result = 31 * result + dynBassEnabled.hashCode()
            result = 31 * result + dynBassPreset.hashCode()
            result = 31 * result + dynBassGainPct.hashCode()
            result = 31 * result + limiterEnabled.hashCode()
            return result
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    var onStateChanged: ((State) -> Unit)? = null

    @Volatile var isEnabled: Boolean = false
        private set

    @Volatile var hpfHz: Float = 20f
        private set

    @Volatile var lpfHz: Float = 20000f
        private set

    private val filterLock = Any()
    @Volatile private var sampleRate: Float = 44100f

    private val defaultProcessor = AudioDspProcessor()

    private fun smoothLimit(x: Float): Float {
        val t = 0.90f
        val ax = abs(x)
        if (ax <= t) return x
        val sign = if (x >= 0f) 1f else -1f
        return sign * (t + (1f - t) * tanh((ax - t) / (1f - t)))
    }

    init {
        synchronized(filterLock) {
            loadSettings()
            recalculateFiltersLocked()
        }
    }

    fun hasCutoffsActive(): Boolean {
        return isEnabled && (hpfHz > 21f || lpfHz < 19900f)
    }

    /**
     * Логарифмическая интерполяция гейна 10 полос эквалайзера для заданной частоты.
     */
    fun interpolateGain(freqHz: Float, gains: FloatArray): Float {
        if (gains.isEmpty()) return 0f
        if (freqHz <= STANDARD_FREQUENCIES.first()) return gains.first()
        if (freqHz >= STANDARD_FREQUENCIES.last()) return gains.last()

        for (i in 0 until STANDARD_FREQUENCIES.size - 1) {
            val fLow = STANDARD_FREQUENCIES[i]
            val fHigh = STANDARD_FREQUENCIES[i + 1]
            if (freqHz in fLow..fHigh) {
                val logCenter = log10(freqHz)
                val logLow = log10(fLow)
                val logHigh = log10(fHigh)
                val fraction = (logCenter - logLow) / (logHigh - logLow)
                return gains[i] + fraction * (gains[i + 1] - gains[i])
            }
        }
        return 0f
    }

    /**
     * Расчет результирующего гейна в дБ для заданной частоты freqHz.
     * Объединяет 10 полос эквалайзера и спады фильтров Low-Cut (HPF) и High-Cut (LPF).
     */
    fun calculateEffectiveGainDb(freqHz: Float): Float {
        if (!isEnabled) return 0f

        val safeFreq = freqHz.coerceAtLeast(1f)

        // 1. 10 полос эквалайзера
        val eqGain = interpolateGain(safeFreq, _state.value.gains)

        // 2. Срез НЧ (HPF / Low-Cut): спад 2-го порядка (-12 дБ/октава) ниже частоты среза
        val currentHpf = hpfHz
        val hpfAttenuation = if (currentHpf > 21f && safeFreq < currentHpf) {
            val octavesBelow = log2(currentHpf / safeFreq)
            (-12f * octavesBelow).coerceAtMost(0f)
        } else 0f

        // 3. Срез ВЧ (LPF / High-Cut): спад 2-го порядка (-12 дБ/октава) выше частоты среза
        val currentLpf = lpfHz
        val lpfAttenuation = if (currentLpf < 19900f && safeFreq > currentLpf) {
            val octavesAbove = log2(safeFreq / currentLpf)
            (-12f * octavesAbove).coerceAtMost(0f)
        } else 0f

        return (eqGain + hpfAttenuation + lpfAttenuation).coerceIn(-24f, 15f)
    }

    fun setSampleRate(rate: Float) {
        if (rate <= 8000f) return
        synchronized(filterLock) {
            if (rate != sampleRate) {
                sampleRate = rate
                defaultProcessor.setSampleRate(rate)
                recalculateFiltersLocked()
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        synchronized(filterLock) {
            isEnabled = enabled
            _state.value = _state.value.copy(isEnabled = enabled)
            saveAppConfig("eq_enabled", enabled.toString())
            recalculateFiltersLocked()
        }
    }

    fun setViperBassEnabled(enabled: Boolean) {
        synchronized(filterLock) {
            _state.value = _state.value.copy(viperBassEnabled = enabled)
            saveAppConfig("eq_viper_bass_enabled", enabled.toString())
            recalculateFiltersLocked()
        }
    }

    fun setViperBassMode(mode: Int) {
        synchronized(filterLock) {
            _state.value = _state.value.copy(viperBassMode = mode)
            saveAppConfig("eq_viper_bass_mode", mode.toString())
            recalculateFiltersLocked()
        }
    }

    fun setViperBassCutoff(cutoff: Float) {
        synchronized(filterLock) {
            _state.value = _state.value.copy(viperBassCutoffHz = cutoff)
            saveAppConfig("eq_viper_bass_cutoff", cutoff.toString())
            recalculateFiltersLocked()
        }
    }

    fun setViperBassGain(gain: Float) {
        synchronized(filterLock) {
            _state.value = _state.value.copy(viperBassGainDb = gain)
            saveAppConfig("eq_viper_bass_gain", gain.toString())
            recalculateFiltersLocked()
        }
    }
    
    fun setDynBassEnabled(enabled: Boolean) {
        synchronized(filterLock) {
            _state.value = _state.value.copy(dynBassEnabled = enabled)
            saveAppConfig("eq_dyn_bass_enabled", enabled.toString())
            recalculateFiltersLocked()
        }
    }
    
    fun setDynBassPreset(preset: Int) {
        synchronized(filterLock) {
            _state.value = _state.value.copy(dynBassPreset = preset)
            saveAppConfig("eq_dyn_bass_preset", preset.toString())
            recalculateFiltersLocked()
        }
    }
    
    fun setDynBassGain(gain: Float) {
        synchronized(filterLock) {
            _state.value = _state.value.copy(dynBassGainPct = gain)
            saveAppConfig("eq_dyn_bass_gain", gain.toString())
            recalculateFiltersLocked()
        }
    }

    fun setLimiterEnabled(enabled: Boolean) {
        synchronized(filterLock) {
            _state.value = _state.value.copy(limiterEnabled = enabled)
            saveAppConfig("eq_limiter_enabled", enabled.toString())
            recalculateFiltersLocked()
        }
    }

    fun setHpfCutoff(freqHz: Float) {
        val clamped = freqHz.coerceIn(20f, 250f)
        synchronized(filterLock) {
            hpfHz = clamped
            _state.value = _state.value.copy(hpfHz = clamped, presetName = "Custom")
            saveAppConfig("eq_hpf", clamped.toString())
            saveAppConfig("eq_preset", "Custom")
            recalculateFiltersLocked()
        }
    }

    fun setLpfCutoff(freqHz: Float) {
        val clamped = freqHz.coerceIn(4000f, 20000f)
        synchronized(filterLock) {
            lpfHz = clamped
            _state.value = _state.value.copy(lpfHz = clamped, presetName = "Custom")
            saveAppConfig("eq_lpf", clamped.toString())
            saveAppConfig("eq_preset", "Custom")
            recalculateFiltersLocked()
        }
    }

    fun setBandGain(index: Int, gainDb: Float) {
        if (index !in 0..9) return
        synchronized(filterLock) {
            val currentGains = _state.value.gains.copyOf()
            currentGains[index] = gainDb.coerceIn(-12f, 12f)
            _state.value = _state.value.copy(gains = currentGains, presetName = "Custom")
            saveAppConfig("eq_band_$index", currentGains[index].toString())
            saveAppConfig("eq_preset", "Custom")
            recalculateFiltersLocked()
        }
    }

    fun applyPreset(preset: Preset) {
        val gainsCopy = preset.gains.copyOf()
        synchronized(filterLock) {
            hpfHz = preset.hpfHz
            lpfHz = preset.lpfHz
            _state.value = _state.value.copy(
                gains = gainsCopy,
                hpfHz = preset.hpfHz,
                lpfHz = preset.lpfHz,
                presetName = preset.name
            )
            saveAppConfig("eq_preset", preset.name)
            saveAppConfig("eq_hpf", preset.hpfHz.toString())
            saveAppConfig("eq_lpf", preset.lpfHz.toString())
            for (i in 0..9) {
                saveAppConfig("eq_band_$i", gainsCopy[i].toString())
            }
            recalculateFiltersLocked()
        }
    }

    fun resetToFlat() {
        val flatPreset = PRESETS.first { it.name == "Flat" }
        applyPreset(flatPreset)
    }

    fun resetCutoffs() {
        setHpfCutoff(20f)
        setLpfCutoff(20000f)
    }

    fun recalculateFilters() {
        synchronized(filterLock) {
            recalculateFiltersLocked()
        }
    }

    private fun recalculateFiltersLocked() {
        onStateChanged?.invoke(_state.value)
    }

    /**
     * Потоковая фильтрация сырого 16-битного стерео PCM (little-endian).
     * Выполняется in-place без выделения памяти на куче.
     */
    fun processPcm(buffer: ByteArray, bytesRead: Int) {
        defaultProcessor.processPcm(buffer, bytesRead, _state.value)
    }

    private fun loadSettings() {
        val enabled = loadAppConfig("eq_enabled")?.toBooleanStrictOrNull() ?: false
        val hpf = loadAppConfig("eq_hpf")?.toFloatOrNull() ?: 20f
        val lpf = loadAppConfig("eq_lpf")?.toFloatOrNull() ?: 20000f
        val preset = loadAppConfig("eq_preset") ?: "Flat"

        val gains = FloatArray(10) { index ->
            loadAppConfig("eq_band_$index")?.toFloatOrNull() ?: 0f
        }
        
        val viperBassEnabled = loadAppConfig("eq_viper_bass_enabled")?.toBooleanStrictOrNull() ?: false
        val viperBassMode = loadAppConfig("eq_viper_bass_mode")?.toIntOrNull() ?: 0
        val viperBassCutoff = loadAppConfig("eq_viper_bass_cutoff")?.toFloatOrNull() ?: 60f
        val viperBassGain = loadAppConfig("eq_viper_bass_gain")?.toFloatOrNull() ?: 0f
        
        val dynBassEnabled = loadAppConfig("eq_dyn_bass_enabled")?.toBooleanStrictOrNull() ?: false
        val dynBassPreset = loadAppConfig("eq_dyn_bass_preset")?.toIntOrNull() ?: 0
        val dynBassGain = loadAppConfig("eq_dyn_bass_gain")?.toFloatOrNull() ?: 33f
        val limiterEnabled = loadAppConfig("eq_limiter_enabled")?.toBooleanStrictOrNull() ?: true

        isEnabled = enabled
        hpfHz = hpf.coerceIn(20f, 250f)
        lpfHz = lpf.coerceIn(4000f, 20000f)

        _state.value = State(
            isEnabled = enabled,
            hpfHz = hpfHz,
            lpfHz = lpfHz,
            gains = gains,
            presetName = preset,
            viperBassEnabled = viperBassEnabled,
            viperBassMode = viperBassMode,
            viperBassCutoffHz = viperBassCutoff,
            viperBassGainDb = viperBassGain,
            dynBassEnabled = dynBassEnabled,
            dynBassPreset = dynBassPreset,
            dynBassGainPct = dynBassGain,
            limiterEnabled = limiterEnabled
        )
    }
}
